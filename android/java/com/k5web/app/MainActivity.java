package com.k5web.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * K5Web 安卓壳：把打包进 APK 的离线前端放在自定义 https 源上加载，
 * 再通过 K5Bridge 暴露原生 USB（CH340/CH341），补上安卓 WebView 缺失的 WebUSB。
 */
public class MainActivity extends Activity implements K5Bridge.Host {
    private static final String TAG = "K5Web";
    private static final String APP_HOST = "k5.local";
    private static final String START_URL = "https://" + APP_HOST + "/index.html";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_SAVE_FILE = 1002;
    private static final int REQ_GEO_LOCATION = 1003;

    private WebView webView;
    private K5Bridge bridge;
    private ValueCallback<Uri[]> fileCallback;

    private String pendingSaveId;
    private String pendingSaveName;
    private byte[] pendingSaveData;

    /** 页面 geolocation 请求：运行时权限回来后再回调给 WebView。 */
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    /** 任务③：系统选择框选中本应用后，等待页面加载完成的 USB 设备。 */
    private UsbDevice pendingAttachedDevice;
    private volatile boolean pageReady;

    /** 任务④：伪全屏（沉浸式横屏）进行中，窗口重新获得焦点时需要重新应用。 */
    private volatile boolean immersiveActive;

    /** 应用层固定的 手机/电脑版 切换按钮（WebView 之上的原生控件，不随页面缩放）。 */
    private TextView viewportBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        createWebView();
        // 不保存/不恢复上次使用状态：每次冷启动都从首页开始，不恢复页面历史。
        webView.loadUrl(START_URL);
        // 任务③：从「为USB设备选择一个应用」对话框启动时，Intent 里带有 USB 设备。
        handleUsbAttachedIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask：应用已运行时系统直接把任务切到前台并回调这里（任务③）。
        handleUsbAttachedIntent(intent);
    }

    private void handleUsbAttachedIntent(Intent intent) {
        if (intent == null
                || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            return;
        }
        UsbDevice dev;
        try {
            dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        } catch (Throwable t) {
            dev = null;
        }
        if (dev == null) {
            return;
        }
        if (pageReady && bridge != null) {
            bridge.autoAttachDevice(dev);
        } else {
            // 页面尚未加载完成：先记录，等 onPageFinished 后自动补发。
            pendingAttachedDevice = dev;
        }
    }

    private void createWebView() {
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 任务②：允许手势捏合自由缩放（隐藏 +/- 按钮气泡）
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        if (Build.VERSION.SDK_INT >= 26) {
            s.setSafeBrowsingEnabled(false);
        }
        if (Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                if (uri == null || !APP_HOST.equalsIgnoreCase(uri.getHost())) {
                    return null;
                }
                return serveAsset(uri.getPath());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                if (uri == null || APP_HOST.equalsIgnoreCase(uri.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.e(TAG, "WebView render process gone, rebuilding");
                rebuildWebView(view);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 任务③：页面就绪后才能接收设备推送；补发等待中的自动连接设备。
                Uri uri = url == null ? null : Uri.parse(url);
                if (uri != null && !APP_HOST.equalsIgnoreCase(uri.getHost())) {
                    return;
                }
                pageReady = true;
                if (pendingAttachedDevice != null && bridge != null) {
                    bridge.autoAttachDevice(pendingAttachedDevice);
                    pendingAttachedDevice = null;
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                String type = "*/*";
                try {
                    String[] types = params == null ? null : params.getAcceptTypes();
                    if (types != null && types.length > 0 && types[0] != null && types[0].length() > 0) {
                        String t = types[0].split(",")[0].trim();
                        if (t.contains("/") && !t.startsWith(".")) {
                            type = t;
                        }
                    }
                } catch (Throwable ignored) {
                }
                intent.setType(type);
                if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null) {
                    Log.i(TAG, "console: " + cm.message() + " @" + cm.sourceId() + ":" + cm.lineNumber());
                }
                return true;
            }

            // 页面 geolocation（星位上传 / 卫星过境定位）：WebView 默认全部拒绝，
            // 必须在这里放行；没有运行时定位权限时先向系统申请，结果回来再回调。
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                try {
                    if (hasLocationPermission()) {
                        callback.invoke(origin, true, false);
                        return;
                    }
                    pendingGeoCallback = callback;
                    pendingGeoOrigin = origin;
                    requestPermissions(new String[]{
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    }, REQ_GEO_LOCATION);
                } catch (Throwable t) {
                    Log.w(TAG, "geolocation prompt failed", t);
                    try {
                        callback.invoke(origin, false, false);
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onGeolocationPermissionsHidePrompt() {
                pendingGeoCallback = null;
                pendingGeoOrigin = null;
            }
        });

        bridge = new K5Bridge(this);
        webView.addJavascriptInterface(bridge, "K5Native");

        // 应用层固定控件：WebView 之上叠一个原生切换按钮（手机版 ⇄ 电脑版）。
        // 原生控件在 WebView 窗口之外，页面缩放/布局变化不影响其尺寸（任务：尺寸稳定）。
        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(webView, wlp);

        viewportBtn = buildViewportButton();
        final FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                dp(52), dp(52), Gravity.BOTTOM | Gravity.END);
        blp.rightMargin = dp(16);
        blp.bottomMargin = dp(20);
        root.addView(viewportBtn, blp);
        // 跟随导航栏高度调整按钮位置，避免被系统导航栏遮挡
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                try {
                    FrameLayout.LayoutParams lp2 = (FrameLayout.LayoutParams)
                            viewportBtn.getLayoutParams();
                    lp2.bottomMargin = dp(20) + insets.getSystemWindowInsetBottom();
                    viewportBtn.setLayoutParams(lp2);
                } catch (Throwable ignored) {
                }
                return insets;
            }
        });
        setContentView(root);
        updateViewportButton();
    }

    /**
     * 构建右下角的原生切换按钮（52dp 圆形，图标体现当前模式状态）。
     * 支持按住拖动换位：位置实时跟手、边界钳制，松手停在最终位置（不吸附、
     * 不持久化——与"退出不保存历史状态"一致，重启回到默认右下角）；
     * 位移未超过触摸阈值视为点击，保留切换功能。
     */
    private TextView buildViewportButton() {
        TextView btn = new TextView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xD9165DFF);
        btn.setBackground(bg);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        btn.setGravity(Gravity.CENTER);
        btn.setAllCaps(false);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumWidth(0);
        btn.setMinimumHeight(0);
        btn.setPadding(0, 0, 0, 0);
        btn.setElevation(dp(4));
        btn.setOnTouchListener(buildViewportDragListener());
        return btn;
    }

    /** 按钮拖动/点击统一手势处理（Pointer 由 MotionEvent 原生处理）。 */
    private View.OnTouchListener buildViewportDragListener() {
        return new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startLeft;
            private int startTop;
            private boolean dragging;
            private final int touchSlop = ViewConfiguration
                    .get(MainActivity.this).getScaledTouchSlop();

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startLeft = v.getLeft();
                        startTop = v.getTop();
                        dragging = false;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging) {
                            if (Math.hypot(dx, dy) <= touchSlop) {
                                return true;
                            }
                            // 进入拖拽：转为绝对定位便于实时跟随，振动给出明确反馈
                            dragging = true;
                            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)
                                    v.getLayoutParams();
                            lp.gravity = Gravity.TOP | Gravity.START;
                            lp.leftMargin = startLeft;
                            lp.topMargin = startTop;
                            lp.rightMargin = 0;
                            lp.bottomMargin = 0;
                            v.setLayoutParams(lp);
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        }
                        // 实时跟手：left/top 即时更新，边界内钳制
                        ViewGroup parent = (ViewGroup) v.getParent();
                        int maxX = Math.max(0, parent.getWidth() - v.getWidth());
                        int maxY = Math.max(0, parent.getHeight() - v.getHeight());
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)
                                v.getLayoutParams();
                        lp.leftMargin = Math.min(Math.max(0, startLeft + Math.round(dx)), maxX);
                        lp.topMargin = Math.min(Math.max(0, startTop + Math.round(dy)), maxY);
                        v.setLayoutParams(lp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            // 未拖动 → 判定为点击，切换模式
                            toggleViewport();
                        }
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    /** 点击切换：热切换（不 reload、无白屏），页面内容经 meta viewport 即时重排。 */
    private void toggleViewport() {
        try {
            boolean desktop = bridge != null && "desktop".equals(bridge.getViewportMode());
            final String next = desktop ? "mobile" : "desktop";
            // 交给页面桥接层统一处理：原生 UA/宽视口 + 现场改写 meta viewport，
            // 与页面内原有 __K5USB.setViewportMode 为同一条路径，功能保持一致。
            evalInPage("window.__K5USB&&window.__K5USB.setViewportMode('" + next + "')");
            viewportBtn.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateViewportButton();
                }
            }, 60);
            toast(desktop ? "已切换到手机版视图" : "已切换到电脑版视图");
        } catch (Throwable t) {
            Log.w(TAG, "toggleViewport failed", t);
        }
    }

    /** 按钮图标体现当前模式状态：📱=当前手机版，🖥️=当前电脑版；点按切换、按住拖动。 */
    private void updateViewportButton() {
        if (viewportBtn == null) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (viewportBtn == null) {
                    return;
                }
                boolean desktop = bridge != null && "desktop".equals(bridge.getViewportMode());
                viewportBtn.setText(desktop ? "🖥️" : "📱");
                viewportBtn.setContentDescription(desktop
                        ? "当前电脑版视图：点按切换手机版，按住可拖动"
                        : "当前手机版视图：点按切换电脑版，按住可拖动");
            }
        });
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void rebuildWebView(WebView dying) {
        final ViewGroup parent = dying == null ? null : (ViewGroup) dying.getParent();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (bridge != null) {
                        bridge.close();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (parent != null) {
                        parent.removeView(dying);
                    }
                    if (dying != null) {
                        dying.destroy();
                    }
                } catch (Throwable ignored) {
                }
                pageReady = false;
                createWebView();
                webView.loadUrl(START_URL);
            }
        });
    }

    // ------------------------------------------------------------ 资源服务

    private WebResourceResponse serveAsset(String rawPath) {
        String path = (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath))
                ? "index.html" : rawPath.substring(1);
        if (path.contains("..")) {
            return textResponse("bad path");
        }
        try {
            byte[] bytes = readFully(getAssets().open("www/" + path));
            String mime = mimeOf(path);
            if ("text/html".equals(mime)) {
                String html = new String(bytes, StandardCharsets.UTF_8);
                html = injectBridgeScript(html);
                bytes = html.getBytes(StandardCharsets.UTF_8);
            }
            return new WebResourceResponse(mime, isText(mime) ? "utf-8" : null,
                    new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return textResponse("not found: " + path);
        }
    }

    private static WebResourceResponse textResponse(String text) {
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String injectBridgeScript(String html) {
        // 只认真正的 <script src="...k5-bridge.js"> 标签，而不是任意出现 "k5-bridge.js"
        // 字样的文本——页面引导脚本的注释里也提到了 k5-bridge.js，裸字符串守卫会
        // 误判为"已注入"，导致桥接脚本从未加载、navigator.usb 缺失（v1.0.3 串口失效根因）。
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<script[^>]*src=(\"|')[^\"']*k5-bridge\\.js[^\"']*\\1",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        if (m.find()) {
            return html;
        }
        String tag = "<script src=\"k5-bridge.js\"></script>";
        int idx = html.toLowerCase(Locale.ROOT).indexOf("<head>");
        if (idx >= 0) {
            return html.substring(0, idx + 6) + tag + html.substring(idx + 6);
        }
        return tag + html;
    }

    private static boolean isText(String mime) {
        return mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript")
                || mime.contains("xml") || mime.contains("svg");
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        int dot = p.lastIndexOf('.');
        String ext = dot >= 0 ? p.substring(dot + 1) : "";
        switch (ext) {
            case "html":
            case "htm":
                return "text/html";
            case "js":
            case "mjs":
                return "application/javascript";
            case "css":
                return "text/css";
            case "json":
                return "application/json";
            case "svg":
                return "image/svg+xml";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "ico":
                return "image/x-icon";
            case "woff":
                return "font/woff";
            case "woff2":
                return "font/woff2";
            case "ttf":
                return "font/ttf";
            case "wasm":
                return "application/wasm";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "bin":
            case "jpg.bin":
                return "application/octet-stream";
            case "txt":
                return "text/plain";
            default:
                return "application/octet-stream";
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 文件读写

    @Override
    public void saveFile(final String id, final String name, String mime, final byte[] data) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                pendingSaveId = id;
                pendingSaveName = name;
                pendingSaveData = data;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                intent.putExtra(Intent.EXTRA_TITLE, name == null || name.isEmpty() ? "k5web.bin" : name);
                try {
                    startActivityForResult(intent, REQ_SAVE_FILE);
                } catch (Throwable t) {
                    pendingSaveId = null;
                    pendingSaveData = null;
                    reply(id, errorEnvelope("NetworkError", "无法打开系统保存对话框"));
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
            return;
        }
        if (requestCode == REQ_SAVE_FILE) {
            String id = pendingSaveId;
            String name = pendingSaveName;
            byte[] bytes = pendingSaveData;
            pendingSaveId = null;
            pendingSaveName = null;
            pendingSaveData = null;
            if (id == null) {
                return;
            }
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                reply(id, "{\"ok\":true,\"data\":{\"saved\":false,\"cancelled\":true}}");
                return;
            }
            Uri target = data.getData();
            try (OutputStream os = getContentResolver().openOutputStream(target, "w")) {
                os.write(bytes == null ? new byte[0] : bytes);
                os.flush();
                JSONObject payload = new JSONObject();
                payload.put("saved", true);
                payload.put("name", name);
                payload.put("uri", target.toString());
                reply(id, "{\"ok\":true,\"data\":" + payload + "}");
                toast("已保存：" + name);
            } catch (Throwable t) {
                reply(id, errorEnvelope("NetworkError", "写入文件失败：" + t.getMessage()));
            }
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_GEO_LOCATION) {
            return;
        }
        GeolocationPermissions.Callback callback = pendingGeoCallback;
        String origin = pendingGeoOrigin;
        pendingGeoCallback = null;
        pendingGeoOrigin = null;
        if (callback == null) {
            return;
        }
        boolean granted = false;
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }
        try {
            callback.invoke(origin, granted, false);
        } catch (Throwable ignored) {
        }
        if (!granted) {
            toast("定位被拒绝：请在系统设置中允许本应用使用位置权限");
        }
    }

    private static String errorEnvelope(String name, String message) {
        return "{\"ok\":false,\"name\":" + JSONObject.quote(name)
                + ",\"message\":" + JSONObject.quote(message) + "}";
    }

    private void toast(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------ Host 实现

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public WebView webView() {
        return webView;
    }

    @Override
    public void reply(final String id, final String envelopeJson) {
        final WebView target = webView;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (target == null) {
                    return;
                }
                String js = "window.__K5USB&&window.__K5USB.onReply("
                        + JSONObject.quote(id) + "," + JSONObject.quote(envelopeJson) + ");";
                try {
                    target.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    public void evalInPage(final String js) {
        final WebView target = webView;
        if (target == null) {
            return;
        }
        // 从 USB 读线程调用，必须转到 UI 线程；不等待执行结果，读线程不会被页面拖慢。
        target.post(new Runnable() {
            @Override
            public void run() {
                try {
                    target.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------ 沉浸式全屏（任务④）

    @Override
    public void enterImmersive() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    immersiveActive = true;
                    applyImmersive();
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    if (viewportBtn != null) {
                        viewportBtn.setVisibility(View.GONE);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    public void exitImmersive() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    immersiveActive = false;
                    getWindow().getDecorView().setSystemUiVisibility(0);
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    if (viewportBtn != null) {
                        viewportBtn.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 任务④：权限弹窗/对话框关闭后窗口重新聚焦，系统栏会被系统恢复，
        // 伪全屏进行中时重新应用沉浸式标志，保证编辑不被打断。
        if (hasFocus && immersiveActive) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        applyImmersive();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 任务①：manifest 已声明 uiMode 不重建 Activity，这里把系统深浅色变化实时推给页面。
        int mask = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean dark = mask == Configuration.UI_MODE_NIGHT_YES;
        if (pageReady) {
            evalInPage("window.__K5USB&&window.__K5USB.onSystemThemeChanged(" + dark + ")");
        }
    }

    @Override
    public void onBackPressed() {
        // 任务④：伪全屏编辑时，返回键先退出全屏而不是回退页面历史。
        final WebView target = webView;
        if (target == null) {
            super.onBackPressed();
            return;
        }
        try {
            target.evaluateJavascript(
                    "(function(){var el=document.querySelector('.pixel-editor-host.is-fs');"
                            + "if(el){window.dispatchEvent(new Event('k5app:exit-fs'));return '1';}"
                            + "return '0';})()",
                    value -> {
                        if (!"\"1\"".equals(value)) {
                            if (webView != null && webView.canGoBack()) {
                                webView.goBack();
                            } else {
                                finish();
                            }
                        }
                    });
        } catch (Throwable t) {
            if (target.canGoBack()) {
                target.goBack();
            } else {
                super.onBackPressed();
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (bridge != null) {
                bridge.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (webView != null) {
                webView.destroy();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
