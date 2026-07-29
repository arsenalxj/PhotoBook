# PhotoBook Android 客户端

这是 PhotoBook 唯一的运行时应用，负责：

- 接收 Instagram `ACTION_SEND text/plain` 分享。
- 通过 Android 前台服务和 Chaquopy/Instaloader 在手机内匿名优先解析并下载公开帖子，登录墙出现时可用本机会话重试。
- 用 SQLite 保存帖子、媒体清单、任务和同步状态，用 App 沙盒保存媒体文件。
- 默认完全本地运行；用户配置 R2 后才启用多设备同步。
- 用 Android Keystore 分别加密保存用户填写的 R2 凭证和可选 Instagram Session。
- 展示本地帖子、任务进度、失败重试、详情和 R2 设置。
- 从 Public GitHub Release 检查、校验并安装正式更新。

开发验证：

```bash
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
```

当前只支持 Android 和 Instagram 公开内容；可选登录只用于处理公开帖登录墙，不归档私密帖子。架构、同步协议和真机验收项见根目录 `docs/`。
