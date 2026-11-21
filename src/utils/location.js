/**
 * 统一定位入口：网络定位优先，失败自动切换卫星定位兜底。
 *
 * - 网络正常时：沿用浏览器 navigator.geolocation（原有流程，结果对象原样透传）；
 * - 网络定位失败（无网络 / 超时 / 位置不可用 / WebView 权限被拒）时：
 *   若运行在 K5Web 安卓壳内，自动切换到原生卫星（GNSS/GPS）定位
 *   （K5Bridge.getLocation，走系统 LocationManager，离线可用）；
 * - 兜底也失败时，把浏览器原始错误抛回给调用方（错误提示保持不变）。
 *
 * 返回值统一为 { coords: { latitude, longitude, altitude, accuracy }, timestamp }，
 * 与 navigator.geolocation 的 Position 结构兼容，现有调用方无需改动取值方式。
 */
export function getCurrentPositionCompat(options) {
  return new Promise((resolve, reject) => {
    const nativeGeo = (typeof window !== 'undefined' && window.K5GEO && typeof window.K5GEO.getLocation === 'function')
      ? window.K5GEO.getLocation : null;
    const hasWeb = typeof navigator !== 'undefined' && navigator.geolocation;

    const fromNative = () => {
      if (!nativeGeo) {
        return Promise.reject(new Error('当前环境不支持卫星定位'));
      }
      return nativeGeo().then((c) => ({
        coords: {
          latitude: c.latitude,
          longitude: c.longitude,
          altitude: (typeof c.altitude === 'number' ? c.altitude : null),
          accuracy: (typeof c.accuracy === 'number' ? c.accuracy : null),
        },
        timestamp: Date.now(),
      }));
    };

    if (!hasWeb) {
      // 桌面浏览器(壳内必有 geolocation)或极旧环境：直接走原生/失败
      fromNative().then(resolve).catch(reject);
      return;
    }

    navigator.geolocation.getCurrentPosition(
      (pos) => resolve(pos),
      (err) => {
        // 网络定位失败 → 卫星定位兜底；兜底失败仍抛出原始错误，
        // 调用方现有的错误提示（权限/超时文案）保持不变。
        if (!nativeGeo) {
          reject(err);
          return;
        }
        fromNative().then(resolve).catch(() => reject(err));
      },
      options || { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 }
    );
  });
}
