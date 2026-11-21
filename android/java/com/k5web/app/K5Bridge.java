package com.k5web.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 原生 USB 桥：把安卓没有的 WebUSB API 暴露给页面里的 webusb-ch341.js。
 *
 * JS 侧只有两种约定：
 *   K5Native.&lt;method&gt;(id, argsJson)   —— 立即返回，结果通过 __K5USB.onReply(id, json) 回调
 *   回调 json = {"ok":true,"data":...} 或 {"ok":false,"name":"...","message":"..."}
 */
public final class K5Bridge {
    private static final String TAG = "K5Bridge";
    public static final String ACTION_USB_PERMISSION = "com.k5web.app.USB_PERMISSION";

    private static final int CONTROL_TIMEOUT_MS = 2000;
    private static final int BULK_OUT_TIMEOUT_MS = 2000;
    // 读超时故意短：没有数据时尽快把控制权交回 JS 读循环，避免卡死页面。
    private static final int BULK_IN_TIMEOUT_MS = 300;

    // ---- 连续读线程参数（性能关键） ----
    // CH340 在 38400 波特下每 32 字节约 8.3ms，一个包一次 JS↔Java 往返必然吃满带宽，
    // 所以改由原生线程连续读、成批推给页面：
    //   · 空闲时用 60ms 超时轮询，省电又不会拖慢响应；
    //   · 一旦读到数据，用 12ms 超时继续“攒包”，帧尾出现 >12ms 空隙时整批下发；
    //   · 单批上限 2048B，既限制单条 evaluateJavascript 的长度，也让页面对大块数据尽早可见。
    private static final int READ_PACKET_SIZE = 32;
    private static final int READ_IDLE_TIMEOUT_MS = 60;
    private static final int READ_COALESCE_TIMEOUT_MS = 12;
    private static final int READ_BATCH_MAX = 2048;
    // 写：整帧一次 JS 调用，原生按 256B 分片下发，避免一帧几十次往返。
    private static final int BULK_OUT_CHUNK = 256;

    /** 需要 Activity 配合的部分（保存文件、JS 回调）。 */
    public interface Host {
        Activity activity();

        WebView webView();

        void reply(String id, String envelopeJson);

        /** 主动往页面里执行一段 JS（用于推送串口数据，不等待返回值）。 */
        void evalInPage(String js);

        void saveFile(String id, String name, String mime, byte[] data);

        /** 任务④：原生沉浸式全屏（隐藏系统栏）+ 横屏锁定。 */
        void enterImmersive();

        void exitImmersive();
    }

    /** 任务②：电脑版视口使用的桌面 UA。 */
    static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120.0.0.0 Safari/537.36";

    private String viewportMode = "mobile";
    private String defaultUserAgent;

    private final Host host;
    private final Activity activity;
    private final UsbManager usbManager;
    private final ExecutorService io;

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private final Map<Integer, UsbEndpoint> inEndpoints = new HashMap<>();
    private final Map<Integer, UsbEndpoint> outEndpoints = new HashMap<>();
    private final Map<Integer, UsbInterface> interfaces = new HashMap<>();
    private final List<UsbInterface> claimed = new ArrayList<>();

    private volatile String pendingPermissionId;
    private volatile boolean receiverRegistered;

    /** 系统选择框选中本应用后待自动连接的设备（任务③）。 */
    private volatile UsbDevice pendingAutoDevice;

    private final Object readLock = new Object();
    private Thread readThread;
    private volatile boolean readRunning;

    public K5Bridge(Host host) {
        this.host = host;
        this.activity = host.activity();
        this.usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        try {
            this.defaultUserAgent = android.webkit.WebSettings.getDefaultUserAgent(activity);
        } catch (Throwable t) {
            this.defaultUserAgent = null;
        }
        this.io = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "k5-usb-io");
                t.setDaemon(true);
                return t;
            }
        });
        registerPermissionReceiver();
    }

    // ---------------------------------------------------------------- 生命周期

    private void registerPermissionReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(permissionReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver failed", t);
        }
    }

    void close() {
        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(permissionReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        releaseAll();
        io.shutdownNow();
    }

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }
            String id = pendingPermissionId;
            pendingPermissionId = null;
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && dev != null) {
                if (id != null) {
                    replyDevice(id, dev);
                }
                // 自动连接通道：权限授予后立即通知页面（任务③）。
                UsbDevice auto = pendingAutoDevice;
                pendingAutoDevice = null;
                if (auto != null) {
                    pushAutoDevice(auto);
                }
            } else {
                if (id != null) {
                    replyError(id, "SecurityError", "已拒绝 USB 访问权限，无法连接电台");
                }
                UsbDevice auto = pendingAutoDevice;
                pendingAutoDevice = null;
                if (auto != null) {
                    host.evalInPage("window.__K5USB&&window.__K5USB.onDeviceAttachError("
                            + JSONObject.quote("已拒绝 USB 访问权限，无法自动连接") + ")");
                }
            }
        }
    };

    /**
     * 任务③：系统「为USB设备选择一个应用」里选中本应用后调用。
     * 已有权限则直接通知页面；否则发起系统授权对话框，授权结果在
     * permissionReceiver 里继续走 pushAutoDevice / onDeviceAttachError。
     */
    public void autoAttachDevice(final UsbDevice dev) {
        if (dev == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (usbManager.hasPermission(dev)) {
                        pendingAutoDevice = null;
                        pushAutoDevice(dev);
                        return;
                    }
                    pendingAutoDevice = dev;
                    Intent perm = new Intent(ACTION_USB_PERMISSION).setPackage(activity.getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) {
                        flags |= PendingIntent.FLAG_MUTABLE;
                    }
                    PendingIntent pi = PendingIntent.getBroadcast(activity, 1, perm, flags);
                    usbManager.requestPermission(dev, pi);
                } catch (Throwable t) {
                    pendingAutoDevice = null;
                    Log.w(TAG, "autoAttachDevice failed", t);
                    host.evalInPage("window.__K5USB&&window.__K5USB.onDeviceAttachError("
                            + JSONObject.quote("USB 设备授权失败: " + t.getMessage()) + ")");
                }
            }
        });
    }

    /** 把自动连接的设备 vid/pid 推给页面，由 k5-bridge.js 分发 k5usb:device-attached 事件。 */
    private void pushAutoDevice(UsbDevice dev) {
        if (dev == null) {
            return;
        }
        host.evalInPage("window.__K5USB&&window.__K5USB.onDeviceAttached("
                + dev.getVendorId() + "," + dev.getProductId() + ")");
    }

    // ---------------------------------------------------------------- JS 接口

    @JavascriptInterface
    public void requestDevice(String id, String argsJson) {
        final JSONArray filters = parseFilters(argsJson);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<UsbDevice> matches = findMatchingDevices(filters);
                    if (matches.isEmpty()) {
                        replyError(id, "NotFoundError",
                                "没有检测到 USB 写频线：请用 OTG 线连接 CH340（1a86:7523）后重试");
                        return;
                    }
                    UsbDevice dev = matches.get(0);
                    if (usbManager.hasPermission(dev)) {
                        replyDevice(id, dev);
                        return;
                    }
                    pendingPermissionId = id;
                    Intent perm = new Intent(ACTION_USB_PERMISSION).setPackage(activity.getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) {
                        flags |= PendingIntent.FLAG_MUTABLE;
                    }
                    PendingIntent pi = PendingIntent.getBroadcast(activity, 0, perm, flags);
                    usbManager.requestPermission(dev, pi);
                } catch (Throwable t) {
                    replyError(id, "NotFoundError", "枚举 USB 设备失败: " + t.getMessage());
                }
            }
        });
    }

    @JavascriptInterface
    public void open(String id, String argsJson) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject a = new JSONObject(argsJson == null ? "{}" : argsJson);
                    UsbDevice dev = findDeviceById(a.optInt("vendorId", -1), a.optInt("productId", -1));
                    if (dev == null) {
                        throw new BridgeError("NotFoundError", "USB 设备已断开，请重新插入写频线");
                    }
                    if (!usbManager.hasPermission(dev)) {
                        throw new BridgeError("SecurityError", "缺少 USB 访问权限，请重新连接");
                    }
                    if (connection != null) {
                        releaseAll();
                    }
                    UsbDeviceConnection conn = usbManager.openDevice(dev);
                    if (conn == null) {
                        throw new BridgeError("NetworkError",
                                "无法打开 USB 设备（可能已被系统内核驱动占用，部分机型需 root 或屏蔽 ch341 驱动）");
                    }
                    device = dev;
                    connection = conn;
                    indexInterfaces(dev);
                    JSONObject data = new JSONObject();
                    data.put("vendorId", dev.getVendorId());
                    data.put("productId", dev.getProductId());
                    data.put("configuration", buildConfigurationJson(dev));
                    replyOk(id, data);
                } catch (Throwable t) {
                    replyError(id, t);
                }
            }
        });
    }

    @JavascriptInterface
    public void claimInterface(String id, String argsJson) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    UsbDeviceConnection conn = requireConnection();
                    int num = new JSONObject(argsJson).optInt("interfaceNumber", 0);
                    UsbInterface itf = interfaces.get(num);
                    if (itf == null) {
                        throw new BridgeError("NotFoundError", "接口 " + num + " 不存在");
                    }
                    boolean ok;
                    try {
                        ok = conn.claimInterface(itf, true);
                    } catch (Throwable t) {
                        ok = false;
                    }
                    if (!ok) {
                        throw new BridgeError("NetworkError",
                                "claimInterface 失败：系统内核驱动（ch341）占用了该接口");
                    }
                    if (!claimed.contains(itf)) {
                        claimed.add(itf);
                    }
                    replyOk(id, new JSONObject());
                } catch (Throwable t) {
                    replyError(id, t);
                }
            }
        });
    }

    @JavascriptInterface
    public void releaseInterface(String id, String argsJson) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int num = new JSONObject(argsJson).optInt("interfaceNumber", 0);
                    UsbInterface itf = interfaces.get(num);
                    if (connection != null && itf != null) {
                        try {
                            connection.releaseInterface(itf);
                        } catch (Throwable ignored) {
                        }
                        claimed.remove(itf);
                    }
                    replyOk(id, new JSONObject());
                } catch (Throwable t) {
                    replyError(id, t);
                }
            }
        });
    }

    @JavascriptInterface
    public void close(String id, String argsJson) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                releaseAll();
                replyOk(id, new JSONObject());
            }
        });
    }

    @JavascriptInterface
    public void selectConfiguration(String id, String argsJson) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    UsbDeviceConnection conn = requireConnection();
                    JSONObject a = new JSONObject(argsJson);
                    int value = a.optInt("configurationValue", 1);
                    for (int i = 0; i < device.getConfigurationCount(); i++) {
                        android.hardware.usb.UsbConfiguration cfg = device.getConfiguration(i);
                        if (cfg.getId() == value) {
                            boolean ok = conn.setConfiguration(cfg);
                            if (!ok) {
                                throw new BridgeError("NetworkError", "setConfiguration 失败");
                            }
                            break;
                        }
                    }
                    JSONObject data = new JSONObject();
                    data.put("configuration", buildConfigurationJson(device));
                    replyOk(id, data);
                } catch (Throwable t) {
                    replyError(id, t);
                }
            }
        });
    }

    @JavascriptInterface
    public void controlTransferOut(String id, String argsJson) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject a = new JSONObject(argsJson);
                    UsbDeviceConnection conn = requireConnection();
                    int bm = bmRequestType(a.optString("requestType", "vendor"),
                            a.optString("recipient", "device"), false);
                    byte[] data = decodeBase64(a.optString("data", ""));
                    int r = conn.controlTransfer(bm, a.optInt("request", 0), a.optInt("value", 0),
                            a.optInt("index", 0), data, data.length, CONTROL_TIMEOUT_MS);
                    replyStatus(id, r < 0 ? "stall" : "ok");
                } catch (Throwable t) {
                    replyError(id, t);
                }
            }
        });
    }

    @JavascriptInterface
    public void controlTransferIn(String id, String argsJson) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject a = new JSONObject(argsJson);
                    UsbDeviceConnection conn = requireConnection();
                    int bm = bmRequestType(a.optString("requestType", "vendor"),
                            a.optString("recipient", "device"), true);
                    int length = Math.max(0, Math.min(a.optInt("length", 0), 4096));
                    byte[] buf = new byte[length];
                    int r = conn.controlTransfer(bm, a.optInt("request", 0), a.optInt("value", 0),
                            a.optInt("index", 0), buf, length, CONTROL_TIMEOUT_MS);
                    JSONObject data = new JSONObject();
                    if (r < 0) {
                        data.put("status", "stall");
                        data.put("data", "");
                    } else {
                        data.put("status", "ok");
                        data.put("data", encodeBase64(buf, r));
                    }
                    replyOk(id, data);
                } catch (Throwable t) {
                    replyError(id, t);
                }
            }
        });
    }

    @JavascriptInterface
    public void transferOut(String id, String argsJson) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject a = new JSONObject(argsJson);
                    UsbDeviceConnection conn = requireConnection();
                    int epNum = a.optInt("endpoint", -1);
                    UsbEndpoint ep = outEndpoints.get(epNum);
                    if (ep == null) {
                        throw new BridgeError("NotFoundError", "USB OUT 端点 " + epNum + " 不存在");
                    }
                    byte[] data = decodeBase64(a.optString("data", ""));
                    if (data.length == 0) {
                        // ZLP：安卓 bulkTransfer 传 0 长度不可靠，直接确认成功。
                        replyStatus(id, "ok");
                        return;
                    }
                    // 整帧一次调用，这里按 256B 分片循环下发：JS 侧一帧只付一次往返成本，
                    // 38400 波特下分片本身有足够的时间间隔，不会压垮 CH340 的 UART FIFO。
                    int offset = 0;
                    while (offset < data.length) {
                        int len = Math.min(BULK_OUT_CHUNK, data.length - offset);
                        byte[] chunk = new byte[len];
                        System.arraycopy(data, offset, chunk, 0, len);
                        int r = conn.bulkTransfer(ep, chunk, len, BULK_OUT_TIMEOUT_MS);
                        if (r < 0) {
                            replyStatus(id, "stall");
                            return;
                        }
                        offset += (r == 0 ? len : r);
                    }
                    replyStatus(id, "ok");
                } catch (Throwable t) {
                    replyError(id, t);
                }
            }
        });
    }

    @JavascriptInterface
    public void transferIn(String id, String argsJson) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (readRunning) {
                        // 连续读线程运行时，IN 端点已被它独占；这里只回空包保底兼容。
                        JSONObject data = new JSONObject();
                        data.put("status", "ok");
                        data.put("data", "");
                        replyOk(id, data);
                        return;
                    }
                    JSONObject a = new JSONObject(argsJson);
                    UsbDeviceConnection conn = requireConnection();
                    int epNum = a.optInt("endpoint", -1);
                    UsbEndpoint ep = inEndpoints.get(epNum);
                    if (ep == null) {
                        throw new BridgeError("NotFoundError", "USB IN 端点 " + epNum + " 不存在");
                    }
                    int length = Math.max(1, Math.min(a.optInt("length", 32), 16384));
                    byte[] buf = new byte[length];
                    int r = conn.bulkTransfer(ep, buf, length, BULK_IN_TIMEOUT_MS);
                    JSONObject data = new JSONObject();
                    if (r < 0) {
                        // 超时：返回空包让 JS 读循环继续等；设备真的拔掉了才报错。
                        if (!isDevicePresent()) {
                            throw new BridgeError("NetworkError", "USB 设备已断开");
                        }
                        data.put("status", "ok");
                        data.put("data", "");
                    } else {
                        data.put("status", "ok");
                        data.put("data", encodeBase64(buf, r));
                    }
                    replyOk(id, data);
                } catch (Throwable t) {
                    replyError(id, t);
                }
            }
        });
    }

    /**
     * 启动原生连续读线程。
     *
     * 旧做法是 JS 每读 32 字节调一次 transferIn：一次 USB 包要付「JS→Java 调用 +
     * Java 线程切换 + UI 线程 evaluateJavascript 回包」三段开销，而 38400 波特下
     * 包间隔只有 8.3ms，开销直接吃掉了有效带宽。改成原生线程常驻读、按批推给页面后，
     * JS 侧只从本地队列取数据，不再有每包往返。
     */
    @JavascriptInterface
    public void startRead(String id, String argsJson) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    UsbDeviceConnection conn = requireConnection();
                    int epNum = -1;
                    try {
                        epNum = new JSONObject(argsJson).optInt("endpoint", -1);
                    } catch (Throwable ignored) {
                    }
                    UsbEndpoint ep = inEndpoints.get(epNum);
                    if (ep == null) {
                        throw new BridgeError("NotFoundError", "USB IN 端点 " + epNum + " 不存在");
                    }
                    startReadLoop(conn, ep);
                    replyOk(id, new JSONObject());
                } catch (Throwable t) {
                    replyError(id, t);
                }
            }
        });
    }

    @JavascriptInterface
    public void stopRead(String id, String argsJson) {
        stopReadLoop();
        replyOk(id, new JSONObject());
    }

    private void startReadLoop(final UsbDeviceConnection conn, final UsbEndpoint ep) {
        synchronized (readLock) {
            if (readRunning) {
                return;
            }
            readRunning = true;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop(conn, ep);
                }
            }, "k5-usb-read");
            t.setDaemon(true);
            readThread = t;
            t.start();
        }
    }

    private void stopReadLoop() {
        Thread t;
        synchronized (readLock) {
            readRunning = false;
            t = readThread;
            readThread = null;
        }
        if (t != null) {
            try {
                t.join(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void readLoop(UsbDeviceConnection conn, UsbEndpoint ep) {
        byte[] buf = new byte[READ_PACKET_SIZE];
        ByteArrayOutputStream batch = new ByteArrayOutputStream(READ_BATCH_MAX);
        // 空闲后的第一个包立刻下发（握手/ACK 这类小响应没有额外延迟）；
        // 随后若数据还在连续到达，就切到“攒包”模式，按帧尾空隙成批下发。
        boolean coalescing = false;
        try {
            while (readRunning) {
                int timeout = coalescing ? READ_COALESCE_TIMEOUT_MS : READ_IDLE_TIMEOUT_MS;
                int n;
                try {
                    n = conn.bulkTransfer(ep, buf, READ_PACKET_SIZE, timeout);
                } catch (Throwable t) {
                    if (readRunning) {
                        pushReadError("USB 读取异常: " + t.getMessage());
                    }
                    return;
                }
                if (!readRunning) {
                    return;
                }
                if (n > 0) {
                    batch.write(buf, 0, n);
                    if (!coalescing) {
                        flushBatch(batch);
                        coalescing = true;
                        continue;
                    }
                    if (batch.size() >= READ_BATCH_MAX) {
                        flushBatch(batch);
                    }
                    continue;
                }
                if (batch.size() > 0) {
                    // 帧尾空隙：把攒下的数据整批下发，回到空闲模式。
                    flushBatch(batch);
                    coalescing = false;
                    continue;
                }
                // 完全空闲：继续用 60ms 超时轮询，省电且不拖慢响应。
                coalescing = false;
                if (n < 0 && !isDevicePresent()) {
                    pushReadError("USB 设备已断开");
                    return;
                }
            }
        } finally {
            if (batch.size() > 0) {
                flushBatch(batch);
            }
        }
    }

    private void flushBatch(ByteArrayOutputStream batch) {
        if (batch.size() == 0) {
            return;
        }
        byte[] bytes = batch.toByteArray();
        batch.reset();
        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        host.evalInPage("window.__K5USB&&window.__K5USB.onData(\"" + b64 + "\")");
    }

    private void pushReadError(String message) {
        host.evalInPage("window.__K5USB&&window.__K5USB.onReadError("
                + JSONObject.quote(message == null ? "USB 读取失败" : message) + ")");
    }

    @JavascriptInterface
    public void clearHalt(String id, String argsJson) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject a = new JSONObject(argsJson);
                    UsbDeviceConnection conn = requireConnection();
                    boolean in = "in".equalsIgnoreCase(a.optString("direction", "in"));
                    int epNum = a.optInt("endpoint", 0);
                    UsbEndpoint ep = in ? inEndpoints.get(epNum) : outEndpoints.get(epNum);
                    if (ep == null) {
                        replyOk(id, new JSONObject());
                        return;
                    }
                    int bm = in ? 0x02 : 0x02;
                    conn.controlTransfer(bm, 1 /* CLEAR_FEATURE */, 0,
                            ep.getAddress(), new byte[0], 0, CONTROL_TIMEOUT_MS);
                    replyOk(id, new JSONObject());
                } catch (Throwable t) {
                    replyError(id, t);
                }
            }
        });
    }

    @JavascriptInterface
    public void saveFile(String id, String argsJson) {
        try {
            JSONObject a = new JSONObject(argsJson);
            String name = a.optString("name", "k5web-download.bin");
            String mime = a.optString("mime", "application/octet-stream");
            byte[] data = decodeBase64(a.optString("data", ""));
            host.saveFile(id, name, mime, data);
        } catch (Throwable t) {
            replyError(id, t);
        }
    }

    @JavascriptInterface
    public String deviceInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("android", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("model", Build.MODEL);
            JSONArray list = new JSONArray();
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                JSONObject item = new JSONObject();
                item.put("vendorId", d.getVendorId());
                item.put("productId", d.getProductId());
                item.put("name", d.getDeviceName());
                item.put("granted", usbManager.hasPermission(d));
                list.put(item);
            }
            o.put("devices", list);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    /** 任务①：系统是否处于深色模式（页面首屏引导脚本在 matchMedia 不可用时的兜底）。 */
    @JavascriptInterface
    public boolean isSystemDark() {
        try {
            int mask = activity.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 任务②：切换 WebView 视口模式（电脑版 UA + wide viewport / 手机版默认）。 */
    @JavascriptInterface
    public void setViewportMode(final String mode, final String reloadFlag) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    viewportMode = "desktop".equals(mode) ? "desktop" : "mobile";
                    WebView wv = host.webView();
                    if (wv == null) {
                        return;
                    }
                    android.webkit.WebSettings s = wv.getSettings();
                    if ("desktop".equals(viewportMode)) {
                        s.setUserAgentString(DESKTOP_UA);
                        s.setUseWideViewPort(true);
                        s.setLoadWithOverviewMode(true);
                    } else {
                        String ua = defaultUserAgent != null ? defaultUserAgent : s.getUserAgentString();
                        s.setUserAgentString(ua);
                        s.setUseWideViewPort(false);
                        s.setLoadWithOverviewMode(false);
                    }
                    if (!"0".equals(reloadFlag)) {
                        wv.reload();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "setViewportMode failed", t);
                }
            }
        });
    }

    @JavascriptInterface
    public String getViewportMode() {
        return viewportMode;
    }

    /** 任务④：WebView 无元素级全屏时，进入原生沉浸式横屏（伪全屏）。 */
    @JavascriptInterface
    public void enterAppFullscreen() {
        host.enterImmersive();
    }

    @JavascriptInterface
    public void exitAppFullscreen() {
        host.exitImmersive();
    }

    // ---------------------------------------------------------------- 设备枚举

    private List<UsbDevice> findMatchingDevices(JSONArray filters) {
        List<UsbDevice> out = new ArrayList<>();
        Map<String, UsbDevice> all = usbManager.getDeviceList();
        if (filters == null || filters.length() == 0) {
            for (UsbDevice d : all.values()) {
                if (hasVendorInterface(d)) {
                    out.add(d);
                }
            }
            return out;
        }
        for (int i = 0; i < filters.length(); i++) {
            JSONObject f = filters.optJSONObject(i);
            if (f == null) {
                continue;
            }
            int vid = f.optInt("vendorId", -1);
            int pid = f.optInt("productId", -1);
            for (UsbDevice d : all.values()) {
                if (d.getVendorId() != vid) {
                    continue;
                }
                if (pid >= 0 && d.getProductId() != pid) {
                    continue;
                }
                if (!out.contains(d)) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    private UsbDevice findDeviceById(int vendorId, int productId) {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getVendorId() == vendorId && d.getProductId() == productId) {
                return d;
            }
        }
        // 写频线 VID/PID 相同、可能重新枚举成新对象：退化成按当前连接匹配
        if (vendorId < 0) {
            return device;
        }
        return null;
    }

    private static boolean hasVendorInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            int cls = d.getInterface(i).getInterfaceClass();
            if (cls == 0xff || cls == 0x02) {
                return true;
            }
        }
        return false;
    }

    private boolean isDevicePresent() {
        if (device == null) {
            return false;
        }
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getDeviceName().equals(device.getDeviceName())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 描述符

    private void indexInterfaces(UsbDevice dev) {
        interfaces.clear();
        inEndpoints.clear();
        outEndpoints.clear();
        claimed.clear();
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface itf = dev.getInterface(i);
            // 同一个 interfaceNumber 可能有多个 alternate setting，保留第一个就够了
            if (!interfaces.containsKey(itf.getId())) {
                interfaces.put(itf.getId(), itf);
            }
            for (int e = 0; e < itf.getEndpointCount(); e++) {
                UsbEndpoint ep = itf.getEndpoint(e);
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    if (!inEndpoints.containsKey(ep.getEndpointNumber())) {
                        inEndpoints.put(ep.getEndpointNumber(), ep);
                    }
                } else {
                    if (!outEndpoints.containsKey(ep.getEndpointNumber())) {
                        outEndpoints.put(ep.getEndpointNumber(), ep);
                    }
                }
            }
        }
    }

    private JSONObject buildConfigurationJson(UsbDevice dev) throws JSONException {
        JSONObject cfg = new JSONObject();
        cfg.put("configurationValue", 1);
        JSONArray ifaces = new JSONArray();
        Map<Integer, List<UsbInterface>> grouped = new HashMap<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface itf = dev.getInterface(i);
            int id = itf.getId();
            if (!grouped.containsKey(id)) {
                grouped.put(id, new ArrayList<UsbInterface>());
                order.add(id);
            }
            grouped.get(id).add(itf);
        }
        for (int id : order) {
            JSONObject iface = new JSONObject();
            iface.put("interfaceNumber", id);
            JSONArray alts = new JSONArray();
            for (UsbInterface itf : grouped.get(id)) {
                JSONObject alt = new JSONObject();
                alt.put("interfaceClass", itf.getInterfaceClass());
                alt.put("interfaceSubclass", itf.getInterfaceSubclass());
                alt.put("interfaceProtocol", itf.getInterfaceProtocol());
                alt.put("alternateSetting", itf.getAlternateSetting());
                JSONArray eps = new JSONArray();
                for (int e = 0; e < itf.getEndpointCount(); e++) {
                    UsbEndpoint ep = itf.getEndpoint(e);
                    JSONObject eo = new JSONObject();
                    eo.put("endpointNumber", ep.getEndpointNumber());
                    eo.put("direction", ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out");
                    eo.put("type", endpointType(ep.getType()));
                    // 给 JS 侧的报告窗口故意放大：驱动据此决定一次 transferIn/Out 传多少。
                    // 真实 USB 包仍是 32B，由原生读写线程负责分片/攒包，这样 JS 一帧只付
                    // 一次桥接成本，而不是每 32B 一次。
                    int reportWindow;
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        reportWindow = ep.getDirection() == UsbConstants.USB_DIR_IN ? 1024 : 4096;
                    } else {
                        reportWindow = ep.getMaxPacketSize();
                    }
                    eo.put("packetSize", reportWindow);
                    eo.put("address", ep.getAddress());
                    eps.put(eo);
                }
                alt.put("endpoints", eps);
                alts.put(alt);
            }
            iface.put("alternates", alts);
            ifaces.put(iface);
        }
        cfg.put("interfaces", ifaces);
        return cfg;
    }

    private static String endpointType(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL:
                return "control";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC:
                return "isochronous";
            case UsbConstants.USB_ENDPOINT_XFER_INT:
                return "interrupt";
            default:
                return "bulk";
        }
    }

    private static int bmRequestType(String requestType, String recipient, boolean in) {
        int type;
        if ("standard".equals(requestType)) {
            type = 0x00;
        } else if ("class".equals(requestType)) {
            type = 0x20;
        } else {
            type = 0x40;
        }
        int rec;
        switch (recipient) {
            case "interface":
                rec = 1;
                break;
            case "endpoint":
                rec = 2;
                break;
            case "other":
                rec = 3;
                break;
            default:
                rec = 0;
        }
        return (in ? 0x80 : 0x00) | type | rec;
    }

    // ---------------------------------------------------------------- 工具

    private UsbDeviceConnection requireConnection() throws BridgeError {
        if (connection == null) {
            throw new BridgeError("InvalidStateError", "USB 设备尚未打开");
        }
        return connection;
    }

    private void releaseAll() {
        // 先停读线程，再释放接口/关闭连接，避免 bulkTransfer 打到已关闭的 fd 上。
        stopReadLoop();
        for (UsbInterface itf : new ArrayList<>(claimed)) {
            try {
                if (connection != null) {
                    connection.releaseInterface(itf);
                }
            } catch (Throwable ignored) {
            }
        }
        claimed.clear();
        if (connection != null) {
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
            connection = null;
        }
        device = null;
        inEndpoints.clear();
        outEndpoints.clear();
        interfaces.clear();
    }

    private static byte[] decodeBase64(String s) {
        if (s == null || s.length() == 0) {
            return new byte[0];
        }
        try {
            return Base64.decode(s, Base64.DEFAULT);
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static String encodeBase64(byte[] data, int length) {
        int n = Math.max(0, Math.min(length, data.length));
        return Base64.encodeToString(data, 0, n, Base64.NO_WRAP);
    }

    private static JSONArray parseFilters(String argsJson) {
        try {
            JSONObject o = new JSONObject(argsJson == null ? "{}" : argsJson);
            JSONArray f = o.optJSONArray("filters");
            return f == null ? new JSONArray() : f;
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private void replyDevice(String id, UsbDevice dev) {
        try {
            JSONObject data = new JSONObject();
            data.put("vendorId", dev.getVendorId());
            data.put("productId", dev.getProductId());
            data.put("vendor", dev.getManufacturerName());
            data.put("product", dev.getProductName());
            try {
                data.put("serial", dev.getSerialNumber());
            } catch (Throwable ignored) {
            }
            replyOk(id, data);
        } catch (Throwable t) {
            replyError(id, t);
        }
    }

    private void replyStatus(String id, String status) {
        try {
            JSONObject data = new JSONObject();
            data.put("status", status);
            replyOk(id, data);
        } catch (Throwable t) {
            replyError(id, t);
        }
    }

    private void replyOk(String id, JSONObject data) {
        JSONObject env = new JSONObject();
        try {
            env.put("ok", true);
            env.put("data", data == null ? new JSONObject() : data);
        } catch (Throwable ignored) {
        }
        host.reply(id, env.toString());
    }

    private void replyError(String id, Throwable t) {
        if (t instanceof BridgeError) {
            replyError(id, ((BridgeError) t).name, t.getMessage());
        } else {
            replyError(id, "NetworkError", t == null ? "unknown" : (t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    private void replyError(String id, String name, String message) {
        Log.w(TAG, "usb error " + name + ": " + message);
        JSONObject env = new JSONObject();
        try {
            env.put("ok", false);
            env.put("name", name);
            env.put("message", message);
        } catch (Throwable ignored) {
        }
        host.reply(id, env.toString());
    }

    private static final class BridgeError extends Exception {
        final String name;

        BridgeError(String name, String message) {
            super(message);
            this.name = name;
        }
    }

    // ---------------------------------------------------------------- 卫星定位兜底
    //
    // 页面网络定位失败（无网络 / 网络定位超时 / 位置不可用）时由 JS 经
    // K5GEO.getLocation() 调起：走系统 GNSS（卫星）定位取经纬度，结果经
    // replyOk/replyError 回 __K5USB.onReply，与 USB 桥共用同一套回调约定。

    /** 单次定位整体超时：GPS 冷启动首次定位可能较慢，放宽到 45s。 */
    private static final long GEO_TIMEOUT_MS = 45 * 1000L;
    /** 最近位置新鲜度阈值：5 分钟内视为可用，直接返回（离线也可用）。 */
    private static final long GEO_FRESH_MS = 5 * 60 * 1000L;

    @JavascriptInterface
    public void getLocation(final String id, final String argsJson) {
        // 定位可能等待数十秒，绝不能占用 USB IO 线程，单独起守护线程。
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                geoAcquire(id);
            }
        }, "k5-geo");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 定位环境前置检查（每次定位检测的开头调用）：先权限检测，随后立即判断
     * 定位服务是否已开启（位于权限检测之后、其他定位逻辑之前）。任一不通过
     * 时直接回复错误（服务未开启会弹窗引导），通过时回复 ok。
     */
    @JavascriptInterface
    public void checkLocationEnv(final String id, final String argsJson) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (geoEnvReady(id)) {
                        replyOk(id, new JSONObject());
                    }
                } catch (Throwable t) {
                    replyError(id, t);
                }
            }
        }, "k5-geoenv");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 定位环境前置检查公共实现：① 权限检测；② 定位服务开关检测（位于权限
     * 检测之后）。不通过时回复对应错误并返回 false（服务未开启同时弹窗引导）。
     */
    private boolean geoEnvReady(final String id) {
        boolean fine = activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean coarse = activity.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            replyError(id, "NotAllowedError", "定位权限未授予：请在系统设置中允许本应用使用位置权限");
            return false;
        }
        final LocationManager lm = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            replyError(id, "NotSupportedError", "设备不支持定位服务");
            return false;
        }
        boolean gpsOn = geoProviderEnabled(lm, LocationManager.GPS_PROVIDER);
        boolean netOn = geoProviderEnabled(lm, LocationManager.NETWORK_PROVIDER);
        if (!gpsOn && !netOn) {
            // 定位服务总开关未开启：弹窗引导用户去系统设置开启，而不是静默失败/超时
            promptEnableLocationService();
            replyError(id, "LocationServiceDisabled", "设备定位服务未开启：请在弹窗中开启定位服务后重试");
            return false;
        }
        return true;
    }

    private void geoAcquire(final String id) {
        try {
            // 前置环境检查：先权限，后定位服务开关；未通过（含服务未开启弹窗）直接返回
            if (!geoEnvReady(id)) {
                return;
            }
            final LocationManager lm = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);

            // 1) 有 5 分钟内的最近位置时直接返回（无网络也能拿到 GPS 缓存结果）
            Location recent = geoBetter(lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                    lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
            if (recent != null && System.currentTimeMillis() - recent.getTime() < GEO_FRESH_MS) {
                replyLocation(id, recent);
                return;
            }

            // 2) 请求一次实时定位：GPS(卫星) 优先，网络 Provider 若开启则并行竞速，首个结果胜出
            boolean gpsOn = geoProviderEnabled(lm, LocationManager.GPS_PROVIDER);
            boolean netOn = geoProviderEnabled(lm, LocationManager.NETWORK_PROVIDER);
            final GeoSession session = new GeoSession(this, id, lm);
            try {
                if (gpsOn) {
                    lm.requestSingleUpdate(LocationManager.GPS_PROVIDER,
                            session.newListener(), session.main.getLooper());
                }
                if (netOn) {
                    lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,
                            session.newListener(), session.main.getLooper());
                }
            } catch (Throwable t) {
                if (session.listeners.isEmpty()) {
                    replyError(id, "NotAvailableError", "无法发起卫星定位请求：" + t.getMessage());
                    return;
                }
                Log.w(TAG, "requestSingleUpdate partial failure", t);
            }
            session.armTimeout(new Runnable() {
                @Override
                public void run() {
                    if (session.settled.compareAndSet(false, true)) {
                        session.cancel();
                        replyError(id, "TimeoutError", "卫星定位超时：请到空旷处重试，或检查设备定位服务");
                    }
                }
            }, GEO_TIMEOUT_MS);
        } catch (Throwable t) {
            replyError(id, t);
        }
    }

    /** 一次定位请求的会话状态：监听器集合 + 超时任务，成功/超时后统一清理。 */
    private static final class GeoSession {
        final K5Bridge bridge;
        final String id;
        final LocationManager lm;
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final java.util.concurrent.atomic.AtomicBoolean settled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final List<LocationListener> listeners = new ArrayList<>();
        Runnable timeout;

        GeoSession(K5Bridge bridge, String id, LocationManager lm) {
            this.bridge = bridge;
            this.id = id;
            this.lm = lm;
        }

        LocationListener newListener() {
            LocationListener l = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location == null) {
                        return;
                    }
                    if (settled.compareAndSet(false, true)) {
                        cancel();
                        bridge.replyLocation(id, location);
                    }
                }

                @Override
                public void onProviderDisabled(String provider) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
                }
            };
            listeners.add(l);
            return l;
        }

        void armTimeout(Runnable r, long delayMs) {
            timeout = r;
            main.postDelayed(r, delayMs);
        }

        void cancel() {
            Runnable t = timeout;
            if (t != null) {
                main.removeCallbacks(t);
                timeout = null;
            }
            for (LocationListener l : listeners) {
                try {
                    lm.removeUpdates(l);
                } catch (Throwable ignored) {
                }
            }
            listeners.clear();
        }
    }

    private boolean geoProviderEnabled(LocationManager lm, String provider) {
        try {
            return lm.isProviderEnabled(provider);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 定位服务（GPS/网络定位开关）未开启时弹窗引导用户前往系统设置开启；重复触发不叠加弹窗。 */
    private android.app.AlertDialog geoSettingsDialog;

    private void promptEnableLocationService() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (geoSettingsDialog != null && geoSettingsDialog.isShowing()) {
                        return; // 已有引导弹窗，不重复弹
                    }
                    geoSettingsDialog = new android.app.AlertDialog.Builder(activity)
                            .setTitle("定位服务未开启")
                            .setMessage("获取经纬度需要开启设备的定位服务（GPS）。是否前往系统设置开启？")
                            .setCancelable(true)
                            .setPositiveButton("去开启", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    try {
                                        activity.startActivity(new Intent(
                                                android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                                    } catch (Throwable t) {
                                        Log.w(TAG, "open location settings failed", t);
                                    }
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                } catch (Throwable t) {
                    Log.w(TAG, "show location dialog failed", t);
                    // 弹窗失败（如页面切换中）时退化为 Toast 提示
                    try {
                        android.widget.Toast.makeText(activity,
                                "设备定位服务未开启：请打开系统的 GPS/定位开关",
                                android.widget.Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private Location geoBetter(Location a, Location b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.getTime() >= b.getTime() ? a : b;
    }

    private void replyLocation(String id, Location loc) {
        JSONObject data = new JSONObject();
        try {
            data.put("latitude", loc.getLatitude());
            data.put("longitude", loc.getLongitude());
            if (loc.hasAltitude()) {
                data.put("altitude", loc.getAltitude());
            }
            if (loc.hasAccuracy()) {
                data.put("accuracy", loc.getAccuracy());
            }
            data.put("provider", loc.getProvider());
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "geo fix via " + loc.getProvider());
        replyOk(id, data);
    }
}
