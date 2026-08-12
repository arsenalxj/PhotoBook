# PhotoBook UI 重做交接文档（Shadcn 原型 → Flutter）

## 这是什么

本目录下的 7 个 HTML 文件是 PhotoBook 全部界面的**高保真交互原型**，采用 Shadcn 设计系统，已覆盖代码中所有界面的所有状态。它们是 UI 重做的**唯一视觉权威**：布局、间距、字号、颜色、圆角、组件结构、状态呈现都以 HTML 为准。

用浏览器打开 `index.html` 可以导航到每个界面；每个页面顶部的原型工具条（状态切换按钮）是调试控件，用来看该屏的各种状态，**不是产品 UI，不要照抄进 Flutter**。

## 硬约束

1. **以当前功能合同为边界**：常规视觉调整只改 UI；手动 R2 备份这一轮允许按架构文档同步调整状态管理、原生桥接、配置模型和备份任务逻辑。
2. **颜色只允许来自下表令牌**。Flutter 侧禁止出现表外的 `Color(0xFF...)` 字面量；先在主题里定义令牌常量，组件全部引用常量。
3. **状态全覆盖**：每屏必须实现该屏 HTML 中状态切换演示的全部状态，不能只实现默认态。
4. 一屏改完后跑 `flutter analyze` 和 `flutter test`，通过后再改下一屏。
5. 中文文案以 HTML 中的文案为准。

## 令牌映射（CSS 变量 → Flutter）

| CSS 变量 | 值 | Flutter 用途 |
|---|---|---|
| `--bg` / `--surface` | `#FFFFFF` | `scaffoldBackgroundColor`、卡片底色 |
| `--fg` | `#111827` | 正文、标题文字色 |
| `--muted` | `#64748B` | 次级文字、说明、占位符 |
| `--border` | `#E5E7EB` | 1px 发丝边框、Divider |
| `--accent` | `#000000` | 主操作按钮填充、选中态（唯一高信号色，克制使用） |
| `--accent-on` | `#FFFFFF` | 黑底上的文字和图标（反白，必须） |
| `--accent-hover` / `--accent-active` | 黑提亮 10% / 18% | 主按钮 pressed 态，约 `#1A1A1A` / `#2E2E2E` |
| `--success` | `#16A34A` | 成功徽标/状态，仅小面积 |
| `--warn` | `#D97706` | 警告状态，仅小面积 |
| `--danger` | `#DC2626` | 错误、删除、失败状态，仅小面积 |
| `--radius-sm` | `6` | 按钮、输入框圆角 |
| `--radius-md` | `8` | 卡片圆角 |
| `--radius-lg` | `12` | 底部弹层、对话框圆角 |
| 字号刻度 | `12 / 14 / 16 / 20 / 24 / 32` | caption / 按钮输入框 / 正文 / lede / 卡片标题 / 页面标题 |
| 字重 | `400 / 500 / 600 / 700` | 正文 / 强调 / 标题 / 页面标题 |
| 间距刻度 | `4 / 8 / 12 / 16 / 20 / 24 / 32 / 48` | 全部 padding/margin 从此刻度取值 |

**字体**：Geist（正文与标题），等宽场景用 Fira Code。未打包字体时使用系统回退栈，不要因为字体缺失改字号或字重。

**组件状态**：按钮、输入框、列表项必须有 default / pressed / disabled /（输入框另加 error）态，参照 HTML 中对应组件的 CSS。

## 逐屏任务清单

按此顺序执行，一屏一个任务。原型顶部工具条可切换状态，先逐个点一遍再动手。

### 1. 图库主页 — `client/lib/screens/home_screen.dart` + `client/lib/widgets/post_card.dart`
原型：`photobook-home.html`
状态：默认网格 / 帖子备份中 / 保存中 / 博主筛选 / 空图库 / 筛选无结果。
要点：帖子卡片（封面、多图角标、视频角标、平台标识含小红书）、博主筛选条、备份状态指示；首页顶部不显示全局运行动画。当前 generation 已在至少一个位置备份成功时优先显示成功图标；尚未成功且存在无错误待处理任务时，在卡片封面右上角原位显示固定尺寸动画。任务失败后停止动画，重试真正开始后重新显示，成功后原位切换为成功图标。其他帖子不受影响。

### 2. 任务列表 — `client/lib/screens/task_list_screen.dart`
原型：`photobook-tasks.html`
状态：进行中（含进度）/ 失败可重试 / 已取消分区 / 队列忙碌 / 空态。
要点：任务项的平台、链接、阶段文案、进度条、重试按钮；已取消独立分区；长按整行复制原始链接并显示成功反馈。

### 3. 设置主页 — `client/lib/screens/settings_screen.dart` + `client/lib/widgets/update_dialog.dart`
原型：`photobook-settings.html`
状态：Instagram 账号三态卡片（未登录 / 已登录 / 登录过期）、R2 配置入口、应用更新对话框状态机（检查中 / 有更新 / 下载中 / 校验失败）。
要点：设置分组列表、账号状态徽标、更新对话框的步骤与按钮。

### 4. R2 存储设置 — `client/lib/screens/settings_screen.dart`（R2 子页）
原型：`photobook-r2-settings.html`
状态：无连接 / 多连接与多位置 / 新增连接 / 新增位置 / 更新凭证 / 验证中 / 校验错误。
要点：R2 连接保存 endpoint / bucket / access key / secret；一个连接下可新增多个“名称 + prefix”备份位置。连接按 bucket 分组，新增 prefix 不重复输入凭证；删除位置或连接不删除远端对象。

### 5. Instagram 账号 — `client/lib/screens/instagram_login_screen.dart`
原型：`photobook-instagram.html`
状态：未登录 / WebView 登录中（加载中 / 加载失败重试）/ Cookie 验证中 / 已登录 / 登录过期。
要点：WebView 容器外的状态层、登录过期提示与重新登录入口；状态卡下方放独立 Cookie 登录卡，包含默认隐藏的输入框与“验证并登录”按钮，成功后顶部立即回显真实用户名。

### 6. 帖子详情 — `client/lib/screens/detail_screen.dart` + `client/lib/widgets/post_action_sheets.dart`
原型：`photobook-detail.html`
状态：多图 / 视频 / Live Photo / 帖子备份中 / 原图下载中 / 下载失败 / 帖子不存在。
弹层：分享、保存、手动备份位置、删除（含确认）、更多。
要点：右上角依次为分享、保存、备份、更多；删除媒体移入更多菜单底部。备份抽屉按 bucket 分组列出位置及当前帖的未备份、等待或备份中、已备份、失败状态，每次选择一个位置备份当前整帖；没有位置时提供进入 R2 设置的入口。当前 generation 已在至少一个位置备份成功时优先显示成功图标；尚未成功且存在无错误待处理任务时，在作者信息右侧的完成图标位置显示固定尺寸动画。失败后停止动画，重试真正开始后重新显示，成功后原位切换为成功图标。状态最多，排最后做。

## 验收

每屏完成后对照原型逐项检查：

- [ ] 颜色、字号、圆角、间距全部来自令牌表，无表外字面量
- [ ] 该屏所有状态都已实现且可触发
- [ ] 黑底区域文字图标为白色（`--accent-on`）
- [ ] 主操作按钮每屏最多一处实心黑底
- [ ] `flutter analyze` 无新告警，`flutter test` 通过
- [ ] 未触碰 UI 层以外的代码
