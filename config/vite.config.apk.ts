import { mergeConfig } from 'vite';
import baseConfig from './vite.config.base';

/**
 * 安卓离线包专用构建配置。
 *
 * 与 vite.config.prod.ts 的区别：只产出纯静态资源，不启用 gzip 预压缩、
 * 包体积可视化和图片压缩（imagemin / sharp）——这些是发布到 Web 服务器时用的，
 * 打进 APK 反而会引入原生依赖并拖慢构建。
 */
export default mergeConfig(baseConfig, {
  mode: 'production',
  build: {
    rollupOptions: {
      external(id) {
        return typeof id === 'string' && id.startsWith('#wasm');
      },
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            return id.toString().split('node_modules/')[1].split('/')[0].toString();
          }
        },
      },
    },
    chunkSizeWarningLimit: 2000,
  },
});
