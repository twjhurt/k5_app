import { createApp } from 'vue';
import ArcoVueIcon from '@arco-design/web-vue/es/icon';
import globalComponents from '@/components';
import router from './router';
import store, { useAppStore } from './store';
import i18n from './locale';
import directive from './directive';
import App from './App.vue';
// Styles are imported via arco-plugin. See config/plugin/arcoStyleImport.ts in the directory for details
// 样式通过 arco-plugin 插件导入。详见目录文件 config/plugin/arcoStyleImport.ts
// https://arco.design/docs/designlab/use-theme-package
// Component-library CSS first so mobile.less can override it.
import 'tdesign-vue-next/es/style/index.css';
import '@/assets/style/global.less';
import '@/assets/style/ui.less';
import '@/assets/style/mobile.less';
import '@/api/interceptor';
import Updater from "./utils/AutoUpdate.js";
import VueMatomo from 'vue-matomo';

const AutoUpdate = new Updater()
AutoUpdate.on('update',()=>{
  setTimeout(async()=>{
    if(process.env.NODE_ENV == 'development'){
      return
    }
    const result = confirm('当前网站有更新，请点击确定刷新页面体验');
    if(result){
      location.reload();
    }
  },500)
})

const app = createApp(App);

app.use(ArcoVueIcon);

app.use(router);
app.use(store);
app.use(i18n);
app.use(globalComponents);
app.use(directive);

if(import.meta.env.VITE_METER_SITE.split(',').indexOf(location.hostname) !== -1){
  app.use(VueMatomo, {
    host: '//analytics.vicicode.com',
    siteId: 2,
    router: router
  })
}

// 挂载前按系统配色初始化主题，与 index.html 首屏引导脚本保持一致，
// 避免无 navbar 的页面（如 /satloc）主题不生效。
useAppStore().initTheme();

// APK 内系统深浅色实时切换（onConfigurationChanged 推送）：用户未手动固定主题时跟随。
window.addEventListener('k5usb:system-theme', ((e: CustomEvent) => {
  let stored: string | null = null;
  try {
    stored = localStorage.getItem('arco-theme');
  } catch {
    stored = null;
  }
  if (!stored) {
    useAppStore().toggleTheme(!!e?.detail?.dark);
  }
}) as EventListener);

app.mount('#app');
