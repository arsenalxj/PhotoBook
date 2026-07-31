# PhotoBook 项目协作约定

## 项目目标

PhotoBook 是个人使用的 Android 多平台帖子归档 App，首批支持 Instagram 和小红书公开帖子。App 接收系统分享链接，在手机内通过 Chaquopy 运行平台解析器，并由 Android 前台服务完成解析、下载、落库和可选 Cloudflare R2 同步。项目不依赖自建服务器、Worker、D1、PhotoBook 账号或设备配对；Instagram 登录是可选的本机会话能力，小红书只支持匿名公开内容。

## 目录约定

- `client/`：Flutter Android 客户端，也是唯一运行时应用。
- `client/android/app/src/main/kotlin/com/mantou/photobook/archive/`：分享接收、前台服务、任务执行、本地数据库和 R2 同步。
- `client/android/app/src/main/kotlin/com/mantou/photobook/update/`：GitHub Release APK 下载、校验和系统安装。
- `client/android/app/src/main/python/`：Chaquopy Python 桥，只承载 Instagram Instaloader 与小红书公开页解析。
- `client/android/python/wheels/`：固定版本的纯 Python wheel；来源和校验值必须记录。
- `client/lib/`：Flutter UI、页面状态和原生桥接。
- `client/tool/`：构建与发布使用的确定性元数据校验脚本，不承载运行时业务。
- `docs/`：中文架构、同步协议、构建配置和运维文档。
- `docs/prototype-shadcn/`：全部界面的 Shadcn 高保真 HTML 原型，是 UI 的唯一视觉权威。UI 工作以 `handoff.md` 的令牌映射和逐屏任务清单为准，颜色只允许来自令牌表。
- `.github/workflows/release.yml`：仅由版本标签触发的正式 APK 发布流程。

仓库只保留 Android 客户端源码和现行文档，不保留迁移前的服务端、Discord Bot、Docker、Mihomo 或兼容代码。

新目录和文件使用小写英文命名：目录用 `snake_case`，Markdown 文件用 `kebab-case.md`。SQL 表和字段使用 `snake_case`；Python 使用 `snake_case`；Kotlin、TypeScript 和 Dart 变量使用 `lowerCamelCase`。

## 数据归属

- 本机 SQLite 是本机帖子元数据、媒体清单、抓取任务、失败记录和同步状态的权威数据源。
- 帖子 ID 固定为 `<source_platform>:<source_post_id>`；`source_platform` 当前只允许 `instagram` 和 `xiaohongshu`。不同平台的原始帖子 ID 不共享命名空间。
- 媒体 ID 固定为 `<post_id>:<sort_index>`。`logical_index` 表示用户看到的逻辑媒体序号，`media_role` 只允许 `primary / live_still / live_motion`；Live Photo 的静态图和动态视频使用相同 `logical_index`。
- `media_type` 只允许 `image / video`；GIF 使用 `media_type=image + mime_type=image/gif`，不增加独立媒体类型。
- 本机媒体文件保存在 App 沙盒；SQLite 只保存路径、大小和 SHA-256，不保存二进制。
- R2 是用户可选配置的共享资料库。未配置 R2 时，本地归档功能必须完整可用。
- 配置相同规范化 `endpoint + bucket + prefix` 的设备视为同一资料库；Access Key 不参与资料库身份判断。
- 每个安装生成独立 `device_id`。同步操作使用每设备单调递增 `seq`，每个远端设备维护独立高水位。
- 媒体使用 SHA-256 内容寻址。帖子和单媒体删除通过本地墓碑及 R2 操作同步；删除只改变资料库可见状态，不物理删除 R2 内容寻址对象。
- 详情页保存和删除都以界面可见的逻辑媒体为选择单位并默认全选；Live Photo 静态图和动态视频视为一项。批量保存逐项执行并允许部分成功；批量删除必须原子提交，选择全部媒体时写 `delete_post`，选择部分媒体时为选中逻辑媒体的全部物理文件写 `delete_media`。

## 安全约定

- App 不内置 R2 或 Instagram 密钥。
- R2 Access Key ID 和 Secret 由用户在设置中填写，通过 Android Keystore 加密后保存。
- 日志、SQLite、通知、异常文本和同步操作中禁止出现 Secret 或 Instagram Cookie。
- R2 token 应只允许目标 bucket 的对象读写，不要求账户管理权限。
- Instagram 请求必须匿名优先；只有匿名请求明确返回 `LOGIN_REQUIRED` 时，才允许使用已验证的本机会话重试一次。
- Instagram 账号密码只填写在官方 WebView 页面，业务代码不得读取或保存；WebView Cookie 验证并加密保存后必须清理 WebView Cookie、缓存和本地存储。
- Instagram Session 使用独立 Android Keystore 密钥加密，只属于本机，不得进入 Flutter 状态、SQLite、R2、备份、日志、通知、异常文本或崩溃上报。
- App 不读取 Instagram App 或其他浏览器的数据，不提供手动 Cookie、Instaloader session 文件或账号密码导入。
- 正式签名文件只允许保存在仓库外的 `MyKeys/PhotoBook/` 或 GitHub Actions Secrets，禁止提交、打印或写入 Release。
- 本机 debug 为覆盖安装并复用正式 App 数据，可通过被忽略的 `client/android/local.properties` 使用正式证书；debug APK 只允许用于自有测试设备，禁止分发或上传 Release。
- 更新 APK 必须同时通过大小、SHA-256、包名、整数版本号和当前 App 签名证书校验后才能交给系统安装器。

## Android 运行约定

- Android `applicationId`、namespace 和 Kotlin 根包统一为 `com.mantou.photobook`；Flutter 原生通道使用同一前缀。
- `ACTION_SEND` 必须在原生 Activity 可见期间先持久化任务，再启动 `dataSync` 前台服务。
- 剪贴板只允许在 App 冷启动或重新进入前台后读取一次；系统分享启动时不得同时读取。自动模式只处理最近 10 分钟复制的白名单帖子链接，同一规范化链接自动导入一次，不保存或记录其他剪贴板内容；首页必须保留不受时间限制的手动粘贴入口。
- 前台服务必须立即显示通知；所有任务结束后移除通知并停止自身。
- Instaloader 同步调用只能在串行后台队列运行，禁止阻塞主线程。
- Instaloader 单次网络请求超时固定为 30 秒；运行中任务取消后进入 `cancelling`，解析或下载返回并完成文件回滚、临时目录清理后才进入 `failed + CANCELLED`。取消期间不得读取 Session、发起认证重试或保存刷新后的 Session。
- 匿名抓取成功时禁止读取或解密 Instagram Session；限流、断网、帖子不存在等非登录错误不得触发认证重试或改变 Session 状态。
- 登录态只提高公开帖抓取成功率。即使当前账号有权查看，私密账号帖子也必须返回不可访问，不得归档或同步。
- Chaquopy 只负责把 shortcode 解析成 JSON；媒体流式下载、哈希、缩略图和视频元数据使用 Android 原生 API。
- 本地归档和云同步是两条独立状态机。R2 失败不得回滚已经完成的本地归档。
- WorkManager 只作为进程或网络中断后的恢复保险，分享主流程仍直接启动前台服务。
- 抓取恢复与 R2 同步恢复必须使用两个独立的唯一 WorkManager 计划；取消抓取任务只重算抓取恢复，不得取消 R2 重试，也不得中断正在处理其他抓取任务的执行器。
- 恢复 Worker 正在执行时，新分享启动的前台服务必须等待执行权并继续处理，不能因竞争锁直接退出或把用户任务留给退避重试。
- App 使用 Android 默认网络；系统 VPN 是否接管流量由系统配置决定。不得加入 Mihomo、代理节点切换或静默直连逻辑。
- App 冷启动只检查一次 GitHub Release 更新；发现更新后必须先征得用户确认才下载，不提供静默安装。

## 同步约定

- 本地业务写入和同步 outbox 必须位于同一 SQLite 事务。
- 同一帖子的一次批量删除必须在一个 SQLite 事务内校验并提交，任何选中媒体无效或 outbox 写入失败都不得留下部分删除。
- 当前唯一同步格式只允许 `upsert_post`、`delete_post` 和 `delete_media`；收到其他操作必须拒绝且不得推进设备高水位。
- `upsert_post` 必须携带帖子 `sourcePlatform`，以及每个媒体的 `logicalIndex + mediaRole`。所有设备必须先升级到支持当前格式的版本，再向同一资料库写入新操作。
- 远端操作路径为 `<prefix>/devices/<device_id>/ops/<20 位 seq>.json`，不设置协议版本目录或版本字段。
- 操作顶层 `device_id + seq` 只表示传输顺序；帖子和媒体状态使用独立的 `entity_version(version + device_id + seq)` 确定新旧。墓碑必须能在业务行不存在时独立保存，旧 upsert 不得复活已删除实体。
- 远端逻辑版本必须处于可递增的正整数范围；接收和本地递增都必须显式防止 `Long` 溢出。
- 上传按 seq 串行，首个失败处停止；远端操作对象一经创建不得覆盖。
- 拉取只有在当前序号成功应用后才推进该设备高水位，发现缺口不得越过。
- 分批拉取、预览补齐或拉取错误必须返回明确的“仍需续跑”状态；前台服务和 WorkManager 在没有本地待上传操作时也必须继续调度。
- 已配置 R2 的 App 每次冷启动都发起同步。同步错误写入本地状态并展示给用户，不能只记录后静默结束。
- 首次配置 R2 时为现有本地帖子补写 outbox；更换 Access Key 不重置同步状态，更换 endpoint、bucket 或 prefix 才切换资料库。
- 切换资料库时必须为当前 active 帖子和 deleted 帖子/媒体墓碑生成新快照，并保留各实体原始 `entity_version`，不能把快照伪装成更新的业务写入。历史操作上传媒体时按该操作 payload 的 SHA-256 取文件。缺失的原媒体允许从上一个资料库恢复，迁移完成后再清除旧配置。
- 元数据、头像和缩略图随同步补齐；原图和原视频在详情页按需下载。
- 项目处于开发阶段，不实现本机数据库升级或旧 R2 数据兼容。结构或同步格式变化后，卸载 App 并清空对应 R2 prefix，从空数据重新开始。

## 运行时文件与清理

- 抓取临时文件放 App 沙盒 `archive/jobs/<job_id>/`。
- 更新临时文件放 App 缓存 `updates/`，下载写 `.part`，完成校验后原子改名；失败、取消和过期缓存必须清理。
- Live Photo 转 GIF 临时文件放 App 缓存 `share_exports/`；系统分享面板可能延迟读取，拉起后不得立即删除，只清理超过 24 小时的文件。
- 永久媒体分别放 `archive/avatars/`、`archive/thumbnails/` 和 `archive/originals/`。
- 下载先写 `.part`，完成大小与 SHA-256 校验后原子改名。
- 成功或最终失败都清理对应任务临时目录；启动时清理超过 24 小时的遗留 `.part` 和任务目录。
- 整帖提交前新发布的内容寻址文件必须被跟踪；准备或 SQLite 提交失败时，只删除尚未被帖子或同步操作引用的新文件。
- R2 原始媒体长期保存。删除帖子或媒体不得调用 R2 对象删除；本地文件也只能在当前业务状态和不可变同步操作均不再引用时清理。

## 开发流程

1. 架构或目录变化先更新 `docs/` 与本文件，再修改代码。
2. 先写失败测试或可复现用例，再修复行为。
3. 每次只改当前阶段涉及的模块，不顺手处理无关问题。
4. 不复用现有 Snapit bucket；PhotoBook 使用独立 bucket 或独立 prefix，默认 prefix 为 `photobook`。
5. 未经明确要求，不执行 `git add`、`git commit`、`git push` 或生产部署。

## 发布约定

- 正式版本号来自 `client/pubspec.yaml`；`versionName` 使用三段数字，标签必须严格为 `v<versionName>+<versionCode>`。
- GitHub Release 只发布 `arm64-v8a` APK 和 `photobook-update.json`；本地 debug 默认同时保留 `arm64-v8a`、`x86_64`。
- 更新清单固定发布到 `https://github.com/arsenalxj/PhotoBook/releases/latest/download/photobook-update.json`。
- Public `PhotoBook` 仓库只允许审计后的单一根提交起步；旧 ArMedia 提交、Issue、Actions 日志和归档文件只能留在私有归档仓库。
- 首个正式版本使用全新的 PhotoBook 证书；发布后所有更新必须沿用该证书。

## 验证基线

- `client/`：`flutter analyze`、`flutter test`、Android debug APK 构建。
- Python 桥：脱敏 fixture 单元测试，并验证固定 Instaloader wheel 可导入。
- Android：覆盖先持久化后启动、后台继续、进程恢复、任务结束自动停服和通知权限。
- 抓取：覆盖图片帖、多图帖、Reel、重复分享、失效链接、匿名优先、登录墙后 Session 重试、Session 失效、私密帖拒绝和系统 VPN。
- 登录：覆盖普通登录、2FA、取消、重新登录、WebView 数据清理、Keystore 持久化以及 Cookie 全链路脱敏。
- 同步：覆盖无 R2、本地成功但上传失败、首次 seed、多设备独立高水位、离线设备晚上传、序号缺口、重复操作、换 Key 和换资料库。
