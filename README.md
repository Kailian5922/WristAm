# WristAm (Wear OS Apple Music Client)

<p align="center">
  <img src="app/src/main/res/drawable/ic_apple_music_foreground.png" width="120" height="120" alt="WristAm Logo" />
</p>

<p align="center">
  <b>专为 Wear OS 智能手表量身打造的原生 Apple Music 客户端与动态逐字歌词播放器</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Wear%20OS%20%7C%20Android-blue?logo=android" alt="Platform" />
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple?logo=kotlin" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Compose-Wear%20Material%203-brightgreen?logo=jetpackcompose" alt="Compose" />
  <img src="https://img.shields.io/badge/Min%20SDK-Wear%2030%20%7C%20Phone%2024-orange" alt="Min SDK" />
</p>

---

## 📖 项目简介 (Overview)

**WristAm** 是一款专为圆形/方形 Wear OS 智能手表设计的全功能 Apple Music 音乐播放器。支持手表独立联网在线点播、离线歌曲下载、个性化智能电台，并拥有与 Apple Music 官方高度一致的**毫秒级逐字卡拉OK扫光动效**与**动态流光封面高斯模糊背景**。

同时附带手机端配套应用 (`:phone`)，通过 WebView 登录 Apple ID 并利用 Google Play Services Wearable 协议一键跨端同步鉴权 Token 至手表，彻底解决智能手表端输入账号繁琐的痛点。

---

## ✨ 核心特性 (Features)

### 1. 灵动逐字歌词 (Synchronized Lyrics)
- **毫秒级卡拉OK扫光**：深度解析 Apple Music 官方 TTML（Timed Text Markup Language）时轴，实现精准到音节/汉字的平滑光泽扫色渲染 (`AppleMusicKaraokeSweepText`)。
- **黄金光学居中 (45% Viewport)**：针对 Wear OS 圆形表盘定制视口中心偏上对齐，确保正在播放的歌词始终停留在视线焦点。
- **弹性动效与间奏呼吸点**：当前播放行具备弹簧缩放效果；音乐间奏时动态展示三点呼吸渐隐脉动动效 (`AppleMusicInterludeDots`)。
- **动态主题强调色**：自动同步 Wear OS 系统 Material 3 主题主色（Accent Color），告别单调灰白。
- **双语对齐与翻译补全**：内置 QQ 音乐歌词双引擎匹配算法，自动为外文歌曲对齐高质量中文字幕。

### 2. 流畅音频与解密管线 (Streaming & DRM)
- **现代流媒体引擎**：基于 AndroidX Media3 (ExoPlayer) 构建的前台保活播放服务 (`WearMusicService`)。
- **CENC 与 Widevine 解密**：集成 ISO-BMFF 样本级 AES-CTR 实时解密器与 Widevine 密钥提取管线，支持 Apple Music 加密音轨流式播放。
- **离线下载管理**：支持一键下载高品质歌曲至手表独立存储，支持分类管理与智能缓存自动清理。

### 3. 原生手表交互与视觉 (Horologist Experience)
- **动态封面流光背景**：根据当前播放歌曲的专辑原图，实时生成高斯模糊流光背景，营造沉浸式氛围。
- **表冠旋转支持 (Rotary Input)**：支持物理表冠与触摸环精准滚动列表与调节系统媒体音量。
- **个性化电台与艺人探索**：支持 Apple Music 智能电台（Smart Radio）无限流播放、艺人详情分页流式触底加载、精选专辑浏览等。
- **云端曲库双向收藏**：可在播放页实时检查、添加或取消收藏歌曲，并自动同步至 Apple Music 个人资料库。

### 4. 双端协同与快捷登录 (Phone-Watch Link)
- **手机扫码/网页免密中继**：手机端配套 App (`:phone`) 提供原生 Apple ID 登录窗口，无需在手表端输入复杂密码。
- **Google Play Wearable Data Layer**：登录成功后，安全令牌自动加密中继传输给手表端并持久化存储。
- **硬件级安全加密**：手表端采用 AndroidX Security 提供的 EncryptedSharedPreferences 硬件密钥库安全存放用户 Token。

---

## 🏗️ 架构与模块划分 (Architecture)

项目采用现代化多模块 Android Gradle 架构，职责解耦清晰：

```
WristAm/
├── app/               # [Wear OS 端] 主手表应用
│   ├── wear/          # 播放界面、歌词视图、控制盘、Token 监听服务
│   └── res/           # 手表专用矢量图标、排版字体 (Outfit, Plus Jakarta Sans)
│
├── phone/             # [Android 手机端] 配套辅助应用
│   ├── phone/         # Apple ID 网页登录拦截器、跨端令牌中继器
│   └── res/           # 手机端 Material 3 界面资源
│
├── lyrics-core/       # [歌词解析核心库]
│   └── lyrics/        # 高性能 TTML/LRC 格式解析器、字词级别时轴实体定义
│
└── shared/            # [共享业务与流媒体层]
    ├── apple/         # Apple Music REST API、曲库同步、搜索、下载器
    ├── cenc/          # CENC 样本级解密器 (AES-CTR)
    ├── drm/           # MediaDrm 接口与 Widevine 密钥获取器
    ├── hls/           # HLS 播放列表流解析与切片管理
    ├── mp4/           # MP4 容器解包、Box 格式解析、流清洗与 Remuxer
    ├── widevine/      # Widevine CDM 原生客户端逻辑、Proto 协议序列化
    ├── player/        # Media3 ExoPlayer 封装、前台通知控制器
    └── data/          # 官方/备用歌词抓取器、磁盘二级 LRU 缓存、DES 解密
```

---

## 🛠️ 开源库引用与二次开发说明 (Credits & References)

本项目在开发过程中，吸收、借鉴并对以下优秀的开源项目与工程进行了深度二次开发与适配：

1. **[Google Horologist](https://github.com/google/horologist)**
   - **引用与借鉴**：参考了 Google 官方 Horologist 库在 Wear OS 上的媒体控件设计规范（Media UI Design Patterns）、表冠旋转联动（Rotary Scroll & Volume Handling）及曲面响应式排版理念。
   - **二次开发**：将控制盘组件与紧凑型圆形表盘进行原生适配，重构了契合小屏幕交互的操作热区。

2. **Apple Music 歌曲解密源码来源与思路 ([酷安动态](https://www.coolapk.com/feed/73737066?s=NjdhODM2MDAzN2JlMDhnNmFlNDg3OGN6a1670))**
   - **引用与说明**：本项目中核心的 Apple Music 音频解密管线（包括 Widevine L3 密钥获取、ISO-BMFF Common Encryption (CENC) 样本级 AES-CTR 解密以及音轨 Remuxer / Cleaner 逻辑）的底层实现思路与核心算法代码来源于酷安开发者所分享的技术方案，特此致谢。
   - **二次开发**：在原有解密逻辑基础上，进行了面向 AndroidX Media3 / ExoPlayer 的现代流媒体管道适配，实现边解密边缓冲的无缝后台播放引擎 (`PlaybackEngine` / `CencDecryptor`)。

3. **`lyrics-beta` (Apple Music API & 曲库交互参考工程)**
   - **引用与借鉴**：参考了其对 Apple Music Web API 的逆向接口规范、Widevine CDM 交互流程、曲库管理逻辑以及 QQ 音乐备用歌词接口 (`DESHelper`)。
   - **二次开发**：从原有的原型脚本重构为高度工程化的 Kotlin 协程架构，全面迁移至 Android 现代化流媒体规范；加入了针对土耳其/美区/国区跨区域请求的自动 Storefront 刷新适配与容错兜底机制。

4. **`wearlyric-` (Wear OS 逐字歌词实现)**
   - **引用与借鉴**：歌词界面的字级别平滑扫光算法（Canvas `clipRect` 与横向插值权重计算）、视口 45% 光学居中滚动定位算法、间奏呼吸动效点 (`AppleMusicInterludeDots`)。
   - **二次开发**：重构了列表的垂直留白空间（从冗余的 30dp 优化至紧凑舒适的 6~8dp），将硬编码纯白配色改造为全面响应 Wear OS Material 3 动态强调色（Accent Color）；保留并融合了动态高斯模糊流光专辑背景。

5. **[AndroidX Media3](https://developer.android.com/media/media3) / ExoPlayer**
   - 官方高性能音视频流媒体播放架构，驱动 HLS/MP4 解密音轨的低功耗后台连续播放。

6. **[Jetpack Compose for Wear OS](https://developer.android.com/training/wearables/compose)**
   - 全面基于声明式 UI 构建，利用 Wear Material 3 体系打造现代化、高帧率的穿戴设备体验。

7. **[Coil Compose](https://coil-kt.github.io/coil/)**
   - 极轻量的异步图片加载库，负责智能手表端的网络专辑图加载与内存自适应缓存。

8. **[Google Play Services Wearable](https://developers.google.com/android/reference/com/google/android/gms/wearable/package-summary)**
   - 手机端与手表端跨设备低功耗数据通信（DataClient / MessageClient / ChannelClient）标准实现。

---

## 🚀 构建与运行 (Build & Run)

### 环境要求
- **Android Studio**：Ladybug (2024.2.1) 或更高版本
- **JDK**：OpenJDK 17
- **Kotlin**：2.0.0+
- **Gradle**：8.5+ (推荐使用项目内置 Wrapper 9.x)
- **编译 SDK (compileSdk)**：36
- **支持设备**：
  - 手表：Wear OS 3.0+ (API 30+)
  - 手机：Android 7.0+ (API 24+)

### 构建步骤
1. 克隆代码仓库：
   ```bash
   git clone https://github.com/Kailian5922/WristAm.git
   cd WristAm
   ```
2. 配置 Android SDK 路径：
   在项目根目录下创建 `local.properties` 文件并指定 SDK 路径：
   ```properties
   sdk.dir=/path/to/your/android-sdk
   ```
3. 编译验证代码：
   ```bash
   # 仅检查编译，无需全量打包
   ./gradlew compileDebugKotlin
   ```
4. 部署与安装：
   - 将手表通过 Wi-Fi 调试或蓝牙与计算机连接，运行 `:app`。
   - 将手机与计算机连接，运行 `:phone`。

---

## 🔒 隐私与安全说明 (Security)

- **无第三方云端存储**：用户的所有登录凭据（Apple Music User Token、Cookie）均通过手机与手表之间的本地加密通道传输，仅持久化存储于设备本地的安全 Keystore 中，绝不上报至任何第三方服务器。
- **免责声明**：本项目仅供 Android / Wear OS 开发者及音乐爱好者进行技术学习、界面交互与流媒体技术研究交流，不提供任何破解或商业分发功能。请遵守 Apple Music 官方相关服务协议。

---

## 📄 开源许可证 (License)

本项目采用 [Apache License 2.0](LICENSE) 开源许可协议。部分引用的第三方加密辅助算法代码版权归原作者所有，详见各模块内对应许可证文件。
