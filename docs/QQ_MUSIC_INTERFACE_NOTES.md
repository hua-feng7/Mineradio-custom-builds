# QQ 音乐接口排障记录

更新日期：2026-06-21

## 触发症状

- 登录 QQ 后顶部能显示 QQ 账号状态，但很快又显示未登录，或者只有账号状态没有 QQ 头像和昵称。
- QQ 歌单能读取，但点击 QQ 音乐正式歌曲听不了。
- `/api/qq/song/url` 对部分片段/非正式曲目可返回 URL，但正式曲目常返回 `104003`。

## 已确认根因

- `p_skey` 只能证明网页 QQ 账号态，不等于 QQ 音乐播放授权。
- 真正更接近播放授权的 cookie 字段是 `qm_keyst`、`qqmusic_key`、`music_key`，微信通道可看 `wxskey`。
- 只有 `p_skey` 时，资料、歌单可能可用，但正式歌曲 vkey 仍可能不给 `purl`。
- QQ 资料接口 `fcg_get_profile_homepage.fcg` 可能返回 `code:1000`，此时不能直接把账号判成未登录，应从 cookie 的 `ptnick_*` 和 `qlogo.cn` 兜底昵称/头像。

## 当前修复点

- `server.js`
  - `qqCookieNickname()` 从 `ptnick_*` 等 cookie 兜底 QQ 昵称。
  - `qqCookieAvatar()` 用 `https://q1.qlogo.cn/g?b=qq&nk=<uin>&s=100` 兜底头像。
  - `qqCookiePlaybackKey()` 单独判断播放票据，不再把 `p_skey` 当成完整播放授权。
  - `/api/qq/login/status` 返回 `playbackKeyReady`。
  - `/api/qq/song/url` 同时尝试 `mediaMid` 和 `songmid` 生成文件名候选。
  - 缺播放票据且 vkey 返回 `104003` 时归类为 `login_required`，提示重新授权，而不是误判为普通版权失败。
- `desktop/main.js`
  - QQ 登录窗口不再一拿到 `p_skey` 就自动关闭。
  - 登录后会继续跳到 QQ 音乐播放器页 warmup，等待 `qm_keyst`、`qqmusic_key`、`music_key` 或 `wxskey`。
  - 如果用户手动关窗但只有网页态，返回 `partial: true`。
- `public/index.html`
  - QQ 登录状态只有在 `profileUnavailable` 且没有昵称/头像时才标记 stale。
  - QQ 播放失败先降音质重试，再自动查找网易云同名同歌手版本换源。
  - 登录成功提示会区分完整播放授权和账号态同步。

## 2026-07-03 Android 搜索与官方歌单补充

依据 GitHub `jsososo/QQMusicApi` 当前实现核对：

- 搜索：`routes/search.js` 默认歌曲搜索使用 `https://c.y.qq.com/soso/fcgi-bin/client_search_cp`，参数包含 `format=json`、`n`、`p`、`w`、`cr=1`、`g_tk=5381`、`t=0`，Referer 为 `https://y.qq.com`。
- 搜索降级：保留 `music.search.SearchCgiService/DoSearchForQQMusicDesktop` 和 `smartbox_new.fcg`，用于 `client_search_cp` 返回空或异常时兜底。
- 官方歌单：`routes/recommend.js` 的 `/playlist` 使用 `http://u.y.qq.com/cgi-bin/musicu.fcg`，模块 `playlist.PlayListPlazaServer`，方法 `get_playlist_by_category`，默认分类 `id=3317` 表示官方歌单。
- Android `LocalHttpServer` 和桌面 `server.js` 均按上述路径实现 `/api/qq/search` 与 `/api/qq/official/playlists`，避免凭空猜接口。

## 2026-07-04 Android 搜索参数补齐与归档构建

- 继续按 GitHub `jsososo/QQMusicApi` 的 `routes/search.js` 和 `routes/recommend.js` 核对接口；同时参考 Rain120/qq-music-api issue 中记录的完整 `client_search_cp` 请求参数。
- `/api/qq/search` 的 `client_search_cp` 参数补齐为歌曲搜索专用：`remoteplace=txt.yqq.song`、`catZhida=1`、`ct=24`、`qqmusic_ver=1298`、`aggr=1`、`lossless=0`、`flag_qc=0`、`platform=yqq.json`，并保留 `DoSearchForQQMusicDesktop` 与 `smartbox_new.fcg` 兜底。
- 桌面 `server.js` 和 Android `LocalHttpServer` 搜索返回仍保留 `songs`，新增 `total`、`pageNo`、`pageSize`、`source`，方便后续做分页/加载更多。
- 2026-07-04 本机实测：`client_search_cp` 搜索“白色风车”返回 `30/600`；`PlayListPlazaServer.get_playlist_by_category` 分类 `3317` 返回 `24/736`。
- APK 构建产物从本日起保存到 `android/apk-builds/`，文件名带时间戳，并由 `android/apk-builds/BUILDS.md` 记录每个版本说明。

## 2026-07-04 LyricProvider QQ 逐字歌词补充

- 参考 `tomakino/LyricProvider` 的 QQ 实现，新增“歌词提供（原版-三方）”开关；默认仍走原版歌词接口，切到“三方”时才启用 LyricProvider 风格的 QRC 链路。
- 三方链路：用 `music.search.SearchCgiService/DoSearchForQQMusicDesktop` 按歌名、歌手、专辑搜索 QQ 数字 `song id`，再 POST `https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg`，参数 `version=15&miniversion=100&lrctype=4&musicid=<id>`。
- QRC 内容从返回 XML 的 `content` / `contentts` / `contentroma` CDATA 取出；注意它不是标准 `DESede/ECB/NoPadding`，而是 `qrc-decoder` / LyricProvider 同源的非标准 QRC DES。服务端和 Android 本地服务返回 `qrcEncrypted`，前端 `public/vendor/qrc-codec-browser.js` 解密后用 `DecompressionStream('deflate')` 解压。
- 前端按 QRC XML 中 `LyricContent` 的 `[lineStart,lineDur]文字(wordStart,wordDur)` 格式解析为现有逐字高亮结构。
- 桌面 `server.js` 和 Android `LocalHttpServer` 均实现同一套 `/api/qq/lyric?provider=third`，WebView 不直接跨域请求 QQ。

## 后续同类问题优先检查

1. 先看 `C:\Users\Administrator\AppData\Roaming\Mineradio\.qq-cookie` 是否有 `qm_keyst`、`qqmusic_key`、`music_key` 或 `wxskey`。
2. 调 `/api/qq/login/status`，确认 `loggedIn`、昵称、头像和 `playbackKeyReady`。
3. 调 `/api/qq/song/url?mid=<songmid>&mediaMid=<media_mid>&quality=highest`，检查 `reason`、`qqCode`、`playbackKeyReady`。
4. 如果 `playbackKeyReady=false` 且 `qqCode=104003`，优先重新跑 QQ 官方登录窗口，不要先改搜索或播放器 audio 逻辑。
5. 如果 `playbackKeyReady=true` 仍大量 `104003`，再判断版权、会员、地区、官方客户端限制或换源策略。
