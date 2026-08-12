# PhotoBook 项目协作约定

## 项目目标

PhotoBook 是个人使用的 Android 多平台帖子归档 App，首批支持 Instagram 和小红书公开帖子。App 接收系统分享链接，在手机内通过 Chaquopy 运行平台解析器，并由 Android 前台服务完成解析、下载、落库和可选 Cloudflare R2 单向备份。项目不依赖自建服务器、Worker、D1、PhotoBook 账号或设备配对；Instagram 登录是可选的本机会话能力，小红书只支持匿名公开内容。

## 目录约定

- `client/`：Flutter Android 客户端，也是唯一运行时应用。
- `client/android/app/src/main/kotlin/com/mantou/photobook/archive/`：分享接收、前台服务、任务执行、本地数据库和 R2 备份。
- `client/android/app/src/main/kotlin/com/mantou/photobook/update/`：GitHub Release APK 下载、校验和系统安装。
- `client/android/app/src/main/python/`：Chaquopy Python 桥，只承载 Instagram Instaloader 与小红书公开页解析。
- `client/android/python/wheels/`：固定版本的纯 Python wheel；来源和校验值必须记录。
- `client/lib/`：Flutter UI、页面状态和原生桥接。
- `client/tool/`：构建与发布使用的确定性元数据校验脚本，不承载运行时业务。
- `docs/`：中文架构、备份协议、构建配置和运维文档。
- `docs/prototype-shadcn/`：全部界面的 Shadcn 高保真 HTML 原型，是 UI 的唯一视觉权威。UI 工作以 `handoff.md` 的令牌映射和逐屏任务清单为准，颜色只允许来自令牌表。
- `.github/workflows/release.yml`：仅由版本标签触发的正式 APK 发布流程。

仓库只保留 Android 客户端源码和现行文档，不保留迁移前的服务端、Discord Bot、Docker、Mihomo 或兼容代码。

新目录和文件使用小写英文命名：目录用 `snake_case`，Markdown 文件用 `kebab-case.md`。SQL 表和字段使用 `snake_case`；Python 使用 `snake_case`；Kotlin、TypeScript 和 Dart 变量使用 `lowerCamelCase`。

## 数据归属

- 本机 SQLite 是本机帖子元数据、媒体清单、抓取任务、失败记录和备份状态的唯一权威数据源；R2 数据不得反向创建或修改本机帖子。
- 帖子 ID 固定为 `<source_platform>:<source_post_id>`；`source_platform` 当前只允许 `instagram` 和 `xiaohongshu`。不同平台的原始帖子 ID 不共享命名空间。
- 媒体 ID 固定为 `<post_id>:<sort_index>`。`logical_index` 表示用户看到的逻辑媒体序号，`media_role` 只允许 `primary / live_still / live_motion`；Live Photo 的静态图和动态视频使用相同 `logical_index`。
- `media_type` 只允许 `image / video`；GIF 使用 `media_type=image + mime_type=image/gif`，不增加独立媒体类型。
- 本机媒体文件保存在 App 沙盒；SQLite 只保存路径、大小和 SHA-256，不保存二进制。
- R2 是用户可选配置的单向云备份目标。未配置 R2 时，本地归档功能必须完整可用；配置相同 R2 的其他设备也不得读取或合并本安装的数据。
- R2 配置分为“连接”和“备份位置”：连接由规范化 `endpoint + bucket` 标识并保存凭证；同一连接可保存多个由 `prefix` 区分的备份位置。规范化 `endpoint + bucket + prefix` 相同时视为同一备份目标，Access Key 不参与身份判断；重复连接或位置必须拒绝，不能静默覆盖。位置创建后只允许修改显示名称，prefix 变化必须新增位置并由用户明确删除旧位置。
- 每次安装生成独立 `device_id`，清除 App 数据或重装后生成新 ID 和新的 R2 目录；不发现其他设备，不维护 peer 或远端高水位。
- 帖子每次归档或重新归档时递增 `backup_generation`，但不自动创建备份任务。只有用户在详情页明确选择备份位置时，才在同一 SQLite 事务中固化当前 generation 的不可变快照并创建任务。删除帖子或媒体不递增 generation、不创建云端删除任务，已经手动进入队列的备份仍必须完成。
- 媒体使用 SHA-256 内容寻址，但对象必须位于当前安装的 `devices/<device_id>/media/` 下。App 不调用 R2 对象删除；本地删除不会改变任何已上传对象或快照。
- 详情页保存和删除都以界面可见的逻辑媒体为选择单位并默认全选；Live Photo 静态图和动态视频视为一项。批量保存逐项执行并允许部分成功；批量删除只在本机 SQLite 中原子提交，不写墓碑或 R2 操作。

## 安全约定

- App 不内置 R2 或 Instagram 密钥。
- R2 Access Key ID 和 Secret 由用户在设置中填写，通过 Android Keystore 加密后保存。
- 日志、SQLite、通知、异常文本和备份快照中禁止出现 Secret 或 Instagram Cookie。
- R2 token 应只允许目标 bucket 的对象读写，不要求账户管理权限。
- R2 加密配置不存在、解密失败和内容损坏必须是三种可区分状态。只有确实不存在配置时可返回空设置；解密或解析失败必须保留 SQLite 待备份任务并向用户报错，不得把任务当成已删除位置清理。
- Instagram 请求必须匿名优先；匿名成功时禁止读取 Session。抓取决策固定分为五类：`SUCCESS` 直接返回；断网、超时、429 和 5xx 为 `RETRYABLE_FAILURE`；匿名元数据响应结构有效但只返回 `data=null + 非空 errors`、明确登录墙或匿名 401/403 时为 `AUTH_PROBE_REQUIRED`；链接无效、明确 404、私密账号、登录失效或 authenticated media-info 空结果为 `DEFINITIVE_FAILURE`；GraphQL envelope、帖子字段、media-info 结构或 Instaloader 解析结果无法理解时为 `UNSUPPORTED_RESPONSE`。任务取消是独立控制状态，不属于抓取决策。`AUTH_PROBE_REQUIRED` 不依赖特定 GraphQL 错误码、message、severity 或 path，也不额外请求匿名 permalink；固定 Instaloader wheel 将部分 HTTP 状态包装进 `ConnectionException` 时，Python 必须从其标准异常链恢复状态，不能把 401/403 当网络失败。Python 返回结构化 `sessionProbeRequired`，Kotlin 重新检查任务未取消后，有 `ready` Session 时最多调用一次独立 authenticated media-info，没有可用 Session 时返回说明“需要登录以确认帖子状态”的 `LOGIN_REQUIRED`。authenticated media-info 必须要求 shortcode 一致且 `user.is_private` 为明确布尔值：私密账号返回 `PRIVATE_POST`，没有帖子数据或无登录失效指纹的 401/403 返回 `POST_INACCESSIBLE`，只有确认公开时才允许归档。未知结构必须返回 `UNSUPPORTED_RESPONSE`，不得误报帖子不存在、不得循环探测 Session。
- Instagram 账号密码只填写在官方 WebView 页面，业务代码不得读取或保存；WebView Cookie 验证并加密保存后必须清理 WebView Cookie、缓存和本地存储。设置页另允许用户主动粘贴完整 Cookie Header；该文本只能在隐藏输入框与单次 Flutter -> Kotlin 调用中短暂存在，提交后必须立即清空输入框。
- Instagram Session 使用独立 Android Keystore 密钥加密，只属于本机，不得进入 Flutter 状态、SQLite、R2、备份、日志、通知、异常文本或崩溃上报。手动 Cookie 必须通过 Instaloader `test_login()` 在线验证并取得真实用户名，失败不得覆盖旧 Session。只有用户在已登录页主动点击“复制Cookie”时，Android 原生层才允许把已验证 Session 转为 Cookie Header 写入系统剪贴板；剪贴板必须标记为敏感内容，Flutter 只能收到成功或失败，App 进程存活时在 60 秒后仅清理未被用户替换的该份 Cookie。
- App 不读取 Instagram App 或其他浏览器的数据，不提供 Instaloader session 文件或账号密码导入。
- 正式签名文件只允许保存在仓库外的 `MyKeys/PhotoBook/` 或 GitHub Actions Secrets，禁止提交、打印或写入 Release。
- 本机 debug 为覆盖安装并复用正式 App 数据，可通过被忽略的 `client/android/local.properties` 使用正式证书；debug APK 只允许用于自有测试设备，禁止分发或上传 Release。
- 更新 APK 必须同时通过大小、SHA-256、包名、整数版本号和当前 App 签名证书校验后才能交给系统安装器。

## Android 运行约定

- Android `applicationId`、namespace 和 Kotlin 根包统一为 `com.mantou.photobook`；Flutter 原生通道使用同一前缀。
- `ACTION_SEND` 必须在原生 Activity 可见期间先持久化任务，再启动 `dataSync` 前台服务。
- 剪贴板只允许在 App 冷启动或重新进入前台后读取一次；系统分享启动时不得同时读取。自动模式只处理最近 10 分钟复制的白名单帖子链接，同一规范化链接自动导入一次，不保存或记录其他剪贴板内容；首页必须保留不受时间限制的手动粘贴入口。
- 前台服务必须立即显示通知；所有任务结束后移除通知并停止自身。
- Instaloader 同步调用只能在串行后台队列运行，禁止阻塞主线程。
- Instaloader 单次网络请求超时固定为 30 秒；运行中任务取消后进入 `cancelling`，解析或下载返回并完成文件回滚、临时目录清理后才进入 `failed + CANCELLED`。取消期间不得读取 Session、发起认证重试、从 authenticated GraphQL 继续发起 media-info 请求或保存刷新后的 Session。
- 匿名抓取成功时禁止读取或解密 Instagram Session；限流、断网、已明确判定不存在和 `UNSUPPORTED_RESPONSE` 不得触发认证重试或改变 Session 状态。只有上一条约定的 `AUTH_PROBE_REQUIRED` 允许通过 authenticated media-info 使用 Session 判定，且每个 attempt 最多一次。
- 登录态只提高公开帖抓取成功率。即使当前账号有权查看，私密账号帖子也必须返回不可访问，不得归档或备份。
- Chaquopy 只负责把 shortcode 解析成 JSON；媒体流式下载、哈希、缩略图和视频元数据使用 Android 原生 API。
- 本地归档和云备份是两条独立状态机。R2 失败不得回滚已经完成的本地归档。
- WorkManager 只作为进程或网络中断后的恢复保险，分享主流程仍直接启动前台服务。
- 抓取恢复与 R2 备份恢复必须使用两个独立的唯一 WorkManager 计划；R2 Worker 只续跑用户已经手动创建的持久任务，不得因冷启动、回到前台、网络恢复或新增配置而创建任务。冷启动和回到前台必须按 SQLite 待处理任务补建缺失的唯一 Worker，但不能替换正在运行或已经排期的 Worker。取消抓取任务只重算抓取恢复，不得取消 R2 重试，也不得中断正在处理其他抓取任务的执行器。
- 恢复 Worker 正在执行时，新分享启动的前台服务必须等待执行权并继续处理，不能因竞争锁直接退出或把用户任务留给退避重试。
- App 使用 Android 默认网络；系统 VPN 是否接管流量由系统配置决定。不得加入 Mihomo、代理节点切换或静默直连逻辑。
- App 冷启动只检查一次 GitHub Release 更新；发现更新后必须先征得用户确认才下载，不提供静默安装。

## R2 备份约定

- 本地帖子写入与 `backup_generation` 递增位于同一 SQLite 事务，但不创建 R2 任务。手动备份时，读取当前帖子、固化快照和创建 `r2_backup_jobs` 必须位于同一 SQLite 事务；任何一步失败都不得留下部分任务。
- `r2_backup_jobs` 固化 `backup_target_id + device_id + post_id + generation + backup_seq + snapshot_json`。任务创建后不得因帖子或媒体随后被删除而改写或取消。
- 同一目标内按 `backup_seq` 串行上传，首个失败处停止并由 WorkManager 重试；不同目标的失败不得阻止其他目标继续处理。每个任务严格按“媒体、不可变 snapshot、`latest.json`”顺序完成；只有三步全部成功才标记成功。
- 远端路径固定为 `<prefix>/devices/<device_id>/device.json`、`posts/<platform>/<source_post_id>/snapshots/<20 位 backup_seq>.json`、同帖子 `latest.json` 以及设备目录内的 `media/avatars|thumbnails|originals/`。不创建全局设备索引、manifest、ops 或协议版本目录。
- snapshot 必须携带 `deviceId`、`backupSeq`、`generation`、完整帖子和媒体清单；帖子 ID、平台、媒体角色、SHA-256 等字段在入队前校验。`latest.json` 只指向同设备同帖已经上传的 snapshot。
- 首页云朵勾选表示当前帖子 `backup_generation` 已成功备份到至少一个位置；详情页抽屉逐位置显示 `未备份 / 等待或备份中 / 已备份 / 失败`。部分删除不产生新 generation，剩余帖子继续显示已备份；重新归档会产生新 generation，并在用户重新手动备份成功前显示未备份。
- 新增连接、bucket 或 prefix 只保存配置，不为任何帖子创建任务。更换 Access Key 只更新连接凭证；更换 endpoint、bucket 或 prefix 视为新目标，不读取旧目标、不迁移旧任务，也不从旧目标补文件。
- 用户在详情页每次选择一个备份位置并备份当前整帖。App 冷启动、回到前台和网络恢复只允许续跑已经手动创建的本机待处理任务，不得创建新任务，不列举或拉取任何远端帖子、设备或状态。错误写入本地状态并展示给用户。
- 配置中暂时找不到待处理任务的目标，或配置解密、解析失败时，任务必须保留并显示失败原因；只有用户确认删除位置或连接时才允许清理对应未完成任务。
- 同一安装可按当前帖子记录的 SHA-256，从已完成且仍保存配置的任一备份位置按需恢复缺失原媒体；不恢复元数据，不读取其他 `device_id`，新安装也不恢复旧安装的数据。
- App 删除帖子或媒体不得写墓碑、上传删除事件或调用 R2 删除。R2 媒体和历史快照长期保留。
- 当前 SQLite 结构固定为 v4。R2 单配置升级为多连接、多位置时迁移 Keystore 加密配置，不升级 SQLite；升级时清理旧版本遗留的未完成自动备份任务，保留已完成记录和远端对象。其他结构改版仍不实现旧数据库升级或旧 R2 格式兼容。

## 运行时文件与清理

- 抓取临时文件放 App 沙盒 `archive/jobs/<job_id>/`。
- 更新临时文件放 App 缓存 `updates/`，下载写 `.part`，完成校验后原子改名；失败、取消和过期缓存必须清理。
- Live Photo 转 GIF 临时文件放 App 缓存 `share_exports/`；系统分享面板可能延迟读取，拉起后不得立即删除，只清理超过 24 小时的文件。
- 永久媒体分别放 `archive/avatars/`、`archive/thumbnails/` 和 `archive/originals/`。
- 下载先写 `.part`，完成大小与 SHA-256 校验后原子改名。
- 成功或最终失败都清理对应任务临时目录；启动时清理超过 24 小时的遗留 `.part` 和任务目录。
- 整帖提交前新发布的内容寻址文件必须被跟踪；准备或 SQLite 提交失败时，只删除尚未被帖子或备份任务引用的新文件。
- R2 原始媒体长期保存。删除帖子或媒体不得调用 R2 对象删除；本地文件也只能在当前业务状态和未完成备份任务均不再引用时清理。

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
- 抓取：覆盖图片帖、多图帖、Reel、重复分享、失效链接、匿名优先、五类抓取决策、已知及未知 `data=null + 非空 errors` 的受控 authenticated media-info 探测、明确 404 不触发 Session、未知或畸形结构返回 `UNSUPPORTED_RESPONSE`、无 Session、Session 失效、media-info 空结果、私密帖拒绝、取消期间不读取 Session 以及系统 VPN。
- 登录：覆盖普通登录、2FA、取消、重新登录、手动 Cookie 在线验证与用户名回显、新 Cookie 失败不覆盖旧 Session、复制Cookie、剪贴板敏感标记与定时清理、WebView 数据清理、Keystore 持久化以及 Cookie 全链路脱敏。
- R2 备份：覆盖无 R2、连接与多个 prefix、不同 bucket、本地归档不自动入队、手动入队幂等、媒体/快照/latest 顺序、不同目标隔离、任务重试、删除后队列继续、同安装跨位置原媒体恢复、新安装不拉取、换 Key 和换备份目标。
