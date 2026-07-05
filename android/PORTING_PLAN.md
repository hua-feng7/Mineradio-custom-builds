# Mineradio Android 移植实施方案

## 1. 移植策略

选择方案：Android WebView + 本地 Java HTTP 兼容层。

理由：

- 原项目主视觉集中在 `public/index.html`，大量使用 WebGL、Three.js、Canvas、CSS glass/blur 和 Web Audio。WebView 能最大限度保留歌词舞台、粒子视觉、3D 歌单架的渲染和交互逻辑。
- Electron 的 `server.js` 依赖 Node/Electron 桌面能力，Android 不能直接运行。当前移植用 `LocalHttpServer` 在 App 内监听 `127.0.0.1:<port>`，向 Web 前端提供 `/api/*` 兼容接口。
- 相比 Cordova/Capacitor，本项目只有一个 Activity，手写 WebView 宿主更可控，方便强制横屏、调 WebView GPU/音频策略、接 JavaScript bridge。

当前交付是 Android WebView 移植 APK：核心 WebGL 视觉、启动页、粒子主界面、Android guest 双平台 starter 歌单、QQ 官方歌单、3D 歌单架、搜索、DIY 视觉控制台、触控工具条、音频播放、本地歌单收藏、本地红心、beatmap 磁盘缓存、播客、评论、歌手详情兼容接口、网易云原扫码登录、网易云/QQ Cookie 持久化、网易云个人歌单读取、网易云歌单云端创建/添加请求路径、Android MediaSession/前台媒体通知、QQ 官方扫码登录窗口和 QQ 个人歌单读取路径已迁移。QQ 云端写歌单、真机锁屏后台策略和真实账号大范围回归仍需要第二阶段继续原生化。

## 2. 仓库结构分析

- `public/index.html`：核心 Web 应用，包含视觉舞台、搜索、播放、歌词、粒子、3D 歌单架等主要逻辑。
- `public/vendor/three.r128.min.js`：Three.js 运行时。
- `public/assets/skull-decimation-points.bin`：粒子/点云视觉资源。
- `server.js`：Electron/桌面本地 API 服务，Android 端已由 Java `LocalHttpServer` 做兼容替代。
- `main.js`：Electron 主进程，Android 端不使用。

新增 Android 工程位于 `android/`。

## 3. 分步操作

1. 保留原 Web 前端，把 `public/*` 复制进 `android/assets/www/`。
2. 新建 Android manifest、Activity、Application 和资源主题。
3. 在 `MainActivity` 中创建全屏横屏 WebView，开启 WebGL 所需硬件加速、DOM storage、媒体自动播放和 mixed content。
4. 在 App 内启动 `LocalHttpServer`，服务静态资源和 `/api/*`。
5. 通过 JavaScript bridge 暴露 Android 平台信息、Toast、外链打开、屏幕常亮控制和网易云/QQ 音乐登录窗口。
6. 网易云保留原仓库 QR key/create/check 扫码流程；QQ 通过 Android `Dialog + WebView + CookieManager` 打开 QQ 音乐官方扫码登录页；同时保留 Cookie 导入和网易云官方网页登录备用桥。
7. 通过 `android-touch-adapter.js` 注入 Android 专用触控层，覆盖搜索、DIY、FX、歌单、3D 架、底栏、导入和背景媒体入口。
8. 用 `build_apk.ps1` 先同步 `public/*` 到 `android/assets/www/`，再调用 Android SDK 工具链手工构建、dex、zipalign、apksigner 签名 APK。
9. 用 `verify_android_port.ps1` 做本地静态门禁，校验横屏声明、WebView 配置、包内 assets、QQ 官方歌单接口、歌词舞台修复、JS 语法和 APK 签名。
10. 在显性启动的 Android 模拟器中安装验证启动、横屏、WebGL 渲染、触控工具条、网易云/QQ 登录 bridge、官方网页登录 Dialog、starter 歌单、3D 歌单架、DIY 触控、音频播放和 Back 弹窗行为。

## 4. 核心代码

### AndroidManifest.xml

位置：`android/AndroidManifest.xml`

关键点：所有 Activity 必须声明横屏；同时 Application 再做全局兜底。

```xml
<application
    android:name=".MineradioApplication"
    android:hardwareAccelerated="true"
    android:usesCleartextTraffic="true"
    android:resizeableActivity="false"
    android:theme="@style/AppTheme">
    <activity
        android:name=".MainActivity"
        android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode"
        android:exported="true"
        android:launchMode="singleTask"
        android:screenOrientation="landscape">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

### 全局横屏兜底

位置：`android/src/com/mineradio/android/MineradioApplication.java`

```java
registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }
});
```

### MainActivity WebView

位置：`android/src/com/mineradio/android/MainActivity.java`

```java
localServer = new LocalHttpServer(this);
localServer.start();

webView = new WebView(this);
webView.setBackgroundColor(Color.BLACK);
webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
setContentView(webView, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT));

WebSettings settings = webView.getSettings();
settings.setJavaScriptEnabled(true);
settings.setDomStorageEnabled(true);
settings.setMediaPlaybackRequiresUserGesture(false);
settings.setUseWideViewPort(true);
settings.setTextZoom(100);
settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

webView.addJavascriptInterface(new AndroidBridge(), "MineradioAndroidBridge");
webView.loadUrl(localServer.baseUrl());
```

页面加载完成后，`MainActivity` 注入 Android runtime，并加载触控适配资源：

```java
view.evaluateJavascript(
    "(function(){"
        + "document.documentElement.classList.add('android-webview-root');"
        + "if(document.body)document.body.classList.add('android-webview');"
        + "window.MineradioAndroid={isAndroid:true,openQQMusicLogin:function(){"
        + "return window.desktopWindow&&window.desktopWindow.openQQMusicLogin"
        + "?window.desktopWindow.openQQMusicLogin():Promise.reject(new Error('Android QQ login bridge unavailable'));}};"
        + "window.__MineradioAndroidLoginCallbacks=window.__MineradioAndroidLoginCallbacks||{};"
        + "window.desktopWindow=window.desktopWindow||{};"
        + "window.desktopWindow.isDesktop=true;"
        + "window.desktopWindow.openNeteaseMusicLogin=function(){return new Promise(function(resolve){"
        + "var id='ne_'+Date.now()+'_'+Math.random().toString(36).slice(2);"
        + "window.__MineradioAndroidLoginCallbacks[id]=resolve;"
        + "MineradioAndroidBridge.openNeteaseMusicLogin(id);});};"
        + "window.desktopWindow.openQQMusicLogin=function(){return new Promise(function(resolve){"
        + "var id='qq_'+Date.now()+'_'+Math.random().toString(36).slice(2);"
        + "window.__MineradioAndroidLoginCallbacks[id]=resolve;"
        + "MineradioAndroidBridge.openQQMusicLogin(id);});};"
        + "if(!document.getElementById('android-touch-adapter-script')){"
        + "var s=document.createElement('script');"
        + "s.id='android-touch-adapter-script';"
        + "s.src='/android-touch-adapter.js?v=5';"
        + "(document.head||document.documentElement).appendChild(s);"
        + "}"
        + "if(!document.getElementById('android-media-session-script')){"
        + "var ms=document.createElement('script');"
        + "ms.id='android-media-session-script';"
        + "ms.src='/android-media-session.js?v=1';"
        + "ms.defer=true;"
        + "(document.head||document.documentElement).appendChild(ms);"
        + "}"
        + "})();",
    null);
```

Back 键适配：优先关闭 Web 弹窗或搜索结果，没有可处理内容才退出 Activity。

```java
webView.evaluateJavascript(js, new ValueCallback<String>() {
    @Override
    public void onReceiveValue(String value) {
        if ("true".equals(value)) return;
        if (webView != null && webView.canGoBack()) webView.goBack();
        else MainActivity.super.onBackPressed();
    }
});
```

### assets/www 放置方式

APK 内资源路径：

```text
android/assets/www/index.html
android/assets/www/desktop-lyrics.html
android/assets/www/wallpaper.html
android/assets/www/default-user-fx-archive.json
android/assets/www/android-touch-adapter.js
android/assets/www/android-media-session.js
android/assets/www/vendor/three.r128.min.js
android/assets/www/vendor/gsap.min.js
android/assets/www/vendor/music-tempo.min.js
android/assets/www/assets/skull-decimation-points.bin
```

`android/build_apk.ps1` 每次构建都会用非破坏性覆盖复制把 `public/*` 同步进 `android/assets/www/`，保留 `android-touch-adapter.js`、`android-media-session.js` 等 Android 专用文件，避免前端视觉/触控修复后 APK 仍打进旧资源。

运行时访问方式：

```text
http://127.0.0.1:<dynamic-port>/
http://127.0.0.1:<dynamic-port>/vendor/three.r128.min.js
http://127.0.0.1:<dynamic-port>/api/search?keywords=...
```

## 5. 关键适配点

### 粒子动画性能

- WebView 强制硬件加速：`android:hardwareAccelerated="true"` + `View.LAYER_TYPE_HARDWARE`。
- 保留原项目的 WebGL/Three.js 路径，不把粒子系统重写为原生 OpenGL，避免视觉偏差。
- 后续建议在 Android WebView 上加设备分级：低端机限制 DPR、粒子数量、后处理强度和离屏 canvas 分辨率。

### 触摸事件

- 原 Web 端大量以 pointer/mouse/hover 交互为主，Android 端保留原逻辑，并额外注入触控增强层。
- 右侧固定触控工具条：搜索、DIY、FX、歌单、3D 架、播放控制条、导入音乐/封面、导入背景媒体、收起浮层。
- 边缘手势：左滑出歌单、右滑出 FX、底部上滑或轻点显示播放控制条、顶部下滑显示搜索。
- DIY 面板触控目标放大：FX 页签、按钮、开关、滑条、颜色选择、用户存档、歌单卡片和队列行设置 38-52px 级别最小触控高度。
- 文件导入仍走 Web `<input type=file>`，Android 原生由 `onShowFileChooser` 接管，覆盖本地音乐/封面和背景图片/视频。
- 3D 歌单架入口会切到 `stage`，并收起搜索、FX 和歌单浮层，避免遮挡触控区域。

### 音频播放兼容

- WebView 设置 `setMediaPlaybackRequiresUserGesture(false)`。
- `/api/song/url` 返回可播放 URL，`/api/audio` 负责代理并透传 Range，避免 CORS 和跨域音频分析问题。
- `/api/qq/search` 会用 QQ song detail 补齐 `file.media_mid`；`/api/qq/song/url` 使用真实 `mediaMid` 生成 `M500/M800/F000/RS01/C400` 候选文件，并在返回前用 Range 探测确认 URL 可读，避免把源站 404 JSON 当音频交给 WebView。
- `/api/audio` 会按上游域名设置 QQ/网易云 Referer 和 Cookie；上游 403/404 不再强制标成 `audio/*`，让前端能进入降级/换源逻辑。
- 已验证 Android WebView 中 `<audio>` 为 `paused=false`，`currentTime` 持续前进；AudioFocus 由 Chromium/WebView 的媒体栈持有，MainActivity 不再重复申请，避免抢掉 WebView 自身播放焦点。
- Android 原生 `MediaSession + ForegroundService` 已接入：前端通过 `android-media-session.js` 上报曲名、歌手、进度和播放态；通知栏上一首/播放暂停/下一首动作经 JS bridge 回调 `prevTrack/togglePlay/nextTrack`；播放中进入后台时 MainActivity 不再主动 `webView.onPause()`。
- Android 13+ 通知权限会在主页面加载后延迟请求一次，并写入 `SharedPreferences` 防止用户拒绝后每次打开重复弹窗；播放态更新不会同步弹权限框，避免权限页面抢焦点导致音频暂停。

### QQ 登录适配

- 前端仍保留“扫码登录”和“手动导入”两个入口；平板点击“扫码登录”会调用 `window.desktopWindow.openQQMusicLogin()`。
- Android bridge 打开全屏横屏 `Dialog`，默认直接加载 QQ 官方 `ptlogin2` 扫码授权页，并注入 `pt.switchqr()` 强制显示“使用QQ手机版扫码授权登录”；登录凭证留在 App 内 WebView 的 `CookieManager`，避免 QQ App/系统浏览器完成登录后无法回传 Cookie。
- 顶部“网页登录”按钮可切到 Windows 桌面 UA 的原桌面入口 `https://y.qq.com/n/ryqq/profile`，用于调试 QQ 官方网页登录页；再次点击可回到扫码登录。
- QQ 登录 WebView 会拦截 `mqqapi://`、`intent://` 等非 HTTP scheme，不再默认拉起外部 QQ/浏览器，而是在当前 WebView 切回二维码登录。
- 原生登录 WebView 开启 JavaScript、DOM storage、第三方 Cookie，并通过 `CookieManager` 读取 `y.qq.com`、`qqmusic.qq.com`、`music.qq.com`、`graph.qq.com`、`ptlogin2.qq.com`、`qq.com` 等域 Cookie。
- 捕获到 `uin` 和 `qqmusic_key` / `qm_keyst` / `music_key` / `p_skey` / `wxskey` 等有效票据后，写入 `SharedPreferences`，前端再刷新 `/api/qq/login/status` 和 QQ 歌单；若只有基础登录票据，会自动预热 `https://y.qq.com/n/ryqq/player` 尝试补齐播放授权。
- 若网页登录不可用或票据不完整，用户仍可使用“手动导入”粘贴 `uin=...; qqmusic_key=...; qm_keyst=...`。

### 网易云登录适配

- Android 默认优先保留原仓库扫码流程，`window.MineradioAndroid.preferNeteaseQrLogin=true` 会阻止前端把网易云入口切到网页登录预览。
- `LocalHttpServer` 已迁移 `/api/login/qr/key`、`/api/login/qr/create`、`/api/login/qr/check`：本地 Java 实现网易云 `weapi` AES/RSA 加密，调用 `login/qrcode/unikey` 和 `login/qrcode/client/login`，确认后同时从 HTTP `Set-Cookie` 和 JSON body 的 `cookie/cookies` 字段捕获并保存 `MUSIC_U`。
- `/api/login/qr/create` 生成 `https://music.163.com/login?codekey=...` 并标记 `localQr=true`；WebView 通过 vendored `qrcode@1.5.4` 浏览器 bundle 本地生成 `data:image/png` 二维码，图片生成不依赖外部服务。
- Android bridge 仍保留 `openNeteaseMusicLogin()` 备用入口，可打开全屏横屏 `Dialog` 加载 `https://music.163.com/#/login` 并捕获官方 Web Cookie。
- 手动 Cookie 导入路径仍保留，缺少 `MUSIC_U` 会返回 `INVALID_NETEASE_COOKIE`。

### 平台 API 兼容层

位置：`android/src/com/mineradio/android/LocalHttpServer.java`

当前覆盖：

- 静态资源：`/`, `/vendor/*`, `/assets/*`
- 网易云：`/api/search`, `/api/song/url`, `/api/lyric`
- QQ 音乐：`/api/qq/search`, `/api/qq/song/url`, `/api/qq/lyric`, `/api/qq/user/playlists`, `/api/qq/playlist/tracks`
- QQ 官方歌单：`/api/qq/official/playlists`，实现参考 GitHub `jsososo/QQMusicApi` 的 `recommend.js`，走 `playlist.PlayListPlazaServer/get_playlist_by_category`
- 天气电台：`/api/weather/ip-location`, `/api/weather/radio`
- 音频/封面代理：`/api/audio`, `/api/cover`
- Android guest / Cookie 登录态：`/api/login/status`, `/api/login/cookie`, `/api/logout`, `/api/qq/login/status`, `/api/qq/login/cookie`, `/api/qq/logout`
- 双平台 starter 歌单和真实账号歌单读取：`/api/user/playlists`, `/api/qq/user/playlists`
- 歌单详情：`/api/playlist/tracks`, `/api/qq/playlist/tracks`；QQ 登录后调用 `fcg_ucc_getcdinfo_byids_cp`
- 本地歌单写入：`/api/playlist/create`, `/api/playlist/add-song`
- 本地红心状态：`/api/song/like`, `/api/song/like/check`
- 评论详情：`/api/song/comments`, `/api/qq/song/comments`
- 歌手详情：`/api/artist/detail`, `/api/qq/artist/detail`
- 播客：`/api/podcast/hot`, `/api/podcast/search`, `/api/podcast/detail`, `/api/podcast/programs`, `/api/podcast/my`, `/api/podcast/my/items`, `/api/podcast/dj-beatmap`
- 网易云扫码登录：`/api/login/qr/key`, `/api/login/qr/create`, `/api/login/qr/check`
- 更新通道：Android 端已移除 `/api/update/*` 占位接口；当前版本只手动分发时间戳 APK。
- 状态持久化：网易云/QQ Cookie、本地歌单、本地红心状态落到 app 私有 `SharedPreferences`

## 6. 测试方案

模拟器：

1. 显性启动 AVD，禁止使用 `-no-window`。
2. `adb install -r android/apk-builds/Mineradio-android-20260705-112626-debug.apk`
3. `adb shell am start -W -n com.mineradio.android/.MainActivity`
4. `adb shell dumpsys window` 确认 `land`、`ROTATION_90`、当前焦点为 `MainActivity`。
5. `adb logcat` 确认无 `AndroidRuntime` / `FATAL EXCEPTION`。
6. 用真实 `adb shell input tap` 点击右侧工具条，验证 DIY、FX、歌单、3D 架、底栏触控路径。
7. 用 WebView CDP 或 UI 搜索歌曲，验证 `<audio>` 状态、播放进度和截图。
8. 模拟器/真机运行前先执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\android\verify_android_port.ps1
```

该脚本不替代运行态验证，只作为安装前门禁，覆盖 APK 签名、横屏声明、WebView 配置、包内资源、QQ 官方歌单接口、歌词舞台关键修复和 JS 语法。

真机：

1. Android 12+ 开启 USB 调试。
2. 安装 APK 后直接横屏启动。
3. 验证启动页、粒子舞台、搜索、播放、歌词/播放器控制。
4. 用 `adb shell dumpsys media_session` 和 `adb logcat` 检查音频焦点、网络错误、WebView 崩溃。
5. 重点测试不同 GPU/WebView 版本下的 WebGL 黑屏、内存压力和后台播放。

## 7. 本次验证结果

- 2026-07-05 默认值与触控修复构建：`android/build_apk.ps1` 成功，APK 通过 v1/v2/v3 签名验证。
- APK：`android/apk-builds/Mineradio-android-20260705-121614-debug.apk`
- SHA256：`8F27F678A97378CC08EC053B5FD0DF0AE7D6ADD5B3D63D86965009EE6F31F45D`
- APK 大小：`1644483` bytes
- 本地静态门禁：`android/verify_android_port.ps1` 通过 68 项检查，包括 APK 签名、横屏声明、WebView 配置、包内 Web 资源、歌词舞台关键修复、QQ 登录/播放接口和 JS 语法。
- 本轮同步内容：`public -> android/assets/www` 已同步；默认歌词行数 `15`、歌词提供 `third`、歌词加载方式“优化（SDF/MSDF）”、换句时差 `-0.4` 秒；Android 触控 rail 的歌单/歌单架按钮支持二次点击关闭；Android 本地服务 `/api/update/*` stub 保持移除。

### 历史验证记录

- 构建：`android/build_apk.ps1` 成功，APK 通过 v1/v2/v3 签名验证。
- APK：`Mineradio-android-debug.apk`
- SHA256：`7F3CFD2D1A99A18C4204A12AD72282CEAED1F26C07051D5541ADED0DB2D5B4C1`
- APK 大小：`1607212` bytes
- 本地静态门禁：`android/verify_android_port.ps1` 通过 38 项检查，包括 APK 签名、包内 Web 资源、`android-touch-adapter.js?v=5`、`android-media-session.js?v=1`、`/api/qq/official/playlists`、`client_search_cp`、`get_playlist_by_category`、歌词暂停保持、歌词预热和 5/11 行切换。
- 2026-07-03 追加运行态验证：本机 Android Emulator `36.6.11` 在显性窗口启动阶段崩溃，ADB 未出现设备，APK 未进入安装/启动阶段。已尝试 `migration_test_api35`、`Medium_Phone_API_36.1` 和新建 `mineradio_runtime_api35`，并尝试 `host`、`swiftshader_indirect`、`software`、`swangle`、`-feature -Vulkan`、Qt 软件后端等可见启动参数。失败点均在 emulator/QEMU 图形初始化，日志包含 `Failed to find EmulatedEglImage`；`Medium_Phone_API_36.1` 另有 system image 缺 `kernel-ranchu` 问题。该问题属于本机模拟器工具链阻断，不是 Mineradio APK 崩溃。
- 历史显性启动模拟器验证：`migration_test_api35` 曾成功上线为 `emulator-5554`；`codex_root_api35` 和 `Medium_Phone_API_36.1` 曾因 SDK system image 缺 `kernel-ranchu` 无法启动。当前 2026-07-03 复测被 emulator 36.6.11 图形栈崩溃阻断，需修复/回退本机 emulator 后再继续运行态验证。
- Android 15 模拟器安装：成功。
- 冷启动：成功，无闪退。
- 横屏：`mDisplayRotation=ROTATION_90`，全局配置 `land`。
- WebGL/视觉：启动页和粒子主界面截图正常。
- Web 资源同步：`build_apk.ps1` 构建日志确认 `public -> android/assets/www` 同步；当前包内 `assets/www/index.html` 包含歌词预热、暂停保持、1/3/5/7/9/11/13/15 奇数行切换和 QQ 官方歌单逻辑，`android-touch-adapter.js` 保留。
- Android 触控层：`MineradioAndroidTouch` 注入成功，右侧工具条 9 个按钮可见。
- 登录入口：CDP 验证 `window.desktopWindow.openNeteaseMusicLogin`、`window.desktopWindow.openQQMusicLogin`、`window.MineradioAndroid.openNeteaseMusicLogin`、`window.MineradioAndroid.openQQMusicLogin` 均为 function。
- QQ 扫码保留：登录弹窗显示“扫码登录”“打开官方扫码窗口”“手动导入”，guest 状态不再误报为真实 QQ 会话。
- QQ 原生登录 Dialog：触发 `openQQWebLogin()` 后打开 Android 全屏横屏登录窗口，默认加载 `xui.ptlogin2.qq.com/cgi-bin/xlogin` QQ 扫码授权页；关闭后 Activity 保持前台。
- QQ 桌面 UA：顶部“网页登录”可切到 `Windows NT 10.0; Win64; x64` 桌面 Chrome UA 的 `https://y.qq.com/n/ryqq/profile`，不再使用手机/平板 UA 的 404 路径。
- QQ 外部唤起处理：`mqqapi://`、`intent://` 等外部 App scheme 已改为拦截并切回二维码，避免登录完成后跳系统浏览器导致 Mineradio 无 Cookie。
- QQ 默认二维码：CDP 验证登录弹窗首次打开即为 `ptlogin2`，`qrimg.src` 为 `ssl/ptqrshow`，页面文案为“使用QQ手机版扫码授权登录”，当前焦点仍在 `com.mineradio.android/.MainActivity`。
- QQ 登录闭环：真实 QQ 扫码后 `/api/qq/login/status` 返回 `loggedIn=true`、`playbackKeyReady=true`、`uin=2457640943`。
- QQ 搜索/官方歌单接口：`/api/qq/search` 当前优先走 `client_search_cp`，再降级 `DoSearchForQQMusicDesktop` 和 smartbox；`/api/qq/official/playlists` 返回官方歌单并可与个人 QQ 歌单合并展示。
- QQ 播放闭环：CDP 验证 `/api/qq/search` 返回“晴天”的真实 `mediaMid=003Qui1q2u1Zho`；`/api/qq/song/url` 返回 `M500003Qui1q2u1Zho.mp3`；`/api/audio` Range 返回 `206 audio/mpeg`、`Content-Range=bytes 0-4095/4317292`；WebView `Audio.play()` resolved，`currentTime` 前进到 `1.0657`，无 `audio.error`。
- QQ 歌词闭环：Android `/api/qq/lyric` 已迁移 `music.musichallSong.PlayLyricInfo` 和旧 `fcg_query_lyric_new` 双路径；CDP 用 `/api/qq/search?keywords=晴天` 的真实歌曲对象调用前端 `fetchLyric()`，`originalLyricsState.timingSource=lrc-line`、`lyricsLines=63`，前 5 行均非 fallback，不再落到歌名占位。
- 歌词舞台最新适配：舞台歌词保留 5/11 行切换；当前句按 QRC/YRC/LRC 词级时间或合成词级时间高亮；播放中切行只消费预热 mesh，缺失行进入预热队列，暂停时保留当前歌词画面不清空。
- Beatmap 磁盘缓存：Android `/api/beatmap/cache/status` 返回 `enabled=true, mode=disk`；CDP 验证 POST 写入 `codex:test:beatmap` 后 GET 命中 `hit=true`，返回 `duration=12`、`cameraBeats=1`。
- 网易云原扫码保留：Android 上 `preferNeteaseQrLogin=true`，登录弹窗保持 `qr-shell`，按钮为“刷新二维码”，不会因存在 `openNeteaseMusicLogin()` 自动切到网页登录预览。
- 网易云 QR 接口：CDP 验证 `/api/login/qr/key` 返回 `code=200` 且有 key；`/api/login/qr/create` 返回 `localQr=true` 和扫码 URL；WebView 本地生成 `data:image/png` 二维码；`/api/login/qr/check` 返回 `code=801` 等待扫码。
- 网易云扫码确认处理：`/api/login/qr/check` 已补齐从 JSON body `cookie/cookies` 读取登录凭证，避免确认扫码后只有 body cookie、没有 `Set-Cookie` 时前端仍显示未登录。
- 网易云备用网页登录 Dialog：`openNeteaseWebLogin()` 仍可打开 Android 全屏横屏登录窗口，加载网易云官方登录页；关闭后 Activity 保持前台。
- Android 横屏登录弹窗：截图验证标题、平台切换、二维码、状态和按钮完整显示，弹窗高度 `392/412 CSS px`，无顶部裁切。
- 启动引导：Android WebView 冷启动和进入主界面后均未自动弹登录引导/视觉引导；手动 `?` 引导入口仍保留。
- 返回键：无弹窗/搜索/输入焦点时按 Back 不退出应用，Activity 仍保持前台。
- DIY/FX：真实 `adb input tap` 开启 DIY 和 FX，FX 面板 5 个页签、34 个 range 滑条、132 个按钮/开关可触控。
- DIY/FX 精调：Android 触控层为 34 个 FX range 全部生成 `- / +` 精调按钮；可见交互目标扫描 `smallCount=0`；真实 ADB 点击 `-` 后 `fx-bgopacity` 从 `1` 变为 `0.99`。
- 歌单触控：真实点击打开左侧歌单/队列面板，starter 网易云/QQ 歌单显示正常。
- 3D 歌单架触控：真实点击切到 `shelfMode=stage`，`shelf.canInteract=true`，搜索/FX/歌单浮层自动收起。
- 播放底栏触控：真实点击“控”后 `bottom-bar` 为 `stage-mode visible`，`controlsVisible=true`。
- Android guest 登录：`loginStatus.loggedIn=true`，昵称 `Mineradio Android`。
- Cookie 登录路径：缺少 `MUSIC_U` 的网易云 Cookie 会返回 `INVALID_NETEASE_COOKIE`；带 `MUSIC_U` 的 Cookie 可保存到私有存储并在 `/api/login/status` 返回 `hasCookie=true`。
- 网易云真实歌单详情：`/api/playlist/tracks?id=3778678` 返回“热歌榜”和 200 首曲目。
- 网易云真实播放 URL：`/api/song/url?id=3357698666&quality=exhigh` 返回 `song/enhance/player/url` 的真实音频地址，`/api/audio` Range 探测返回 `206`。
- 网易云歌单云写入：Android 已按原仓库 `NeteaseCloudMusicApi@4.32.0` 的 `playlist_create`、`playlist_tracks`、`playlist_track_add` 路径迁移 `/api/playlist/create` 和 `/api/playlist/add-song`；有 `MUSIC_U` 时优先走 weapi 云端创建/添加并带 `__csrf`，无 Cookie 时保留本地歌单降级。本轮模拟器为 guest，已验证本地降级创建 `android-custom-2` 并添加 1 首；真实账号云端 mutation 未自动执行，避免未经确认修改用户网易云账号。
- 双平台歌单：3 个网易云 starter 歌单，2 个 QQ starter 歌单，2 个播客集合。
- 本地歌单写入：`/api/playlist/create` 创建 `android-custom-*`，`/api/playlist/add-song` 添加歌曲后 `/api/playlist/tracks` 可验证到曲目。
- 本地红心：`/api/song/like` 设置状态，`/api/song/like/check` 返回已红心 map。
- 本地持久化：创建 `android-custom-1` 并添加“稻香”后，强停重启 App，`/api/user/playlists` 仍返回该歌单，详情仍有 1 首，红心状态仍为 true。
- 播客：`/api/podcast/hot` 返回 3 个 Android 预设电台，`/api/podcast/programs?id=android-weather&limit=3` 返回 3 个可播放节目。
- 评论：`/api/song/comments`, `/api/qq/song/comments` 返回详情页可渲染评论结构。
- 歌手详情：`/api/artist/detail?id=6452&limit=5` 返回周杰伦资料和 5 首热门曲；`/api/qq/artist/detail` 返回非崩溃占位。
- 登录兼容：`/api/login/qr/create`, `/api/login/qr/check`, `/api/login/cookie`, `/api/logout`, `/api/qq/login/cookie`, `/api/qq/logout` 均返回 200。`/api/update/*` 已在当前版本移除，不再作为 Android 兼容接口保留。
- 3D 歌单架：`shelf.mode=stage`，`shelf.canInteract=true`，已打开 starter 歌单详情。
- 歌单详情：网易云 starter 歌单 10 首，QQ starter 歌单 8 首。
- 音乐播放：starter 播放成功，队列 6 首，当前曲目“稻香”，`paused=false`，`currentTime=4.54`，`readyState=4`，音频走 `/api/audio?url=` 代理。
- Android MediaSession/通知：CDP 播放 starter “稻香”后，`dumpsys media_session` 显示 `com.mineradio.android/Mineradio` 为 `PLAYING`，metadata 为“稻香 / 周杰伦 / Android Starter”；`dumpsys notification` 显示 `mineradio_playback` transport 通知，含“上一首 / 暂停 / 下一首”3 个动作；`MineradioPlaybackService` 为 foreground service，type `mediaPlayback`。系统 `cmd media_session dispatch pause` 后 WebView `audio.paused=true`、MediaSession 切到 `PAUSED`、通知按钮变为“播放”；再 dispatch play 后 `audio.paused=false` 恢复播放。最终包验证时额外确认播放态不再弹权限框抢焦点，`audio.paused=false`、`currentTime=5.816599`，系统 pause/play 后仍恢复到 `audio.paused=false`、`currentTime=84.38712`。
- Back：登录弹窗打开时按 Back 仅关闭弹窗，Activity 保持前台。
- Back 最新回归：安装最终包后按 Back，`MainActivity` 仍为 `mCurrentFocus`，`SCREEN_ORIENTATION_LANDSCAPE` 和 `ROTATION_90` 保持。
- logcat：最近 800 行无 `AndroidRuntime` / `FATAL EXCEPTION` / ANR。
- WebView 性能警告：模拟器上出现 `tile memory limits exceeded`，需在后续做低端设备 DPR/粒子/后处理分级。

验证截图：

- `android/build/visible-emulator-home.png`
- `android/build/visible-emulator-main.png`
- `android/build/visible-emulator-playing.png`
- `android/build/visible-emulator-starter-shelf-playing.png`
- `android/build/visible-emulator-starter-shelf-detail.png`
- `android/build/visible-emulator-touch-rail.png`
- `android/build/visible-emulator-touch-diy-fx-open.png`
- `android/build/visible-emulator-touch-playlist.png`
- `android/build/visible-emulator-touch-shelf-clean-final.png`
- `android/build/visible-emulator-touch-controls.png`
- `android/build/visible-emulator-touch-playing.png`
- `android/build/mineradio_android_regression.png`
- `android/build/mineradio-qq-bridge-screen.png`
- `android/build/mineradio-qq-login-modal-fixed.png`
- `android/build/mineradio-native-qq-login-dialog.png`
- `android/build/mineradio-qq-desktop-auto-login.png`
- `android/build/mineradio-qq-scan-fallback.png`
- `android/build/mineradio-qq-default-qr.png`
- `android/build/mineradio-android-touch-fx-steppers-final.png`
- `android/build/mineradio-native-netease-login-desktop.png`
- `android/build/mineradio-final-netease-qr.png`
- `android/build/mineradio-local-qr.png`

## 8. 已知问题与解决方案

- QQ App 一键登录不能作为 Android WebView 闭环：实测登录完成会跳外部浏览器/QQ 音乐页面，Cookie 不会回到 Mineradio。因此 Android 默认改为 App 内 QQ 二维码扫码，外部 scheme 只做拦截回二维码；当前已用真实 QQ 账号验证扫码登录、Cookie 保存和标准音质播放闭环。
- 网易云原扫码协议已迁移并在模拟器验证到 `801` 等待扫码；尚未用真实账号完成手机确认后的 `803 + MUSIC_U` 闭环验证。当前已补齐 body cookie 读取，仍需要在平板或真机上扫码确认 cookie 捕获稳定性。
- 网易云 QR 图片已本地生成；后续仍需在真机账号上确认扫码后 `803 + MUSIC_U` 的闭环稳定性。
- 网易云官方网页登录窗口仅作为备用桥保留，模拟器中官方页面可能展示手机号/第三方登录组件，不保证直接展示二维码。
- QQ 云端歌单写入未完整迁移；网易云创建歌单和添加歌曲请求路径已按原仓库 weapi 迁移，但仍需在用户确认后用真实网易云账号做一次端到端 mutation 回归。QQ 搜索、官方歌单、个人歌单读取和数字歌单详情已接入真实 Web API，本地歌单写入已持久化；QQ 云写入仍需继续迁移。
- 3D 歌单架已能用触控工具条进入舞台模式；仍需在真实账号歌单下做长列表、多平台播放和不同 GPU/WebView 版本回归。
- WebView 视觉内存压力：模拟器 logcat 出现 `tile memory limits exceeded`。解决方案是低端设备限制 DPR、粒子数量、模糊层、后处理强度和大图纹理尺寸。
- Beatmap 磁盘缓存已迁移到 Android app-private storage，按 SHA-256 key 落盘并限制 200 条；后续可按真实设备容量增加 LRU 空间策略和清理 UI。
- 播客 DJ 后端长音频分析未原生化：`/api/podcast/dj-beatmap` 在 Android 返回前端 OfflineAudioContext 分析提示。当前预设节目较短，可走页面内离线锁拍；长播客应后续接 WorkManager 或原生音频分析。
- 后台媒体能力已具备 Android MediaSession/前台通知；通知权限请求已改为延迟一次性请求。仍需在真机锁屏、耳机按键和不同厂商后台限制下做长时间回归。
- 平台版权限制不可完全绕过：未登录外链、试听和 VIP 歌曲会受源站策略影响。解决方案是账号登录和多平台自动换源补齐后再扩大可播覆盖率。

## 9. 成功标准对照

- Android 12+ 编译安装：已构建 APK，并在 Android 15 模拟器安装成功。
- 默认横屏且不闪退：已验证。
- 歌词舞台、粒子视觉、3D 歌单架核心视觉：WebGL/粒子主视觉、播放歌词舞台、starter 3D 歌单架详情均已验证。
- 音乐播放：未登录搜索播放、starter 歌单自动播放、QQ 登录态标准音质播放均已验证。
- 视觉体验与桌面版基本一致：核心 Web 前端未重写，视觉路径保真；网易云原扫码流程已接回，网易云歌单云写入请求路径已迁移，Android MediaSession/前台媒体通知已接入，QQ 默认 App 内二维码和桌面网页登录切换路径已保留，QQ 搜索/官方歌单已按 GitHub 开源实现核对迁移，QQ 云写入和真机后台策略仍需第二阶段补齐。
