# PhotoBook Android 客户端

这是 PhotoBook 唯一的运行时应用，负责：

- 接收 Instagram `ACTION_SEND text/plain` 分享。
- 通过 Android 前台服务和 Chaquopy/Instaloader 在手机内解析并下载公开帖子。
- 用 SQLite 保存帖子、媒体清单、任务和同步状态，用 App 沙盒保存媒体文件。
- 默认完全本地运行；用户配置 R2 后才启用多设备同步。
- 用 Android Keystore 加密保存用户填写的 R2 凭证。
- 展示本地帖子、任务进度、失败重试、详情和 R2 设置。
- 从 Public GitHub Release 检查、校验并安装正式更新。

开发验证：

```bash
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
```

当前只支持 Android 和 Instagram 匿名公开内容。架构、同步协议和真机验收项见根目录 `docs/`。
