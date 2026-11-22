<template>
  <div class="navbar">
    <div class="left-side">
      <a-space>
        <a-typography-title
          :style="{ margin: 0, fontSize: '18px' }"
          :heading="5"
        >
          <div v-if="!(!topMenu && appStore.device === 'mobile')">
            K5Web
          </div>
        </a-typography-title>
        <icon-menu-fold
          v-if="!topMenu && appStore.device === 'mobile'"
          style="font-size: 22px; cursor: pointer"
          @click="toggleDrawerMenu"
        />
      </a-space>
    </div>
    <div class="center-side">
      <Menu v-if="topMenu" />
    </div>
    <ul class="right-side">
      <template v-if="!topMenu && appStore.device === 'mobile'">
        <li>
          <t-link v-if="t('navbar.qa') == 'Feedback'" href="https://t.me/losehu" target="_blank">{{ $t('navbar.qa') }}</t-link>
          <t-link v-else href="https://qa.vicicode.cn/" target="_blank">{{ $t('navbar.qa') }}</t-link>
        </li>
        <li>
          <a-button v-show="route.path !== '/tool/flash'" type="primary" @click="connectIt">{{ appStore.connectState ? $t('navbar.disconnect') : $t('navbar.connect') }}</a-button>
        </li>
        <li>
          <a-tooltip :content="usbLogVisible ? '隐藏 WebUSB 日志' : '显示 WebUSB 日志'">
            <a-button
              class="nav-btn"
              type="outline"
              :shape="'circle'"
              @click="toggleUsbLog"
            >
              <template #icon>
                <span class="serial-icon-wrap">
                  <svg class="serial-icon" viewBox="0 0 24 24" width="17" height="17" fill="none"
                    stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M3.5 6.5 h17 l-1.8 8.6 a2.8 2.8 0 0 1 -2.8 2.3 h-7.8 a2.8 2.8 0 0 1 -2.8 -2.3 Z" />
                    <circle cx="8" cy="10.5" r="1" fill="currentColor" stroke="none" />
                    <circle cx="12" cy="10.5" r="1" fill="currentColor" stroke="none" />
                    <circle cx="16" cy="10.5" r="1" fill="currentColor" stroke="none" />
                    <path d="M8 17.5 v2 M16 17.5 v2" />
                  </svg>
                  <icon-eye v-if="usbLogVisible" class="serial-eye" />
                  <icon-eye-invisible v-else class="serial-eye" />
                </span>
              </template>
            </a-button>
          </a-tooltip>
        </li>
        <li>
          <a-tooltip :content="$t('settings.language')">
            <a-button
              class="nav-btn"
              type="outline"
              :shape="'circle'"
              @click="setDropDownVisible"
            >
              <template #icon>
                <icon-language />
              </template>
            </a-button>
          </a-tooltip>
          <a-dropdown trigger="click" @select="changeLocale as any">
            <div ref="triggerBtn" class="trigger-btn"></div>
            <template #content>
              <a-doption
                v-for="item in locales"
                :key="item.value"
                :value="item.value"
              >
                <template #icon>
                  <icon-check v-show="item.value === currentLocale" />
                </template>
                {{ item.label }}
              </a-doption>
            </template>
          </a-dropdown>
        </li>
        <li>
          <a-tooltip
            :content="
              theme === 'light'
                ? $t('settings.navbar.theme.toDark')
                : $t('settings.navbar.theme.toLight')
            "
          >
            <a-button
              class="nav-btn"
              type="outline"
              :shape="'circle'"
              @click="handleToggleTheme"
            >
              <template #icon>
                <icon-moon-fill v-if="theme === 'dark'" />
                <icon-sun-fill v-else />
              </template>
            </a-button>
          </a-tooltip>
        </li>
      </template>
      <template v-else>
        <li>
          <t-link v-if="t('navbar.qa') == 'Feedback'" href="https://t.me/losehu" target="_blank">{{ $t('navbar.qa') }}</t-link>
          <t-link v-else href="https://qa.vicicode.cn/" target="_blank">{{ $t('navbar.qa') }}</t-link>
        </li>
        <li>
          <a-button v-show="route.path !== '/tool/flash' && route.path !== '/idea/firmware'" type="primary" @click="connectIt">{{ appStore.connectState ? $t('navbar.disconnect') : $t('navbar.connect') }}</a-button>
          <a-button disabled="disabled" title="升级模式无需连接" v-show="route.path == '/tool/flash' || route.path == '/idea/firmware'" type="primary" @click="connectIt">{{ appStore.connectState ? $t('navbar.disconnect') : $t('navbar.connect') }}</a-button>
        </li>
        <li>
          <a-tooltip :content="usbLogVisible ? '隐藏 WebUSB 日志' : '显示 WebUSB 日志'">
            <a-button
              class="nav-btn"
              type="outline"
              :shape="'circle'"
              @click="toggleUsbLog"
            >
              <template #icon>
                <span class="serial-icon-wrap">
                  <svg class="serial-icon" viewBox="0 0 24 24" width="17" height="17" fill="none"
                    stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M3.5 6.5 h17 l-1.8 8.6 a2.8 2.8 0 0 1 -2.8 2.3 h-7.8 a2.8 2.8 0 0 1 -2.8 -2.3 Z" />
                    <circle cx="8" cy="10.5" r="1" fill="currentColor" stroke="none" />
                    <circle cx="12" cy="10.5" r="1" fill="currentColor" stroke="none" />
                    <circle cx="16" cy="10.5" r="1" fill="currentColor" stroke="none" />
                    <path d="M8 17.5 v2 M16 17.5 v2" />
                  </svg>
                  <icon-eye v-if="usbLogVisible" class="serial-eye" />
                  <icon-eye-invisible v-else class="serial-eye" />
                </span>
              </template>
            </a-button>
          </a-tooltip>
        </li>
        <li>
          <a-tooltip :content="$t('settings.language')">
            <a-button
              class="nav-btn"
              type="outline"
              :shape="'circle'"
              @click="setDropDownVisible"
            >
              <template #icon>
                <icon-language />
              </template>
            </a-button>
          </a-tooltip>
          <a-dropdown trigger="click" @select="changeLocale as any">
            <div ref="triggerBtn" class="trigger-btn"></div>
            <template #content>
              <a-doption
                v-for="item in locales"
                :key="item.value"
                :value="item.value"
              >
                <template #icon>
                  <icon-check v-show="item.value === currentLocale" />
                </template>
                {{ item.label }}
              </a-doption>
            </template>
          </a-dropdown>
        </li>
        <li>
          <a-tooltip
            :content="
              theme === 'light'
                ? $t('settings.navbar.theme.toDark')
                : $t('settings.navbar.theme.toLight')
            "
          >
            <a-button
              class="nav-btn"
              type="outline"
              :shape="'circle'"
              @click="handleToggleTheme"
            >
              <template #icon>
                <icon-moon-fill v-if="theme === 'dark'" />
                <icon-sun-fill v-else />
              </template>
            </a-button>
          </a-tooltip>
        </li>
        <li>
          <a-tooltip
            :content="
              isFullscreen
                ? $t('settings.navbar.screen.toExit')
                : $t('settings.navbar.screen.toFull')
            "
          >
            <a-button
              class="nav-btn"
              type="outline"
              :shape="'circle'"
              @click="toggleFullScreen"
            >
              <template #icon>
                <icon-fullscreen-exit v-if="isFullscreen" />
                <icon-fullscreen v-else />
              </template>
            </a-button>
          </a-tooltip>
        </li>
      </template>
    </ul>
  </div>
</template>

<script lang="ts" setup>
  import { computed, ref, inject, nextTick, onMounted, onUnmounted } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import { useRoute } from 'vue-router';
  import { useDark, useToggle, useFullscreen } from '@vueuse/core';
  import { useAppStore } from '@/store';
  import { LOCALE_OPTIONS } from '@/locale';
  import useLocale from '@/hooks/locale';
  import useUser from '@/hooks/user';
  import Menu from '@/components/menu/index.vue';
  import { connect, disconnect, eeprom_init, rtc_sync_beijing } from '@/utils/serial.js';
  import { isDebugOverlayEnabled, setDebugOverlayEnabled } from '@/utils/serial-log.js';
  import { useI18n } from 'vue-i18n';
  const { t } = useI18n();
  const drivers = import.meta.glob('@/drivers/*.json', { eager: true });

  const appStore = useAppStore();
  const route = useRoute();

  const { logout } = useUser();
  const { changeLocale, currentLocale } = useLocale();
  const { isFullscreen, toggle: toggleFullScreen } = useFullscreen();
  const locales = [...LOCALE_OPTIONS];
  const avatar = computed(() => {
    return userStore.avatar;
  });
  const theme = computed(() => {
    return appStore.theme;
  });
  const topMenu = computed(() => appStore.topMenu && appStore.menu);
  const isDark = useDark({
    selector: 'body',
    attribute: 'arco-theme',
    valueDark: 'dark',
    valueLight: 'light',
    storageKey: 'arco-theme',
    // 无存储偏好时以 store 当前主题为初始值（APK 内由桥接层按原生系统检测设定），
    // 避免部分机型 matchMedia 失效导致挂载时把桥接层已设好的主题覆盖回浅色。
    initialValue: appStore.theme === 'dark' ? 'dark' : 'light',
    onChanged(dark: boolean) {
      // overridden default behavior
      appStore.toggleTheme(dark);
    },
  });
  const toggleTheme = useToggle(isDark);
  const handleToggleTheme = () => {
    toggleTheme();
  };
  const triggerBtn = ref();
  const handleLogout = () => {
    logout();
  };
  const setDropDownVisible = () => {
    const event = new MouseEvent('click', {
      view: window,
      bubbles: true,
      cancelable: true,
    });
    triggerBtn.value.dispatchEvent(event);
  };
  const switchRoles = async () => {
    const res = await userStore.switchRoles();
    Message.success(res as string);
  };
  const toggleDrawerMenu = inject('toggleDrawerMenu') as () => void;

  // 右下角 WebUSB 日志窗口显示/隐藏：与 serial-log.js 浮层开关同步，
  // 睁眼=显示，闭眼(划线)=隐藏。初始状态取浮层当前实际状态。
  const usbLogVisible = ref(isDebugOverlayEnabled());
  const toggleUsbLog = () => {
    usbLogVisible.value = !usbLogVisible.value;
    setDebugOverlayEnabled(usbLogVisible.value);
  };

  const configuration_list : any = {
    "L_BL[0-9][0-9][0-9]" : "losehubl.json",
    "LOSEHU.*P.*K"        : "ltsk.json",
    "LOSEHU.*P.*"         : "lts.json",
    "LOSEHU.*D"           : "losehud.json",
    "UVE.*"               : "losehu124h.json",
    "LOSEHU13[0-9].*HS"   : "losehu124h.json",
    "LOSEHU13[0-9].*H"    : "losehu124h.json",
    "LOSEHU13[0-9].*KS"   : "losehu120k.json",
    "LOSEHU13[0-9].*K"    : "losehu120k.json",
    "LOSEHU13[0-9].*"     : "losehu118.json",
    "LOSEHU12[4-9].*HS"   : "losehu124h.json",
    "LOSEHU12[4-9].*H"    : "losehu124h.json",
    "LOSEHU12[0-3].*H"    : "losehu118h.json",
    "LOSEHU12[0-9].*KS"   : "losehu120k.json",
    "LOSEHU12[0-9].*K"    : "losehu120k.json",
    "LOSEHU12[0-9].*"     : "losehu118.json",
    "LOSEHU11[8-9].*H"    : "losehu118h.json",
    "LOSEHU11[8-9].*K"    : "losehu118k.json",
    "LOSEHU11[8-9].*"     : "losehu118.json",
    "LOSEHU11[2-7].*K"    : "losehu117k.json",
    "LOSEHU11[2-7].*"     : "losehu117.json",
    "7\..*"               : "k1.json",
    ".*"                  : "todo.json"
  }

  const connectIt = async () => {
    if(appStore.connectState == false){
      try{
        if(appStore.connectPort)await disconnect(appStore.connectPort);
      }catch{}
      let _connect;
      // Web Serial (desktop) or WebUSB CH341 (Android Chrome) — connect() handles both
      _connect = await connect();

      // connect() alerts for WebUSB/unsupported failures; user cancel returns null silently.
      if(!_connect){
        return;
      }

      appStore.updateSettings({ connectPort: _connect });

      const driversList : any = {};
      Object.keys(drivers).forEach((key) => {
        driversList[key.substring(key.lastIndexOf('/') + 1)] = drivers[key].default;
      })

      let _configuration = null;
      let version = "";
      try{
        version = await eeprom_init(_connect);
      }catch{
        await disconnect(_connect);
        appStore.updateSettings({ connectState: false, connectPort: null, firmwareVersion: "" });
        alert(t('global.handshakeFail'));
        throw new Error(t('global.handshakeFail'));
      }
      const config = {
        "name": "TODO",
        "uart": "official",
        "charset": "official",
        "K": false,
        "H": false,
        "localmdc": false,
        "sat": false,
        "sat2": false,
        "newpinyin": false,
        'fm30': false
      }

      Object.keys(configuration_list).some(e=>{
        const _re = new RegExp(e);
        if(_re.test(version)){
          _configuration = Object.assign(config, driversList[configuration_list[e]]);
          return true
        }
      })

      // Auto sync device RTC to current Beijing time on connect (best effort).
      // Only do this for UVE builds to avoid slowing down other firmwares.
      if (/^UVE/i.test(version)) {
        try { await rtc_sync_beijing(_connect); } catch {}
      }

      appStore.updateSettings({ connectState: true, firmwareVersion: version, configuration: _configuration });
    }else{
      await disconnect(appStore.connectPort);
      appStore.updateSettings({ connectState: false, connectPort: null, firmwareVersion: "" });
    }
  }

  // 任务③：安卓壳在系统「为USB设备选择一个应用」里被选中后，原生侧完成 USB 授权
  // 并把设备 vid/pid 推给 k5-bridge.js，后者分发 k5usb:device-attached 事件。
  // 这里自动执行与手动点「连接」完全相同的 connectIt()（打开端口 + eeprom 握手 +
  // 固件版本识别 + 驱动配置），无需任何额外手动操作；已连接则保持现有会话不动。
  const onK5UsbDeviceAttached = () => {
    if (appStore.connectState) {
      return;
    }
    // 稍作延迟，等页面路由与初始化稳定后再连接。
    setTimeout(() => {
      if (!appStore.connectState) {
        connectIt().catch(() => {});
      }
    }, 400);
  };
  window.addEventListener('k5usb:device-attached', onK5UsbDeviceAttached);
  onUnmounted(() => {
    window.removeEventListener('k5usb:device-attached', onK5UsbDeviceAttached);
  });

  // 补偿：若原生推送（onDeviceAttached）发生在本组件挂载之前（快网/缓存页面），
  // 事件已经错过；挂载后检查一次遗留标记。
  onMounted(() => {
    if ((window as any).__K5_USB_ATTACHED__ && !appStore.connectState) {
      (window as any).__K5_USB_ATTACHED__ = null;
      onK5UsbDeviceAttached();
    }
  });
</script>

<style scoped lang="less">
  .navbar {
    display: flex;
    justify-content: space-between;
    height: 100%;
    background-color: var(--color-bg-2);
    border-bottom: 1px solid var(--color-border);
  }

  .left-side {
    display: flex;
    align-items: center;
    padding-left: 20px;
  }

  .center-side {
    flex: 1;
  }

  .right-side {
    display: flex;
    padding-right: 20px;
    list-style: none;
    :deep(.locale-select) {
      border-radius: 20px;
    }
    li {
      display: flex;
      align-items: center;
      padding: 0 10px;
    }

    a {
      color: var(--color-text-1);
      text-decoration: none;
    }
    .nav-btn {
      border-color: rgb(var(--gray-2));
      color: rgb(var(--gray-8));
      font-size: 16px;
    }
    .trigger-btn,
    .ref-btn {
      position: absolute;
      bottom: 14px;
    }
    .trigger-btn {
      margin-left: 14px;
    }
    // 串口图标 + 右下角眼睛叠加（WebUSB 日志开关按钮）
    .serial-icon-wrap {
      position: relative;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }
    .serial-eye {
      position: absolute;
      right: -6px;
      bottom: -5px;
      font-size: 13px;
      color: rgb(var(--gray-8));
      background: var(--color-bg-3);
      border-radius: 50%;
      line-height: 1;
    }
  }
</style>

<style lang="less">
  .message-popover {
    .arco-popover-content {
      margin-top: 0;
    }
  }
</style>
