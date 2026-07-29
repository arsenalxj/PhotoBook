# PhotoBook

PhotoBook 是一个纯客户端 Android Instagram 帖子归档 App。用户把公开帖子分享到 PhotoBook 后，App 在手机内通过 Chaquopy 运行 Instaloader，并由 Android 前台服务完成媒体下载和本地保存。

核心特性：

- 不需要服务器、Worker、D1、PhotoBook 账号或设备配对；Instagram 登录按需启用。
- App 进入后台或锁屏后继续当前下载，完成后自动停止前台服务。
- 默认完全本地使用。
- 用户可选配置私有 Cloudflare R2；配置同一资料库的设备自动同步帖子信息和媒体。
- 网络统一使用 Android 系统网络和 VPN，不控制 Mihomo。

项目结构：

```text
client/   Flutter Android App、原生前台服务和 Chaquopy Python 桥
docs/     中文架构、桥接协议、构建与运维文档
```

详细设计见 [纯客户端架构方案](docs/architecture-plan.md)。

开发验证：

```bash
cd client
flutter analyze
flutter test
flutter build apk --debug
```

Release APK 使用仓库外的 PhotoBook 专用正式证书签名：

```bash
cd client
PHOTOBOOK_SIGNING_PROPERTIES=/absolute/path/to/key.properties \
  flutter build apk --release
```

`PHOTOBOOK_SIGNING_PROPERTIES` 必须指向本机 `key.properties` 的绝对路径。签名配置和 Keystore 禁止复制或提交到 PhotoBook 仓库。

正式版本通过 `v<versionName>+<versionCode>` 标签发布到 GitHub Release。App 直接读取公开的 `photobook-update.json` 检查更新，用户确认后才下载并交给 Android 系统安装器。

当前版本只支持公开内容。每个帖子先匿名抓取，只有 Instagram 明确要求登录时才使用用户在官方 WebView 中建立并经 Keystore 加密的本机会话重试一次；私密帖子仍不在支持范围内。
