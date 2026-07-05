# Mineradio Next Chat Handoff

更新时间：2026-07-05

## 新对话先确认

```powershell
cd "D:\Default path\zhuomian\codex\Mineradio"
git status --short --branch
Get-Content docs\PROJECT_MEMORY.md -TotalCount 80
Get-Content RELEASE.md
Get-Content CHANGELOG.md -TotalCount 80
```

涉及 Android 移植、安装包、歌词舞台或发布时再读：

```powershell
Get-Content android\PORTING_PLAN.md
Get-Content android\apk-builds\BUILDS.md
Get-Content package.json
```

## 当前状态

- 当前工作区：`D:\Default path\zhuomian\codex\Mineradio`
- 当前版本：`v1.1.1`
- 当前发布策略：手动分发 Windows 安装包和 Android 时间戳 APK。
- 当前更新边界：应用内自动更新、`/api/update/*`、快速补丁、`latest.yml` 通道、自动打开安装包 IPC、Android 更新 stub 和 `package.json` 更新配置均已移除。
- 不再生成或发布快速补丁 JSON；不再让旧客户端通过软件内更新链路拉取新版。

## 本轮产物

- Windows 安装包：`windows-builds/20260706-000004/Mineradio-1.1.1-Setup.exe`
- Windows 安装包 SHA256：`E4271AA72BD5C7A5134544DB088E87C9C54E2188C5D2D7878497A17278472796`
- Windows blockmap：`windows-builds/20260706-000004/Mineradio-1.1.1-Setup.exe.blockmap`
- Android APK：`android/apk-builds/Mineradio-android-20260705-235805-debug.apk`
- Android APK SHA256：`5085FDD894079183394E07D07AE246B41F33472891821CF698AEF05D8D8DCBF3`

## 本轮重点改动

- 删除前端标题栏更新入口和更新弹窗。
- 删除后端 `/api/update/latest`、下载、下载状态、补丁应用和补丁状态接口。
- 删除桌面端更新安装包 IPC、重启更新 IPC 和更新目录环境变量。
- 删除 Android 本地服务 `/api/update/*` 兼容 stub，返回键逻辑不再检查 `update-modal`。
- 删除 `package.json` 的 GitHub publish/update 配置，改为 `packageManager: pnpm@11.7.0` 配合当前依赖安装方式。
- 安装器启动参数不再带 `--updated`。
- 默认歌词行数改为 15 行，歌词提供默认三方，歌词加载方式默认“优化（SDF/MSDF）”，换句时差默认 `-0.4` 秒。
- Android 触控 rail 的歌单和 3D 歌单架按钮支持二次点击关闭。
- 已同步歌词溢光羽化、暂停歌词景深慢速后退、歌手详情/搜索结果分页加载到 Android APK 与 Windows 安装包。
- Windows 端补齐歌手详情分页：`/api/artist/detail` 和 `/api/qq/artist/detail` 现在会按 `page/pageNo` 请求后续歌曲，不再点击“查看更多”重复第一页。
- 暂停歌词景深手感补调：暂停瞬间加入小幅起步位移，并提高进入暂停时的 Z 位移跟随；恢复播放速度保持原来的快速响应。
- 文档已改为当前手动分发版本：`README.md`、`CHANGELOG.md`、`RELEASE.md`、`docs/PROJECT_MEMORY.md`、`android/PORTING_PLAN.md`。

## 已知验证

- `node --check server.js`：通过。
- `node --check desktop/main.js`：通过。
- `node --check desktop/preload.js`：通过。
- `node --check public/lyric-render-worker.js`：通过。
- `public/index.html` 内联脚本解析：通过。
- `git diff --check`：通过，只有 `build/installer.nsh` 的 CRLF 提示。
- Android：`android/verify_android_port.ps1` 通过 89 项检查。
- Windows：`electron-builder --win nsis` 成功，产物已复制到 `windows-builds/20260706-000004`。
- 本地 `node_modules/` 已保留作为后续打包依赖缓存；`dist/` 保留本次 electron-builder 输出，正式分发包已复制到 `windows-builds/20260706-000004`。

## 构建命令

```powershell
node --check server.js
node --check desktop\main.js
node --check desktop\preload.js
node --check public\lyric-render-worker.js
powershell -ExecutionPolicy Bypass -File .\android\build_apk.ps1 -BuildNote "说明"
powershell -ExecutionPolicy Bypass -File .\android\verify_android_port.ps1
pnpm install --no-frozen-lockfile --ignore-scripts=false --lockfile=false
.\node_modules\.bin\electron-builder.cmd --win nsis
```

## 不要做

- 不要再恢复软件内自动更新、`latest.yml` 或快速补丁 JSON。
- 不要每次复制完整工作区；在当前工作区内增量构建即可。
- 不要覆盖 `android/apk-builds` 或 `windows-builds` 里的历史产物；新增包按时间目录或时间文件名保存。
- 不要用 `git reset --hard` 或 `git checkout --` 回滚用户改动。
