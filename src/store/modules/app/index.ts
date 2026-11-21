import { defineStore } from 'pinia';
import { Notification } from '@arco-design/web-vue';
import type { NotificationReturn } from '@arco-design/web-vue/es/notification/interface';
import type { RouteRecordNormalized } from 'vue-router';
import defaultSettings from '@/config/settings.json';
import { getMenuList } from '@/api/user';
import { AppState } from './types';

const useAppStore = defineStore('app', {
  state: (): AppState => ({ ...defaultSettings }),

  getters: {
    appCurrentSetting(state: AppState): AppState {
      return { ...state };
    },
    appDevice(state: AppState) {
      return state.device;
    },
    isUve5(state: AppState): boolean {
      return typeof state.firmwareVersion === 'string' && state.firmwareVersion.startsWith('UVE');
    },
    appAsyncMenus(state: AppState): RouteRecordNormalized[] {
      return state.serverMenu as unknown as RouteRecordNormalized[];
    },
  },

  actions: {
    // Update app settings
    updateSettings(partial: Partial<AppState>) {
      // @ts-ignore-next-line
      this.$patch(partial);
    },

    // Change theme color
    toggleTheme(dark: boolean) {
      if (dark) {
        this.theme = 'dark';
        document.documentElement.setAttribute('theme-mode', 'dark');
        document.body.setAttribute('arco-theme', 'dark');
      } else {
        this.theme = 'light';
        document.documentElement.removeAttribute('theme-mode');
        document.body.removeAttribute('arco-theme');
      }
    },

    // 按系统配色初始化主题：与 index.html 的首屏引导脚本同一套优先级
    // （手动偏好 localStorage 'arco-theme' > 系统配色），保证 store 状态、
    // TDesign（html[theme-mode]）与 Arco（body[arco-theme]）三处一致。
    initTheme() {
      let stored: string | null = null;
      try {
        stored = localStorage.getItem('arco-theme');
      } catch {
        stored = null;
      }
      let dark = false;
      if (stored === 'dark') {
        dark = true;
      } else if (stored === 'light') {
        dark = false;
      } else {
        // 与 index.html 引导脚本一致：APK 内优先用原生注入的 __K5_SYSTEM_DARK__
        // （WebView 的 prefers-color-scheme 在部分机型不跟随系统），否则用 matchMedia。
        const injected = (window as any).__K5_SYSTEM_DARK__;
        dark = typeof injected === 'boolean'
          ? injected
          : !!window.matchMedia?.('(prefers-color-scheme: dark)').matches;
      }
      this.toggleTheme(dark);

      // 用户未手动固定主题时，跟随系统配色实时变化；一旦手动切换过则以用户为准。
      if (!stored) {
        // 桌面浏览器：matchMedia 监听。
        if (window.matchMedia) {
          const mq = window.matchMedia('(prefers-color-scheme: dark)');
          const listener = (e: MediaQueryListEvent) => {
            let current: string | null = null;
            try {
              current = localStorage.getItem('arco-theme');
            } catch {
              current = null;
            }
            if (!current) {
              this.toggleTheme(e.matches);
            }
          };
          if (typeof mq.addEventListener === 'function') {
            mq.addEventListener('change', listener);
          } else if (typeof (mq as any).addListener === 'function') {
            // 旧版 WebView 兼容
            (mq as any).addListener(listener);
          }
        }

        // APK：WebView 的 prefers-color-scheme 在部分机型不跟随系统，
        // 由原生 onConfigurationChanged 推送 k5usb:system-theme 事件兜底。
        const onNativeTheme = (e: Event) => {
          let current: string | null = null;
          try {
            current = localStorage.getItem('arco-theme');
          } catch {
            current = null;
          }
          if (current) {
            return;
          }
          const dark = !!(e as CustomEvent).detail?.dark;
          (window as any).__K5_SYSTEM_DARK__ = dark;
          this.toggleTheme(dark);
        };
        window.addEventListener('k5usb:system-theme', onNativeTheme as EventListener);
      }
    },
    toggleDevice(device: string) {
      this.device = device;
      this.hideMenu = device === 'mobile';
    },
    toggleMenu(value: boolean) {
      this.hideMenu = value;
    },
    async fetchServerMenuConfig() {
      let notifyInstance: NotificationReturn | null = null;
      try {
        notifyInstance = Notification.info({
          id: 'menuNotice', // Keep the instance id the same
          content: 'loading',
          closable: true,
        });
        const { data } = await getMenuList();
        this.serverMenu = data;
        notifyInstance = Notification.success({
          id: 'menuNotice',
          content: 'success',
          closable: true,
        });
      } catch (error) {
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        notifyInstance = Notification.error({
          id: 'menuNotice',
          content: 'error',
          closable: true,
        });
      }
    },
    clearServerMenu() {
      this.serverMenu = [];
    },
  },
});

export default useAppStore;
