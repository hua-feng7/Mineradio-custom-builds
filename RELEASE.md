# 发布流程

## 当前发布边界

- 当前版本：`v1.1.1` 手动分发版。
- 已移除软件内自动更新：不再提供更新按钮、`/api/update/*` 接口、快速补丁应用、自动打开安装包 IPC、Android 更新 stub 或 `mineradio.update` 配置。
- Windows 更新方式：重新构建并分发 `Mineradio-*-Setup.exe`。
- Android 更新方式：重新构建并分发 `android/apk-builds/` 下的时间戳 APK。
- 不上传、不依赖 `latest.yml`；不生成快速补丁 JSON；不让旧客户端通过软件内更新链路拉取新版。

## 本轮产物

- Windows 安装包：`windows-builds/20260706-000004/Mineradio-1.1.1-Setup.exe`
- Windows SHA256：`E4271AA72BD5C7A5134544DB088E87C9C54E2188C5D2D7878497A17278472796`
- Android APK：`android/apk-builds/Mineradio-android-20260705-235805-debug.apk`
- Android SHA256：`5085FDD894079183394E07D07AE246B41F33472891821CF698AEF05D8D8DCBF3`

本轮功能差异：默认 15 行歌词、歌词提供默认三方、歌词加载方式默认“优化（SDF/MSDF）”、换句时差默认 `-0.4` 秒；Android 触控 rail 的歌单和 3D 歌单架按钮支持二次点击关闭。

## 发布前检查

- 从当前源码构建，不复用旧 `dist/`、旧安装包、旧备份包或历史 packaged build；当前工作区可保留本地 `node_modules/` 作为后续打包依赖缓存，但不要提交。
- 确认 `.cookie`、`.qq-cookie`、`updates/`、`node_modules/`、`dist/` 不进入 git。
- 运行 `git diff --check`、`node --check server.js`、`node --check desktop/main.js`、`node --check desktop/preload.js` 和前端内联脚本解析。
- Android 运行 `powershell -ExecutionPolicy Bypass -File .\android\verify_android_port.ps1`。
- Windows 构建使用 `pnpm@11.7.0`；构建完成后复制安装包到 `windows-builds/<时间戳>/` 并写 `SHA256SUMS.txt`。
- Android 构建使用 `android/build_apk.ps1`；脚本会同步 `public/*` 到 `android/assets/www/`，并追加 `android/apk-builds/BUILDS.md`。

## 构建命令

```powershell
pnpm install --no-frozen-lockfile
pnpm run build:win
```

当前 Codex 环境没有全局 npm 时，可直接调用：

```powershell
.\node_modules\.bin\electron-builder.cmd --win nsis
```

Android：

```powershell
powershell -ExecutionPolicy Bypass -File .\android\build_apk.ps1 -Notes "本次构建说明"
```

## 分发说明

对外只分发完整 Windows 安装包和 Android APK。`.blockmap` 可作为构建旁路校验产物保存，但不是当前应用内更新通道的一部分。`latest.yml`、快速补丁 JSON、旧 `updates/` 缓存目录都不再参与当前版本发布。
