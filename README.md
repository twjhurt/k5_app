# K5Web 安卓离线版

> **⚠️ 使用前必读**
>
> 1. **不建议用本应用刷写固件**：手机数据口与写频线的连接稳定性无法保证，刷写中断可能导致变砖。写频、备份等常规操作不受影响。
> 2. **本项目完全 vibe coding**：作者没有代码基础，由 AI 辅助逐版本迭代而成，无法对项目做任何承诺，请自行评估风险、量力使用。

基于开源项目 [K5Web](https://github.com/silenty4ng/k5web)（UV-K5/K6 写频 Web 应用）构建的**安卓离线版**：把完整 Web 应用打包成单个 APK，站点资源内置，**无需网络即可运行**；通过原生 USB 桥（K5Bridge）驱动 CH340/CH341 写频线（OTG）连接手台，写频、备份、固件升级等全部功能与桌面版一致。

**从 [Releases](https://github.com/twjhurt/k5_app/releases) 下载 APK**

## 特性

- **完全离线**：Web 应用整体打包进 APK，打开即用，不依赖外网
- **OTG 直连写频**：原生 K5Bridge USB 串口桥驱动 CH340/CH341 写频线，功能与桌面版一致
- **手机 / 电脑视图切换**：悬浮按钮一键切换视口与 UA，支持拖拽换位，尺寸不随页面缩放
- **深色模式跟随系统**：每次冷启动重新检测系统深浅色
- **中英双语**：启动时按手机系统语言自动切换界面语言（简繁归一化为中文，其他语言为 English），也可手动切换
- **星历体系**：
  - 联网获取星历，带进度提示窗与"终止获取"（离线时自动使用缓存）
  - 获取成功后自动保存到应用内部存储，随时一键"导入上次星历"，纯离线也能用
  - **收藏卫星**：星历写入 / 星历写入 2.0 两页共享收藏列表（下拉框星标收藏、"仅显示收藏卫星"过滤、唯一收藏自动选中、"一键加入收藏卫星"批量入表）
- **look4sat 联动**：写入星历成功后可自动跳转 look4sat 查看过境与多普勒
- **两阶段定位**：先 WebView 网络定位、失败自动切换卫星定位，全程有进度提示窗、可中断；定位服务未开启时给出引导弹窗（跟随系统语言）

<p align="center">
  <img width="1704" height="1206" alt="定位两阶段真实信号源" src="https://github.com/user-attachments/assets/a304a76f-4cad-45c3-bab0-ac035c9a1071" />
</p>

> 定位这东西到底有没有比纯 WebView 方案更好，作者其实也怀疑（实测设备上 WebView 就够了），但既然做了就留着吧。

## 功能列表

- 固件版本 / EEPROM 大小检测
- 信道管理（手机版表格分页已适配）
- 备份 / 还原 EEPROM（快速 / 完整 / 清空，完整备份含前置校验与读超时自动重试）
- 固件升级
- 开机图片读取与写入（LOSEHU 固件）
- 字库写入、星历写入（普通版 + 2.0 表格版，LOSEHU 固件）
- 启动画面文字管理
- MDC 联系人管理、本地侧音控制
- DTMF ID 设置、收音机频道管理
- 卫星过境预测 / 多普勒频移计算（星历写入配套）

## 安装

1. 从 [Releases](https://github.com/twjhurt/k5_app/releases) 下载最新 APK（如 `K5Web-android-1.0.35.apk`）
2. 允许安装未知来源应用后直接安装（minSdk 26 / targetSdk 34，Android 8.0+）
3. 所有版本**同签名**，可直接覆盖安装升级，无需卸载旧版
4. 用 OTG 线连接 CH340 写频线与手台，打开应用选择串口设备即可

每个版本的详细变更记录见 [README-ANDROID.md](./README-ANDROID.md)。

## 从源码构建

### 环境

- Node.js 18+（推荐使用 npmmirror 镜像）
- JDK 17、Android SDK（`tools/` 目录提供本地构建工具链，不入库）
- Windows + PowerShell（APK 打包脚本）

### Web 构建

```bash
npm install
npm run build   # 产物输出至 dist/
```

### APK 打包

```powershell
powershell -ExecutionPolicy Bypass -File .\android\build.ps1 -VersionName 1.0.35 -VersionCode 36
```

流程：`vite build` → 同步 `dist/` 到 `android/assets/www/`（含注入桥 `k5-bridge.js`）→ `javac` 编译原生层（`MainActivity.java` WebView 壳 + `K5Bridge.java` USB 串口桥）→ `apksigner` 签名（密钥库默认 `android/build/k5web.keystore`，密码从环境变量 `K5WEB_KS_PASS` 或本地 `android/build/ks.pass.txt` 读取）→ 产物输出至 `release/`。

> 密钥库与密码文件均被 `.gitignore` 排除，**不会**进入仓库；首次本地构建会自动生成新密钥库（注意：与官方发布的 APK 签名不同，无法覆盖安装 Release 版本）。

## 项目结构

```
├── src/                    # Web 应用源码（Vue 3 + Arco Design / TDesign）
│   └── utils/
│       ├── serial.js       # 串口协议层（EEPROM 读写、固件刷写）
│       ├── webusb-ch341.js # 安卓 WebUSB CH341 驱动
│       └── location.js     # 统一定位入口（网络定位 + 卫星兜底）
├── android/
│   ├── java/com/k5web/app/ # 原生层：MainActivity（WebView 壳）、K5Bridge（USB 桥）
│   ├── bridge/k5-bridge.js # 注入页面的 JS 桥（navigator.usb shim 等）
│   ├── assets/www/         # dist 构建产物同步目标（不入库）
│   └── build.ps1           # APK 打包脚本
├── release/                # 各版本 APK 产物（已入库并发布至 Releases）
└── README-ANDROID.md       # 安卓版逐版本变更记录
```

## 讨论（上游社区，本仓库未搭建讨论社区！）

- QQ 群：957225277（K5Web 相关）
- QQ 群：201308015（固件相关）
- Telegram: https://t.me/losehu
- Matrix: https://matrix.to/#/#losehu:mozilla.org

## 关联项目

- 星历计算接口：https://github.com/silenty4ng/k5sat
- LTS 固件：https://github.com/silenty4ng/uv-k5-firmware-chinese-lts
- k5web：https://github.com/silenty4ng/k5web
- k5web（他人 fork 支持手机版）：https://github.com/wty2019wty/k5web

## 感谢项目

- https://github.com/whosmatt/uvmod
- https://github.com/egzumer/uvtools
- https://github.com/losehu/uv-k5-firmware-custom
- https://github.com/selevo/WebUsbSerialTerminal
- https://github.com/fagci/uvk5-manager
- https://github.com/hank9999/K5_Tools
- https://github.com/kk7ds/chirp
- https://github.com/silenty4ng/k5web
- https://github.com/wty2019wty/k5web

## 开源协议

本项目使用 MIT 许可证，内容见下：

```
MIT License

Copyright (c) 2026 twjhurt
Copyright (c) 2024-2025 Silent YANG

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
