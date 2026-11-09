# 川海影视TV（ChuanHaiTV）

> 一个**纯原生 Kotlin** 编写的 Android TV / 手机双端影视聚合浏览与播放客户端。
> 内容来自赞片 CMS（zanpian cms12 模板）站点，客户端只做页面解析与播放，不托管任何资源。
>
> | | |
> | --- | --- |
> | 应用名 / 项目名 | 川海影视TV / ChuanHaiTV（早期代号 YuJianTV） |
> | 包名 | `com.yujiannatian.tv` |
> | 版本 | `1.0.0`（`versionCode 1`） |
> | 平台 | Android TV、Android 手机／平板（全局横屏） |
> | 作者 | polaris7765（董先生） |
> | 文档更新 | 2026-11-09 |
>
> ⚠️ **本项目仅供个人学习与技术交流使用，请支持正版，勿用于任何商业用途。**

---

## 1. 项目简介

客户端抓取站点 HTML（Jsoup 解析）得到分类、列表、详情与播放地址，再用 media3 / ExoPlayer 播放 HLS（m3u8）或 MP4。设计上刻意保持轻量：**没有第三方 UI 框架、没有图片加载库、没有 DI、没有数据库**，仅依赖 AndroidX + media3 + OkHttp + Jsoup + Gson，本地数据用 `SharedPreferences` + Gson + 磁盘文件。

核心约束：

| 约束 | 说明 |
| --- | --- |
| 双端一致 | TV（遥控器 / D-pad）与手机（触屏）共用同一套页面，仅靠 `DeviceKind.isTv` 做少量分支 |
| 强制横屏 | 所有 Activity 在 Manifest 中声明 `screenOrientation="landscape"` |
| 弱网友好 | 任何网络失败都不白屏、不闪退；封面必须有兜底图；`Error` 级异常也归一化捕获 |
| 地址可换 | 所有 href 存为站点相对路径，换域名不影响已缓存结构 |
| 低版本兼容 | `minSdk 23`，必须开启核心库降级（desugaring） |

深入设计与历次故障的根治记录见 **[TECH_NOTES.md](./TECH_NOTES.md)**。

---

## 2. 功能特性

| 模块 | 能力 |
| --- | --- |
| 启动 | 自定义启动页（`SplashActivity`）、自适应 App 图标、TV Leanback banner |
| 首页 | 左栏分类导航（热门推荐／历史记录／我的收藏／**搜索**／电视剧／电影／动漫／综艺／分类索引／我的下载／设置／**联系作者**），右侧封面网格，上一页／下一页／刷新；左栏无滚动条 |
| 浏览 | 分类分页、热播榜、分类索引（纯垂直列表，只允许上下滑动）、全站搜索 |
| 详情 | 海报与简介、多播放源切换、选集网格、收藏、单集下载 |
| 搜索 | 拼音首字母 / 中文片名直搜；本地索引 + 站内中文补查 + **首字母候选汉字反查**（`ZMSR` → 《走马上任》） |
| 播放 | HLS / MP4 全屏播放；中间圆形播放／暂停按钮、底部进度条与当前时间／总时长；倍速播放、自动下一集、遥控器快捷键、返回／设置入口；手机端设置面板内含投屏 |
| 进度记忆 | 退出时保存播放进度，从历史记录／收藏重新打开自动续播到上次位置 |
| 下载 | m3u8 分片下载 + AES-128 解密、MP4 流式下载；**断点续传**（分片指纹 / HTTP Range）；剧集下载按钮背景进度填充；「我的下载」分「下载中 / 下载完成」两组并实时显示进度（TV 端默认关闭下载，可在设置开启） |
| 投屏 | DLNA / UPnP，SSDP 发现设备（手机端可用，TV 端作为接收端故隐藏入口） |
| 设置 | 站点地址保存与连通性检测、一键回退上次可用地址、允许下载开关、默认播放源选择 |
| 联系作者 | 作者信息、赞赏二维码、支持文案与版权／侵权联系邮箱 |
| 健壮性 | 全局崩溃兜底、页面／图片分离超时策略、内存 LRU + 磁盘 TTL 缓存、封面图床熔断与 App 图标兜底 |

---

## 3. 运行环境

| 项 | 版本／要求 |
| --- | --- |
| JDK | **17**（AGP 8.x 要求；本项目实测 OpenJDK 17.0.20） |
| Gradle | 8.14.3（由 `gradlew` 自动下载，无需全局安装） |
| Android Gradle Plugin | 8.11.1 |
| Kotlin | 2.2.0 |
| Android SDK | `compileSdk / targetSdk 34`，需安装 `platforms;android-34` 与 Build Tools |
| 最低系统 | Android 6.0（API 23） |
| 网络 | 首次构建需要访问 Maven 仓库；`settings.gradle.kts` 已配置阿里云镜像优先、海外源兜底 |

> 依赖解析与 Jsoup 1.17.x 需要 `coreLibraryDesugaring` 已开启，请勿关闭，否则 API 23 设备会 `NoClassDefFoundError`。

---

## 4. 快速开始

### 4.1 准备环境

```bash
# 指定本机 Android SDK（二选一：环境变量，或项目根目录 local.properties 中写 sdk.dir=...）
export ANDROID_HOME="$HOME/Library/Android/sdk"   # Windows 用 %LOCALAPPDATA%\Android\Sdk

java -version   # 需为 17；若不是，请自行设置 JAVA_HOME 指向 JDK 17
```

### 4.2 构建与测试

```bash
./gradlew assembleDebug          # 调试包：app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # 发布包：app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # 纯 JVM 单元测试（24 个用例）
./gradlew clean assembleRelease  # 增量缓存异常时用 clean 重建
```

### 4.3 安装到设备

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

> ⚠️ debug 与 release **签名不同**，来回覆盖安装会报
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE ... signatures do not match`，
> 需先执行 `adb uninstall com.yujiannatian.tv` 再安装（会清空本机历史／收藏）。

TV 设备还需要保证 `LEANBACK_LAUNCHER` 入口可用；Manifest 中已同时声明 `MAIN/LAUNCHER` 与 `MAIN/LEANBACK_LAUNCHER`。

---

## 5. 项目结构

```
ChuanHaiTV/
├── app/
│   ├── build.gradle.kts              # 模块构建配置、签名、依赖
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/yujiannatian/tv/
│       │   │   ├── App.kt            # Application：初始化站点配置 / 崩溃兜底
│       │   │   ├── core/             # 纯数据层：解析、仓库、缓存、下载、投屏、设置
│       │   │   └── ui/               # Activity / Fragment / Adapter / 封面加载
│       │   └── res/                  # layout、drawable、mipmap、values、xml
│       └── test/                     # JUnit4 单元测试
├── build.gradle.kts                  # 根构建脚本（AGP / Kotlin 插件版本）
├── settings.gradle.kts               # 仓库镜像与模块声明
├── gradle.properties                 # Gradle 参数 + 本地 release 签名配置
├── ChuanhaiTV-v1.0.0-release.apk     # 已签名分发包（可直接安装）
├── TECH_NOTES.md                     # 技术开发文档备忘录
└── README.md
```

关键文件速查：

| 想改什么 | 去哪里 |
| --- | --- |
| 应用名／文案 | `res/values/strings.xml` |
| 图标／启动页／Banner | `res/mipmap-*`、`res/drawable-nodpi/`、`ui/SplashActivity.kt` |
| 默认站点地址 | `core/SiteConfig.kt` 的 `DEFAULT_SITE` |
| 页面解析规则 | `core/HtmlParser.kt` |
| 网络超时 | `core/SiteHttpClient.kt`（页面长超时 / 图片短超时双通道） |
| 封面加载与兜底（含磁盘缓存） | `ui/ImageLoader.kt` |
| 播放控制条 | `res/layout/playback_controller.xml`、`ui/PlaybackActivity.kt` |
| 设置项 | `ui/SettingsFragment.kt`、`core/AppSettings.kt` |
| 搜索索引 / 拼音反查 | `core/SearchIndex.kt`、`core/Pinyin.kt`、`ui/SearchFragment.kt` |
| 下载、断点续传与分组 | `core/VideoDownloader.kt`、`ui/DownloadsFragment.kt`、`res/drawable/bg_download_progress.xml` |
| 分类索引（垂直列表） | `ui/CategoryIndexFragment.kt`、`res/layout/fragment_category_index.xml` |
| 联系作者页 | `ui/ContactFragment.kt`、`res/layout/fragment_contact.xml`、`res/drawable-nodpi/pay.png` |
| 版本号 | `app/build.gradle.kts` 的 `versionCode` / `versionName` |

---

## 6. 技术栈

| 领域 | 选型 | 版本 |
| --- | --- | --- |
| 语言 / 构建 | Kotlin + Gradle Kotlin DSL | JVM 17 |
| UI | AppCompat / Fragment / RecyclerView / Leanback | 1.6.1 / 1.6.2 / 1.3.2 / 1.0.0 |
| 播放 | androidx.media3 ExoPlayer + HLS + UI | 1.3.1 |
| 网络 | OkHttp | 4.12.0 |
| HTML 解析 | Jsoup | 1.17.2 |
| 本地存储 | SharedPreferences + Gson + 磁盘文件 | Gson 2.10.1 |
| 拼音搜索 | pinyin4j（含内置 GB2312 首字母表兜底） | 2.5.1 |
| 协程 | kotlinx-coroutines-android | 1.7.3 |
| 单测 | JUnit4 + org.json | 4.13.2 |

---

## 7. 关键行为说明

### 站点地址

默认地址写在 `SiteConfig.DEFAULT_SITE`，也可在 App 内「设置 → 新地址」中修改。保存前会先抓取并解析分类数据，**校验通过才生效**；失败时保留原地址，`使用上次可用地址` 可一键回退。更换地址会递增配置版本号使旧缓存整体失效。

### TV 与手机的差异

| 行为 | TV | 手机 |
| --- | --- | --- |
| 下载按钮 | 默认隐藏 | 默认显示 |
| 投屏入口 | 隐藏（TV 是接收端） | 显示 |
| 返回键 | 控制层显示时只隐藏 UI，不退出播放 | 直接返回 |
| 导航 | D-pad 焦点链，所有按钮显式声明 `nextFocus*` | 触屏为主 |
| 设置页焦点 | 进入时默认聚焦左上角「返回」，不弹输入法 | 同左 |

判定逻辑集中在 `core/DeviceKind.kt`（纯函数，有单元测试）。

### 播放与续播

- 控制层由 `PlaybackActivity` 统一管控：任意按键／触屏唤起「四角按钮 + media3 控制条」，4 秒后一起隐藏（关闭了 media3 自带计时器，避免两套计时器错开）。
- 控制条中间只有圆形半透明底的播放／暂停按钮，底部为进度条与时间文本。**进度条必须用 media3 官方占位符 `exo_progress_placeholder`**，详见 TECH_NOTES §7。
- 遥控器：OK／空格暂停，左右 10 秒快退快进，上到返回、下到设置，MENU 打开设置，播完自动下一集。
- 设置面板：倍速播放 + 投屏入口。**手机端**（发送端）显示投屏，TV 端（接收端）隐藏；标题栏在所有页面都不再显示站点 http 地址。
- `onStop()` 把 `currentPosition` 写入 `LibraryStore`；从历史／收藏进入时经 `EXTRA_START_POSITION` → `seekTo()` 自动续播。

### 下载与断点续传

- 点剧集「下载」后立即在「我的下载」登记一条 `downloading` 记录，按钮背景用 `ClipDrawable` 从左向右填充进度。
- HLS：`.part` 文件记录分片列表指纹与已完成分片数，续传时从下一片以 append 模式写入；播放列表变化（换源/直播）会丢弃旧进度从头开始。
- MP4：`Range: bytes=<已写入>-` 流式续传，服务端返回 `206` 才追加，返回 `200` 则从头覆盖。
- 取消任务保留已下载部分并把记录标为「已暂停」，「我的下载」里直接点「继续」即从断点续传；只有「删除」才清除文件与 `.part`。
- 应用启动时会自动校正上次被中断的下载记录：文件已完整则归入「下载完成」，否则归入可续传的「下载中 / 已暂停」，不会卡在「下载中 100%」。
- 「我的下载」分「下载中」「下载完成」两组：下载中显示百分比、已缓存大小、横向进度条与「取消」；已完成显示「播放」「删除」。进度刷新只更新可见行，不会因整表重建导致卡顿或按钮点不中。

### 封面图

站点封面分散在多个外部图床，部分图床可能失效。`ImageLoader` 的策略是：绑定时**同步显示圆角 App 图标兜底** → 查内存 LRU → **查磁盘缓存（命中就不再请求网站）** → 最后才走独立短超时网络通道，成功后写入内存与磁盘；同一图床连续失败会临时熔断，避免拖垮线程池。磁盘缓存位于 `cacheDir/covers/`，以 URL 的 MD5 命名，超过 64MB 自动按时间裁剪。所有设备统一 12dp 圆角裁剪。

---

## 8. 测试

```bash
./gradlew testDebugUnitTest
```

纯 JVM 单元测试共 24 个，覆盖 `HtmlParser`（4 类页面解析）、`HlsPlaylist`、`Pinyin`、`DeviceKind`、`CastController`。测试报告在 `app/build/reports/tests/testDebugUnitTest/`。

TV 真机的 D-pad 焦点链、对话框选择、返回键行为目前依赖布局＋代码核对与构建验证，建议在实机 TV 上复验一次。

---

## 9. 签名与发布

`app/build.gradle.kts` 的 `release` 签名从 Gradle Property 读取，缺省时自动跳过（方便无密钥的 CI 环境）：

```properties
# gradle.properties（本地文件）
RELEASE_STORE_FILE=release-key.jks
RELEASE_STORE_PASSWORD=<口令>
RELEASE_KEY_ALIAS=yujian
RELEASE_KEY_PASSWORD=<口令>
```

```bash
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk ChuanhaiTV-v1.0.0-release.apk

# 校验签名（apksigner 在 Android SDK build-tools 内）
apksigner verify --print-certs ChuanhaiTV-v1.0.0-release.apk
```

当前分发包已启用 v1 + v2 签名，证书 `CN=Chuanhai TV`。

> 🔐 **安全提醒**：签名密钥与明文口令不应提交到公开仓库。建议把上述四个属性移到用户级
> `~/.gradle/gradle.properties` 或改用环境变量 / CI Secret，并在 `.gitignore` 中排除 `*.jks`。
> 当前仓库的 `gradle.properties` 中含明文配置，公开前应清理并**更换密钥**。

---

## 10. 已知问题与注意事项

| 项 | 说明 |
| --- | --- |
| 封面图床失效 | 站点侧部分图床已不可达（如 `viptulz.com`、`img.lzipic.com` 等），端内无法修复；表现为显示圆角 App 图标。可选方案是自建封面代理或多图床回退，见 TECH_NOTES §12 |
| 构建产物入库 | 首次提交把 `.gradle/`、`app/build/` 等 2000+ 个构建产物纳入了版本控制；仓库中**缺少 `.gitignore`**，建议补充并清理索引 |
| 默认播放源 | 设置页内置 3 条保底线路，并在进入设置时异步探测站点真实线路；网络不通时仍可用保底项 |
| 版本号 | 当前仍为 `1.0.0 / versionCode 1`，对外发新版前需手动递增 |
| 项目代号 | `settings.gradle.kts` 中 `rootProject.name = "YuJianTV"` 为历史代号，不影响构建；如需统一可改名 |
| 端口与明文流量 | 站点为 HTTP，Manifest 已开启 `usesCleartextTraffic` 并配置 `network_security_config.xml` |

---

## 11. 文档索引

| 文档 | 内容 |
| --- | --- |
| [README.md](./README.md) | 项目概览、快速上手、构建与发布、已知问题（本文） |
| [TECH_NOTES.md](./TECH_NOTES.md) | 技术开发备忘录：架构与数据流、HTML 解析陷阱、封面加载故障根治、TV 遥控器交互、构建签名、排查清单与后续建议 |

---

## 12. 支持作者与版权

### 12.1 支持一下作者

**支持一下作者，您的鼓励是我持续更新的动力。**

如果本项目对你有帮助，欢迎微信扫码赞赏，金额随意，感谢支持：

<img src="./app/src/main/res/drawable-nodpi/pay.png" width="220" alt="微信赞赏二维码" />

> 微信扫码 · 赞赏作者　｜　[查看二维码原图](./app/src/main/res/drawable-nodpi/pay.png)

二维码源文件为 `app/src/main/res/drawable-nodpi/pay.png`，与应用内展示的是同一张图。
在 App 左栏「设置」下方点「**联系作者**」即可看到二维码、支持文案、版权声明与侵权联系邮箱
（作者信息已从原「设置」页底部迁移至此）。

| 页面 | 内容 |
| --- | --- |
| 标题 | 联系作者 |
| 作者 | 作者：polaris7765（董先生） |
| 支持文案 | 支持一下作者，您的鼓励是我持续更新的动力 |
| 版权声明 | 支持正版，如有侵权请联系，polairs7765@qq.com |
| 二维码 | 微信扫码 · 赞赏作者（`res/drawable-nodpi/pay.png`） |

### 12.2 版权与作者

- 作者：**polaris7765（董先生）**
- 联系邮箱：**polairs7765@qq.com**
- © 2026 川海影视TV　仅供个人学习交流，请支持正版

影视内容版权归各权利方所有，本项目不存储、不上传、不分发任何影视资源，仅提供对第三方公开页面的浏览与播放能力。
请支持正版，如有侵权请联系 **polairs7765@qq.com**。
