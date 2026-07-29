# PhotoBook 构建、发布与安装

PhotoBook 纯客户端版本只交付 Android APK，不部署 Worker、D1、下载服务器或 Docker。

Android 包名、applicationId 与 namespace 统一为 `com.mantou.photobook`。
Android 会把旧包名与当前包名视为两个独立 App，旧包名沙盒中的 SQLite、媒体和 Keystore 配置不会自动迁入；需要跨安装恢复时应配置同一个 R2 资料库。

项目处于开发阶段，不提供旧 ArMedia 本机数据库或 R2 格式迁移。新安装默认使用 `photobook` prefix，不自动读取或删除旧 `armedia` prefix。

## 1. 构建环境

- Flutter 与项目当前锁定版本兼容。
- JDK 17。
- Android SDK 与项目 `compileSdk`。
- Python 3.13，用于 Chaquopy 构建和生成固定 wheel。

Chaquopy 17 要求 Android `minSdk >= 24`。正式 Release 只包含 `arm64-v8a`，本地默认同时保留 `arm64-v8a` 和 `x86_64`。可通过 `PHOTOBOOK_TARGET_ABIS` 指定逗号分隔的受支持 ABI，禁止使用 `--split-per-abi`。

MinIO 9.0.3 默认依赖的 `simple-xml-safe` 需要 Android 不提供的 StAX，客户端必须改用支持 XmlPullParser fallback 的 `simple-xml` 2.7.1。正式构建还必须保留 MinIO Args、XML 响应模型、泛型签名和完整 Simple XML 反射运行时；调整 R8 规则后必须重新安装 release APK，并通过 R2 的 LIST、PUT、GET、DELETE 真机探针。

## 2. 固定 Instaloader

客户端必须使用项目记录的 fork 提交：

```text
https://github.com/hyperplural/instaloader/commit/b1d233362e335cbbccba5c5e4b614a1032764118
```

构建流程先生成纯 Python wheel，放入 `client/android/python/wheels/`，再由 Chaquopy 打包。不得在发布构建时动态拉取未固定版本，也不得无说明切回 PyPI 官方版本。

wheel 更新时必须同时更新来源提交、SHA-256、Python 桥 fixture 测试和 Android debug APK 验证。

## 3. 本地验证

```bash
cd client
flutter pub get
flutter analyze
flutter test
PYTHONPATH=android/app/src/main/python \
  uv run --python 3.13 --with pytest \
  --with ./android/python/wheels/instaloader-4.15.2-py3-none-any.whl \
  --with requests==2.34.2 \
  --with charset-normalizer==3.4.9 \
  --with idna==3.18 \
  --with urllib3==2.7.0 \
  --with certifi==2026.7.22 \
  pytest android/app/src/test/python/test_photobook_bridge.py -q
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug
cd ..
flutter build apk --debug
```

正式手机包通过 `PHOTOBOOK_TARGET_ABIS=arm64-v8a flutter build apk --release` 构建。发布前必须用 Android build-tools 校验包名、版本、ABI 和签名证书，不能重新带入不完整的 `armeabi-v7a`。

2026-07-28 本机正式 arm64 APK 验证结果：`com.mantou.photobook`、`1.1.0 (2)`、39,967,971 字节，SHA-256 为 `8fe8a04942ede926742a8741015775b0730c5a8f600d1c9e64d56eb587585a39`。GitHub Actions 产物必须重新计算自身大小和 SHA-256，不能复用本机值。

APK 构建通过不代表抓取可用。还必须在 arm64 真机验证：

- 分享图片帖、多图帖和 Reel。
- 验证未登录时匿名抓取；只有登录墙出现后才使用 Session 重试。
- 在官方 WebView 中完成普通登录和 2FA，杀进程重启后 Session 仍可使用；重新登录、取消和清除后状态正确。
- 登录成功或取消后，WebView Cookie、缓存和本地存储已清理；SQLite、R2、日志、通知和异常文本中没有 Cookie。
- 已登录账号可见的私密帖子仍返回不可访问。
- 分享后立刻回桌面、锁屏，再确认任务继续。
- 任务完成后前台服务通知消失。
- 断网后任务保持可恢复，网络恢复后能够重试。
- App 使用系统 VPN 时 Instagram 与 R2 请求确实经过该 VPN。

## 4. 用户配置 R2

R2 不是安装前提。用户需要多设备同步时，在 App 设置页填写：

- S3 endpoint，例如 `https://<account-id>.r2.cloudflarestorage.com`。
- bucket。
- 可选 prefix；默认使用 `photobook`。
- Access Key ID。
- Secret Access Key。

token 只授予目标 bucket 的 Object Read & Write 权限，不需要账户管理权限。建议 PhotoBook 使用独立 bucket；至少使用独立 prefix，禁止复用 Snapit 的业务目录。

保存前 App 会写入、读取并删除一个随机测试对象。任何一步失败都不替换当前可用配置。

## 5. 应用签名

- 本机签名目录为仓库外的 `MyKeys/PhotoBook/`，包含 `photobook-release.jks` 和权限为 `600` 的 `key.properties`。
- Gradle 优先通过 `PHOTOBOOK_SIGNING_PROPERTIES` 读取签名配置，本机开发也可在被忽略的 `client/android/local.properties` 中设置 `photobook.signingProperties`；两者都只允许指向仓库外的绝对路径。
- 有有效签名配置时，debug 和 release 使用同一正式证书，使自有测试设备可以覆盖安装并保留 App 数据；debug APK 可调试，禁止分发、上传 Release 或用于不受控设备。
- 未配置签名时普通 debug 仍使用 Android 默认调试证书；release 构建缺少有效配置时必须失败。
- GitHub Actions 使用 `KEYSTORE_BASE64`、`KEY_STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，不得输出 Secret。
- 首个 Release 发布后不得更换证书；证书 SHA-256 指纹为 `79294e51a747e96ffca87d31fc14daf25b75c2a0b3e9c73afb4d2522be2c0574`，由 CI 强制校验。

## 6. GitHub Release

- 版本以 `client/pubspec.yaml` 为准，`versionName` 使用三段数字，标签严格使用 `v<versionName>+<versionCode>`。
- 推送版本标签后，`.github/workflows/release.yml` 使用 Flutter 3.41.2、JDK 17 和 Python 3.13 构建 `arm64-v8a` APK。
- Release 先保持 Draft；APK、更新清单和全部校验完成后再公开，避免 `latest` 短暂指向不完整版本。
- 首个资产为 `photobook-v1.1.0+2-arm64-v8a.apk` 和 `photobook-update.json`。
- App 使用 `https://github.com/arsenalxj/PhotoBook/releases/latest/download/photobook-update.json` 检查更新，不依赖 Worker 或 R2。

## 7. 发布检查

- APK 中不得包含 `.env`、R2 Secret、Instagram Session 或测试凭证。
- release 和需要覆盖正式 App 的本机 debug 必须使用正式签名；默认 debug keystore 产物不得覆盖正式安装。
- 检查 Chaquopy 和 Instaloader 许可证随包分发要求。
- 记录 APK 体积、Python 冷启动时间和目标 Android 版本真机结果。
