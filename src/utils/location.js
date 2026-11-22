/**
 * 统一定位入口：网络定位优先，失败自动切换卫星定位兜底。
 *
 * - 运行在 K5Web 安卓壳内时，定位检测开始前先做原生前置检查（先权限检测，
 *   随后立即判断定位服务是否开启，位于权限检测之后、其他定位逻辑之前）：
 *   任一不通过时直接失败（服务未开启由原生侧弹窗引导），不进入实际定位；
 * - 网络正常时：沿用浏览器 navigator.geolocation（原有流程，结果对象原样透传）；
 * - 网络定位失败（无网络 / 超时 / 位置不可用 / WebView 权限被拒）时：
 *   自动切换到原生卫星（GNSS/GPS）定位（K5Bridge.getLocation，离线可用）；
 * - 兜底也失败时，把浏览器原始错误抛回给调用方（错误提示保持不变）。
 *
 * 可选第二参数 onStage(stage)：阶段回调，stage 为 'web'（浏览器/网络定位
 * 开始）或 'native'（卫星定位兜底开始），供调用方展示详细状态信息。
 *
 * 返回 promise 上附带 cancel()：调用后置取消标志，丢弃迟到结果；
 * 若卫星定位已在原生侧进行，则同时调 K5GEO.cancelLocation 真正中止监听。
 *
 * 返回值统一为 { coords: { latitude, longitude, altitude, accuracy }, timestamp }，
 * 与 navigator.geolocation 的 Position 结构兼容，现有调用方无需改动取值方式。
 */
export function getCurrentPositionCompat(options, onStage) {
  let cancelled = false;
  let nativePromise = null;

  const promise = new Promise((resolve, reject) => {
    const nativeGeo = (typeof window !== 'undefined' && window.K5GEO && typeof window.K5GEO.getLocation === 'function')
      ? window.K5GEO.getLocation : null;
    const nativeEnv = (typeof window !== 'undefined' && window.K5GEO && typeof window.K5GEO.checkLocationEnv === 'function')
      ? window.K5GEO.checkLocationEnv : null;
    const hasWeb = typeof navigator !== 'undefined' && navigator.geolocation;
    const notify = (stage) => {
      if (typeof onStage === 'function') {
        try { onStage(stage); } catch { /* 回调异常不影响定位流程 */ }
      }
    };

    const fromNative = () => {
      if (cancelled) {
        return Promise.reject({ name: 'AbortError', message: '已停止定位' });
      }
      if (!nativeGeo) {
        return Promise.reject(new Error('当前环境不支持卫星定位'));
      }
      notify('native');
      nativePromise = nativeGeo().then((c) => ({
        coords: {
          latitude: c.latitude,
          longitude: c.longitude,
          altitude: (typeof c.altitude === 'number' ? c.altitude : null),
          accuracy: (typeof c.accuracy === 'number' ? c.accuracy : null),
        },
        timestamp: Date.now(),
      }));
      return nativePromise;
    };

    const start = () => {
      if (cancelled) {
        reject({ name: 'AbortError', message: '已停止定位' });
        return;
      }
      if (!hasWeb) {
        // 桌面浏览器(壳内必有 geolocation)或极旧环境：直接走原生/失败
        fromNative().then(resolve).catch(reject);
        return;
      }
      notify('web');
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          if (cancelled) return; // 已停止：丢弃迟到结果
          resolve(pos);
        },
        (err) => {
          if (cancelled) return; // 已停止：不再进入卫星兜底
          // 网络定位失败 → 卫星定位兜底；兜底失败仍抛出原始错误，
          // 调用方现有的错误提示（权限/超时文案）保持不变。
          // 例外：定位服务未开启（原生侧已弹窗引导）时如实抛出该错误，
          // 避免被浏览器超时/不可用错误覆盖导致文案失真。
          if (!nativeGeo) {
            reject(err);
            return;
          }
          fromNative().then(resolve).catch((nativeErr) => {
            if (nativeErr && nativeErr.name === 'LocationServiceDisabled') {
              reject(nativeErr);
            } else {
              reject(err);
            }
          });
        },
        options || { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 }
      );
    };

    if (nativeEnv) {
      // 壳内：前置检查（先权限、后定位服务开关），不通过直接失败，不发起定位
      nativeEnv().then(start).catch(reject);
    } else {
      start();
    }
  });

  promise.cancel = () => {
    cancelled = true;
    try {
      if (nativePromise && nativePromise.__k5id
        && typeof window !== 'undefined' && window.K5GEO
        && typeof window.K5GEO.cancelLocation === 'function') {
        window.K5GEO.cancelLocation(nativePromise.__k5id).catch(() => { /* 取消失败不影响 */ });
      }
    } catch { /* 取消失败不影响 */ }
  };

  return promise;
}
