# 存片（CunPian）

一个零第三方依赖的 Android 本地解析下载小工具：粘贴抖音 / B站分享链接，解析出可下载项（视频 / 音频 / 图片），保存到 `Downloads/存片/`。

## 功能

- 抖音：视频短链、PC 链接、图文作品（图片 + 原声）
- B站：BV 号、b23 短链（360P 单文件 mp4）
- 每项独立下载，按钮上显示百分比进度，顶部有「已完成 X/Y · 进行中 Z · 失败 F」汇总
- 图片缩略图网格，点开全屏、左右滑动切换
- 「存文案到便签」：把作品文案通过系统分享交给便签 / 备忘录 App
- 纯 Java 实现，APK 约 90KB

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

## License

MIT
