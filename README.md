# 本地图片浏览器（Local Image Browser）

完全离线运行的本地文件阅览器，以一个单页 HTML 为核心，既可在浏览器中直接使用，也打包为安卓 App；虽然名字叫图片浏览器，实际上同时支持图片、视频、离线网页归档（.mht）、PDF 文档（.pdf）和文本文档（.txt）的本地阅览。设计目的是让用户可以在单一软件内方便的实现对本地较多常见类型资源的阅览，且每一种类型都能获得较好地浏览体验。

## 特性

### 图片阅览
- 左右翻页、右左翻页（适合漫画）、上下整页、上下连续条漫四种模式
- 支持双指捏合缩放（1–5 倍）、双击缩放
- 横向滑动翻页带手势吸附，一次手势最多翻一页
- 缩略图懒加载、阅读进度自动记忆
- 条漫未加载图片按比例预估高度，快速跳转不卡页
- 菜单打开时进度条常驻；切换屏幕常亮，选择自动记忆

### 视频播放（仅安卓 App，ExoPlayer 原生硬解）
- 双击暂停 / 播放，单击显隐菜单
- 长按 0.15 秒倍速播放（0.25–5.0 倍，速度与长按倍速分别记忆）
- 走 AudioTrack 即时变速通道，按下 / 松手切换倍速跟手不延迟、松手不"冲"一段
- 画面中部横拖快进 / 快退，循环 / 单次播放切换，同文件夹视频播单
- 横屏锁定；视频首帧封面缩略图后台抓取
- 播放时屏幕默认常亮，暂停或播完后恢复自动息屏
- 原生网页视频播放器对部分较大较长视频的播放支持较差，因此网页版阉割了视频播放功能

### 文本文档（.txt）小说模式阅读
- 自动识别 UTF-8 / UTF-16（含 BOM）/ GB18030(GBK) 编码
- 左右 / 右左 / 上下三种阅读模式；横排按行分页、段落可跨页续写，章节标题自动另起一页
- 字号、行距、三种背景主题、屏幕常亮可调并记忆
- 自动识别「第 X 章 / 回 / 节 / 卷」「Chapter N」「序章 / 番外」等生成目录，支持多卷小说
- 每个文件的阅读位置独立记忆

### PDF 文档（.pdf，仅安卓 App，内置 pdf.js）
- 内置 pdf.js v3.11 legacy 完全离线解析，含中日韩字符集（cmaps）与标准字体，中文 PDF 不乱码，无需联网
- 纵向条漫连续滚动（默认，页间无缝）、纵向整页翻页、左右翻页、右左翻页；翻页与图片阅览同为原生吸附整页，跟手不跳页
- 双指捏合 / 双击 1–5 倍缩放；整页模式放大后可单指拖动，翻页自动复位
- 全页 DOM 常驻 + IntersectionObserver 窗口化渲染（预加载约两屏、远处回收），渲染限并发、当前页优先，长文档滚动跟手
- 大文件走 206 分片按需读取、不预取不可见页，首屏快速开页
- 进度条拖动按页跳转并浮出页码，支持屏幕常亮、横屏锁定，阅读位置按文件分别记忆，音量键可翻页
- 网页版中 PDF 显示为灰色卡片，提示仅支持 App

### 离线网页归档（.mht / .mhtml，仅安卓 App）
- 独立 WebView 离线还原文字、图片、样式，拦截外链跳转，支持归档内历史回退与双指缩放
- 大部分浏览器已默认支持 .mht / .mhtml 格式，因此网页版不提供离线网页浏览功能

### 文件浏览
- SAF 授权浏览完整目录树；历史文件夹、收藏（文件夹 / 视频 / .mht / .pdf / .txt）
- 长按收藏、文件名搜索（支持多关键词与是否包含子目录）、随机排序
- 类别过滤：可只显示文件夹 / 图片 / 视频 / 离线网页 / 文本文档 / PDF 中的任意几类，全不选即显示全部
- 默认排序：文件夹 → 图片 → 视频 → 离线网页 → 文本文档 → PDF → 其他，各类内部按名称数字感知排序
- 右下角悬浮键自上而下为「回到顶部 → 随机排序 → 主页」
- 「上次浏览」精确恢复到子目录
- 大体量目录优化：App 端多线程并行扫描目录；再次打开授权过的文件夹时先用本机目录树索引秒进，随后后台静默检查增删，有变化自动热替换并提示「目录已更新」，无变化零打扰；顶栏刷新键可立即手动更新；加载中可放弃扫描，已发起的扫描线程立即收工不占资源
- 目录树索引缓存带语法 / 完整性校验（损坏自动删除走全量扫描），写入为临时文件 + 原子改名，防止进程被杀留下半截 JSON
- 「清理缓存」支持分类清理（阅览器图片缓存 / 文件夹路径缓存 / 浏览历史 / 阅读器个性设置 / 全部），历史文件夹、授权与收藏始终保留
- 授权丢失的历史文件夹、路径已不存在的收藏会标红提示失效，点击后可确认删除；文件重新出现时自动恢复
- 网页版支持收藏列表导出 / 导入 JSON 备份，导入默认与现有收藏按收藏键合并去重
- 暂不考虑添加涉及到文件管理的功能，如文件的移动/重命名等

### 网页版与 App 版
| 能力 | 安卓 App | 浏览器 |
| --- | --- | --- |
| 图片阅览 / 条漫 / 缩放 / TXT 阅读 | ✅ | ✅ |
| 文件夹与 TXT 收藏、历史、子目录恢复 | ✅ （收藏应用内存储）| ✅（收藏可导入导出，重开需再次授权文件夹） |
| 视频播放、.mht / .pdf 浏览及其收藏 | ✅ | ❌ |
| 横屏锁定、音量键翻页 | ✅ | ❌ |
| 授权永久免重选、启动自动恢复 | ✅ | 受浏览器、https限制 |
网页版文件解析不稳定，建议使用App版以获得最好的使用体验

## 目录结构

```
image-browser.html          核心单页应用（全部前端逻辑与样式）
android-app/                安卓 WebView 套壳工程
  app/src/main/java/.../    MainActivity（SAF/桥接）、VideoActivity、MhtActivity
  app/src/main/assets/      构建时复制 image-browser.html 为 index.html；pdfjs/ 为内置 PDF 引擎
  app/src/main/res/         播放器矢量图标与布局
  build-apk.ps1             同步 HTML 并构建 debug APK
  build-release.ps1         同步 HTML 并构建已签名 release APK（需本地 keystore）
```

## 自行构建安卓 APK

项目保留了构建所需的脚本，用户可以自行构建安卓 APK。

环境要求：JDK 17、Android SDK（compileSdk 35）

Debug 包（包名 `com.lctechgroup.imgbrowser`）：

```powershell
cd android-app
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

Release 包（包名 `com.lctechgroup.imagebrowser`，需在 `android-app/keystore.properties` 配置本机签名凭据，该文件不入库）：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-release.ps1
```

构建脚本会把 `image-browser.html` 复制到 `app/src/main/assets/index.html`，再用 Gradle wrapper 构建，debug / release 产物分别位于
`app/build/outputs/apk/debug/app-debug.apk` 与 `app/build/outputs/apk/release/app-release.apk`。两个包名不同，可在同一台设备并存。

首次构建时 wrapper 会自动下载 Gradle 8.13。本机 SDK 路径写在 `android-app/local.properties`（不入库）：

```
sdk.dir=D:/android-sdk
```

## 网页版使用

直接用支持 [File System Access API](https://developer.mozilla.org/docs/Web/File_System_Access_API) 的浏览器（推荐安卓 / 桌面 Chrome、Edge）打开 `image-browser.html`，点击「选择文件夹」授权即可。其他浏览器降级为目录选择，不保留历史。可以部署为静态站，但若未配备证书，浏览器会提示不安全，无法保存已授权的文件夹的访问权限

## 技术说明

- 本软件代码在TRAECode开发环境下，由豆包Seed-Evolving编写生成
- 前端为零依赖原生 HTML / CSS / JavaScript，无构建步骤；PDF 能力由随包内置的 pdf.js v3.11 legacy 提供（约 3.3MB，含 cmaps 与标准字体）
- 安卓侧通过 `@JavascriptInterface` 桥接 SAF 文件读取、视频播放、MHT、横屏、音量键、屏幕常亮等能力
- 视频通过 ExoPlayer 直接读取 SAF content uri，配合 206 分片响应支持拖动；倍速走 AudioTrack.setPlaybackParams 即时变速
- 目录扫描为 4 线程并行（Phaser 协调，可代次取消）；目录树索引缓存在 `filesDir/treecache`，原子写入，与历史记录一一对应
- 虚拟分页与窗口化渲染均为离屏测高 + 只渲染邻近页 / 段；PDF 条漫为固定 px 宽度链 + canvas 窗口化 + 渲染并发闸门

## 许可证

[MIT](./LICENSE) © 2026 R.I.S.E. <2947059843@qq.com>
