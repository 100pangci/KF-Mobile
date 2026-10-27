# Native Interaction Pass

## 实现与边界

- Android 仍使用 Navigation Compose NavHost 与 `KfMotion`，保留 top-level save/restore 和 predictive back。搜索是独立 destination，不再使用搜索对话框。编辑器仅在有实际修改或正在提交时拦截返回。
- `PageData` 仍然是一页服务器响应。`PageTimeline` 只聚合 UI 列表，按主题/消息 ID 和帖子 ID 去重，保持服务器楼层顺序。每个 destination 保留列表、续页状态及错误；Compose 保存对应滚动位置和分区选择。
- 接近列表底部由 `snapshotFlow` 请求续页。请求锁、已访问 URL、请求版本和 origin 检查避免重入、循环页、取消后旧响应污染及跨服务器数据串用。只允许同一列表的安全页码变化，不能自动跟随操作链接或其他列表的跳转。
- 首页没有合法下一页时保持有限 feed，不臆造论坛接口。复杂管理页保留手动分页，正常板块、搜索、收藏、消息与 Reader 不再依赖分页按钮。
- 下拉刷新只替换列表第一页，失败保留已显示内容。Reader 的菜单刷新保留其入口页，不强制将深层阅读入口重置到第一楼。
- `SiteActionRisk` 使用 URL / action / form 结构的保守白名单。收藏、单帖取消收藏、推荐和已识别的普通颜色切换直接执行；删除（包括消息批量删除）、支付、转账、退出登录、修改密码及未知操作继续确认。服务器返回的提示优先展示，避免把拒绝提示改成固定成功文案。
- 编辑器复用原有 BBCode 插入辅助函数、附件槽位、GBK 校验及提交结果不确定保护。发布直接提交，表情面板可连续选择，关闭后恢复正文焦点；提交期间不能丢弃正在发送的内容。
- 图片查看器支持单击隐藏/显示工具栏，保留 pager、双指缩放、双击和保存。没有增加未经设备验证的下滑关闭手势。
- 首次请求使用页面 loading / error + retry；刷新使用 M3 PullToRefreshBox；续页使用 footer；发布和收藏使用按钮反馈；普通错误用 Snackbar；session 提醒仍使用 banner。

## 未改动的兼容层

此节仅描述最初的交互阶段：当时未修改 `ForumHtmlParser`、`NativeSiteParser`、HTTP 提交/搜索编码、CookieJar、SecureStore、购买复核协议、Theme 或 `KfMotion`。后续 Coverage Audit 及 P1 接管复核已修改 Parser、表单复核和消息提交保护；当前实现应以 `docs/native-coverage.md` 的增量和源码为准。兼容 fallback 仍保留。

## 验证

三个阶段均执行：

```powershell
.\gradlew.bat :shared:desktopTest :androidApp:assembleDebug :androidApp:lintDebug
```

离线测试共 145 项：143 通过，2 项真实站点测试按原有开关跳过，0 失败；Android debug 构建通过，lint 为 0 errors / 11 warnings（既有依赖升级、API 与 KTX 提示）。

新增覆盖搜索 route 编码、缓存 key、精确 URL 分类、续页去重/顺序/重入/显式重试、首屏缓存保留、刷新失败与取消竞态、登录墙、服务器切换、续页楼层购买、tab 缓存复用、操作风险及编辑器 dirty/discard。原有解析、session、Cookie 隔离、购买和 fallback 测试继续运行。

离线 Compose 渲染截图位于 `shared/build/reports/ui/`；这些截图使用合成数据，不包含真实账户。

当前环境没有连接 Android 设备，因此未声称完成真机手势、IME 与振动验收；真实站点登录/交易测试仍受原有环境变量开关控制，不会自动提交真实操作。

## 设备回归清单

- Feed / 板块 / 消息 / 收藏：下拉刷新、续页失败重试、返回后保持位置。
- 四个 tab 来回切换：分区保留，重复点击当前 tab 回顶且不新增 route。
- 搜索：首次进入焦点和键盘、IME Search、作者筛选、结果续页、打开主题再返回保留关键词与位置。
- Reader：跨页楼层顺序、引用、追加楼层中的购买、返回手势完成与取消。
- 编辑器：空光标成对标签、选区包裹、连续表情、附件插入/移除、发送期间返回、修改与未修改的丢弃行为。
- 收藏/推荐直接执行；删除、批量删除、支付、退出及转账必须确认。
- 图片：单击 UI 显隐不改变图片尺寸；翻页、缩放、双击、保存及系统返回正常。
- 服务器切换后不显示旧服务器的列表、草稿、搜索或 Cookie。

## 后续

优先增加 Android 导航/手势仪器测试和长列表性能测量，再决定是否加入图片下滑关闭。未来新增低风险操作应先补充风险策略测试，不应依赖按钮文字直接取消确认。

## P1 接管补充（2026-10-26）

- 普通请求现在也检查版本、origin 和 active，取消后旧响应不能修改新页面；刷新结果必须属于原列表，续页回到旧页会显示 footer 错误。
- Feed/Account 的各分区使用独立 saveable 列表状态；这是共享 Compose 状态实现，不是设备 scroll/进程重建验收结论。
- 消息/收藏选择按来源页及 option 值隔离，已加载页可切换；一次只 fresh-revalidate 并提交一页。任一选择失效就拒绝写请求，提交后重新加载并清除旧页边界/选择，不自动循环删除。
- 私信未知提交增加网络层摘要标记，跨编辑器/服务重建阻止相同内容直接重发；核对入口指向发件箱。没有自动消歧/解除标记流程，仍需协议调查。
- 本次无 Android 设备，IME、触摸、predictive back、旋转/进程死亡和图库手势依然未验收；不要将合成或私有 capture 的 JVM 渲染写成真机通过。

## P1/P2 收尾（2026-10-26，`30118c5` 之后）

本轮不重做页面，只补状态与事务语义。实现以源码为准；下面的“完成”均指离线/合成或真实只读证据，不等于真实副作用操作已执行。

- **进程恢复**：新增加密 `RecoveryStore`，只保存可重建的 destination、origin、列表页 URL、anchor、搜索词和草稿内容。分页最多恢复 6 页（根页 + 5 页），失败即停止，不循环轰炸；状态 3 天、草稿 7 天后失效。草稿不保存隐藏字段、verify/safeid、密码、银行/购买表单；附件只保留名称/MIME/大小/slot，本进程重启后明确提示重新选择，不保存 bytes。
- **@ 结构**：`ForumPost.mentions` 只从同源 `profile.php/show.php` 的 `uid` 链接中提取；邮箱和普通文本 `@` 不误判。正文中的 `@用户名` 在发送时合并到站点的 `diy_guanjianci` 关键词字段（站点真实 @ 提醒机制），不发明 BBCode。Reader 的资料链接仍走原生 RichText 点击。
- **状态时间线**：新增 `StatusKind`/`StatusTimelineEntry`/`NativeStatusTimeline`。评分奖励只按页面上明确的“已完成/待检查/可领取/失败/过期”显示，无法判断一律 `UNKNOWN`；成长只按 claimed/claim 入口显示。没有正常结构时不伪造 timeline。
- **私信回执**：发送前读取发件箱作为基线；只有 POST 后发件箱出现**新的**标题+收件人匹配行才 `Confirmed`。编辑器回显+明确拒绝文本才 `Rejected`；其余（包括 HTTP 200、普通跳转、发件箱无新行）为 `Uncertain`。发送前有收件人确认对话框，Success 文案不声称 UID。
- **跨页事务**：`BatchTransactionItem`/`BatchItemResult`/`BatchTransactionResult`；按来源页逐页 fresh revalidation，删除后按“选项是否仍存在”逐项判定成功/失败。任一页返回未知就停止后续页并标 `NOT_ATTEMPTED`，不自动重放。写入前持久化 pending marker，进程死亡后启动只标核对、不重放。移动收藏因无法从列表证明目标分类，逐项返回 `Uncertain`，不伪装完全成功。
- **银行日志**：`hack.php?H_name=bank&action=log` 与 `&to=1` 加入只读路由；新增 `NativeBankLog`。只按表格列解析时间/类型/描述/日期，金额方向只由 `to=1` 与转帐类型决定；未提供对方或金额时留空，不根据余额变化推断交易成功。UI 与余额分开展示。
- **奖励协议**：评分奖励的 `do=1&safeid&ptid` GET 入口以及 `kf_fw_ig_intel.php` 的单步 POST `safeid` 进入 `RewardAction`；不实现“前进至结束”的客户端循环。领取后重新读页面，入口消失才 `Confirmed`；战斗步骤返回任意日志只报 `Uncertain`，不报胜利或奖励成功。
- **ajaxface**：独立 `AvatarUploadRequest`/`AvatarUploadResult`，使用 Android Photo Picker、本机中心裁剪为 140×200 JPG、MIME/magic/size 校验、multipart `profile.php?action=ajaxface`，解析真实 `facepath(...,'success',...)` 回调。上传结果不被当作资料表单保存；若站点要求再保存，UI 明确提示。失败分 Invalid/Rejected/Uncertain，未知不自动重试。
- **咕咕镇**：使用独立游戏域和独立 CookieJar，论坛 Cookie 不转发；从站点签发的 302 进入游戏，只读 `fyg_index.php`、`fyg_beach.php`、`fyg_read.php` 的 f=6/7/8 列表。Native `GuguScreen` 展示角色/段位/等级/贝壳/冷却/装备/背包，不提供战斗、使用道具、商店或 WebView。
- **图库手势**：`GalleryGesturePolicy` 统一仲裁。scale≈1（±0.02）、单指、纵向比例 > 1.15 才进入 dismiss；缩放/拖动/横向 pager 优先；距离 > 高度 22% 或速度 > 1100px/s 且超过 6% 距离才关闭，否则 spring 回中并恢复 chrome。仅策略有离线测试，真机触摸仍未验收。
- **事务边界复核**：HTTP 2xx/redirect 对普通 mutation 只作为页面回执并标 `uncertainReceipt`；收藏需读回相反操作链接；签到需明确完成状态；奖励需匹配项目的明确 completed 状态。通用 HTTP 200 不直接等价于业务成功。
- **取消/进程死亡**：跨页批量 pending marker 在 coroutine cancellation / Activity close 时保留；重启只要求只读核对，不自动重放。单页管理核对来源加入加密恢复 state，logout 不清理未知回执。
- **私信发送前基线**：必须先读取已认证、结构可识别的发件箱；读不出则 POST 不发出并清理 marker。只有 baseline 成功才切换 `sending` marker 并提交；POST 阶段取消保留保护。
- **头像两阶段边界**：上传前重读并复核 profile multipart form；上传 callback 成功不直接等同 Profile 保存成功，需再读 Profile 并匹配头像 URL，否则显示“已上传但最终保存未确认”。
- **图库下滑方向**：只有向下 drag/向下速度触发 dismiss，向上 fling 不关闭；拖动内容整体位移并轻微缩小，背景 alpha 同步降低。

> 旧“P1 续办”中关于“银行日志仍 CHANGE、进程恢复未实现、私信回执 adapter 不存在”的限制已被上节取代；保留旧记录时应以其时间点为准。
