# 存片（CunPian）

一个零第三方依赖的 Android 本地解析下载小工具：粘贴抖音 / B站分享链接，解析出可下载项（视频 / 音频 / 图片），保存到 `Downloads/存片/`。

## 功能

- 抖音：视频短链、PC 链接、图文作品（图片 + 原声）
- B站：BV 号、b23 短链（单文件 mp4，自动取最高公开档）
- 每项独立下载，按钮上显示百分比进度，顶部有「已完成 X/Y · 进行中 Z · 失败 F」汇总
- 图片缩略图网格，点开全屏、左右滑动切换
- 「存文案到便签」：把作品文案通过系统分享交给便签 / 备忘录 App
- 跟随系统深浅色：日间浅色玻璃，夜间暗色
- 纯 Java 实现，APK 约 94KB

## 下载

到 [Releases 页面](https://github.com/eventide0/cunpian-videosaver/releases) 下载最新版本的 APK 安装即可。Android 8.0 以上。

当前版本：v0.5.3。也可直接下载 [apk/cunpian-v0.5.3.apk](apk/cunpian-v0.5.3.apk)。APK 为 debug 签名，仅供个人使用。

## 构建

需要 JDK、Android SDK platform 34 与 build-tools（aapt2 / d8 / zipalign / apksigner），并把 `adb` 加入 PATH。

```powershell
$env:ANDROID_SDK_ROOT = "你的 Android SDK 路径"
powershell -File app/build.ps1 -Install
```

`-Install` 表示装到当前连接的设备。签名密钥缺失时，构建脚本会自动用 `keytool` 生成 debug keystore。

## 用法

1. 在 App 里粘贴分享链接点「解 析」，或直接把链接分享给本 App
2. 点每项右侧「下载」：视频 / 音频 / 图片写入 `Downloads/存片/`，按钮上显示进度
3. 图片项点缩略图看大图，左右滑动切换
4. 「存文案到便签」把文案发给系统便签 App
5. 「清空」重置输入框、结果与进度计数

## 目录

| 目录 | 内容 |
|---|---|
| `app/` | 应用源码、资源与无 Gradle 构建脚本 |

## 声明

- 本项目仅用于个人学习与技术研究。使用前请确认你有权访问相关内容，并遵守目标平台的服务条款及所在地法律法规。
- 解析与下载均在本机完成，项目不收集或上传任何用户数据。
- 平台接口可能随时变动，本项目不保证功能长期可用；使用后果由使用者自行承担。
- 本项目与抖音、哔哩哔哩等平台无关联，未包含其任何代码或资源。

## License

MIT，详见 [LICENSE](LICENSE)。
