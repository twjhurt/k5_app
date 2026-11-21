/**
 * K5Web 安卓桥（注入到每个页面 <head> 最前面）。
 *
 * 安卓 WebView 没有 WebUSB / Web Serial，这里用原生 K5Native 接口补一个足够完整的
 * navigator.usb，使得 src/utils/webusb-ch341.js 原样可用；同时接管 <a download> 的
 * blob 下载，改走系统「另存为」对话框，否则 WebView 里备份 EEPROM / 导出 xlsx 会静默失败。
 */
(function () {
  'use strict';

  var NATIVE = window.K5Native;
  if (!NATIVE) {
    return;
  }
  if (window.__K5USB_INSTALLED__) {
    return;
  }
  window.__K5USB_INSTALLED__ = true;

  var pending = new Map();
  var seq = 0;

  // 原生连续读线程推过来的数据先落队列；JS 读循环从队列里取，不再为每个 32B 包
  // 走一次 JS→Java→UI线程 往返（那是安卓版比 Edge 直连 WebUSB 慢的主要原因）。
  var rxQueue = [];
  var rxQueuedBytes = 0;
  var rxWaiters = [];
  var readEndpoint = -1;
  var readStartPromise = null;

  // ------------------------------------------------------------ 基础编解码

  function b64encode(bytes) {
    if (!bytes || !bytes.length) {
      return '';
    }
    var chunk = 0x8000;
    var out = '';
    for (var i = 0; i < bytes.length; i += chunk) {
      out += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return btoa(out);
  }

  function b64decode(str) {
    if (!str) {
      return new Uint8Array(0);
    }
    var raw = atob(str);
    var bytes = new Uint8Array(raw.length);
    for (var i = 0; i < raw.length; i++) {
      bytes[i] = raw.charCodeAt(i);
    }
    return bytes;
  }

  function toBytes(data) {
    if (!data) {
      return new Uint8Array(0);
    }
    if (data instanceof Uint8Array) {
      return data;
    }
    if (data instanceof ArrayBuffer) {
      return new Uint8Array(data);
    }
    if (ArrayBuffer.isView(data)) {
      return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    }
    return new Uint8Array(data);
  }

  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = String(++seq);
      pending.set(id, { resolve: resolve, reject: reject });
      try {
        NATIVE[method](id, JSON.stringify(args || {}));
      } catch (e) {
        pending.delete(id);
        reject(e);
      }
    });
  }

  function makeError(name, message) {
    var err = new Error(message || name || 'USB 操作失败');
    err.name = name || 'Error';
    return err;
  }

  window.__K5USB = {
    onReply: function (id, json) {
      var entry = pending.get(id);
      if (!entry) {
        return;
      }
      pending.delete(id);
      var env;
      try {
        env = JSON.parse(json);
      } catch (e) {
        entry.reject(makeError('InvalidStateError', '原生回调解析失败'));
        return;
      }
      if (env && env.ok) {
        entry.resolve(env.data || {});
      } else {
        entry.reject(makeError((env && env.name) || 'NetworkError',
            (env && env.message) || 'USB 操作失败'));
      }
    },
    onData: function (b64) {
      pushRx(b64decode(b64));
    },
    onReadError: function (message) {
      errorWaiters(makeError('NetworkError', message || 'USB 读取失败'));
    },
    // 统一保存入口（任务④）：任何下载/保存前先弹系统“另存为”对话框让用户
    // 指定目录；用户取消时返回 {saved:false, cancelled:true}，页面据此中止且不回退。
    saveBlob: function (blob, name) {
      if (!(blob instanceof Blob)) {
        return Promise.resolve({ saved: false, cancelled: false });
      }
      return saveBlob(blob, name);
    },
    // 任务③：原生侧（系统选择框选中本应用并完成授权后）推过来的设备信息。
    onDeviceAttached: function (vendorId, productId) {
      try {
        window.__K5_USB_ATTACHED__ = { vendorId: vendorId, productId: productId };
        window.dispatchEvent(new CustomEvent('k5usb:device-attached', {
          detail: window.__K5_USB_ATTACHED__,
        }));
      } catch (e) {}
    },
    onDeviceAttachError: function (message) {
      try {
        window.dispatchEvent(new CustomEvent('k5usb:device-attach-error', {
          detail: { message: message },
        }));
      } catch (e) {}
    },
    // 任务①：原生推送的系统深浅色变化（configChanges 含 uiMode，Activity 不重建）。
    onSystemThemeChanged: function (dark) {
      try {
        window.dispatchEvent(new CustomEvent('k5usb:system-theme', {
          detail: { dark: !!dark },
        }));
      } catch (e) {}
    },
    // 任务②：视口模式热切换——不再 reload（reload 是白屏卡顿的根源）。
    // 模式由原生内存字段持有（应用层固定按钮驱动），不写 localStorage：
    // 应用退出后状态随之结束，重新打开不恢复历史状态。
    setViewportMode: function (mode) {
      var m = mode === 'desktop' ? 'desktop' : 'mobile';
      try { NATIVE.setViewportMode(m, '0'); } catch (e) {}
      applyViewportMeta(m);
    },
    getViewportMode: function () {
      try { return String(NATIVE.getViewportMode() || 'mobile'); } catch (e) { return 'mobile'; }
    },
    // 任务④：原生沉浸式横屏（WebView 无元素级全屏时的伪全屏）。
    enterAppFullscreen: function () {
      try { NATIVE.enterAppFullscreen(); } catch (e) {}
    },
    exitAppFullscreen: function () {
      try { NATIVE.exitAppFullscreen(); } catch (e) {}
    },
  };

  window.__K5_NATIVE_USB__ = true;

  // 任务①：把原生系统深色状态交给首屏引导脚本（本脚本先于页面所有脚本执行）。
  // 同时立即应用主题属性：本脚本注入在 <head> 最前面，先于 index.html 的
  // 内联引导脚本和一切 CSS，暗色时 loading 遮罩、首屏 DOM 从第一帧就是暗的，
  // 彻底消除"先白后暗"的闪烁。优先级与页面一致：手动偏好 > 原生系统检测。
  try {
    var sysDark = null;
    var apkMode = false;
    if (typeof NATIVE.isSystemDark === 'function') {
      sysDark = !!NATIVE.isSystemDark();
      window.__K5_SYSTEM_DARK__ = sysDark;
      apkMode = true;
    }
    var pref = null;
    try { pref = localStorage.getItem('arco-theme'); } catch (e2) {}
    if (apkMode) {
      // 每次重新打开应用都重新检测系统深浅色：清除上次会话遗留的手动偏好，
      // 手动切换仅在本次会话内有效，跨启动一律跟随系统（系统运行中变化由
      // 原生 onConfigurationChanged → k5usb:system-theme 事件实时推送）。
      try { localStorage.removeItem('arco-theme'); } catch (e4) {}
      pref = null;
    }
    var bootDark = pref === 'dark'
      || ((pref === null || pref === '') && sysDark === true)
      || ((pref === null || pref === '') && sysDark === null
          && window.matchMedia
          && window.matchMedia('(prefers-color-scheme: dark)').matches);
    if (bootDark) {
      document.documentElement.setAttribute('theme-mode', 'dark');
      window.__K5_BOOT_DARK__ = true;
    } else if (sysDark === false && document.documentElement.getAttribute('theme-mode') === 'dark') {
      // 原生明确报浅色时纠正引导脚本可能误设的暗色。
      document.documentElement.removeAttribute('theme-mode');
      window.__K5_BOOT_DARK__ = false;
    }
  } catch (e) {}

  // 任务②：启动时应用视口模式。模式只保存在原生内存字段中——应用每次冷启动
  // 从手机版开始（不恢复上次使用的历史状态）；会话内页面 reload / WebView 崩溃
  // 重建时原生字段仍在，当前模式不丢。顺带清理旧版本写入 localStorage 的历史键。
  try {
    if (typeof NATIVE.setViewportMode === 'function') {
      try {
        localStorage.removeItem('k5-viewport-mode');
        localStorage.removeItem('k5-fab-pos');
      } catch (e2) {}
      var bootMode = 'mobile';
      try { bootMode = String(NATIVE.getViewportMode() || 'mobile'); } catch (e3) {}
      if (bootMode !== 'desktop') { bootMode = 'mobile'; }
      NATIVE.setViewportMode(bootMode, '0');
      applyViewportMeta(bootMode);
    }
  } catch (e) {}

  // 任务②：桌面/手机视图的 meta viewport 改写。
  // 电脑版：固定 1024 宽布局 + 可捏合缩放（原生 wide viewport + overview 负责初始适配）；
  // 手机版：恢复页面原始 meta。每次调用都强制重写 content（而非按标记跳过），
  // 保证快速来回切换时无任何旧布局残留；data-k5-orig 只在首次备份原始值。
  function applyViewportMeta(mode) {
    var apply = function () {
      try {
        var meta = document.querySelector('meta[name="viewport"]');
        if (!meta) {
          return;
        }
        // 手机模式下缓存真实设备宽：桌面模式里 innerWidth 变成 1024，
        // 快速来回切换时若仍读 innerWidth 会算错 initial-scale。
        try {
          var curW = Math.round(window.innerWidth || 0);
          if (curW > 0 && curW < 1024) {
            window.__K5_DEVICE_W__ = curW;
          }
        } catch (e4) {}
        if (mode === 'desktop') {
          if (meta.getAttribute('data-k5-mv') !== '1') {
            meta.setAttribute('data-k5-mv', '1');
            meta.setAttribute('data-k5-orig', meta.getAttribute('content') || '');
          }
          // 每次都重算并重写：initial-scale = 设备宽 / 1024，切换瞬间即为整页概览。
          // 设备宽优先用缓存（快速连点时 innerWidth 可能还停留在上次桌面布局）。
          var content = 'width=1024, user-scalable=yes';
          try {
            var w = Math.round(window.__K5_DEVICE_W__ || window.innerWidth || 0);
            if (w > 0 && w < 1024) {
              content = 'width=1024, initial-scale='
                + (Math.round((w / 1024) * 1000) / 1000) + ', user-scalable=yes';
            }
          } catch (e3) {}
          if (meta.getAttribute('content') !== content) {
            meta.setAttribute('content', content);
          }
        } else if (meta.getAttribute('data-k5-mv') === '1') {
          meta.setAttribute('data-k5-mv', '0');
          var orig = meta.getAttribute('data-k5-orig')
            || 'width=device-width, initial-scale=1.0, viewport-fit=cover';
          if (meta.getAttribute('content') !== orig) {
            meta.setAttribute('content', orig);
          }
        }
      } catch (e) {}
    };
    // 本脚本注入在 <head> 最前，meta 标签此时多半尚未解析：立即试一次，DOM 就绪后再兜底。
    apply();
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', apply);
    }
  }

  // ------------------------------------------------------------ WebUSB 兼容层

  function toConfiguration(desc) {
    if (!desc) {
      return null;
    }
    var interfaces = (desc.interfaces || []).map(function (itf) {
      return {
        interfaceNumber: itf.interfaceNumber,
        alternates: (itf.alternates || []).map(function (alt) {
          return {
            interfaceClass: alt.interfaceClass,
            interfaceSubclass: alt.interfaceSubclass,
            interfaceProtocol: alt.interfaceProtocol,
            alternateSetting: alt.alternateSetting,
            endpoints: (alt.endpoints || []).map(function (ep) {
              return {
                endpointNumber: ep.endpointNumber,
                direction: ep.direction,
                type: ep.type,
                packetSize: ep.packetSize,
              };
            }),
          };
        }),
      };
    });
    return {
      configurationValue: desc.configurationValue || 1,
      interfaces: interfaces,
    };
  }

  function viewOf(bytes) {
    return { status: 'ok', data: new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength) };
  }

  function errorWaiters(err) {
    var waiters = rxWaiters;
    rxWaiters = [];
    for (var i = 0; i < waiters.length; i++) {
      try {
        clearTimeout(waiters[i].timer);
        waiters[i].reject(err);
      } catch (e) {}
    }
  }

  // 从队列里取最多 length 字节；没有数据返回 null。
  function takeQueued(length) {
    if (!rxQueue.length) {
      return null;
    }
    var want = length > 0 ? length : rxQueue[0].length;
    var take = Math.min(want, rxQueuedBytes);
    if (take <= 0) {
      return null;
    }
    var out = new Uint8Array(take);
    var offset = 0;
    while (offset < take) {
      var head = rxQueue[0];
      var n = Math.min(head.length, take - offset);
      out.set(head.subarray(0, n), offset);
      offset += n;
      if (n === head.length) {
        rxQueue.shift();
      } else {
        rxQueue[0] = head.subarray(n);
      }
    }
    rxQueuedBytes -= take;
    return out;
  }

  // 原生推送的一批数据：优先直接喂给正在等待的读请求，剩余入队。
  function pushRx(bytes) {
    if (!bytes || !bytes.length) {
      return;
    }
    var offset = 0;
    while (rxWaiters.length && offset < bytes.length) {
      var waiter = rxWaiters.shift();
      clearTimeout(waiter.timer);
      var remain = bytes.length - offset;
      var n = Math.min(waiter.length > 0 ? waiter.length : remain, remain);
      var chunk = bytes.subarray(offset, offset + n);
      offset += n;
      try {
        waiter.resolve(viewOf(chunk));
      } catch (e) {}
    }
    if (offset < bytes.length) {
      var rest = offset === 0 ? bytes : bytes.subarray(offset);
      rxQueue.push(rest);
      rxQueuedBytes += rest.length;
    }
  }

  function ensureReadLoop(endpoint) {
    if (readStartPromise && readEndpoint === endpoint) {
      return readStartPromise;
    }
    readEndpoint = endpoint;
    readStartPromise = call('startRead', { endpoint: endpoint }).catch(function (err) {
      readStartPromise = null;
      errorWaiters(err);
      throw err;
    });
    return readStartPromise;
  }

  function resetRxState() {
    rxQueue = [];
    rxQueuedBytes = 0;
    readEndpoint = -1;
    readStartPromise = null;
    errorWaiters(makeError('NetworkError', 'USB 端口已重置'));
  }

  function setupArgs(setup) {
    return {
      requestType: (setup && setup.requestType) || 'vendor',
      recipient: (setup && setup.recipient) || 'device',
      request: (setup && setup.request) | 0,
      value: (setup && setup.value) | 0,
      index: (setup && setup.index) | 0,
    };
  }

  function USBDevice(info) {
    this._vendorId = info.vendorId;
    this._productId = info.productId;
    this._opened = false;
    this._configuration = null;
    this._listeners = {};
  }

  Object.defineProperties(USBDevice.prototype, {
    vendorId: { get: function () { return this._vendorId; } },
    productId: { get: function () { return this._productId; } },
    opened: { get: function () { return this._opened; } },
    configuration: { get: function () { return this._configuration; } },
  });

  USBDevice.prototype.addEventListener = function (type, fn) {
    (this._listeners[type] = this._listeners[type] || []).push(fn);
  };

  USBDevice.prototype.removeEventListener = function (type, fn) {
    var list = this._listeners[type];
    if (!list) {
      return;
    }
    var idx = list.indexOf(fn);
    if (idx >= 0) {
      list.splice(idx, 1);
    }
  };

  USBDevice.prototype.dispatchEvent = function () {
    return true;
  };

  USBDevice.prototype.open = function () {
    var self = this;
    if (self._opened) {
      return Promise.resolve();
    }
    resetRxState();
    return call('open', { vendorId: self._vendorId, productId: self._productId }).then(function (info) {
      self._opened = true;
      self._configuration = toConfiguration(info.configuration);
      return info;
    });
  };

  USBDevice.prototype.close = function () {
    var self = this;
    self._opened = false;
    resetRxState();
    // 先让原生读线程退出，再释放接口/关闭设备，否则 bulkTransfer 会打到已关闭的 fd。
    return call('stopRead', {})
      .catch(function () {})
      .then(function () {
        return call('close', {}).catch(function () {});
      });
  };

  USBDevice.prototype.selectConfiguration = function (value) {
    var self = this;
    return call('selectConfiguration', { configurationValue: value | 0 }).then(function (info) {
      self._configuration = toConfiguration(info.configuration);
      return self._configuration;
    });
  };

  USBDevice.prototype.claimInterface = function (interfaceNumber) {
    return call('claimInterface', { interfaceNumber: interfaceNumber | 0 });
  };

  USBDevice.prototype.releaseInterface = function (interfaceNumber) {
    return call('releaseInterface', { interfaceNumber: interfaceNumber | 0 });
  };

  USBDevice.prototype.clearHalt = function (direction, endpoint) {
    return call('clearHalt', { direction: direction, endpoint: endpoint | 0 });
  };

  USBDevice.prototype.controlTransferOut = function (setup, data) {
    var args = setupArgs(setup);
    var bytes = toBytes(data);
    args.data = b64encode(bytes);
    return call('controlTransferOut', args).then(function (res) {
      return { status: res.status || 'ok', bytesWritten: res.status === 'ok' ? bytes.length : 0 };
    });
  };

  USBDevice.prototype.controlTransferIn = function (setup, length) {
    var args = setupArgs(setup);
    args.length = length | 0;
    return call('controlTransferIn', args).then(function (res) {
      var bytes = b64decode(res.data);
      return { status: res.status || 'ok', data: new DataView(bytes.buffer, 0, bytes.byteLength) };
    });
  };

  USBDevice.prototype.transferOut = function (endpoint, data) {
    var bytes = toBytes(data);
    if (bytes.length === 0) {
      // ZLP：原生侧没有可靠的 0 长度 bulkTransfer，按成功处理
      return Promise.resolve({ status: 'ok', bytesWritten: 0 });
    }
    return call('transferOut', { endpoint: endpoint | 0, data: b64encode(bytes) }).then(function (res) {
      var ok = (res.status || 'ok') === 'ok';
      return { status: res.status || 'ok', bytesWritten: ok ? bytes.length : 0 };
    });
  };

  USBDevice.prototype.transferIn = function (endpoint, length) {
    var len = length | 0;
    var ready = takeQueued(len);
    if (ready) {
      return Promise.resolve(viewOf(ready));
    }
    return ensureReadLoop(endpoint | 0).then(function () {
      var again = takeQueued(len);
      if (again) {
        return viewOf(again);
      }
      // 没数据时挂起等待原生推送；200ms 内没等到就返回空包，让驱动读循环继续轮转。
      return new Promise(function (resolve, reject) {
        var waiter = { resolve: resolve, reject: reject, length: len, timer: null };
        waiter.timer = setTimeout(function () {
          var idx = rxWaiters.indexOf(waiter);
          if (idx >= 0) {
            rxWaiters.splice(idx, 1);
          }
          resolve({ status: 'ok', data: new DataView(new ArrayBuffer(0)) });
        }, 200);
        rxWaiters.push(waiter);
      });
    });
  };

  var usb = {
    requestDevice: function (options) {
      var raw = (options && options.filters) || [];
      var filters = raw.map(function (f) {
        return { vendorId: f.vendorId | 0, productId: f.productId === undefined ? -1 : (f.productId | 0) };
      });
      return call('requestDevice', { filters: filters }).then(function (info) {
        return new USBDevice(info);
      });
    },
    getDevices: function () {
      return Promise.resolve([]);
    },
    addEventListener: function () {},
    removeEventListener: function () {},
    dispatchEvent: function () { return true; },
    onconnect: null,
    ondisconnect: null,
  };

  try {
    Object.defineProperty(navigator, 'usb', { value: usb, configurable: true, writable: true });
  } catch (e) {
    try {
      navigator.usb = usb;
    } catch (e2) {
      console.error('K5 bridge: cannot install navigator.usb', e2);
    }
  }

  // ------------------------------------------------------------ 下载接管

  var blobUrls = new Map();
  var origCreate = URL.createObjectURL;
  if (typeof origCreate === 'function') {
    URL.createObjectURL = function (obj) {
      var url = origCreate.call(URL, obj);
      try {
        if (obj instanceof Blob) {
          blobUrls.set(url, obj);
        }
      } catch (e) {}
      return url;
    };
  }
  var origRevoke = URL.revokeObjectURL;
  if (typeof origRevoke === 'function') {
    URL.revokeObjectURL = function (url) {
      try {
        blobUrls.delete(url);
      } catch (e) {}
      return origRevoke.call(URL, url);
    };
  }

  function toast(message) {
    try {
      var box = document.createElement('div');
      box.textContent = message;
      box.style.cssText = 'position:fixed;left:50%;bottom:12%;transform:translateX(-50%);' +
        'z-index:2147483647;max-width:86vw;padding:10px 16px;border-radius:8px;' +
        'background:rgba(0,0,0,.82);color:#fff;font:14px/1.4 sans-serif;text-align:center;' +
        'pointer-events:none;transition:opacity .3s';
      document.body.appendChild(box);
      setTimeout(function () {
        box.style.opacity = '0';
        setTimeout(function () {
          if (box.parentNode) {
            box.parentNode.removeChild(box);
          }
        }, 400);
      }, 2200);
    } catch (e) {}
  }

  function saveBlob(blob, name) {
    var fileName = (name && String(name).trim()) || 'k5web-download.bin';
    return new Promise(function (resolve) {
      var reader = new FileReader();
      reader.onload = function () {
        var result = String(reader.result || '');
        var comma = result.indexOf(',');
        var b64 = comma >= 0 ? result.slice(comma + 1) : '';
        call('saveFile', {
          name: fileName,
          mime: blob.type || 'application/octet-stream',
          data: b64,
        })
          .then(function (res) {
            if (res && res.saved) {
              toast('已保存：' + fileName);
            } else {
              // 用户在系统“另存为”对话框里取消了：不保存、不回退到默认目录。
              toast('已取消保存');
            }
            resolve(res || { saved: false, cancelled: true });
          })
          .catch(function (err) {
            if (err && err.name === 'AbortError') {
              resolve({ saved: false, cancelled: true });
              return;
            }
            toast('保存失败：' + (err && err.message ? err.message : err));
            resolve({ saved: false, cancelled: false });
          });
      };
      reader.onerror = function () {
        toast('读取文件内容失败');
        resolve({ saved: false, cancelled: false });
      };
      reader.readAsDataURL(blob);
    });
  }

  function handleAnchor(anchor) {
    try {
      var href = anchor.getAttribute('href') || '';
      var name = anchor.getAttribute('download');
      if (!name || href.indexOf('blob:') !== 0) {
        return false;
      }
      var blob = blobUrls.get(href);
      if (!blob) {
        return false;
      }
      saveBlob(blob, name);
      return true;
    } catch (e) {
      return false;
    }
  }

  // 页面多用 a.click() 程序化触发，事件不会冒泡到 document，直接改原型最稳。
  var proto = window.HTMLAnchorElement && HTMLAnchorElement.prototype;
  if (proto && typeof proto.click === 'function') {
    var origClick = proto.click;
    proto.click = function () {
      if (handleAnchor(this)) {
        return;
      }
      return origClick.apply(this, arguments);
    };
  }

  document.addEventListener('click', function (event) {
    var target = event.target;
    if (!target) {
      return;
    }
    var anchor = target.closest ? target.closest('a[download]') : null;
    if (!anchor) {
      return;
    }
    if (handleAnchor(anchor)) {
      event.preventDefault();
      event.stopPropagation();
    }
  }, true);

  // 卫星定位兜底入口：页面网络定位失败（无网络/超时/位置不可用）时由
  // src/utils/location.js 调用，走原生 GNSS(卫星) 定位获取经纬度。
  window.K5GEO = {
    getLocation: function (opts) {
      return call('getLocation', opts || {});
    },
    // 定位环境前置检查（权限 → 定位服务开关）：通过 resolve({})，不通过 reject
    // （name 为 NotAllowedError / NotSupportedError / LocationServiceDisabled）。
    checkLocationEnv: function (opts) {
      return call('checkLocationEnv', opts || {});
    },
  };

  // 方便用户/支持人员排查：window.k5diag()
  window.k5diag = function () {
    try {
      return NATIVE.deviceInfo();
    } catch (e) {
      return String(e);
    }
  };
})();
