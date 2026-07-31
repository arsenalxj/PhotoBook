# PhotoBook 本地桥接、更新与 R2 协议

纯客户端版本没有 HTTP 业务 API。本文件定义 Flutter/Kotlin、Kotlin/Python 和设备/R2 之间的稳定边界。

## 1. Flutter 与 Kotlin

MethodChannel：`com.mantou.photobook/archive`

| 方法 | 入参 | 返回 | 用途 |
|---|---|---|---|
| `getRuntimeState` | 无 | JSON map | 获取活动/失败任务数、Instagram Session 和 R2 配置摘要 |
| `retryJob` | `jobId` | 无 | 将失败任务重新排队并启动服务 |
| `cancelJob` | `jobId` | 无 | 排队任务直接改为 `failed + CANCELLED`；运行任务先改为 `cancelling`，清理完成后再收口为已取消 |
| `deleteJob` | `jobId` | 无 | 删除一条 `failed` 任务记录，不删除帖子或媒体 |
| `beginInstagramLogin` | 无 | 无 | 清理 WebView 数据并开始新的官方网页登录 |
| `captureInstagramSession` | 无 | Session 摘要 | 从 Android WebView CookieManager 验证并加密保存登录态 |
| `cancelInstagramLogin` | 无 | 无 | 取消登录并清理 WebView 数据，不改变已保存 Session |
| `clearInstagramSession` | 无 | 无 | 清除加密 Session、Keystore 密钥和 WebView 数据 |
| `saveR2Config` | 配置 map | 配置摘要 | 加密保存通过验证的 R2 配置 |
| `clearR2Config` | 无 | 无 | 清除密钥和当前资料库绑定 |
| `syncNow` | 无 | 无 | 启动一次前台同步 |
| `ensureOriginal` | `mediaId` | 本地文件路径 | 从当前 R2 按需恢复原媒体 |
| `deletePost` | `postId` | 无 | 本地删除帖子并写入 `delete_post` outbox |
| `deleteMediaSelection` | `postId + mediaIds` | `postId + postDeleted` | 原子删除选中的逻辑媒体；全选写 `delete_post`，部分选择为组内物理媒体写 `delete_media` |
| `shareMedia` | `mediaIds` | 无 | 确保原媒体存在后打开 Android 系统分享面板 |
| `saveMedia` | `mediaId` | 保存后的显示名称 | 将原媒体复制到系统相册 |

EventChannel：`com.mantou.photobook/archive_events`

事件类型为 `archiveChanged`、`jobChanged`、`runStarted` 和 `runFinished`。事件只用于驱动刷新和即时反馈，不作为权威状态；`jobChanged` 不携带任务快照，Flutter 收到后合并连续刷新请求并重新查询 SQLite。阶段或媒体项进度落库、入队、取消、删除、重试和任务结束时发送 `jobChanged`，不复用 `archiveChanged` 上报下载进度。`runFinished` 可携带脱敏后的同步错误，冷启动仍从 `app_meta.last_sync_error` 恢复错误状态。

`getRuntimeState.instagramSession` 只返回非敏感摘要：未配置时为 `null`，否则为 `{status: "ready" | "needs_refresh", username, validatedAt}`。Flutter 侧不存在读取 Cookie 的接口。

详情页批量保存由 Flutter 按帖子顺序逐项调用 `saveMedia`。普通媒体使用 `original`；完整 Live Photo 使用本次选择的统一 `static / gif / video` 模式；降级 Live Photo 固定使用 `static`。单项失败不回滚已经写入系统相册的副本，界面保留失败项供重试。

`deleteMediaSelection.mediaIds` 只能包含该帖子界面可见的逻辑媒体 ID，不能直接传入 `live_motion`。Android 必须在同一 SQLite 事务中校验完整选择：选择覆盖全部 `logical_index` 时只写一个 `delete_post`，否则对每个选中逻辑组的静态图和动态视频分别写现有 `delete_media`。校验或 outbox 写入失败时整批回滚。

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

传入 Session 时，成功响应的 `refreshedSession` 包含 Instaloader 更新后的 Cookie，只允许 Kotlin 立即重新加密保存。调用策略固定为匿名请求优先；只有匿名调用返回 `LOGIN_REQUIRED`，Kotlin 才读取 `ready` Session 并调用第二次。Python 层不得写数据库、下载媒体、读取 R2 密钥或保存默认 Instaloader session 文件。异常由 Kotlin 映射为稳定错误码：`NETWORK_ERROR`、`POST_UNAVAILABLE`、`LOGIN_REQUIRED`、`RATE_LIMITED` 或 `INVALID_RESPONSE`。

### 小红书公开页桥

```text
xiaohongshu_bridge.fetch_post(request_url: str) -> str
```

输入只允许小红书 HTTPS 分享域名。小红书 App 复制出的 `http://xhslink.cn` 或 `http://xhslink.com` 官方短链在本机改写为 HTTPS 后再请求；其他 HTTP 地址仍拒绝。短链最多跟随 6 次跳转且每一跳重新校验域名；解析结果直接返回统一帖子 JSON，`sourcePlatform` 固定为 `xiaohongshu`。Live Photo 为同一 `logicalIndex` 返回相邻的 `live_still(image)` 和 `live_motion(video)`；普通图片、视频和 GIF 使用 `primary`。规范 `sourceUrl` 不携带分享 query 或 `xsec_token`。稳定错误码包括 `INVALID_URL`、`POST_UNAVAILABLE`、`VERIFICATION_REQUIRED`、`RATE_LIMITED`、`NETWORK_ERROR` 和 `INVALID_RESPONSE`。

## 4. R2 操作对象

路径：`<prefix>/devices/<deviceId>/ops/<seq>.json`

```json
{
  "deviceId": "随机设备 ID",
  "seq": 1,
  "entityVersion": {
    "version": 1,
    "deviceId": "产生实体状态的设备 ID",
    "seq": 1
  },
  "operation": "upsert_post",
  "entityId": "xiaohongshu:64abc",
  "createdAt": 1750000000000,
  "payload": {
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
        "mimeType": "image/jpeg"
      }
    ]
  }
}
```

操作对象不可变，允许的 `operation` 为 `upsert_post`、`delete_post` 和 `delete_media`。顶层 `deviceId + seq` 必须与 R2 路径一致，只负责该上传设备的操作顺序；`entityVersion` 才是帖子或媒体状态的逻辑版本。相同 key 已存在时必须核对内容，不能无条件覆盖。未知操作类型、设备 ID 或 seq 与路径不一致时拒绝应用且不推进高水位。

`delete_post` 的 `entityId` 是帖子 ID，payload 只记录 `deletedAt`；`delete_media` 的 `entityId` 是媒体 ID，payload 同时记录 `postId`、`mediaId` 和 `deletedAt`。删除操作不携带文件，也不得删除 R2 内容寻址对象。

接收端还必须验证 `sourcePlatform` 只允许 `instagram / xiaohongshu`，帖子 ID 严格等于 `<sourcePlatform>:<sourcePostId>`。`mediaCount` 必须与媒体数组长度一致，`coverMediaId` 必须属于媒体数组，每个媒体 ID 都严格等于 `<entityId>:<sortIndex>`。`sortIndex` 和 `logicalIndex` 必须非负，`sortIndex` 在同帖内唯一；`mediaRole` 只允许 `primary / live_still / live_motion`，其中 `live_still` 必须为图片、`live_motion` 必须为视频。单媒体删除后允许索引不连续，以保持媒体 ID 稳定。GIF 仍使用 `mediaType=image + mimeType=image/gif`。所有媒体 SHA-256 必须是 64 位小写十六进制，禁止把未校验字段拼入本地文件路径。实体状态以 `entityVersion` 的 `(version, deviceId, seq)` 比较；逻辑版本必须是仍可安全递增的正整数，禁止溢出。业务行不存在时也必须保存墓碑，只有更新版本的 upsert 才能恢复实体。

切换资料库时，本设备会在历史本地操作之后追加当前 active 帖子和 deleted 帖子/媒体墓碑快照。快照沿用 `sync_entity_states` 中的原始 `entityVersion`，只分配新的顶层传输 seq，避免旧设备快照被误判为更新业务状态。媒体上传以每条操作 payload 的哈希为准，不能用帖子表的现值代替历史操作引用。

## 5. 设备 manifest

```json
{
  "deviceId": "随机设备 ID",
  "lastSeq": 42,
  "updatedAt": 1750000000000
}
```

`devices/index/<deviceId>.json` 只用于发现设备。拉取端仍以设备 manifest 和逐号 op 为准，不能把列表排序当同步游标。

## 6. 当前格式约束

- R2 直接使用 `<prefix>/devices/` 和 `<prefix>/media/`，JSON 不携带协议版本字段。
- 当前 SQLite 结构版本为 v2。项目处于开发阶段，v1 不迁移；格式变化后直接卸载 App 并清空对应 R2 prefix，不保留旧 SQLite 或 R2 兼容逻辑。
- 所有时间使用 UTC epoch milliseconds；同步正确性不依赖设备时间排序。
- JSON key 使用 lowerCamelCase；R2 object key 使用小写英文目录。
