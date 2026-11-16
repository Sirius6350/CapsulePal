<div align="center">

# 胶囊伙伴 · CapsulePal

**一个完全离线的 Android 时间与电量管家。没有网络权限，没有账号，没有云端。**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](./LICENSE)
![No INTERNET](https://img.shields.io/badge/权限-无%20INTERNET-brightgreen.svg)
![Android](https://img.shields.io/badge/Android-12%2B%20(API%2031)-3ddc84.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7f52ff.svg)

</div>

---

## 它是什么

CapsulePal 把「待办、日程、电池、功耗」收进一个胶囊形状的极简外壳里。所有数据只存在你自己的手机上：应用在 Manifest 中通过 `tools:node="remove"` 强制移除 `INTERNET` 权限，因此它在系统层面**无法**发起任何网络请求，也无法被任何第三方 SDK 偷偷联网。

同一套本地数据库提供五种外观与页面组合，切换版本不丢数据：

| 版本 | 定位 |
| --- | --- |
| 完整本体 | 监测、伙伴、待办、计划与设置 |
| 极简综合 | 去掉伙伴页，监测与日程并重 |
| 极简效率 | 只留计划与待办 |
| 极简待办 | 只有一个待办列表 |
| 极客体验 | 电池采样、放电历史、应用功耗全量记录 |

---

## 核心卖点

**纯本地离线**
Room + DataStore，数据全部落在应用私有目录。备份是你手动导出的一个 JSON 文件，没有服务器，也没有同步。

**零网络权限**
不申请 `INTERNET`，并在合并阶段强制剔除依赖带入的该权限。没有「隐私政策」话术，只有一条可以在 `AndroidManifest.xml` 里逐行核对的声明。

**流体云上岛**
即时待办通过实况通知（Live Updates / 胶囊）上岛，胶囊右侧直接显示待办名称，一眼可读，点一下就能完成。

**极简设计**
胶囊造型、柔和配色、克制动效。三款桌面组件（电池与温度 / 今日待办 / 即时待办）覆盖 1×1 到 5×2，随尺寸自适应排布。

---

## 截图

> 截图占位：把图片放进 `docs/screenshots/`（建议 1080×2400、WebP、单张 < 300 KB），
> 然后取消下方注释块即可。

| 首页 | 电池监测 | 待办 | 桌面组件 |
| :---: | :---: | :---: | :---: |
| `docs/screenshots/01-home.png` | `docs/screenshots/02-battery.png` | `docs/screenshots/03-todo.png` | `docs/screenshots/04-widgets.png` |

<!--
<p align="center">
  <img src="docs/screenshots/01-home.png"    width="200" alt="首页" />
  <img src="docs/screenshots/02-battery.png" width="200" alt="电池监测" />
  <img src="docs/screenshots/03-todo.png"    width="200" alt="待办" />
  <img src="docs/screenshots/04-widgets.png" width="200" alt="桌面组件" />
</p>
-->

---

## 下载安装

安装包**只**通过 GitHub Releases 分发，源码仓库内不包含任何 APK。

1. 打开本仓库右侧的 **Releases**，下载最新版本的 `CapsulePal-vX.Y.Z.apk`。
2. 同时下载 `CapsulePal-vX.Y.Z.apk.sha256`，校验文件完整性：

   ```bash
   # Linux / macOS
   shasum -a 256 -c CapsulePal-v1.0.6.apk.sha256

   # Windows PowerShell
   Get-FileHash .\CapsulePal-v1.0.6.apk -Algorithm SHA256
   ```

3. 在手机上允许「安装未知来源应用」，点击 APK 安装。

**系统要求**：Android 12（API 31）及以上。

首次使用建议依次授权：通知、悬浮窗、精确闹钟、电池优化白名单。读取日程、使用情况访问、WiFi 名称属于可选能力，只在对应规则启用时才会请求。

---

## 隐私设计

| 项目 | 说明 |
| --- | --- |
| 网络 | **不声明 `INTERNET`**，无任何联网代码路径 |
| 账号 | 无 |
| 数据存储 | 应用私有目录（Room / DataStore），卸载即清除 |
| 备份 | 手动导出为本地 JSON，由你自己保管 |
| 统计埋点 | 无 |
| 广告 / 第三方 SDK | 无 |

已申请的权限及用途（全部只在本机使用）：

| 权限 | 用途 |
| --- | --- |
| `POST_NOTIFICATIONS` / `POST_PROMOTED_NOTIFICATIONS` | 提醒与待办上岛 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE` | 悬浮窗与提醒保活 |
| `SYSTEM_ALERT_WINDOW` | 指标悬浮窗 |
| `SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED` | 精确提醒、开机重排 |
| `VIBRATE` | 提醒振动 |
| `ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`ACCESS_FINE_LOCATION` | 仅用于「连接某 WiFi 时触发」规则的本地 SSID 匹配 |
| `READ_CALENDAR` | 读取日程并展示 |
| `READ_PHONE_STATE`、`BLUETOOTH_CONNECT` | 场景信号源（飞行模式、蓝牙连接）判断 |
| `PACKAGE_USAGE_STATS`（特殊权限，需手动授予） | 「打开指定应用」规则，仅本地读取 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导加入电池优化白名单 |

以上数据均不离开设备。

---

## 从源码构建

**环境**：JDK 17、Android SDK Platform 36、Android Build Tools 36.x。

```bash
git clone https://github.com/<your-name>/CapsulePal.git
cd CapsulePal

# 1) 指向本机 SDK（此文件已被 .gitignore 忽略，切勿提交）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2) 调试构建
./gradlew :app:assembleDebug

# 3) 产物
# app/build/outputs/apk/debug/app-debug.apk
```

**发布构建**需要你自己的签名文件。在项目根目录新建 `keystore.properties`（已被忽略）：

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=你的口令
keyAlias=你的别名
keyPassword=你的口令
```

然后：

```bash
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

没有 `keystore.properties` 时，Gradle 会跳过签名配置，`assembleRelease` 输出未签名包——这样任何人都能在不接触维护者密钥的前提下编译本项目。

---

## 开源协议

本项目以 **GNU General Public License v3.0** 发布，全文见 [LICENSE](./LICENSE)。

你可以自由使用、修改、分发，包括商业使用；但分发衍生作品时必须以 GPLv3 开放全部对应源码，并保留相同授权。**任何形式的闭源商用都违反本协议。**

```
CapsulePal - 一个完全离线的 Android 时间与电量管家
Copyright (C) 2026 CapsulePal Contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

---

## 品牌与视觉资产声明

**代码开源，品牌不开源。**

「胶囊伙伴」「CapsulePal」名称、「小胶囊」形象、应用图标及全部视觉设计资产的权利均由作者保留，**不适用** GPLv3 授权。你可以在未修改的官方版本上自由分发，但不得：

- 将上述名称、形象或图标用于任何商业产品或付费服务；
- 修改后继续使用相同名称与图标分发，造成与原版的混淆；
- 暗示获得作者授权或背书。

完整条款见 [BRANDING.md](./BRANDING.md)。本声明依据 GPLv3 第 7(e) 条作出，属于该协议允许的附加条款。
