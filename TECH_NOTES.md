# 川海影视TV · 技术开发文档备忘录

> 项目代号：ChuanHaiTV（早期称 YuJianTV / 遇见影视 TV）
> 包名：`com.yujiannatian.tv`　版本：`1.0.0 (versionCode 1)`
> 适用平台：Android TV / Android 手机（横屏）　minSdk 23 / targetSdk 34 / compileSdk 34
> 文档更新：2026-11-10　作者：polaris7765

---

## 1. 项目定位与约束

一个**纯原生 Kotlin** 的影视聚合浏览 / 播放客户端，内容来自赞片 CMS（zanpian cms12 模板）站点。
无第三方 UI 框架、无图片加载库、无 DI、无 Room，尽量只依赖 AndroidX + media3 + OkHttp + Jsoup + Gson。

设计约束：

| 约束 | 说明 |
| --- | --- |
| 双端一致 | TV（遥控器 / D-pad）与手机（触屏）共用同一套页面，靠 `DeviceKind.isTv` 做少量分支 |
| 强制横屏 | 所有 Activity 在 Manifest 里 `screenOrientation="landscape"` |
| 弱网友好 | 任何网络失败都不能白屏 / 崩溃；封面必须有兜底图 |
| 低版本兼容 | minSdk 23，`Error` 级异常（`NoSuchMethodError` 等）也要归一化，不能只 catch `Exception` |
| 站点地址可换 | 所有 href 存为站点相对路径，换域名不影响已缓存结构 |

---

## 2. 技术栈与关键依赖

| 领域 | 选型 | 版本 | 备注 |
| --- | --- | --- | --- |
| 语言 / 构建 | Kotlin + Gradle Kotlin DSL + AGP | JVM 17 | `coreLibraryDesugaring` 必开 |
| UI | AndroidX AppCompat / Fragment / RecyclerView / Leanback | 1.6.1 / 1.6.2 / 1.3.2 / 1.0.0 | Leanback 仅用于部分 Presenter |
| 播放 | androidx.media3 ExoPlayer + HLS + UI | 1.3.1 | 自定义 `controller_layout_id` |
| 网络 | OkHttp | 4.12.0 | 页面 / 图片 / 下载共用，但超时策略分离 |
| HTML 解析 | Jsoup | 1.17.2 | 依赖 `java.util.function`，故需 desugaring |
| 本地存储 | SharedPreferences + Gson + 磁盘文件 | — | 无数据库 |
| 拼音搜索 | pinyin4j | 2.5.1 | 内置 GB2312 首字母表兜底 |
| 协程 | kotlinx-coroutines-android | 1.7.3 | `guardedLaunch` 统一异常兜底 |
| 单测 | JUnit4 + org.json | 4.13.2 | 24 个用例，纯 JVM |

> ⚠️ **核心库降级（desugaring）不能去掉**：Jsoup 1.17.x 用到 `java.util.function` / `java.util.stream`，
> 这些类在 API 24 之前不存在；关掉会在 Android 6.0 电视上直接 `NoClassDefFoundError`。

---

## 3. 模块结构

```
app/src/main/java/com/yujiannatian/tv/
├── App.kt                     Application：初始化 SiteRepository / CrashHandler
├── core/                      纯数据层（不依赖 Android View）
│   ├── HtmlParser.kt          4 类页面解析：sitemap / 列表 / 详情 / 播放器
│   ├── SiteRepository.kt      内存 LRU + 磁盘缓存 + TTL + LoadResult 归一化
│   ├── SiteHttpClient.kt      OkHttp 封装：页面长超时 / 图片短超时双通道
│   ├── SiteConfig.kt          站点地址、版本、回退地址
│   ├── CacheStore.kt          磁盘缓存读写
│   ├── Models.kt              VideoItem / VideoDetail / PlaySource / Episode ...
│   ├── LibraryStore.kt        历史 / 收藏（含播放进度 position）
│   ├── AppSettings.kt         允许下载、默认播放源、已知播放源
│   ├── VideoDownloader.kt     m3u8 分片下载 / AES-128 解密 / 本地合并
│   ├── HlsPlaylist.kt         m3u8 解析
│   ├── CastController.kt      DLNA / UPnP SSDP 发现与投屏控制
│   ├── Pinyin.kt / SearchIndex.kt  拼音首字母搜索索引
│   ├── DeviceKind.kt          TV / 手机判定（纯函数可单测）
│   └── CrashHandler.kt / SiteError.kt / LoadResult.kt
└── ui/
    ├── BaseTvActivity.kt      公共容器：header + container + overlay 状态层
    ├── MainActivity.kt        首页 + 设置（Fragment 切换）
    ├── SplashActivity.kt      启动页
    ├── HomeFragment.kt        左栏分类 + 右侧封面网格 / 历史 / 收藏
    ├── DetailFragment.kt      播放源 tab + 选集网格 + 收藏 / 下载
    ├── PlaybackActivity.kt    ExoPlayer 全屏播放 + 自定义控制层
    ├── GridActivity / SearchActivity / CategoryIndexActivity / DownloadsActivity
    ├── SettingsFragment.kt    地址检测 / 下载开关 / 默认播放源 / 版权信息
    ├── ImageLoader.kt         封面加载（内存 LRU + 磁盘 + 负缓存 / 分池 / 圆角裁剪 / 图床熔断 / 命中统计）
    ├── Presenters.kt          Leanback Presenter（VideoCardPresenter / InfoPresenter）
    └── TvAdapters.kt          VideoGridAdapter / SidebarAdapter / GridSpacingDecoration
```

---

## 4. 数据流

```
站点 HTML
   │  SiteHttpClient.get()（长超时 12s/20s + 3 次重试）
   ▼
HtmlParser ──► Models (VideoItem / VideoDetail / PlaySource / Episode)
   │
   ▼
SiteRepository（内存 ConcurrentHashMap + CacheStore 磁盘 + TTL）
   │  LoadResult.Ok / LoadResult.Err（SiteError 归一化，Error 级也捕获）
   ▼
Fragment (guardedLaunch 协程) ──► Adapter ──► RecyclerView / Presenter
                                      │
                                      └─► ImageLoader.load()  ──► 封面
播放链路：
DetailFragment → SiteRepository.playback(episodePath) → zanpiancms_player JSON
             → PlaybackActivity.start(url, paths, names, index, cover, startPositionMs)
             → media3 HlsMediaSource / ProgressiveMediaSource → 播放
             → onStop / finish() 保存 position 到 LibraryStore → 历史记录续播（收藏只到详情页）
```

`LoadResult` 是关键抽象：UI 层只处理 `Ok` / `Err`，不需要知道 HTTP、解析、缓存细节。
`SiteRepository.load()` 里**必须**捕获 `Throwable`（不只是 `Exception`），因为
`NoSuchMethodError` / `OutOfMemoryError` 属于 `Error`，漏掉会直接闪退。

---

## 5. HTML 解析要点（易踩坑）

解析目标为赞片 CMS `zanpian cms12` 模板，四类页面：

| 页面 | 关键选择器 | 注意 |
| --- | --- | --- |
| sitemap / 分类 | `div.layout-box` → `.box-title h2` → `.sitemap ul li` | 用 `LinkedHashMap` 去重，不能 `putIfAbsent`（API 24+ 才有） |
| 列表 / 搜索 | `#content > li` 或 `ul.img-list > li` → `a.img-pic` | 搜索页 `#content` 里多包一层 `ul.img-list` |
| 详情页 | `.vod-detail-pic img`、`a[id^='#con_playlist_']`、`ul#con_playlist_N` | 桌面 tab 是简称、移动下拉是全称，**按 id 去重取更长的名称** |
| 播放页 | `zanpiancms_player = {...};` 正则 | `url` / `name` / `next` 可能是字符串 `"null"` |

分页规则不统一，务必区分：

- 普通列表：`/dianshiju/` → `/dianshiju/index-2.html`
- **热播榜**：`/dianshiju/` → `/dianshiju/index_____hits.html`（5 个下划线），第 2 页 `index_____hits_2.html`
- 搜索：`/search/关键词.html` → `/search/关键词-2.html`

> 早期把热播榜当普通列表翻页，站点会静默返回第一页，导致"翻页无效 / 搜索索引重复"。

### 封面地址（重点）

站点卡片是懒加载结构：

```html
<img class="lazyload"
     src="/public/tpl/zanpiancms12/images/pic.png"
     data-original="https://hhmage.com/cover/xxxx.jpg" alt="似锦">
```

- `src` 是**本地占位图**，真实地址在 `data-original`；详情页海报则可能直接写在 `src`。
- 统一走 `coverFromImg()`：**优先 `data-original`，回退 `src`，含 `pic.png` 视为无效**。
- 相对路径统一由 `SiteRepository.urlFor()` = `URL(URL(base), path)` 解析。

---

## 6. 封面加载设计（历次故障的根治）

### 6.1 现象与根因

用户多轮反馈"缩略图不显示"，TV / 手机表现一致：满屏只有深色圆角空块
（`cover_placeholder`）。排查结论：

1. 实测站点列表页前 40 张封面里，`viptulz.com`(11) / `img.lzipic.com` /
   `p.lztutu.com` / `img.lzzyimg.com` 等图床**已失效**（连接超时 8s、HTTP 000），
   仅 `hongniuzyimage.com` / `hhmage.com` / `image.maimn.com` 可用（200）。
2. 早期图片请求复用页面抓取的 OkHttp client（connect 12s / read 20s /
   `retryOnConnectionFailure=true`）外加 `ImageLoader` 自身 3 次重试，
   单张最坏接近 100 秒；`Executors.newFixedThreadPool(4)` 被首页几十张卡片占满。
3. 当时的兜底逻辑写在**异步失败回调**里 → 回调永远排不上队 →
   界面一直停在初始占位块，**连 App 图标兜底都看不到**。

### 6.2 现行方案（`ImageLoader.kt`）

| 措施 | 作用 |
| --- | --- |
| **绑定时立即 `setImageBitmap(iconFallback)`** | 不等网络结果，任何时刻都有缩略图；成功才替换成海报，失败保持图标 |
| `SiteHttpClient.imageBytes()` 独立短超时通道 | connect 4s / read 6s / 不自动重试；图片与页面抓取隔离 |
| 重试降为 2 次；现拆为磁盘池 2 / 网络池 4 | 失败快速收敛，且失败任务不再堵住缓存命中任务，见 §6.3 |
| **图床熔断** `blockedHosts` | 同一 host 连续失败 2 次 → 熔断 5 分钟，期间直接走兜底，把线程让给好图床 |
| `ViewOutlineProvider` + `clipToOutline` | 所有 ImageView 统一裁圆角（`cover_corner_radius` = 12dp），**不依赖布局 / 自定义 View**，覆盖首页网格、详情封面、Leanback `ImageCardView` |
| `iconFallback` 按尺寸缓存 | 深色卡片底 + 居中 App 启动图标，避免正方形 icon 被 centerCrop 裁切；滚动时复用 Bitmap 减少 GC |
| `cover_placeholder.xml` 加 `corners` | 占位底本身也圆角，与裁剪叠加不冲突 |
| **磁盘池 / 网络池分离** | 见 §6.3，避免失败网络任务堵住磁盘命中任务 |
| **缓存写 `filesDir`** | `cacheDir` 会被系统在存储紧张时整体清掉，导致「缓存莫名其妙没了」 |
| **URL 负缓存** | 失效封面 6 小时内直接兜底，不再每次启动重复联网 |

> 经验：**兜底图必须在绑定时同步设置，不要放到异步失败分支**。弱网 / CDN 半死状态下，
> "失败"可能需要几十秒甚至几分钟才发生，用户看到的就是长时间空白。
> 但注意：这条只适用于「需要异步加载」的情况，内存命中 / 同图重绑必须先判断，
> 否则每次重绑都会先闪一下兜底图，看起来反而像「缓存没生效」。

### 6.3 缓存命中率专项（本轮修复）

用户反馈「缩略图缓存命中率不高，是不是没优先从缓存读」。核查结论：**旧代码顺序确实是
内存 → 磁盘 → 网络**，但以下几个实现细节让命中在体感上不成立：

| 根因 | 修复 |
| --- | --- |
| 磁盘读取和「2 次网络重试 + 200ms sleep」共用同一个 6 线程**无界**队列；失效图床的失败任务长期占满线程，磁盘命中任务被堵在队尾，用户只看到兜底图 | 拆成 `diskPool`(2 线程，读盘) 与 `netPool`(4 线程，有界队列 48 + `DiscardOldestPolicy`)；磁盘命中不再受网络排队影响 |
| 每次 bind 无条件先 `setImageBitmap(iconFallback)`，哪怕内存已命中或 View 上已经是同一张图 | 新增 `tag_image_shown`：内存命中同步直接显示；同图重绑直接 return，不排队也不闪图 |
| 离屏 / 已复用的 View 仍会跑完整个读盘 + 网络流程，白白占线程 | 每个异步任务开工前 `isStillBound(target, key)` 校验；落图时主线程二次校验 |
| 缓存写在 `cacheDir`，系统清理后全部丢失；同一张图列表页 `http`、详情页 `https`、HTML 残留 `&amp;` / `#` 会被当成不同 key | 改 `filesDir/covers/`；`normalizeCoverKey()` 归一化；读取时兼容 4 种旧目录 / 旧 key 组合并自愈迁移 |
| 写盘 `.tmp` 文件名固定，同一 URL 并发下载会互相覆盖导致 `renameTo` 失败 | 临时文件名加 `System.nanoTime()` 唯一后缀 |
| 图床熔断表只在进程内，重启后要把失效 URL 全部重试一遍 | `filesDir/covers_failed/<md5>.fail` 记录时间戳，`NEGATIVE_TTL_MS = 6h`，期间直接兜底；下载成功时清除标记 |

统计口径：`requests` 只在请求**有结论**后自增（内存 / 磁盘 / 负缓存 / 网络成功 / 确定失败），
每 10 次在 logcat 打一行 `ImageLoader` 快照，命中率 = 未发起网络请求的比例。
调用 `ImageLoader.cacheStats()` 也可主动取快照。

实测（Pixel 3a API 34 模拟器，release 包）：

```
冷启动     请求=10 内存=0 磁盘=0 负缓存=0 网络=4 失败=6 缓存命中率=0%
二次启动   请求=10 内存=0 磁盘=4 负缓存=6 网络=0 失败=0 缓存命中率=100%
三次启动   请求=10 内存=0 磁盘=4 负缓存=6 网络=0 失败=0 缓存命中率=100%
```

> 注意：10 张封面里 6 张来自已失效图床，**这部分永远无法变成真实海报**；
> 负缓存只是把它们从「每次都联网失败」变成「零请求直接兜底」。要提升真实海报覆盖率，
> 仍需站点侧修图床或引入封面代理。

### 6.4 已知外部依赖风险

图床失联属于**站点侧问题，App 无法修复**。当前表现：可用图床的海报正常显示，
失效图床显示圆角 App 图标。若需进一步提升覆盖率，可考虑：
站点自建封面代理 / 多图床地址回退 / 按 `alt` 片名做本地缓存映射。

---

## 7. 播放与遥控器

- `PlayerView` 关闭 media3 自动显隐（`controllerAutoShow=false`、
  `controllerShowTimeoutMs=0`），由 `PlaybackActivity` 用 4000ms 定时器统一控制
  「四角按钮 + 控制条」同显同隐，避免两套计时器错开导致"再次唤起按钮不出来"。
- 自定义控制条 `res/layout/playback_controller.xml`：**中间只保留播放 / 暂停按钮**，
  背景为圆形半透明 `bg_play_pause.xml`（覆盖 `ExoMediaButton` 默认的方形波纹）；
  **底部保留进度条 + 当前时间 / 总时长**，外层用 `bg_controller_bottom.xml` 渐变遮罩保证可读。
- ⚠️ 进度条必须用 media3 官方的 `@id/exo_progress_placeholder` 占位 View：
  `PlayerControlView` 构造时会把它原地替换成库自带的 `DefaultTimeBar`（id=`exo_progress`），
  并自动接管进度 / 缓冲刷新与拖拽 seek。曾尝试在 XML 里直接手写
  `<androidx.media3.ui.DefaultTimeBar>` + 自定义 `app:*` 颜色属性，结果 View 存在、尺寸正常、
  时间文本也刷新，但 **`onDraw` 一个像素都不绘制**（实测截图底部全黑）——属高耗时坑，勿回退。
  `exo_position` / `exo_duration` 两个 TextView 只要 id 正确即由库自动更新时间。
- 进度条不设焦点（`focusable=false`），遥控器左右键仍走 `PlaybackActivity` 的快退 / 快进，
  触屏拖动 seek 不受影响。
- 遥控器快捷键在 `dispatchKeyEvent` 中处理：OK / 空格暂停、左右 10s 快退快进、
  上到顶部返回、下到右上设置、MENU 打开设置、播完自动下一集。
  `focusInPlayer()` 判断焦点归属，焦点在控制按钮上时把按键交还焦点系统。
- TV 端隐藏投屏入口（TV 是接收端）；手机投屏成功后暂停本机播放。
- 返回键：面板打开先关面板；TV 控制层显示时只隐藏 UI、不退出播放。
- 续播：`EXTRA_START_POSITION` → `p.seekTo()`；`onStop()` / `finish()` 把
  `currentPosition` 写回 `LibraryStore`，**仅历史记录**再进入时自动按上次进度播放。
  「我的收藏」只作书签：`HomeFragment.onGridClick()` 对 `NavType.FAVORITES` 直接调
  `DetailActivity.start()`（不带 resume 参数），只打开剧集详情 / 选集列表，不自动起播。
- 顶部「片名 · 第X集」(`play_title`) 与左上「返回」、右上「设置」共用
  `bg_overlay_chip.xml`：默认态仍是原来的 `#99000000` 半透明黑，焦点 / 按下态提亮加描边，
  在 XML 里覆盖 `TvChip` 样式自带的 `bg_button`，TV 上依然看得见焦点。

---

## 8. 设置页（TV 交互专项）

| 项 | 实现 |
| --- | --- |
| 地址检测 | `SiteRepository.probe()` 抓 sitemap 并解析出分类数，成功才 `SiteConfig.commit()` |
| 新地址输入框 | **默认留空**（只显示 hint），进入时不抢焦点，避免弹出输入法选择器 |
| 默认焦点 | **TV 专属**：进设置聚焦左上角 header 的 `btn_back`；手机端 `TouchFocus` 已清掉 `focusableInTouchMode`，触摸模式下 `requestFocus()` 会失败且手机不需要 D-pad 焦点，故只清输入框焦点 + `hideSoftInput()`（`onPause()` / 打开列表时同样处理） |
| 默认播放源 | 用 **Button + AlertDialog 单选列表**，不用 `Spinner`——Spinner 下拉弹层在 Android TV 上无法用方向键选中条目 |
| 播放源列表来源 | `AppSettings.knownSources()` = 内置保底线路（新视觉云 / 爱奇艺 / 好好云）+ 详情页累积的真实线路；进设置再异步探测首页首部影片详情补充 |
| 允许下载 | TV 默认关、其余端默认开；详情页据此显隐单集下载按钮 |
| 标题栏 | 设置 / 搜索 / 分类索引 / 子菜单 / 详情统一 `showHeader(title)`，不再显示 `SiteConfig.baseUrl` |
| 版权信息 | 已迁移到左栏「联系作者」页（`ContactFragment`）：作者信息 + `pay.png` 赞赏二维码 + 支持文案 + 侵权联系邮箱 |

> 侧栏顺序：热门推荐 → 历史记录 → 我的收藏 → **搜索** → 各大类 → 分类索引 → 我的下载 → 设置 → **联系作者**。
> 侧栏 `RecyclerView` 关闭滚动条与 overscroll（`scrollbars="none"` / `overScrollMode="never"`）。
> 分类索引从 Leanback `BrowseSupportFragment`（ListRow 横向滚动）改为 RecyclerView 垂直单列，
> 结构上只允许上下滑动。

**分类下的影片列表为多列封面网格**：`GridFragment` 用普通 `Fragment` + `RecyclerView` +
`GridLayoutManager`，列数按屏幕宽度以 132dp 卡片基准动态计算（`3~6` 列）；卡片直接复用首页
`item_video.xml`，因此海报尺寸、12dp 圆角、字号、焦点高亮与「热播榜」完全一致。方向键
上下换行、左右换列，同一行任意一列都能选中；`canScrollHorizontally()` 恒为 false、
`overScrollMode=never`，列表只做纵向滚动，不会出现整页左右滑动。

配套细节：`GridSpacingDecoration(columns, 8dp)` 给列间距与行间距；分页为「焦点接近尾部」+
「滚动接近尾部」双触发（阈值 12 项）；`focusFirstItem()` 分 120ms / 600ms 两波把首次焦点交给
第一项（状态层移除与系统把焦点给「返回」按钮是分帧完成的），且只要焦点已在列表内就不再抢
焦点，不打断用户操作。

> 演进：这一页先后用过 Leanback `VerticalGridPresenter(5 列)` → 单列行卡片
> (`item_video_row.xml`) → 现行多列网格。单列方案虽彻底禁掉左右键，但一屏只能看到 3 部，
> 用户要求恢复与首页一致的紧凑网格，因此删除 `item_video_row.xml` 与
> `VideoGridAdapter.layoutRes` 参数，左右键重新用于同行换列（这是多列网格能被完整操作的前提）。

> 实机验证：进入「国产剧」后首行首列呈焦点高亮，左右键在同一行换列、下键换行。
> 注意 `uiautomator dump` 不报告 RecyclerView 项的 `focused=true`，这个页面要用截图高亮判断焦点。

> 播放源名称必须与 `HtmlParser.parseDetail` 解析出的名称一致才能匹配生效
> （`DetailFragment` 用 `indexOfFirst { it.name == def }`，匹配不到退回第一个）。

---

## 9. 缓存、存储与下载

- 内存缓存 `ConcurrentHashMap`，TTL：分类 6h / 列表 20min / 详情 30min / 播放地址 1min。
- 缓存 key 含 `SiteConfig.version` 与 `baseUrl`，换地址后自动失效。
- 历史 / 收藏 / 偏好用 SharedPreferences + Gson；下载文件落 `getExternalFilesDir(MOVIES)/yujian_downloads`。
- **封面三级缓存**：`filesDir/covers/<归一化 key 的 MD5>.img` 存原始图片字节，原子写入
  （唯一 tmp 名 + rename）；`cacheDir/covers/` 与「原始 URL 作 key」的旧文件仍只读兼容并自愈迁移。
  超过 64MB 按 lastModified 裁剪到 48MB。加载顺序：内存 LRU → 磁盘 → 负缓存 → 网络；
  命中磁盘 / 负缓存完全不发请求。图床熔断只作用于网络阶段，不影响已缓存封面。
  `filesDir/covers_failed/<md5>.fail` 保存失效时间戳（6h TTL），避免重启后重复联网，见 §6.3。
- `VideoDownloader`：m3u8 解析 + AES-128 解密 + 分片合并；进度通过观察者回显到选集按钮，
  按钮背景用 `bg_download_progress.xml` 的 `ClipDrawable`（level = progress × 100）从左向右填充。
- **断点续传**：
  - m3u8：`stem(key).part` 记录「分片 URL 列表指纹 + 已完成分片数 + 总数」，
    指纹一致且目标文件存在时从下一片 `FileOutputStream(target, append=true)` 继续；换源/直播导致指纹变化则从头开始。
  - MP4：`SiteHttpClient.openRange()` 带 `Range: bytes=<len>-`，206 才追加写、200 从头覆盖；
    用 64KB 缓冲流式写入并按字节数回传进度，避免整包读进内存 OOM。
  - 同步 OkHttp 调用不响应协程取消，两个下载循环里每片／每 64KB 都显式检查 `isActive(key)`，
    否则点「取消」后任务仍会把整部片写完并误标为完成。
  - 取消任务保留文件与 `.part`，并把记录标为 `paused`；「我的下载」直接给「继续」按钮，
    点击后 `enqueue()` 重新解析播放地址并从断点续写，不必再回详情页。删除任务才清除文件与 `.part`。
  - `App.onCreate()` 调用 `VideoDownloader.reconcile()` 校正上次进程被回收时的残留记录：
    文件完整且进度已到 100 → 判定 `done`；否则 → `paused`。避免列表永远停在「下载中 100%」。
    删除记录后同步复位内存状态，否则详情页按钮会一直显示「重试」。
- **我的下载分组**：`enqueue()` 立即写 `status=downloading` 记录，`update()` 每 2% 节流持久化，
  `DownloadsFragment` 分「下载中 / 下载完成」两组并 `observe()` 实时刷新；下载中显示 ProgressBar 与取消按钮。
  `DownloadsStore.list()` 对 `done` 记录才校验文件存在（下载中的文件还没生成，不能过滤掉）。
  - 进度每秒多次回调，**不能每次都 `notifyDataSetChanged()`**：整表重建会让渲染线程掉到 3fps、
    界面持续非 idle，且「取消／删除」的点击会被 rebind 吃掉。现在 `submit(newRows, host)` 先比对
    行结构键（`H:标题` / `E:record.key`），结构不变时只原地刷新屏幕上可见的条目，不触发任何 notify；
    只有增删条目／分组计数变化才整体刷新。

---

## 10. 构建、签名与发布

```bash
./gradlew assembleDebug            # 调试包
./gradlew testDebugUnitTest        # 24 个 JVM 单测
./gradlew assembleRelease          # 已签名发布包
```

- 签名配置在 `gradle.properties`（`RELEASE_STORE_FILE=release-key.jks`、alias `yujian`），
  `app/build.gradle.kts` 读取 4 个属性；缺失时跳过签名，保证无密钥的 CI 也能构建。
- `apksigner verify --print-certs` 校验 v1 + v2，证书 DN `CN=Chuanhai TV`。
- `app/release-key.jks` 属于**不可恢复的发布凭证**，已加入 `.gitignore`，务必另行备份。
- 交付物：`ChuanhaiTV-v1.0.0-release.apk`。

> 曾遇到 `sips` 生成图标时扩展名是 `.png` 但内容仍是 JPEG，AAPT2 直接拒绝；
> 生成真 PNG 必须带 `-s format png`，并检查头字节 `89 50 4E 47`。

---

## 11. 已知问题与排查清单

| 现象 | 排查结论 / 处理 |
| --- | --- |
| 缩略图空白且兜底也不显示 | 图床大量失效 + 长超时堵死线程池；见 §6，已根治 |
| TV 上 Spinner 选不中其他项 | 改用 AlertDialog 单选列表 |
| 进设置弹出输入法 / 键盘 | 输入框不抢焦点 + `hideSoftInput()` |
| SourceTree 提交报 `cannot lock ref 'HEAD'` | `.git/HEAD` 曾指向无效 `refs/heads/.invalid`；用 `git symbolic-ref HEAD refs/heads/main` + `git update-ref` 恢复已生成的 commit |
| AAPT2 级联报 bg_card / bg_sidebar not found | 通常是同目录某个 drawable XML 命名空间畸形（如只写 `xmlns=` 却用 `android:` 前缀），修第一个报错即可 |
| API 23 电视闪退 | 检查 desugaring 是否开启、是否用了 API 24+ 的 `Map.putIfAbsent` 等 |
| 翻页无效 / 索引重复 | 热播榜分页要用 `index_____hits.html` |
| 投屏按钮 TV 仍显示 / 手机端设置里没有投屏 | 检查 `applyDeviceRules()` 与 `DeviceKind.isTv()`；曾因无条件 `btn_settings_cast = GONE` 把手机端投屏也一起隐藏，现改为仅 TV 隐藏 |
| `ZMSR` 搜不到《走马上任》 | 站点 `/search/{kw}.html` 只支持中文，拼音首字母直接丢过去必然零结果；且本地索引只抓各大类前 8 页热播榜，长尾片名不在其中。修复：索引页数提到 12 页 + `SearchIndex.remoteByInitials()` 用 `Pinyin.candidateChars()` 取首字常见汉字（Z → 「走」）并发站内搜索补索引，再由 `Pinyin.filter()` 按首字母过滤 |
| 分类索引能左右滑动 | 旧 BrowseSupportFragment 的 ListRow 是 HorizontalGridView；已改为 RecyclerView 垂直单列 |
| 分类下影片列表一屏只有 1 部 / 与首页网格不一致 | 曾用 `LinearLayoutManager` 单列行卡片；现统一为 `GridLayoutManager` 动态 3~6 列 + `item_video.xml`，见 §8 |
| 封面缓存命中率低 / 每次都像重新加载 | 顺序本来就是缓存优先，但磁盘读取被失败网络任务堵在无界队列里、每次 bind 先闪兜底图、`cacheDir` 会被系统清理、失效 URL 每次重启都重试；修复与实测见 §6.3 |
| 手写 `DefaultTimeBar` 不绘制 | 必须用 `exo_progress_placeholder`，见 §7 |
| `ZMSR` 搜出两张一模一样的卡片 | 多个候选汉字反查会带进同片名不同 path 的条目；`Pinyin.filter()` 排序后按片名 `distinctBy` 去重。同步完成后状态文案改为「已同步站内搜索结果」，不再长期停在「正在同步…」 |
| 下载列表卡在「下载中 100%」 | 进程被回收时最后一次 100% 已落盘但 `done` 状态没写入；`App.onCreate()` 的 `reconcile()` 按文件完整性校正为 done / paused |
| 下载中点不到「取消」、界面卡顿 | 进度每次回调都 `notifyDataSetChanged()` 整表重建；改为结构不变时只原地更新可见条目，见 §9 |
| 取消后条目消失、无处续传 | `cancel()` 由删除记录改为标记 `paused`，「我的下载」提供「继续」按钮 |
| 手机端点按钮要按两次才生效 | `TvButton` / `TvChip` 样式与 `item_sidebar` / `item_video` / `card_text` 都设了 `focusableInTouchMode=true`，触摸模式下第一次点按只聚焦、第二次才触发点击。由 `TouchFocus` 在非 TV 设备统一清掉该属性（`EditText` 除外，否则输入框唤不起键盘），TV 设备完全跳过、焦点属性一个都不改 |
| 从「我的收藏」点剧集直接开始播放 | `HomeFragment.onGridClick()` 原先对历史 / 收藏都查 `LibraryEntry` 并走 `startResume()`；现对 `FAVORITES` 直接 `DetailActivity.start()`，续播只保留给历史记录 |

---

## 12. 后续建议

1. `.gitignore` 已补（忽略 `*.jks`、根级 `/build`、`/.gradle`、`/local.properties`），
   但还需补 `app/build/`、`*.apk`，并用 `git rm -r --cached` 清理首次提交误入的
   2000+ 个构建产物；根目录仍留有两个 `copy.apk`。
2. 版本号发版时递增 `versionCode` / `versionName`（当前仍是 1）。
3. 真机 TV 回归：D-pad 焦点链、播放源对话框、播放按钮圆角、返回键行为
   （本机无 TV AVD，这几项主要靠布局 + 代码核对 + 单测覆盖）。
4. 图床失效是长期外部风险，可评估封面代理或标题兜底图生成策略。
5. `release-key.jks` 口令与密钥离线备份，避免无法升级发布。
