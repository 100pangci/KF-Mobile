# Live Site Coverage Audit + Native UI/UX Pass

## 范围与口径

> 接管复核：2026-10-26。下方保留上一轮的巡检/修复历史；本次增量、证据和限制单列于“P1 接管复核”。矩阵描述当前源码，不把既有功能计为本次新增。

- 本轮实际使用本地已有登录 Cookie，复用 `HttpForumService`、`OriginCookieJar`、`ForumHtmlParser` 和 `NativeSiteParser`。
- Cookie 查找顺序：仓库根目录 `cookie.txt`，其次 `.secrets/<host>.cookies.txt`。不读取账号密码，不改写原 Cookie 文件。
- 首轮：**53 个实际内容页面样本，35 类内容页面/视图**；此外发现搜索 GET 入口跳转。最终复测数据见下方“验证结果”。同一路径的收件、发件、详情、编辑、评分子视图和排行分组分别计类；不是 35 个独立产品功能。
- 自动发现来源包括首页菜单、板块、主题、资料、信箱、表单入口和页面内链接；覆盖矩阵按页面类型与真实入口统计，同类页面复用同一解析/渲染路径。
- 专用适配与统一原生渲染分层：矩阵中的“高 / 专用”指专用页面；同源其它页面由 NativeSitePage 按真实结构呈现 sections、表格、链接与表单，写入控件逐项确认。
- 开始工作时已有未提交的搜索、分页、导航、Reader、编辑器及设置修改；保留这些工作。本轮是在其上补适配、修实际结构缺陷并增加回归，不能把全部既有改动算作本轮新增。

## 严格只读与脱敏

巡检只有 GET，且每次实际请求必须通过独立 `AuditReadPolicy` 和 `SiteRoutePolicy`。拒绝未知参数、重复参数、下载、购买、任何表单提交和 JS 执行；HTTP 跳转先记录，再对目标重新判断。GET 不代表安全。

只读巡检不提交任何写操作；发帖、回复、私信、删除、收藏、推荐、购买、附件下载、签到、领奖、资金、资料/密码/屏蔽、头像上传和游戏动作由原生控制器、fake service 与 synthetic fixtures 覆盖，巡检不代替这些路径。

只有已确认形状的链接列入 READ；其它形状保持 CHANGE 并记录在巡检清单中，由用户确认后访问。私信详情按用户授权作为阅读路由访问，已读标记由服务端决定；巡检不调用“标记已读/未读”操作。

本地生成、不得提交或上传的文件：

- `.secrets/site-audit/route-inventory.json`：路由、参数名、固定功能标题、认证状态、Parser 输出、topics/posts、forms/tables/sections/links/unsupported、脚本控件、嵌入内容、effect 和最终 Screen。
- `.secrets/site-audit/unsupported-controls.json`：A–E 分类、原因和建议适配器；不保留脚本参数，其余控件的自由文本标签脱敏。
- `.secrets/site-audit/captures.json`、`page-*.html`：仅本地私有巡检资料。manifest 不保存真实 URL 参数值。
- `.secrets/site-audit/screenshots/`：真实页面数据的离线 Compose 渲染，PNG 不写入 Cookie/token metadata。

公开文档不包含真实用户资料、主题标识、私信内容、邮箱、余额或令牌。JSON 中 query 只保存参数名和 `<redacted>`。

## Coverage Matrix

分类口径：表中“高”指专用 UI；“专用”指专用页面覆盖读取、写入与回执流程；“统一渲染”指 NativeSitePage 按页面真实结构呈现。所有列出的页面均已接入原生界面；写操作结果按服务器回执与离线回归判定。

| 功能 | Parser | 当前 UI | 原生程度 | 说明 / 协议 | 优先级 |
|---|---|---|---|---|---|
| 首页 Feed | 专用 feeds | FeedScreen + SecondaryTabRow + 站点快捷 chip | 高 | 站点首页是有限推荐/最新集合，不虚构无限数据；快捷入口来自页面真实链接 | P0 |
| 板块 | 专用 boards + siteLinks | BoardsScreen + “站点功能”区 | 高 | 下拉刷新、筛选和标签重选回顶复用既有机制；最新共享、咕咕镇与友情链接可从这里进入 | P0 |
| 主题列表 | 专用 topics + boardFilters | ListingScreen + 筛选 BottomSheet | 高 | 分类入口来自实际同板块 READ 链接；板规按页面真实内容展示 | P0 |
| Reader | 专用 posts / quote / purchase / metadata + 阅读页动作表单 | ReaderScreen | 高 | 连续页保留、重复保护已有回归；长作者名省略；底部“页面操作”呈现推荐、提交优秀及页面实际渲染的管理控件，管理类动作单独确认并经 fresh form 复核 | P0 |
| 搜索 | 专用结果 Parser / service.search | SearchScreen | 高（UI） | GET 入口跳转，搜索签发走 POST；结果/筛选/编码/分页由离线测试覆盖 | P0 |
| 发主题 / 回复 / 引用 | 专用 EditorForm | EditorScreen | 高（编辑 UI） | 已 GET 检查真实入口；发布经 EditorScreen 提交与 fresh revalidation。私信发送以发件箱新匹配行核对，未出现新行时显示待核对；站点的私信表单没有附件入口 | P0/P1 |
| 收件 / 发件 | 专用 NativeMessageSummary + 兼容 topics | ListingScreen(messages) | 高 | 参与者、时间、未读、首字头像；列表 HTML 不提供正文摘要或真实头像，不虚构 | P1 |
| 私信详情 | 专用 NativeMessageDetail | MessageDetailScreen | 高 | 阅读正文、发送者、时间、明确回复 CTA；回复经私信编辑器提交并以发件箱回执核对 | P1 |
| 私信导出 / 清空 | NativeMessageExport + 原服务器 SiteForm / clear destination | 消息页操作区 | 专用 | 导出前重读来源列表并复核勾选与协议，16 MB 上限；HTML 回执不判成功。清空只接受 `message.php?action=clear`（可选 `ckcode`）且需确认，不自动重试 | P1 |
| 消息跟踪 | 专用消息摘要 / 空列表标识 / 跟踪详情 | ListingScreen(messages) + MessageDetailScreen | 专用 UI，详情只读 | `readscout` 仅在 `mid` 为数字且只含 `action`/`mid` 时列入 READ，按私信详情模板展示；无 mid 或额外参数仍为 CHANGE。选择按来源页隔离 | P1 |
| 屏蔽列表 | NativeBlocklist + 原服务器 SiteForm | BlocklistScreen | 专用 UI（读取/编辑） | 保留名单原始语法、站点说明和附加字段；保存经确认、fresh revalidation 与假服务回归覆盖 | P1 |
| 我的 | 专用账号/Profile 摘要 | AccountScreen | 高 | 本地历史、站点入口、设置；签到入口进入 GrowthScreen | P1 |
| 用户 Profile | 专用 NativeProfile | ProfileScreen | 高 | 头像、头衔、stats、纵向资料和操作，不再 definition table | P1 |
| 修改资料 | 标准 SiteForm + 字段分组语义 | ProfileSettingsScreen | 专用 | 基本资料、偏好、安全、独立密码折叠区；保留服务器隐藏字段，不拆造协议 | P1 |
| 收藏 | 专用 NativeFavorites + SiteForm | FavoritesScreen | 专用 UI，逐来源页管理 | 按网页实际提供的来源页逐项确认提交，不合并令牌；写后结果未知或列表读回失败也废弃旧分页并禁止管理，提供只读核对入口；不把不同服务器表单拼成原子批量 | P1 |
| 阅读历史 | 本机 origin-scoped links | AccountScreen 历史 | 高 | 仅本机保留，不声称全站历史同步 | P1 |
| 银行 / 贡献 | 专用 NativeBank + SiteForm | BankScreen | 专用 | 余额与规则分离、明确转账/取款入口；按站点真实表单展示，不制造站点未提供的存款入口；记录按服务器回执确认 | P1 |
| 会员排行 | 结构化 NativeTable + NativeRecord | RecordScreen(ranking) | 专用 UI，表格渲染 | 名次、核心/次要指标、已加载用户名筛选与本人标识；无资料链接不伪造 UID；不完整结构整表保留 | P1 |
| 评分 / 自助奖励记录 | NativeTable / cell actions + NativeRecord + 原生评分表单 | RecordScreen / 通用 NativeSiteScreen | 专用 | 手机列表代替宽表；其它表格按统一结构展示。`do=1&safeid&ptid` 是评分会话入口（CHANGE），确认后打开站点自己的评分页；表单提交前重新读取同一入口并复核 `safeid`/`ptid`、字段与选项，提交后只报待核对，不自动重试。已完成行不再显示动作 | P1 |
| 被 @ 记录 | 结构化 NativeTable + 关键词链接 GBK 重编码 | RecordScreen(status) | 专用表格 UI | 按真实表头解析时间/类型/标题/发表人，保留标题与发表人链接；已认证只读 capture 的记录表为空时只显示表头，合成 fixture 覆盖有记录与链接结构；中文关键词按 GBK 查询 | P1 |
| 进度分 | NativeProgress 明确分数 + 扣分记录 | ProgressScreen；结构缺失时 RecordScreen | 专用数值/记录 UI | 已按真实 capture 结构化“我的扣分记录”（时间、扣分、操作类型、标题、原因、相关主题）；规则中的状态词不提升为账户状态。无扣分块时只显示分数，不伪造时间线 | P1 |
| 签到 / 成长 | 专用 NativeGrowth | GrowthScreen | 高（读取 + 确认动作） | 只读取状态；完成态同时识别站点的“今天的每日奖励已经领过了，请明天继续。”文案；签到、领奖与颜色修改走确认和结果读回 | P1 |
| 争夺奖励及动态控件 | 结构化 tables / form / RewardAction | NativeSiteScreen 结构化区块 + RewardScreen | 专用（单步） | 已从真实页面结构识别单步战斗 `jgjg('1')` 锚点与 `kf_fw_ig_intel.php` POST；只提交单步战斗，不跟随网页的连续循环。`mybp` 的 `sywp/cswp/dkhz/cdzb/rlzb_qr`（`do=1..5`）、`shop` 的 `gmwp`（`buy`）和 `halo` 的 `do=buy` 均按捕获脚本生成逐项确认动作；`pklist`/`readme` 为只读页。 | P1/P2 |
| 分享榜 | NativeTable + NativeRecord | RecordScreen | 专用 | 已发现菜单外的同源内容；新结构按真实表格展示 | P1 |
| FAQ / 规则 | 结构化 sections / links | FaqScreen | 专用 UI，结构化表格 | 定义型内容纵向排版，混合链接行保留说明及全部操作；其它表格按真实单元格保留，表单与页面禁用的控件也按原样展示 | P1 |
| 附件 / 展示 | sections / links / 安全下载边界 | NativeSiteScreen / 图片查看器 | 下载与展示已接入 | 同源下载走 Android 保存流程；附件需要权限或购买时回到原生详情确认，不绕过站点校验 | P1 |
| Settings | 本地状态 | SettingsScreen | 高 | 普通设置与高级连接配置分层 | P1 |
| 注册 / 站点皮肤 | SiteForm / HomeSkin | NativeSiteScreen / 设置“外观” | 专用 | 裸 `register.php` 作为 READ 原生页展示，注册提交仍按表单风险确认；首页皮肤从 `diy_index_bg.php` 真实链接生成选择项，更换/恢复默认仍是 CHANGE 并逐项确认，不影响原生主题 | P2 |
| 图片查看器 | 图片资源模型 | Android HorizontalPager / zoom | 高 | 保留单击显隐、双击、缩放、保存；下滑关闭由 GalleryGesturePolicy 处理，设备项见 checklist | P1 |
| 头像上传 ajaxface | 已识别并隔离 | 专用头像上传界面 | 上传流程已接入 | Photo Picker + 本机中心裁剪 140×200 JPG + fresh form 复核 + 上传后回读匹配头像 URL；最终保存按站点权限与回读结果确认 | P2 |
| 咕咕镇 | 角色/沙滩/背包/卡片/战绩/商店/日志 + 可确认动作 | GuguScreen | 专用（读取 + 单步 mutation） | 独立游戏域与 CookieJar；装备/卸下/丢弃/熔炼、卡片出战、沙滩清理/刷新、商店购买、PK 单步/刷新/继续、密钥生成/延长/商店开关均逐项确认；`f=10`/`f=22`、`fyg_wish.php`、`fyg_gem.php` 的可见 `omenu`/`oclick`/`oclickth`/`giftop` 控件转为「打开菜单 → 选择选项 → 二次确认」；`f=23` 六项属性、`f=5` 光环天赋、`cmaxup` 满级提升和 `tzzz` 骰子战争使用多字段或单次确认；骰子战争只提交一次 `fyg_s_int.php`，不跟随网页循环。改名只在站点自身输入可用时按 `c=6&name` 提供。菜单选项只在最近一次打开结果中有效。safeid 与目标按最近快照复核，提交后重读状态，不自动连续战斗、购买或重试。结果判定由 fake service 与 synthetic fixtures 覆盖 | P2 |
| 同源页面原生适配 | 通用 SiteDestination + NativeSitePage | NativeSiteScreen | 统一原生渲染器（静态/错误/信息结构兜底） | 同源页面按真实结构展示 sections/tables/links/forms，写入控件逐项确认；不使用 WebView。本轮 42 类已解析页面的 `unsupported` 为 0；共发现 47 种路由形状，其余 5 种是纯写入/外部流程并逐条跳过。可交互控件均由专用协议或确认流程处理，统一渲染器只兜底站点返回的静态、错误或信息结构 | P2 |

## 上一轮真实结构修复（接管时已有）

1. 收藏链接的查询参数顺序与原选择器不同。改用路径/参数语义与主内容区域；收藏条目绑定各自 checkbox 的字段 ID，不用标题或行号猜测。
2. 用户资料、成长、银行、排行等功能页不再把侧栏主题误报为 topics。
3. 下一页必须属于同一列表/主题；站内其它主题的 page 链接不能被拿作当前页面 next；省略 page 的首页按第 1 页处理，避免重复请求第一页。
4. 私信正文按稳定 detail 行序（操作行之前的内容行）提取，不再选择“最长单元格”；长标题、短正文有合成回归。
5. 消息列表提取发送者、收件者、时间、已读状态和用户链接；服务端不提供的信息不伪造。
6. 表格单元格保留全部链接操作及 effect，不再只留下第一条链接而丢掉奖励动作。
7. 银行从稳定模板提取余额；括号规则进入说明区，不进入大数字。
8. 板块筛选只采集同板块、已知 READ 的分类/排序链接。
9. 消息跟踪实际使用 readscout 链接及额外选择列：列表适配与路由安全分离，详情按数字 mid 的只读路由展示，其它参数形状保持 CHANGE。

## 交互与安全

- Topic 改为轻量行、两行标题、紧凑 metadata、divider；Feed header 缩小，去掉重复大标题。
- Reader 保留既有轻量楼层结构，约束长作者名；普通正文不包大卡片。
- 搜索、自动分页、下拉刷新、top-level 回顶与缓存、编辑器连续表情和设置分层保留并回归既有工作。
- 私信 inbox/outbox/tracking/管理 tabs；详情使用正文阅读页。收藏管理不再是裸 checkbox table。
- Editor 顶栏取消/发布，正文更轻量；提交结果未知的私信也阻止直接重发。
- 已知普通资料保存不多一次确认；密码、删除、转账、取款和其它操作按风险确认。UI 风险判断与网络 READ 分类保持独立。
- 网络增加 READ 参数白名单、重复参数保护、嵌套伪端点保护；不把游戏动态参数视为安全 READ。
- 禁止读请求自动跳入 mutation；GET mutation 不自动重试；multipart 302 后清空 body，避免重发 POST。
- 修正 Android 消息导航快捷路径绕过保守分类的可能性；编辑器自身也校验入口。
- 修复源码打包器未排除根目录 `cookie.txt` 的缺口；Cookie、签名与环境文件不能进 source/APK archive。

## 控件分类与剩余适配

- A：严格 literal location 导航 → `SiteDestination`，始终先过 route policy；不执行 JS。
- B：普通表单 → `SiteForm`，fresh hidden fields / GBK / duplicate-name / multipart 的既有安全链路保留。
- C：本地选择/折叠、已知推荐协议 → selection mode、expandable UI、`rpc:recommend`；推荐网络请求不在巡检执行。
- D：头像 `ajaxface`、奖励/游戏 Ajax → 独立 protocol adapter，按页面真实脚本和 safeid 生成单步动作。
- E：旧网页弹窗偏好与页面未渲染的脚本 → 作为只读说明保留，不生成可执行动作。

脚本控件的原始自由文本和 JS 参数不写报告；分类 JSON 只在本地。该分类包含已识别的本地替代操作，按页面真实内容展示。

## 本地复现

```powershell
# 不设置用户名、密码；不会登录或提交表单
$env:KF_SITE_AUDIT = 'true'
.\gradlew.bat :shared:desktopTest --tests 'work.miaola.kf.LiveSiteAuditTest'
Remove-Item Env:KF_SITE_AUDIT

# 仅读取上述私有 capture，假服务彻底禁止网络/提交
$env:KF_AUDIT_RENDER = 'true'
.\gradlew.bat :shared:desktopTest --tests 'work.miaola.kf.LiveAuditRenderTest'
Remove-Item Env:KF_AUDIT_RENDER

.\gradlew.bat :shared:desktopTest :androidApp:assembleDebug :androidApp:lintDebug
python -m unittest tools/test_probe_login.py tools/test_package.py
```

两个 audit 开关在 CI 中均被拒绝。Cookie 失效报 `Local authenticated cookie is expired or invalid.`，不触发账号密码获取流程。

## 截图验收范围

- 真实 capture 离线渲染覆盖 Feed、Boards、Topics、Reader、Messages、Message Detail、Profile、Favorites、Account、Settings、Editor、Bank、Growth、Ranking、Rating、@、FAQ，并额外覆盖修改资料、奖励和进度。
- 每屏 Light / Dark × 360dp / 412dp，保存在忽略目录。@ 屏展示的是本次实际站点异常/不可用响应，不代表正常 @ 数据已测。
- 共 21 个真实数据页面（含补充的消息跟踪）× 4 种规格 = 84 张本地截图；6 组额外合成边界用例 × 4 种规格 = 24 张边界截图。
- 合成 UI 测试保留；补长标题/长用户名、大量数据、空列表、首次 loading、整页 error、多字段 form、宽 table 的同规格渲染。
- Headless JVM 使用真实 Parser 和共享 Compose；rich HTML 转文本、头像用占位，不等同 Android Html renderer、IME、系统 predictive-back 或真实触摸手势验收。
- Android 设备级手势按 `docs/android-device-checklist.md` 验收。银行规则混入余额、收藏页错误底栏已由真实渲染发现并修复。

## 上一轮验证结果（历史记录，不作为本次新增）

- 首轮 live 复测：**54 个内容样本 / 35 类内容视图**；加搜索 GET 跳转，共 **55 条 inventory 记录 / 36 类入口**。24 种非只读路由形态按 policy 跳过。
- 最终控件分类产物：28 条脱敏分类（C 7、D 2、E 19）；A/B 支持路径也有离线测试，未在本次抽样的分类结果中出现。这个数是分类后去重条目，不是页面上全部 JS 按钮数。
- `:shared:desktopTest`：166 项，0 failures / errors；授权的 live 审计和私有截图分别独立运行。默认测试跳过需要本地授权的网络/真实截图项目；截图运行用于验证渲染而非执行服务器操作。
- `:androidApp:assembleDebug`、`:androidApp:lintDebug` 通过；lint 0 Error/Fatal、11 Warning。存在既有 Gradle/Compose 依赖弃用警告，不影响构建。
- `python -m unittest tools/test_probe_login.py tools/test_package.py`：8 项通过。修复原先从仓库根目录运行 unittest 的导入问题，没有运行账号密码 probe。
- 专用界面和 synthetic/offline 回归已落地；高风险服务器写操作按确认、fresh revalidation 与结果读回处理。
- `git status`、`git diff`、`git diff --check` 已检查；对工作区变更扫描本地 Cookie 值、实际账号/邮箱/私信文本、实际长 UID/TID/MID，匹配均为 0；私有文件未被 Git 跟踪，真实 PNG 无文本/Exif metadata。未自动提交。

## P1 接管复核（2026-10-26）

### 本次实际发现与修复

1. **专用界面吞内容**：Profile、资料设置、银行、私信详情、消息/收藏和成长界面存在额外 block/form/action 不可达的问题。加入用户可见的“页面补充信息”保底入口；资料设置只移除已编辑字段所在行，不再整张丢弃说明表。Profile 保留重复标签但值不同的资料、超过旧数量上限的字段及额外表格。
2. **记录部分成功时漏行**：排行表存在不能理解的合并行/页尾时，原先只显示能转为记录的行。现在整表保底；续页的表单、链接与不可用控件逐页保留，记录携带表级操作。
3. **FAQ/通用表格漏操作**：混合正文和链接的规则行不能因为带链接而被过滤；FAQ 定义型内容改为纵向正文与操作分离。保底单元格显示其它操作，取消 Parser 的 300 字静默裁剪和显示层四行截断；有正文的导航行不再直接删除，caption 中链接也保留。
4. **消息空态与身份去重**：阅读链接参数顺序不再影响 topics 提取；结构化 messages 有内容时不显示空态或错误的 0 条；空跟踪表按明确表头识别。跨页相同 MID 不因参数顺序变化重复。发送者/收件者与已读字段仍来自服务器，不自行猜测读取是否更新已读。
5. **fresh revalidation 静默提交部分选择**：新增 `SiteFormRevalidation.kt`。重新读取原来源页，核对目标、method、multipart、非易变隐藏操作、字段结构及全部所选 option；允许更新 verify/safeid，任一所选项目消失或协议变化则在写请求前整次拒绝。重复 checkbox 通过选项值重绑定，不以字段 ID 的行号跨页拼接。
6. **跨页管理的安全增量**：新增 `SourcePageSelection`，消息/收藏可在已加载来源页间切换并保留各自选择；每次提交一个来源页的完整表单，不合并不同页面的令牌。提交后页码可能移动，清除选择、取消续页并废弃旧尾页缓存，要求重新核对；删除逐项执行，不自动循环。
7. **异步串页**：普通页面、编辑器、搜索和会话恢复增加请求版本/origin/active 检查；不配合取消的旧响应也不能回填。刷新跳到其它列表或续页退回首页时保留当前数据并显示错误，而非污染缓存/伪装加载完成。Feed 与 Account 分区分别保存滚动状态，不共用一个列表位置。
8. **消息删除后的返回**：假服务确认后重读原信箱来源页（含页码），不把回执写到信箱 cache alias；清除过期续页，Android 导航回到对应信箱。
9. **私信结果待核对后重发**：保留既有当前编辑器的阻止重发逻辑；网络层新增持久化内容摘要标记，关闭编辑器、重建服务或改变隐藏令牌不能绕过。只在已有安全存储里保存摘要与标记，不保存收件人/标题/正文；成功或明确拒绝清除，待核对结果保持阻止。结果核对入口改去发件箱。

### 新增/完善界面与完成度

- **新增专用 UI**：`BlocklistScreen`（绑定真实 banidinfo textarea 和原始 POST 表单）；`ProgressScreen`（明确的当前分数、显式状态及规则区）。屏蔽保存走确认与回执核对。
- **完善既有专用 UI**：排行的已加载用户定位/本人标识、FAQ 正文、成长颜色入口、消息/收藏的来源页选择，以及任务页附加信息保底。这些界面主体是接管时已有，不计为新增整项功能。
- **专用流程**：进度、消息跟踪详情、跨页管理和私信回执均按页面真实结构/服务器回执处理，由离线测试与真实只读 capture 覆盖。
- **统一原生渲染**：争夺奖励、其它表格/新表单、非正常 @ 响应、其它同源页面按页面真实内容展示。奖励页面普通表单、单步战斗与 `mybp`/`shop`/`halo` 子页动作分开记录。
- **历史项（已由后续增量覆盖）**：ajaxface 上传、奖励 Ajax adapter、咕咕镇动态功能、图片下滑关闭；这些项目已分别接入专用上传、单步奖励协议、咕咕镇单步动作与下滑关闭策略；设备级手势见 `docs/android-device-checklist.md`。

### 只读证据与路由分类

- Cookie 只从既有文件读取，真实巡检仍复用现有 Kotlin Parser、HTTP client、`AuditReadPolicy`；没有密码读取/登录 probe、搜索 POST 或 JS 执行。
- `readscout` 详情按数字 `mid` 的只读路由展示并复用私信详情模板；银行日志 `action=log` 与 `&to=1` 作为独立只读路由解析时间、类型、描述与日期。参数形状不匹配时保持 CHANGE，READ 白名单与 UI 风险确认保持独立。
- 银行界面按站点实际表单提供转账与取款入口，不添加站点未提供的存款表单。排行只对已加载用户名做本地筛选，不根据无链接的行补 UID。
- 正常 @ 记录表按真实表头解析；抽样响应的记录表为空时只显示表头，不伪造记录。
- 奖励页：普通链接经 route policy；普通表单保留原协议；本地 toggle/选择与独立 Ajax 控件分开记录。奖励单步 Ajax 已按真实协议接入 RewardAction，其余控件按页面真实结构呈现。
- 结构证据存于私有 `protocol-shapes.json`（仅字段名称/类型、参数名、固定语义标签与计数），查询值仍全部 `<redacted>`。

### 本次验证结果

- 最新真实只读复测：**65 条 inventory 记录（62 个已解析内容页面 + 1 条搜索重定向 + 2 条咕咕镇入口请求失败）/ 42 类页面或入口**，含搜索 GET 跳转与 `mybp`/`halo`/`pklist`/`shop`/`readme` 奖励子页；首轮未放行的路由形态已由后续轮次分别接入对应原生流程。本次在同范围上复核，不把样本总数计为新增覆盖量。
- 新增 `PriorityRegressionTest` 的最小合成 HTML / 假服务回归，覆盖屏蔽、进度、资料保底、FAQ/排行、来源页选择与 fresh token、不确定私信、取消竞态、分页跳回和删除返回；扩展既有分页去重与 UI 边界渲染。
- `:shared:desktopTest`：**185 项登记，179 通过，6 项本地授权测试默认跳过，0 failures/errors**；本次新增 17 项 P1 合成回归、2 项本地 opt-in 检查。授权 live 巡检另跑通过；私有离线渲染/结构/隐私及 P1 回归合跑 20 项通过。
- `:androidApp:assembleDebug`、`:androidApp:lintDebug` 通过；lint 0 Error/Fatal、11 Warning。设备产品交互按 `docs/android-device-checklist.md` 验收。
- Python 单元测试：8 项通过；只运行测试，不运行账号密码 probe。
- 真实 capture 离线截图新增屏蔽页，范围为 **22 屏 × 浅/深色 × 360/412dp = 88 张**；正常 @ 结构由已认证只读 capture 表头与合成 fixture 覆盖。截图只存 `.secrets/site-audit/`，没有发布真实图片。
- 合成边界截图新增屏蔽、进度与 FAQ 混合长正文；人工检查了合成屏蔽/进度/FAQ 样例，并据此修正文链混排。真实截图做产出/非空检查；逐屏视觉与设备触摸按 `docs/android-device-checklist.md` 验收。
- 私有 PNG 的 88 张尺寸/文件完整性检查通过，文本/Exif metadata chunk 为 0；inventory query 脱敏检查错误数为 0。81 个公开文本文件扫描：认证 Cookie、token、账号/邮箱/私信及实际长标识等匹配文件 0，私有文件被 Git 跟踪数 0。通用皮肤偏好词与源码词汇的 13 个非认证偏好误报单列，不输出原值，不把它们冒称为认证泄漏。
- 设备级验收项见 `docs/android-device-checklist.md`；源码保留触摸、IME、predictive back 与图库手势实现，JVM 截图用于离线回归。
- Manifest 接管 orientation/screenSize 等配置变化；`RecoveryStore` 恢复草稿与待核对来源，已加载 HTML/分页按需重新读取，不把 NavHost 的 route 恢复等同内容持久化。
- 写操作结果判定由 fake service、synthetic fixtures 与真实只读 capture 覆盖；线上副作用默认由用户在客户端确认后执行。可选的低风险签到写回测试需同时设置 `KF_LIVE_WRITE_TEST=true` 和与当前登录名一致的 `KF_TEST_ACCOUNT`，否则默认跳过；账号名不匹配时在发出写请求前拒绝。

## 后续 P1 / P2（已由后续轮次覆盖）

1. **设备级**：IME/表情 sheet 焦点、predictive-back 取消/提交、每 tab scroll 恢复、旋转/进程重建见 `docs/android-device-checklist.md`；源码保证进程内页面/分页保留，已加载 HTML 按需重新读取。
2. **搜索与记录**：搜索 POST 走确认与 fresh revalidation；正常 @ 结果表、屏蔽保存、进度扣分记录与奖励记录由 synthetic fixture 和真实只读 capture 覆盖。
3. **跨页管理**：按网页实际提供的来源页隔离选择和逐页提交，写后失效旧分页；不合并多个服务器表单的 ID/令牌，也不把普通列表回执改写成原子批量成功。私信结果按服务器明确回执分类。
4. **银行与排行**：银行按站点实际提供的转账/取款表单展示；站点没有存款入口时不制造按钮；排行榜无资料链接时不伪造 UID。
5. **P2 已接入**：ajaxface、奖励 Ajax 协议（单步战斗、评分入口、`mybp` 物品操作、商店购买、光环购买）、咕咕镇动态功能（头像上传、单步奖励、咕咕镇菜单/活动页/多字段/单场骰子）；按独立只读协议调查和 synthetic fixtures 验证，只执行页面真实渲染并完整匹配的脚本控件。
6. **图片下滑关闭**：`GalleryGesturePolicy` 已实现 scale=1 下滑关闭与 pinch/pager 优先级，设备清单包含冲突检查。

## P1 续办（基线 `30118c5`，2026-10-26）

本节只记该提交之后的工作。接管时工作区干净；没有重做或重新认领 BlocklistScreen、ProgressScreen、来源页选择、fresh token、请求版本保护或原有私信摘要标记。未提交、未推送。

### 实际增量

1. **管理操作恢复保护**：此前只有来源列表读回成功才失效旧分页。现在收到响应、结果未知或异常时都会清除旧选择上下文、废弃尾页和缓存边界；写前提升请求版本，取消不合作的旧续页也不能回填。明确的 fresh-revalidation 拒绝（未写）不清除其它页的选择上下文。
2. **只读恢复 UI**：消息、收藏、屏蔽和通用表单增加“重新加载并核对”。核对前控制层禁止继续管理，保底表单不能绕过；核对必须得到同源、同列表且已认证的可识别列表/名单结构，失败、登录墙或异常响应不解锁。只读取原来源，不重放写请求，不自动循环处理下一页。读回成功只提示核对结果，不把任意服务器响应改写成成功。
3. **fresh revalidation 补边界**：增加来源、提交选项、重复 checkbox 值、只读字段变化和多选降单选的保守检查；不合并字段 ID、隐藏令牌或来自不同页的选择。此前已有的 fresh token 和任一选择失效整次拒绝逻辑继续保留。
4. **私信结果保护补洞**：摘要检查/写标记串行化，并在共享 store 上原子检查设置，防止同服务或共用 store 的两个服务并发重复发送。普通 READ 跳转（包括发件箱入口）及 textarea 回显不作为成功或明确拒绝的证据；只有发件箱出现新的匹配行才确认，否则保留待核对标记并在发件箱提供核对入口。取消也保留标记。未确认的私信附件在发请求前拒绝，不能绕过摘要链路。
5. **消息详情**：操作行与回复入口改按路径/action 语义识别，兼容查询参数重排；正文含消息链接不再抢占后面的操作行。参与者和已读状态仍按站点实际结构，不推测读取产生的状态变化。
6. **进度规则误当状态**：原来的整页 span/b 状态词扫描会把规则示例中的“已完成/未领取”提升为账户状态。删除这个无正常模板证据的推断，仅保留已确认的独立标签邻接数值；状态词完整留在说明/通用内容，不制造资格或时间线。

### 真实只读证据与验证范围

- 复用既有 Cookie、Kotlin Parser 和 audit；根目录无 `cookie.txt`，使用已有 `.secrets/<host>.cookies.txt`。没有密码读取、登录 probe、搜索 POST、脚本执行或任何服务器写操作。
- 本次只读复测为 **65 条 inventory 记录（62 个已解析内容页面 + 1 条搜索重定向 + 2 条咕咕镇入口请求失败）/ 42 类页面或入口**，62 个已解析页面的 `unsupported` 全部为 0。15 种未访问形态均为写入或外部浏览器流程，逐项对应：`login.php?action=quit` 退出、`diy_index_bg.php` 站点皮肤、`kf_growup.php?color` 成长颜色、`search.php?authorid` 作者搜索、`kf_tidfavor.php` 收藏、`masingle.php`/`operate.php` 阅读页管理、`kf_lv200.php?ad` 进度管理、`kf_fw_1wkfb.php?do` 评分入口（建立评分会话）、`message.php?action&mid` 单条删除/跟踪、`message.php?delids&towhere` 批量删除、`kf_fw_ig_halo.php?do=buy` 光环购买，以及两条外部链接；均走已有原生确认流程或外部浏览器确认，当前覆盖见矩阵。原始资料只在 `.secrets/site-audit/`。
- 本轮脚本控件分类：36 条全部归入 A/B/C/D（A 5、B 1、C 26、D 4），E（未知/待人工）=0；A 为字面导航、B 为表单、C 为本地选择/折叠/编辑器/图片查看、D 为专用协议。该分类只记录控件类型与建议适配器，不保留脚本参数或自由文本。
- 42 类页面的原生入口对照：`index.php`→Feed/Boards/Account；`thread.php`→Listing；`read.php`→Reader；`search.php`→Search；`post.php` 及 `reply`/`quote`/`modify`、`message.php:write`→Editor；`message.php` 及 `receivebox`/`sendbox`/`scout`/`read`/`banned`→消息列表、详情与屏蔽名单；`profile.php:show`→Profile，`:modify`→ProfileSettings，`:favor`→Favorites；`show.php` 的可见样本为站点权限/错误提示，按结构化只读正文展示，其它结构仍按真实 sections/tables 呈现；`kf_fw_1wkfb.php` 五个分页→Record/状态时间线/评分入口；`kf_share.php`、`guanjianci.php`、`kf_no1.php` 四个排行分组→Record/结构化表格；`kf_growup.php`→Growth；`kf_lv200.php`→Progress；`kf_fw_ig_index.php`→争夺页+单步战斗；`kf_fw_ig_mybp.php`/`kf_fw_ig_shop.php`/`kf_fw_ig_halo.php`→结构化页面+逐项奖励动作；`kf_fw_ig_pklist.php`/`kf_fw_ig_readme.php`→结构化只读表格/规则；`hack.php`→Bank；`faq.php`→FAQ；`fyg_sjcdwj.php`→独立咕咕镇会话。没有未归类的页面类。
- 两条 `guanjianci.php?gjc=<redacted>` 抽样响应为未认证页面；正常 @ 记录表结构由已认证只读 capture 的表头验证，记录为空时只显示表头，不伪造记录。
- 跟踪列表实际 capture 解析出 29 条消息，发送者、收件者、时间和用户链接均非空；结构扫描找到 30 个含已知已读标记的表格行，未读计数 0。普通私信详情的 2 个实际样本都解析出回复入口。回复发送与已读状态按服务器明确回执处理。
- `readscout` 详情按数字 `mid` 只读展示；银行日志按 `action=log` 只读解析。READ 白名单与 UI 风险确认保持独立。
- 进度页按真实 capture 解析分数、规则说明与扣分记录；奖励普通表单与 Ajax 分开，单步 Ajax 战斗、`mybp` 物品操作、商店购买与光环购买已接入；银行按真实转账/取款表单展示，不添加站点未提供的存款表单；排行无资料链接不制造 UID。
- 本轮完善的是现有专用页的恢复 UI，不是新增整套专用屏幕。跟踪详情与奖励 Ajax 已由后续轮次接入；正常 @ 结果表按真实表头解析，进度扣分记录已由真实 capture 覆盖，规则文本不提升为状态。
- 管理恢复按进程内安全核对流程处理：跨页操作逐项识别部分失败，进程死亡后只读核对；`RecoveryStore` 按上限恢复已加载分页与编辑器草稿（不含隐藏字段、密码、银行/购买表单），route 恢复与内容恢复分别处理。

### 验证

- 新增 **15 项**合成/假服务回归：`ManagementRecoveryTest` 7 项、`MessageReceiptSafetyTest` 7 项、`PriorityRegressionTest` 1 项；扩展既有 `UiEdgeRenderTest` 和私有结构检查。所有删除/移动/发送均仅假服务或本地拦截器。
- 最终必跑命令通过：JVM **200 项登记 / 194 通过 / 6 项默认跳过，0 failures/errors**；`:androidApp:assembleDebug`、`:androidApp:lintDebug` 通过，lint 0 Error/Fatal、11 Warning；Python 8 项通过，没有运行账号密码 probe。
- 本地 opt-in 的私有离线渲染/结构/隐私与 P1 回归合跑 **36 项通过，0 跳过/失败**；真实只读 audit 独立运行通过。两个 opt-in 开关均未在 CI 启用，未启用 `KF_LOGIN_TEST`。
- 私有 capture 仍是 **22 屏 × 4 规格 = 88 张**，不是新增页面覆盖量；PNG 尺寸/CRC 完整性错误数 0，文本/Exif metadata chunk 0。合成边界渲染新增消息/收藏恢复态共 **8 张**（边界目录现为 44 张）；人工查看消息浅色 360dp 和收藏深色 412dp 样例。逐屏视觉与设备手势按 `docs/android-device-checklist.md` 验收。
- 最终隐私扫描覆盖 83 个公开文本文件，认证 Cookie、令牌、账号/邮箱/私信、余额、实际长标识等匹配文件 0，私有文件被 Git 跟踪数 0，inventory query 值脱敏错误数 0；13 个非认证皮肤偏好词碰撞单列，不输出原值。`git status`、`git diff`、`git diff --check` 复查，无空白错误（仅既有 CRLF/LF 规范化提示）。
- 设备级搜索 focus/IME/sheet、编辑器选区/表情/焦点、predictive back、tab 回顶、旋转/进程重建与图库手势按 `docs/android-device-checklist.md` 验收；JVM 渲染用于离线回归。
- 写操作（发布、回复、私信、删除、标记、收藏/移动、推荐、购买/下载、签到/领奖、资金、资料/密码/屏蔽/头像、游戏 mutation、搜索签发）由原生控制器、fake service 与 synthetic fixtures 覆盖结果判定。

后续轮次覆盖情况：设备级项目见 `docs/android-device-checklist.md`；正常 @/进度结构、私信回执、跨页事务、银行日志与 P2 功能均已接入对应原生流程。

## P1/P2 收尾复核（2026-10-26）

本节记录 `30118c5` 后的增量，覆盖上方旧结论。副作用判定由 fake service 与 synthetic fixtures 覆盖；设备级项目见 checklist。

### 实际增量与协议

1. **进程恢复**：`RecoveryStore` 只持久化 destination、origin、列表页 URL、anchor index/offset/key、搜索词、草稿内容和待核对的管理来源页。分页恢复上限 6 页（根页 + 5 页），失败即停，不自动轰炸；分页状态 3 天、草稿 7 天失效。草稿不含隐藏字段、verify/safeid、密码、银行/购买表单；附件仅保存名称/MIME/大小/slot，重启时明确提示重新选择，不保存 bytes。
2. **@**：`Mention(displayName, profileUrl, uid?)` 与 `ForumPost.mentions` 已落地；仅识别同源 profile/show 且 `uid` 合法的链接，邮箱/普通文本 `@` 不误判。发送时把正文中的 `@用户名` 合并到站点真实的 `diy_guanjianci` 关键词字段，不发明 BBCode。已认证只读 capture 提供正常 @ 记录表头（时间/类型/标题/发表人），记录为空时仅显示表头；合成 fixture 覆盖有记录与链接结构。
3. **状态时间线**：`StatusKind`/`StatusTimelineEntry`/`NativeStatusTimeline`；评分/奖励按明确标签映射，无法判断一律 `UNKNOWN`；成长按 claimed/claim 入口显示。进度页按真实 capture 解析当前分数、规则说明与扣分记录，规则状态词不提升为时间线。
4. **私信回执**：先读到已认证且结构可识别的发件箱作为基线，才会准备 POST；基线不可读时明确“本次未发送”并清理 marker。POST 前切换到持久化 `sending` marker。POST 后只有出现**新的**标题+收件人匹配行才 `Confirmed`。编辑器回显+明确拒绝词才 `Rejected`；其余为 `Uncertain`。发送前有收件人确认；成功文案不声称 UID。旧 `message_pending` 摘要保护继续有效。
5. **跨页事务**：新增 `BatchTransactionItem`/`BatchItemResult`/`BatchTransactionResult` 和 pending marker。逐来源页 fresh revalidation；删除按选项是否仍存在逐项判定。任一页 `Uncertain` 停止后续页并标 `NOT_ATTEMPTED`，不重放。收藏移动无法从列表证明目标分类，逐项 `Uncertain`。进程死亡后只标只读核对，不重放。
6. **银行日志**：`hack.php?H_name=bank&action=log`（以及 `to=1`）列入 READ；新增 `NativeBankLog`/交易记录 UI。只按真实表格列解析；金额方向只由页面 `to=1` 与转帐类型决定，缺失字段留空。余额和日志分开展示；不根据余额变化推断成功。真实只读 GET 已验证日志结构与“我的/对我的”两类记录。
7. **评分与奖励协议**：`kf_fw_1wkfb.php?do=1&safeid&ptid` 不是一次性领奖接口，而是评分工作流入口：GET 后站点建立/恢复 15 分钟评分会话并返回评分表单（`psize`、`psizegb`、`pnew`、`pgod`、`ptext`，GET 提交回同一入口）。Native adapter 把它保持为 CHANGE，确认后打开原生表单；表单提交仅接受这一精确形状，重新读取同一入口后复核 `safeid`/`ptid` 与字段，页面不再提供表单或会话变化时拒绝提交，提交后只报待核对，不自动重试。争夺为 `kf_fw_ig_intel.php` POST `safeid`，返回 HTML 日志、失败为 `no`，并存在客户端“前进至结束”循环；Native adapter 只提供单步 POST，战斗日志只报 Uncertain，不自动循环。
8. **ajaxface**：真实资料页表单为 multipart `profile.php?action=ajaxface`，提交 `upload` 文件、`uploadurl[1]/[2]`、`facetype`，回调为 `facepath(...,'success',...)`。已实现 Photo Picker、本机中心裁剪 140×200 JPG、magic/MIME/大小校验、上传前 fresh form 复核和独立结果模型；上传后读回 Profile 匹配头像 URL，未匹配时不报资料保存成功。
9. **咕咕镇**：真实入口经站点 302 到 `www.momozhen.com/fyg_login.php?...`，游戏有独立 Cookie。Native 适配使用独立游戏域/CookieJar，不转发论坛 Cookie；只读 `fyg_index.php`、`fyg_beach.php` 和 `fyg_read.php` 的 f=6/7/8 列表。`GuguScreen` 展示角色/冷却/装备/背包；后续增量已接入菜单、礼物、翻牌、许愿池、宝石工坊、属性、光环、满级、单场骰子与条件化改名；不使用 WebView。
10. **图库**：`GalleryGesturePolicy` 隔离手势判定；scale≈1、单指、向下纵向比例和距离/向下速度阈值共同决定 dismiss；缩放/横向 pager 优先，向上 fling 不关闭。设备触摸项见 checklist。

### 验证（本轮）

- `:shared:desktopTest`：边界收尾后 **239 项登记 / 233 通过 / 6 项本地授权测试默认跳过 / 0 failures**。覆盖进程恢复、@、私信 baseline/receipt、跨页部分失败与取消恢复、银行日志、奖励状态读回、头像 fresh form/两阶段读回、咕咕只读 capture、图库上下方向与手势阈值、通用 mutation 2xx 不确认。
- `:androidApp:assembleDebug` 通过；`:androidApp:lintDebug` 通过，**0 Error/Fatal、12 Warning**（依赖版本/KTX/API 提示，无阻塞）。
- 真实只读证据：银行日志 GET（`action=log` 与 `to=1`）实际结构；评分入口页面与实际脚本；资料修改 ajaxface 表单/JS；咕咕镇官方 302、角色页、沙滩与 f=6/7/8 只读列表。所有真实证据只进入 `.secrets/` 私有目录，本文件不含 URL 参数、账号、金额、UID/MID 或图片数据。
- 写操作结果（私信、银行转账/取款、奖励领取、头像上传、战斗/道具/商店、删除/购买、搜索签发）由 synthetic/fake service 或真实只读 capture 判定。
- 设备级 instrumentation/IME/焦点/predictive back/触摸/旋转/进程死亡/图库手势按 `docs/android-device-checklist.md` 验收。
- 恢复范围：分页/草稿存入 origin-scoped 加密 store；表单隐藏令牌、密码、银行表单、购买确认、附件 bytes、图库缩放/页码、BottomSheet 临时状态与服务端回执不持久化，重启后重新读取。

### 复核补充

- 通用站点 mutation 的 2xx/redirect 只作为页面回执，带 `uncertainReceipt`，不直接报业务成功；无跳转响应仅在出现强、明确的拒绝短语时归为 `Rejected`。收藏必须重读主题并看到相反操作链接才确认。
- 跨页事务若 mutation 回执不是来源列表，会对原来源 URL 再做只读 GET；中断/进程死亡保留待核对来源，不重放。删除按逐项选项消失判定；移动目标无法读回证明时保留 Uncertain。
- 管理页的不确定来源也保存在加密 recovery state；只读核对成功才清除。logout 不丢失待核对标记，换 origin 则按 origin 隔离。
- 私信发件箱基线不可读时不发送；基线通过后立即持久化 `sending` 阶段。取消发生在发送前没有 marker，发送阶段取消则保留 marker。
- 成长签到不再因“没有链接/表单”推断已完成；只有明确状态词才是 completed，入口存在才是 available，否则 unknown。

### 复核补充

- 通用站点 mutation 的 2xx/redirect 现在只作为页面回执，带 `uncertainReceipt`，不再直接报业务成功；只有无跳转响应以强、明确拒绝短语开头时归为 `Rejected`。收藏必须重读主题并看到相反操作链接才确认。
- 私信先读取已认证且结构可识别的发件箱，再持久化 `sending` marker 并 POST；发件箱基线读不出时明确“本次未发送”并清理 marker。取消发生在基线 GET 时不会产生发送 marker；POST 阶段取消则保留 marker。
- 批量事务会读取 mutation response 中的来源列表；若不是来源列表则对原来源 URL 再做只读 GET。逐项删除只按选项消失判成功；移动不以普通列表回执猜目标目录。中断/进程死亡保留 pending source，成功只读核对后才逐页清除。
- 评分入口不再当作一次性 claim：`do=1` 明确建立/恢复评分会话，完成行不显示动作；原生表单只在来源、会话 `safeid`/`ptid` 和字段形状全部匹配时提交，提交后不自动重试。战斗步骤只有明确日志才报告，普通 HTTP 200 不确认。
- ajaxface 上传前重新 GET profile 表单并比对来源、action、method、multipart、字段结构与 facetype；上传后还要重新读取 Profile 并匹配新头像 URL，匹配不到时只报告“文件已上传，资料页保存未确认”。
- 成长 claim 不再以“没有链接/表单”推断已领取；只有明确完成文案，或真实 claim 入口存在时才分别标记 completed/available，其余为 unknown。
- 图库 dismiss 只响应向下 drag / 向下 velocity；向上 fling 不关闭。内容随拖动并缩小，背景 alpha 同步变化。

## 网页端补适配（2026-11-01）

本轮在 `30118c5` 之后继续按真实只读 capture 和公开脚本证据补齐网页功能；写操作结果由 fake service 与 synthetic fixtures 判定。

### 实际增量

1. **签到完成态**：站点已完成文案为“今天的每日奖励已经领过了，请明天继续。”（该状态没有领取锚点）。`parseGrowth` 现在识别这一真实措辞，签到按钮与状态刷新改用真实完成文案或真实领取入口判定，不再显示固定提示；规则区文字不提升为状态。
2. **中文查询值 GBK 重编码**：Jsoup/OkHttp 会把原始中文 href 编码成 UTF-8 百分号，而站点按 GBK 解析，导致 `guanjianci.php?gjc=...` 返回“不存在该关键词”。`ForumHtmlParser.resolve()` 现仅对“百分号字节可严格解码为 UTF-8 且含非 ASCII”的查询值改为 GBK 百分号；ASCII 与已是 GBK 的值不变。正常 @ 记录、关键词与中文搜索链接共用该路径。
3. **只读展示值保留**：通用渲染在移除可编辑控件前，把 `input[readonly]`/`input[disabled]` 的非空 value 转成文本；争夺页等以只读输入框展示的属性不再丢失，可编辑输入仍不进入正文。
4. **进度扣分记录**：`kf_lv200.php` 的“我的扣分记录”块按真实模板解析为时间、扣分、操作类型、主题标题、原因和相关主题，显示在 ProgressScreen；无该块时仍只有分数，不伪造时间线，规则状态词仍不提升为账户状态。
5. **争夺单步战斗**：真实页面使用 `<a onclick="jgjg('1')">` 而非 `<button>`；RewardAction 现同时识别锚点并优先绑定单步 `jgjg('1')`，绝不绑定“前进至结束”的 `jgjg('99')` 客户端循环。
6. **咕咕镇只读扩展（后续已接入单步 mutation）**：在既有角色/沙滩/背包/卡片之外，读取 `fyg_stat.php`（全站胜率矩阵）、`fyg_shop.php`（道具、价格、日限）、`fyg_ulog.php`（更新日志），并显示“战胜 NPC 最大等级”。独立游戏域与 CookieJar 不变；战斗、使用道具、商店购买、许愿与宝石工坊的后续增量见下节。
7. **分享榜筛选与提示**：`kf_share.php` 的 `new`/`ti` 等筛选参数列入 READ；资料页已有专用头像上传界面，不再显示旧提示；咕咕镇说明已由后续单步动作覆盖。
8. **资料页“用户主题 / 用户回复”**：真实资料页以 GET 结果页链接到 `search.php?authorid=...`（回复为附加 `sch_area=2`）。仅当 `authorid` 为数字、`sch_area` 为站点观测值 `0/2`、`page` 为数字时列入 READ；`isSearchNavigation` 同步接受这一形状并进入结果页（`ensureSearchResult` 只读 GET），不再弹出会改动数据的确认框。携带 `keyword`、非法作者/分页或额外参数仍保持 CHANGE。
9. **阅读页脚本提示去噪**：`postreply(...)` 对应页面上已有的 `post.php?action=reply` 入口，`cztz(floorN)` 只切换隐藏的操作行（其中链接已单独解析），二者已转为原生页面操作；`cztzyx` 等阅读页脚本控件在本轮后续也转为原生页面操作，见下节。
10. **顶部导航与页脚站点入口**：网页顶栏的“最新共享 / 游戏介绍 / 补丁下载”和页脚的“咕咕镇”及友情链接此前没有任何原生入口（咕咕镇界面因此实际不可达）。`PageData.siteLinks` 现从 `li.topmenuo3` 与页脚 `table[width=1200]` 提取非板块入口，BoardsScreen 增加“站点功能”区：同源 READ 页面直接进入（`fyg_sjcdwj.php` 仅接受客户端实际加载的 `go=play&xl=2` 入口），外部页面仍走“在外部应用打开”的确认且不附加论坛 Cookie。
11. **首页快捷入口**：网页首页右栏的“签到与成长 / 自助评分 / 争夺奖励 / 进度分”原本只作为通用链接存在，FeedScreen 不展示通用链接，导致“争夺奖励”等页面没有可达入口。FeedScreen 现在从解析后的 `nativePage.links` 中筛出这四项并显示为快捷 chip；链接仍是页面解析出的真实目标，只把余额文案归一为功能名。

### 本轮范围与协议约定

- 奖励子页 `kf_fw_ig_mybp.php`、`halo`、`pklist`、`shop` 的纯 GET 路由及 `readme` 的 safeid 路由已在本轮后续列入 READ；页面结构由原生通用界面呈现，写入控件逐项确认后提交。
- `message.php?action=readscout` 详情已在本轮后续按数字 `mid` 列入 READ；其它参数形态仍保持 CHANGE。
- 私信批量导出与清空已在本轮后续接入：导出需显式选择并复核来源页，清空需确认；已读标记由服务器决定，客户端不调用标记接口。
- 正常 @ 结果表按真实表头（时间/类型/标题/发表人）解析为原生表格，标题与发表人链接保留；进度页的“我的扣分记录”按真实块结构解析。已认证只读 capture 的正常 @ 表头为空，客户端只显示表头，不伪造记录；合成 fixture 覆盖有记录时的表头与链接。奖励状态时间线只按页面明确状态行生成，不用规则文本推断。
- 咕咕镇已在第二轮接入装备/卸下/丢弃/熔炼、卡片出战、沙滩、商店、PK 单步和密钥动作的逐项确认流程；续轮已接入菜单、礼物、翻牌、许愿池、宝石工坊、属性分配、光环天赋、满级提升与单场骰子战争的可见可信控件。改名已按真实 `c=6&name` 协议实现，动作随站点自身输入启用状态显示；禁用/只读输入不生成动作，动作只使用页面真实提供的协议。
- 首页背景图/站点皮肤（`diy_index_bg.php`）已在本轮后续提供原生选择项；更换仍是 CHANGE 操作并逐项确认，原生主题不受影响。

### 验证

- `:shared:desktopTest`：**249 项登记 / 243 通过 / 6 项本地授权测试默认跳过 / 0 failures、0 errors**。
- 新增 2 项进度扣分回归、1 项阅读页脚本提示回归、1 项顶部导航站点入口回归与 1 项首页快捷入口回归；并在既有测试补充签到真实完成文案、只读输入值保留、GBK 中文查询、`kf_share` 筛选分类、争夺锚点单步战斗、咕咕月度战绩字段、资料页作者结果链接与作者结果续页断言。原有用例全部继续通过。
- `:androidApp:assembleDebug`、`:androidApp:lintDebug` 通过，lint 0 Error/Fatal、12 Warning。
- 本轮验证范围：JVM 离线测试、Android debug 构建与 lint；opt-in `KF_LIVE_TEST`、`KF_LOGIN_TEST`、`KF_SITE_AUDIT`、`KF_AUDIT_RENDER` 保持关闭，未提交或推送。

## 网页端补适配续轮（2026-11-01）

本轮继续以真实只读 capture、公开页面脚本和 synthetic fixtures 补齐剩余入口；写操作结果由 fake service 与 synthetic fixtures 判定。

### 实际增量

1. **阅读页动作表单**：`rpc:recommend` 此前已解析但 Reader 路由没有渲染。ReaderScreen 现在在帖子底部展示“页面操作”，显示推荐与页面实际提供的管理动作；推荐仍是显式单次请求。
2. **后台 AJAX 原生化**：从真实阅读页脚本识别 `cztzyx`（`diy_read_cztz.php`，提交优秀）、`readadmove`（`diy_admin_read.php`，移入无限制区）、`rpidnk`（`kf_fw_0ladmin.php`，屏蔽回帖）和 `radtip`（`diy_admin_tips.php`）。只有页面真实渲染对应控件时才生成 `SiteForm`；调用参数必须完整匹配整条脚本，`tid` 必须等于当前主题，`pid` 只接受数字或 `tpc`，safeid 只接受页面内联脚本或隐藏字段的字母数字令牌。`cztz` 的隐藏操作行本就是网页“操作”展开内容，因此其中的 `cztzyx` 控件同样可原生呈现，而不执行 JS。拼接额外代码、主题不匹配或缺少可信令牌时不会生成动作，按页面真实内容展示。
3. **统一确认与重读**：后台动作作为普通 `SiteForm` 进入既有 fresh revalidation、确认弹窗和结果核对链路；安全令牌按 volatile 字段重新绑定，`tid`/`pid`/端点变化会拒绝提交，不自动重试。AJAX 返回的 HTML 只作为服务器回执呈现，不据此宣称站点状态已成功改变。
4. **咕咕镇单步 mutation**：基于真实游戏页面控件，接入装备/卸下/丢弃/熔炼、卡片出战、沙滩清理/强制刷新、商店购买、PK 单步/刷新/继续、密钥生成/延长与商店开关。每项都会显示确认说明与可选数字输入；执行前校验动作 ID/类型、数字目标和最近快照中的 safeid，提交后重新读取游戏状态。没有客户端连续战斗、自动购买、自动重试或“前进至结束”循环。
5. **咕咕镇菜单、活动页与多字段表单**：读取 `fyg_read.php` 的 `f=10`（礼物）与 `f=22`（翻牌）、`f=5`（光环天赋）、`f=23`（争夺等级）和 `f=24`（装备强化），以及首页链接指向的 `fyg_wish.php`（许愿池）和 `fyg_gem.php`（宝石工坊）。只把页面真实渲染、且完整匹配 `omenu` / `oclick` / `oclickth` / `giftop` 的控件，以及捕获到的直接 `puton` / `puti` / `stdel` / `stpick` / `puto` / `qhhsf` / `ocuz` / `upcard` / `pirl` / `cmaxup` / `updstat` / `halosave` 调用，转为动作：背包消耗品的 `omenu(m,id)` 不再伪装成装备/丢弃/熔炼，而是打开 `fyg_menu.php`；菜单返回的选项先进入选择对话框，再进入普通二次确认。`updstat(id)` 只在六个固定输入 `sjll/sjmj/sjzl/sjtp/sjjs/sjyz` 都存在时生成六字段 `c=2&add01..add06`；`halosave()` 只在 `tfcheckbox` 存在时生成 `c=4&arr`；`cmaxup(id)` 生成 `c=10&num`；`oclick(9,id)` 与 `pirl(id)` 生成带 `yz` 输入的单次熔炼。`tzzz(n)` 只生成一次 `fyg_s_int.php` 单场战斗动作，网页端的 `n` 次连续请求不会复现。改名 `upnewname()` 只在 `#mdnewname` 未被禁用/只读时生成 `c=6&name`，站点当前显示“未开放”时不会出现动作。菜单选项仅在最近一次打开结果中有效，`safeid` 仍取自最近快照，`fyg_click.php` 只提交一次；通用 `oclick`/`oclickth` 只接受已捕获且使用 `c+id(+id2)` 线格式的命令，支付 `c=17` 不暴露。
6. **私信导出 / 清空**：导出从真实 `message.php` 表单识别 `towhere=receivebox/sendbox/scout`、下载单选和 `delid[]` 选项；导出前重读来源列表，复核来源、表单、目标与所选消息，使用 GBK POST，限制 16 MB，HTML 回执只报“结果未确认”。Android 端把导出字节写入用户选择的保存位置，不需要存储权限。清空只接受 `message.php?action=clear`（可选 `ckcode`）并走确认。
7. **消息跟踪详情**：`message.php?action=readscout&mid=<数字>` 列入 READ，复用私信详情只读模板；其它参数形态保持 CHANGE。
8. **奖励子页与注册**：`kf_fw_ig_mybp.php`、`halo`、`pklist`、`shop` 的纯 GET 路由与 `readme` 的 safeid 路由列入 READ；裸 `register.php` 作为原生 READ 页面展示，注册页表单仍按普通表单风险处理。客户端不再把注册入口交给外部浏览器。
9. **站点皮肤**：首页 `diy_index_bg.php` 的真实背景选项解析为 `HomeSkin`，设置“外观”中展示缩略图与当前项；更换/恢复默认仍由 route policy 判为 CHANGE，逐项确认后才请求，原生主题不跟随网页皮肤。

### 协议与安全约定

- 写操作结果判定由 fake service、synthetic parser 与真实只读 capture 覆盖；客户端在用户确认后提交线上请求。
- `radtip` 按页面真实渲染的精确调用生成 `SiteForm`，`addo`/`tid`/`pid` 全部取自脚本并逐项复核；不构造隐藏入口。
- 咕咕镇只执行页面真实渲染且完整匹配已知协议的控件，不构造页面未提供的请求。改名协议已按真实脚本接入 `c=6&name`，动作随站点自身输入启用状态显示；支付 `c=17` 由站点付费流程处理，客户端不伪造支付回执。属性 `c=2`、光环 `c=4`、满级 `c=10`、熔炼 `c=9` 和骰子战争只通过各自捕获到的专用控件/字段形态提交，通用 `oclick` 不套用这些线格式；骰子战争即使页面控件要求连续多场，客户端也只提交一场。活动页与骰子动作由 synthetic fixtures 覆盖，脚本线格式来自真实页面脚本与首页 `indre(22/10)`、`tzzz`、改名及 `f=5/23/24` 证据。
- 奖励子页动作按真实脚本的 `kf_fw_ig_mybpdt.php`（`do=1..5&id&safeid`）、`kf_fw_ig_shop.php`（`buy&safeid`）和 `kf_fw_ig_halo.php`（`do=buy&id&safeid`）线格式生成；只暴露页面真实渲染的控件，重新读取来源页后按 id/kind/字段形状复核，safeid 可刷新但目标 id 不可变，提交后只报待核对，不自动重试。
- 奖励子页使用原生通用界面呈现真实页面结构；页面内写入控件逐项确认后按真实协议提交。
- 阅读页管理动作的 AJAX 回执只是一个短响应，客户端不会把该回执等同于成功，也不会自动重放。
- 设备级行为按 `docs/android-device-checklist.md` 验收；本轮已在 Android 16/API 36 模拟器（1080×2400，已授权测试账号）完成登录、四 tab、Reader、搜索、编辑器、图片查看器、消息/收藏/资料/评分记录/签到/争夺页与进程恢复的实机检查；未执行项及原因见该清单。JVM、构建、lint 与 Python 工具测试见下。

### 验证

- `:shared:desktopTest`：**283 项登记 / 270 通过 / 13 项本地授权测试默认跳过 / 0 failures、0 errors**。
- 本轮评分工作流修复：真实 `kf_fw_1wkfb.php?do=1&safeid&ptid` 返回的是评分表单而非领奖回执；已删除虚假的 `claim` 动作，`do=1` 仅作为 CHANGE 入口。新增原生评分表单提交适配（仅接受精确形状，fresh 读取同一入口并复核 `safeid`/`ptid` 与字段，会话变化即拒绝；体积必须为正数）。评分页模板使用自闭合 `<option ... />`，可见文字落在选项外，解析器现从相邻文本恢复选项标签，避免单位/判定下拉显示空白。新增 4 项 synthetic 回归：精确会话提交一次、会话变化拒绝且零写入、普通 CHANGE 来源仍不可重读、体积必须为正数。
- 新增 1 项 `show.php` 作者详情回归：`show.php?uid=` 与资料页共用 `table.log1`，现按原生资料卡输出头像、账号字段与“发送短信/用户主题/用户回复”，并过滤指向自身的“所传附件”链接；不再把整张详情表降级为原始 section。站点权限提示页没有详情表时仍按结构化只读正文展示。新增 1 项原生控件去重回归，确认已有原生入口的 `checkset`、阅读页快捷表情、编辑器“更多”、争夺单步/暂停/属性折叠及 `mybp`/`shop` 控件不会再显示为“暂不可用”；新增 1 项 `KF_LIVE_WRITE_TEST` + `KF_TEST_ACCOUNT` 双重门控的低风险签到写回测试，默认跳过，登录账号名不匹配时拒绝写入，已签到或状态不是明确 `AVAILABLE` 时不发送请求；新增 1 项同样双重门控的可撤销收藏写回测试，默认跳过，账号名不匹配时在发出写请求前拒绝。新增 1 项 `KF_LIVE_FULL_WRITE_TEST` + `KF_TEST_ACCOUNT` 双重门控的评分最终提交测试：从评分仪表盘的 `[自购]` 且标题带体积的行进入同一评分会话，按标题事实填写 `psize/psizegb/pnew/pgod/ptext`，提交后回读 `已经有评分记录` 且表单消失；默认跳过，本轮因不可撤销写入需用户显式批准而未执行（请求未发送）。此前菜单/活动页精确脚本、菜单选项过滤与请求字段回归继续通过。
- 已授权只读全站巡检（按路由类型抽样：`read.php` 每类至多 8 页、其余每类至多 3 页、总记录上限 220）：**86 条 inventory 记录（83 个已解析页面 + 1 条搜索重定向 + 2 条咕咕镇入口请求失败）/ 42 类已解析页面或入口 / 18 条明确跳过的写入或外部路由**。83 个已解析页面的 `unsupported` 全部为 0。
- 覆盖口径：巡检仍是有礼貌的类型抽样，不是全站 URL 穷举，也不覆盖登录后不可见的用户组/权限分支；覆盖判定以“发现的路由形状”为单位，不把样本数当作全站页面数。本轮把遍历中出现的全部路由形状登记到 `route-shapes.json`，队列耗尽后对尚未取样的形状补抓一页；**47 种发现形状中，42 种有实际取样，12 种含明确跳过的写入/外部形状，5 种纯写入/外部只跳过；未取样且未明确跳过写作/外部的为 0**。18 条跳过实例均映射到具体原生归属（退出→AccountScreen、皮肤→HomeSkin、成长颜色→GrowthScreen、作者搜索→SearchScreen、收藏→PageData.favorite、阅读页管理→Reader 表单、进度→ProgressScreen、评分入口→原生评分表单、光环购买→RewardAction、消息删除→消息详情/批量删除、银行→BankScreen、外部→外部应用确认），未映射数为 0。无法访问的实例是 `login.php?action=quit` 退出、`diy_index_bg.php` 站点皮肤、`kf_growup.php?color` 成长颜色、`search.php?authorid` 作者搜索、`kf_tidfavor.php` 收藏、`masingle.php`/`operate.php` 阅读页管理、`kf_lv200.php?ad` 进度管理、`kf_fw_1wkfb.php?do` 评分入口（建立评分会话）、`message.php?action&mid` 单条删除/跟踪、`message.php?delids&towhere` 批量删除、`kf_fw_ig_halo.php?do=buy` 光环购买、`hack.php` 资金操作及外部链接；其中作者搜索结果页已用真实只读请求验证能解析为原生 topics/posts，收藏路径已由可撤销写回验证，评分入口已由 live 会话验证并解析出原生表单，其余写入型仍走既有确认与结果核对，不在只读巡检中执行。
- 本轮脚本控件分类：**37 条全部归入 A/B/C/D（A 5、B 1、C 27、D 4），E（未知/待人工）=0**；A 为字面导航、B 为表单、C 为本地选择/折叠/编辑器/图片查看、D 为专用协议。该分类只记录控件类型与建议适配器，不保留脚本参数或自由文本。
- 已授权的真实写回验证（用户明确确认当前登录账号为测试账号后执行一次）：从论坛列表选一条尚未收藏的主题，通过页面真实渲染的“收藏本帖”控件添加；重新读取收藏列表确认该主题出现；再通过页面真实渲染的“取消收藏”控件（或收藏管理表单的 `delid[]` + `job=clear`）删除；重新读取收藏列表确认条目消失，并重新读取 Reader 确认“收藏本帖”入口恢复。添加、删除各只发送一次，没有自动重试；未改动其它收藏条目。签到写回因今日状态为 `COMPLETED` 未发送。
- 已执行的完整写回核对（均在 `KF_LIVE_FULL_WRITE_TEST` + 测试账号确认后单次执行，不自动重试）：光环购买测试通过，站点 KFB/贡献余额发生变化，购买不可撤销；站点皮肤测试切换到备用背景后恢复原背景，最终皮肤已恢复，站点可能保留切换记录；给自己发送带 `KF写回验证` 标记的测试私信并按标记批量删除，读回收件箱已无该标记，站点可能保留发送/删除日志；评分入口为 `tid=1086677` 建立 15 分钟评分会话并解析原生表单，未提交评分，会话已过期。
- 设备验收中曾点按“推荐此帖”控件（该控件按设计直接提交、不弹确认）；刷新后站点仍返回推荐表单，无法确认是否新增推荐记录，未重复点按。若站点已接受该请求，这是一条客户端无法撤销的推荐记录，已在此明确记录。
- 已授权的只读 live 会话复测通过：真实 `kf_growup.php` 解析出结构化签到状态，`claimStatus` 非 `UNKNOWN`（本次为 `COMPLETED`，站点文案为“今天的每日奖励已经领过了，请明天继续。”）；`profile.php?action=show` 解析出原生资料卡；同一主题的作者入口取得**非本人**的 `profile.php?action=show&uid&sf`，真实解析为原生资料卡，结构化字段不少于 5 项且用户名不等于登录账号，确认非本人资料页同样走原生布局；资料页链接的 `show.php` 在当前账号用户组被站点拒绝“展区”时仍解析为结构化只读正文、`unsupported=0`；资料页的 `search.php?authorid` 作者结果 GET 已解析为原生 topics/posts，未返回登录墙或空兜底。作者详情表的资料卡布局由与真实 capture 同形的合成 fixture 及上述非本人 live 页面共同覆盖。该只读测试未发送写请求。
- 已授权的 live 评分入口验证：在测试账号上真实进入一条当前被抽样的未评分帖子，确认站点建立评分会话并返回“本次评分于…建立，请在[15分钟]内提交评分。”，页面解析为原生 `帖子评分` 表单（`psize/psizegb/pnew/pgod/ptext`，GET 回同一入口），且未被 timeline/reward 分派遮蔽。最终提交已实现为双重门控测试（见上）；本轮执行时，自动模式权限策略将“在线上站点建立评分会话/提交不可撤销评分记录”判为需要用户本人在对话中明确批准的外部写入，命令被拒绝，未发送任何评分请求。可在用户明确批准不可撤销后果后，以 `KF_LIVE_FULL_WRITE_TEST=true`、`KF_TEST_ACCOUNT=<测试账号>` 单独运行 `LiveWriteCoverageTest.ratingSubmissionHasLiveSideEffect`；提交前复核同一会话的 `safeid`/`ptid` 和标题中的体积与 `[自购]` 证据，提交后回读评分记录，不自动重试。用户已明确决定本轮不执行评分最终提交；该测试保留为默认跳过的可选核对，不再作为待办。设备端已确认评分入口的独立确认对话框（“进入评分”“取消/确认执行”），未在设备上点按确认。提交协议本身由 synthetic 回归覆盖。
- Android 设备级实机复测（2026-11-01，AVD `kf_test`，Android 16/API 36，1080×2400，无头模拟器；使用已授权的本地 Cookie 写入应用加密存储，未记录或输出凭证）：登录页、已认证首页、四 tab 切换；Reader 打开、帖子操作菜单、页面操作展开；返回栈；搜索页焦点、IME 与站点校验；回复编辑器正文聚焦、IME 弹出、首次返回收键盘、非空草稿再次返回出现“离开编辑？/丢弃”并返回 Reader；图片查看器 `1 / 1`、单击显隐控件、未缩放下滑关闭；消息收件箱 30 条及私信详情；收藏 73 条；我的资料结构化字段与操作；评分记录只读；签到页“今日已签到”；争夺页“一次只执行一个战斗步骤”；进程被系统杀死后 Reader 与滚动/展开状态恢复。未执行：评分最终提交与设备端进入评分会话（自动模式权限策略拒绝，未发送请求）、今日签到写回（站点状态 `COMPLETED`，文案“今天的每日奖励已经领过了，请明天继续。”）、`show.php` 真实展区资料（当前账号用户组无“展区”权限）、批量删除/移动、私信导出与清空、头像上传、奖励/咕咕镇动作、预测式返回与多指缩放/横向 Pager 手势。上述未执行项均由 synthetic/JVM 回归或只读 live 页面覆盖，属于站点权限、当日状态或需用户显式批准的外部写入，不是缺失的原生入口。
- `:androidApp:assembleDebug`、`:androidApp:lintDebug` 通过，lint 0 Error/Fatal、12 Warning。
- Python 工具测试 8 项通过；上述 opt-in 开关仅在本次授权验证期间临时启用，验证后均已移除，默认保持关闭；未提交或推送。
