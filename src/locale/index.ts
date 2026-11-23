import { createI18n } from 'vue-i18n';
import en from './en-US';
import cn from './zh-CN';

export const LOCALE_OPTIONS = [
  { label: '中文', value: 'zh-CN' },
  { label: 'English', value: 'en-US' },
];
// 启动语言检测：
// 1. 用户在顶栏手动切换过语言（持久化于 arco-locale）时，优先使用其选择——
//    保证用户随时可以把语言"改回去"；
// 2. 无手动选择时按手机系统语言自动判定：
//    中文（简体/繁体，zh-CN/zh-TW/zh-HK/zh-Hant 等一律归一化）→ 中文（zh-CN）；
//    英语及其他任何语言（en-GB/en-US/fr/ja/...）→ 英语（en-US）。
// 旧逻辑（可回滚）：localStorage.getItem('arco-locale') || navigator.language || 'en-US'
//   —— 原始 navigator.language（如 zh-TW / zh-Hant-TW / en-GB / fr）不在消息表中，
//      会落入 fallbackLocale 或显示错乱。
const savedLocale = localStorage.getItem('arco-locale');
const deviceLang = (navigator.language || '').toLowerCase();
const detectedLocale = deviceLang.indexOf('zh') === 0 ? 'zh-CN' : 'en-US';
const defaultLocale =
  savedLocale === 'zh-CN' || savedLocale === 'en-US' ? savedLocale : detectedLocale;

const i18n = createI18n({
  locale: defaultLocale,
  fallbackLocale: 'en-US',
  legacy: false,
  allowComposition: true,
  messages: {
    'en-US': en,
    'zh-CN': cn,
  },
});

export default i18n;
