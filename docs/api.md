# PhotoBook 本地桥接、更新与 R2 协议

纯客户端版本没有 HTTP 业务 API。本文件定义 Flutter/Kotlin、Kotlin/Python 和设备/R2 之间的稳定边界。

## 1. Flutter 与 Kotlin

MethodChannel：`com.mantou.photobook/archive`

| 方法 | 入参 | 返回 | 用途 |
|---|---|---|---|
| `getRuntimeState` | 无 | JSON map | 获取活动/失败任务数、Instagram Session、R2 连接和备份位置摘要 |
| `retryJob` | `jobId` | 无 | 将失败任务重新排队并启动服务 |
| `cancelJob` | `jobId` | 无 | 排队任务直接改为 `failed + CANCELLED`；运行任务先改为 `cancelling`，清理完成后再收口为已取消 |
| `deleteJob` | `jobId` | 无 | 删除一条 `failed` 任务记录，不删除帖子或媒体 |
| `copyJobSourceUrl` | `jobId` | 无 | Android 从 SQLite 读取任务原始链接并写入系统剪贴板，链接不返回 Flutter |
| `beginInstagramLogin` | 无 | 无 | 清理 WebView 数据并开始新的官方网页登录 |
| `captureInstagramSession` | 无 | Session 摘要 | 从 Android WebView CookieManager 验证并加密保存登录态 |
| `cancelInstagramLogin` | 无 | 无 | 取消登录并清理 WebView 数据，不改变已保存 Session |
| `importInstagramCookies` | `cookieHeader` | Session 摘要 | 在线验证用户主动粘贴的 Cookie，成功后加密保存并返回真实用户名 |
| `copyInstagramCookies` | 无 | 无 | 仅在 Session 可用时由 Android 原生层写入敏感剪贴板，Cookie 不返回 Flutter |
| `clearInstagramSession` | 无 | 无 | 清除加密 Session、Keystore 密钥和 WebView 数据 |
| `saveR2Connection` | endpoint、bucket、凭证及默认位置 | 连接与位置摘要 | 验证并加密保存连接，同时建立首个 prefix 位置，不创建帖子任务 |
| `updateR2Connection` | connectionId、endpoint、bucket、凭证 | 连接与位置摘要 | 验证并更新已有连接的凭证，不改变该连接的位置或目标身份 |
| `saveR2Target` | connectionId、名称、prefix | 连接与位置摘要 | 在已有连接下新增位置，或只修改已有位置名称；prefix 不可原地修改，不重复传递 Secret |
| `deleteR2Target` | targetId | 连接与位置摘要 | 删除位置并取消该位置未完成任务，不删除远端对象 |
| `deleteR2Connection` | connectionId | 连接与位置摘要 | 删除连接及其位置并取消对应未完成任务，不删除远端对象 |
| `enqueueR2Backup` | postId、targetId | 入队状态 | 为当前帖子 generation 幂等创建手动备份任务并启动 Worker |
| `resumeCaptureJobs` | 无 | 空 | 仅在存在抓取任务时启动前台服务，不处理或创建 R2 任务 |
| `ensureOriginal` | `mediaId` | 本地文件路径 | 从已完成且仍保存配置的备份位置按需恢复原媒体 |
| `deletePost` | `postId` | 无 | 只在本机删除帖子，不创建 R2 删除操作 |
| `deleteMediaSelection` | `postId + mediaIds` | `postId + postDeleted` | 只在本机原子删除选中的逻辑媒体；全选时删除帖子 |
| `shareMedia` | `mediaIds` | 无 | 确保原媒体存在后打开 Android 系统分享面板 |
| `saveMedia` | `mediaId` | 保存后的显示名称 | 将原媒体复制到系统相册 |

EventChannel：`com.mantou.photobook/archive_events`

事件类型为 `archiveChanged`、`jobChanged`、`runStarted` 和 `runFinished`。事件只用于驱动刷新和即时反馈，不作为权威状态；`jobChanged` 不携带任务快照，Flutter 收到后合并连续刷新请求并重新查询 SQLite。阶段或媒体项进度落库、入队、取消、删除、重试和任务结束时发送 `jobChanged`，不复用 `archiveChanged` 上报下载进度。手动备份任务的状态也由 SQLite 查询，`runFinished` 只携带脱敏后的最近执行错误。

R2 加密配置只有在 SharedPreferences 中确实不存在 IV 和密文时才表示未配置。字段残缺、Keystore 解密失败或 JSON 无效必须返回脱敏错误并保留待备份任务。冷启动和回前台可补建处理现有任务的唯一 Worker，但不能创建新的 `r2_backup_jobs`，也不能替换正在执行或已经排期的 Worker。

`getRuntimeState.instagramSession` 只返回非敏感摘要：未配置时为 `null`，否则为 `{status: "ready" | "needs_refresh", username, validatedAt}`。`importInstagramCookies` 是 Cookie 唯一允许的 Flutter -> Kotlin 入口，输入框提交时立即清空，验证失败不覆盖旧 Session。`copyInstagramCookies` 只返回成功或失败；Flutter 侧不存在读取已保存 Cookie 内容的接口。

详情页批量保存由 Flutter 按帖子顺序逐项调用 `saveMedia`。普通媒体使用 `original`；完整 Live Photo 使用本次选择的统一 `static / gif / video` 模式；降级 Live Photo 固定使用 `static`。单项失败不回滚已经写入系统相册的副本，界面保留失败项供重试。

`deleteMediaSelection.mediaIds` 只能包含该帖子界面可见的逻辑媒体 ID，不能直接传入 `live_motion`。Android 必须在同一 SQLite 事务中校验完整选择：选择覆盖全部 `logical_index` 时删除整帖，否则删除每个选中逻辑组的静态图和动态视频。校验或数据库写入失败时整批回滚；删除不改变 `backup_generation`，也不创建备份或删除任务。

## 2. App 更新

MethodChannel：`com.mantou.photobook/update`

| 方法 | 入参 | 返回 | 用途 |
|---|---|---|---|
| `getInstalledApp` | 无 | 包名、版本名、整数版本号、SDK | 获取可信的本机安装信息 |
| `downloadUpdate` | URL、文件名、大小、SHA-256、版本号 | 校验后的缓存路径 | 下载 `.part`，校验后原子发布 |
| `cancelUpdate` | 无 | 无 | 取消当前下载并删除临时文件 |
| `installUpdate` | 缓存路径、大小、SHA-256、版本号 | 安装状态 | 复核 APK 后请求系统安装 |

EventChannel：`com.mantou.photobook/update_events`，发送下载字节进度和安装失败状态。安装必须使用 Android `PackageInstaller`；未知来源权限只能由用户在系统页面授予。

更新清单固定为：

```json
{
  "schema_version": 1,
  "application_id": "com.mantou.photobook",
  "version_name": "1.1.0",
  "version_code": 2,
  "tag": "v1.1.0+2",
  "min_sdk": 24,
  "release_notes": "本次更新说明",
  "asset": {
    "name": "photobook-v1.1.0+2-arm64-v8a.apk",
    "download_url": "https://github.com/arsenalxj/PhotoBook/releases/download/v1.1.0+2/photobook-v1.1.0+2-arm64-v8a.apk",
    "size": 123,
    "sha256": "64 位小写十六进制"
  }
}
```

客户端只比较整数 `version_code`。解析时必须校验 schema、application ID、标签与版本一致、设备 SDK、固定 GitHub Release URL、文件名、正整数大小和 SHA-256；任何字段无效都不得下载。

## 3. Kotlin 与 Python

模块：`photobook_bridge`

```python
health_check() -> str
validate_session(cookie_header: str) -> str
fetch_post(shortcode: str, session_json: str | None = None) -> str
```

`validate_session` 解析完整 Cookie header，要求 `sessionid` 和 `csrftoken`，通过 Instaloader `test_login()` 取得真实用户名并返回规范化 Session JSON。`fetch_post` 返回 UTF-8 JSON envelope：

```json
{
  "post": {
    "sourcePostId": "Abc123",
    "sourceUrl": "https://www.instagram.com/p/Abc123/",
    "authorUsername": "author",
    "authorDisplayName": "Author",
    "authorProfileUrl": "https://www.instagram.com/author/",
    "authorAvatarUrl": "https://...",
    "caption": "...",
    "publishedAt": 1750000000000,
    "locationName": null,
    "media": []
  },
  "refreshedSession": null
}
```

传入 Session 时，成功响应的 `refreshedSession` 包含 Instaloader 更新后的 Cookie，只允许 Kotlin 立即重新加密保存。调用策略固定为匿名请求优先，并把结果归入五类决策：成功返回帖子；断网、超时、429 和 5xx 分别通过 `NETWORK_ERROR` 或 `RATE_LIMITED` 进入自动重试；链接无效、明确 404、私密账号、登录失效和 authenticated media-info 空结果为确定失败；匿名元数据返回结构有效的 GraphQL envelope，且 `data=null`、`errors` 为非空对象列表时，不依赖具体 error code、message、severity 或 path，统一返回 `{"sessionProbeRequired":true}`；明确登录墙及匿名 HTTP 401/403 也返回同一结构。固定 Instaloader wheel 会把部分 HTTP 状态包装进 `ConnectionException`，Python 从其标准 `状态码 + reason + when accessing URL` 异常链恢复状态，不能把 401/403 当成可重试网络错误。GraphQL envelope、帖子字段、media-info 或 Instaloader 解析结果无法理解时返回 `UNSUPPORTED_RESPONSE`。任务取消是独立状态。Kotlin 在读取 Session 前重新检查任务未取消，有 `ready` Session 时只调用一次 `fetch_post_media_info(shortcode, session_json)`，不会先重复 authenticated GraphQL；没有 Session 时返回说明“需要登录以确认帖子状态”的 `LOGIN_REQUIRED`，也不再为歧义结果额外请求匿名 permalink。authenticated media-info 必须返回相同 shortcode 和明确的 `user.is_private`：私密账号映射为 `PRIVATE_POST`，空 `items` 或无登录失效指纹的 HTTP 401/403 映射为 `POST_INACCESSIBLE`，只有公开账号帖子才能继续归档。authenticated 响应中的 `login_required`、登录重定向或账号登出映射为 `LOGIN_REQUIRED`；未知结构不得误报帖子不存在，也不得再次探测 Session。Python 层不得写数据库、下载媒体、读取 R2 密钥或保存默认 Instaloader session 文件。稳定错误码包括 `NETWORK_ERROR`、`POST_UNAVAILABLE`、`POST_INACCESSIBLE`、`PRIVATE_POST`、`LOGIN_REQUIRED`、`RATE_LIMITED`、`UNSUPPORTED_RESPONSE` 和用于非 Instagram 边界的 `INVALID_RESPONSE`。

### 小红书公开页桥

```text
xiaohongshu_bridge.fetch_post(request_url: str) -> str
```

输入只允许小红书 HTTPS 分享域名。小红书 App 复制出的 `http://xhslink.cn` 或 `http://xhslink.com` 官方短链在本机改写为 HTTPS 后再请求；其他 HTTP 地址仍拒绝。短链最多跟随 6 次跳转且每一跳重新校验域名；解析结果直接返回统一帖子 JSON，`sourcePlatform` 固定为 `xiaohongshu`。Live Photo 为同一 `logicalIndex` 返回相邻的 `live_still(image)` 和 `live_motion(video)`；普通图片、视频和 GIF 使用 `primary`。小红书图片存在可信 `fileId` 时，`url` 使用原图 CDN 的全尺寸 JPEG 地址，并以可选 `fallbackUrl` 携带 H5 详情图；Android 只在 `url` 明确不可下载时尝试 `fallbackUrl`，两个地址都必须通过同一平台 CDN 安全校验。其他平台和视频不返回 `fallbackUrl`。规范 `sourceUrl` 不携带分享 query 或 `xsec_token`。稳定错误码包括 `INVALID_URL`、`POST_UNAVAILABLE`、`VERIFICATION_REQUIRED`、`RATE_LIMITED`、`NETWORK_ERROR` 和 `INVALID_RESPONSE`。

## 4. R2 备份快照

路径：`<prefix>/devices/<deviceId>/posts/<sourcePlatform>/<sourcePostId>/snapshots/<20 位 backupSeq>.json`

```json
{
  "deviceId": "随机设备 ID",
  "backupSeq": 1,
  "generation": 1,
  "createdAt": 1750000000000,
  "post": {
    "id": "xiaohongshu:64abc",
    "sourcePlatform": "xiaohongshu",
    "sourcePostId": "64abc"
  },
  "media": [
    {
      "id": "xiaohongshu:64abc:0",
      "postId": "xiaohongshu:64abc",
      "sortIndex": 0,
      "logicalIndex": 0,
      "mediaRole": "live_still",
      "mediaType": "image",
      "mimeType": "image/jpeg",
      "originalSha256": "64 位小写十六进制"
    }
  ]
}
```

snapshot 不可变，顶层 `deviceId + backupSeq` 必须与路径和本地任务一致，`generation` 必须等于任务创建时固化的帖子 generation。相同 key 已存在时必须核对内容，不能无条件覆盖。备份引擎只上传本机任务，不解析或应用任何远端 snapshot。

入队前必须验证 `sourcePlatform` 只允许 `instagram / xiaohongshu`，帖子 ID 严格等于 `<sourcePlatform>:<sourcePostId>`。`mediaCount` 必须与媒体数组长度一致，`coverMediaId` 必须属于媒体数组，每个媒体 ID 都严格等于 `<postId>:<sortIndex>`。`sortIndex` 和 `logicalIndex` 必须非负且 `sortIndex` 唯一；`mediaRole` 只允许 `primary / live_still / live_motion`，其中 `live_still` 必须为图片、`live_motion` 必须为视频。GIF 使用 `mediaType=image + mimeType=image/gif`。所有媒体 SHA-256 必须是 64 位小写十六进制，禁止把未校验字段拼入本地文件路径。

任务上传时只能使用 `snapshot_json` 内固化的媒体哈希和路径信息，不能用帖子表的现值替代历史任务引用。帖子或媒体在本机删除后，已入队任务仍按原 snapshot 完成。

每个头像、缩略图和原媒体对象必须携带 `x-amz-meta-photobook-sha256: <64 位小写 SHA-256>`。客户端对新上传对象和 `HEAD` 去重命中的已有对象都必须校验对象大小与该 metadata；任一不一致或 metadata 缺失都返回冲突，不得继续写 snapshot、`latest.json` 或标记本地任务完成。

## 5. 设备与 latest 对象

`device.json`：

```json
{
  "deviceId": "随机设备 ID",
  "createdAt": 1750000000000,
  "updatedAt": 1750000000000
}
```

`device.json` 只用于说明对象归属，不创建全局索引。`latest.json` 在对应 snapshot 和全部媒体上传后更新：

```json
{
  "deviceId": "随机设备 ID",
  "postId": "xiaohongshu:64abc",
  "generation": 1,
  "backupSeq": 1,
  "snapshotKey": "photobook/devices/.../snapshots/00000000000000000001.json",
  "updatedAt": 1750000000000
}
```

客户端不通过 `latest.json` 导入帖子。它只表示该安装最后完成的本帖备份；新安装和其他设备不读取。

## 6. 当前格式约束

- R2 直接使用 `<prefix>/devices/<deviceId>/`，JSON 不携带协议版本字段。
- 当前 SQLite 结构版本为 v4。项目处于开发阶段，不迁移旧结构；格式变化后直接卸载 App 并使用全新 R2 prefix，不保留旧 SQLite 或 R2 兼容逻辑。
- 所有时间使用 UTC epoch milliseconds；备份顺序只依赖本机 `backupSeq`，不依赖设备时间排序。
- JSON key 使用 lowerCamelCase；R2 object key 使用小写英文目录。
