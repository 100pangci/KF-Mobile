# Live Site Coverage Audit + Native UI/UX Pass

## 范围与口径

> 接管复核：2026-10-26。下方保留上一轮的巡检/修复历史；本次增量、证据和限制单列于“P1 接管复核”。矩阵描述当前源码，不把既有功能计为本次新增。

- 本轮实际使用本地已有登录 Cookie，复用 `HttpForumService`、`OriginCookieJar`、`ForumHtmlParser` 和 `NativeSiteParser`。
- Cookie 查找顺序：仓库根目录 `cookie.txt`，其次 `.secrets/<host>.cookies.txt`。不读取账号密码，不改写原 Cookie 文件。
- 首轮：**53 个实际内容页面样本，35 类内容页面/视图**；此外发现搜索 GET 入口跳转。最终复测数据见下方“验证结果”。同一路径的收件、发件、详情、编辑、评分子视图和排行分组分别计类；不是 35 个独立产品功能。
- 自动发现来源包括首页菜单、板块、主题、资料、信箱、表单入口和页面内链接；同类内容有限抽样，不是全站爬取或权限穷举。
- **Parser 能输出内容不等于已完成专用原生适配。** Generic Native 仍是保底兼容层，不计作高完成度。
- 开始工作时已有未提交的搜索、分页、导航、Reader、编辑器及设置修改；保留这些工作。本轮是在其上补适配、修实际结构缺陷并增加回归，不能把全部既有改动算作本轮新增。

## 严格只读与脱敏

巡检只有 GET，且每次实际请求必须通过独立 `AuditReadPolicy` 和 `SiteRoutePolicy`。拒绝未知参数、重复参数、下载、购买、任何表单提交和 JS 执行；HTTP 跳转先记录，再对目标重新判断。GET 不代表安全。

未执行：发帖、回复、私信发送、删除/批量删除、收藏/取消收藏、推荐、购买、附件下载、签到、领取奖励、转账、存取款、资料/密码/屏蔽修改、目录创建、成长颜色、头像上传、游戏战斗或物品操作。相应交互只用合成数据和假服务测试。

无法确认的链接记录 `manual verification required`，不会为了覆盖率降级网络安全分类。私信详情按用户授权作为阅读路由访问，但站点是否在读取时更新已读标记由服务端决定；本轮没有调用“标记已读/未读”操作。

本地生成、不得提交或上传的文件：

- `.secrets/site-audit/route-inventory.json`：路由、参数名、固定功能标题、认证状态、Parser 输出、topics/posts、forms/tables/sections/links/unsupported、脚本控件、嵌入内容、effect 和最终 Screen。
- `.secrets/site-audit/unsupported-controls.json`：A–E 分类、原因和建议适配器；不保留脚本参数，未知控件的自由文本标签脱敏。
- `.secrets/site-audit/captures.json`、`page-*.html`：仅本地私有巡检资料。manifest 不保存真实 URL 参数值。
- `.secrets/site-audit/screenshots/`：真实页面数据的离线 Compose 渲染，PNG 不写入 Cookie/token metadata。

公开文档不包含真实用户资料、主题标识、私信内容、邮箱、余额或令牌。JSON 中 query 只保存参数名和 `<redacted>`。

## Coverage Matrix

分类口径：表中历史简写“高”指**专用 UI**；“中高 / 中 / 中低”均为**部分适配**，不是完整支持或写操作验收。“低，fallback / fallback”指 **Generic fallback**；“未支持”单列，不能计入已适配数量。

| 功能 | Parser | 当前 UI | 原生程度 | 问题 / 未完成项 | 优先级 |
|---|---|---|---|---|---|
| 首页 Feed | 专用 feeds | FeedScreen + SecondaryTabRow | 高 | 站点首页是有限推荐/最新集合，不虚构无限数据 | P0 |
| 板块 | 专用 boards | BoardsScreen | 高 | 下拉刷新、筛选和标签重选回顶复用既有机制 | P0 |
| 主题列表 | 专用 topics + boardFilters | ListingScreen + 筛选 BottomSheet | 高 | 分类入口来自实际同板块 READ 链接；保留板规 fallback | P0 |
| Reader | 专用 posts / quote / purchase / metadata | ReaderScreen | 高 | 连续页保留、重复保护已有回归；长作者名省略 | P0 |
| 搜索 | 专用结果 Parser / service.search | SearchScreen | 高（UI）；真实查询未测 | GET 入口跳转，搜索签发需要 POST，本轮不执行；离线测结果/筛选/编码/分页 | P0 |
| 发主题 / 回复 / 引用 | 专用 EditorForm | EditorScreen | 高（编辑 UI） | 已 GET 检查真实入口；发布未测。私信回执没有可信的成功/拒绝协议，发送后保留结果待核对保护；私信附件未支持 | P0/P1 |
| 收件 / 发件 | 专用 NativeMessageSummary + 兼容 topics | ListingScreen(messages) | 高 | 参与者、时间、未读、首字头像；列表 HTML 不提供正文摘要或真实头像，不虚构 | P1 |
| 私信详情 | 专用 NativeMessageDetail | MessageDetailScreen | 高 | 阅读正文、发送者、时间、明确回复 CTA；回复发送未测 | P1 |
| 消息跟踪 | 专用消息摘要 / 空列表标识 | ListingScreen(messages) | 专用 UI，详情部分适配 | 兼容跟踪页额外选择列、空列表；详情 readscout 尚未确认只读，仍为 CHANGE / manual verification required；选择按来源页隔离 | P1 |
| 屏蔽列表 | NativeBlocklist + 原服务器 SiteForm | BlocklistScreen | 专用 UI（读取/编辑）；保存未实测 | 保留名单原始语法、站点说明和附加字段；保存经确认与 fresh revalidation，仅假服务验证 | P1 |
| 我的 | 专用账号/Profile 摘要 | AccountScreen | 高 | 本地历史、站点入口、设置；不执行签到 | P1 |
| 用户 Profile | 专用 NativeProfile | ProfileScreen | 高 | 头像、头衔、stats、纵向资料和操作，不再 definition table | P1 |
| 修改资料 | 标准 SiteForm + 字段分组语义 | ProfileSettingsScreen | 中高 | 基本资料、偏好、安全、独立密码折叠区；保留服务器隐藏字段，不拆造协议 | P1 |
| 收藏 | 专用 NativeFavorites + SiteForm | FavoritesScreen | 专用 UI，跨页管理部分适配 | 逐来源页确认提交，不合并令牌；写后结果未知或列表读回失败也废弃旧分页并禁止管理，提供只读核对入口。仍非跨页一次性/原子批量操作 | P1 |
| 阅读历史 | 本机 origin-scoped links | AccountScreen 历史 | 高 | 仅本机保留，不声称全站历史同步 | P1 |
| 银行 / 贡献 | 专用 NativeBank + SiteForm | BankScreen | 中高 | 余额与规则分离、明确转账/取款入口；当前真实模板没有可用存款表单，不制造按钮；记录仍保守确认 | P1 |
| 会员排行 | 结构化 NativeTable + NativeRecord | RecordScreen(ranking) | 专用 UI，未知行 fallback | 名次、核心/次要指标、已加载用户名筛选与本人标识；无资料链接不伪造 UID；不完整结构整表保底 | P1 |
| 评分 / 自助奖励记录 | NativeTable / cell actions + NativeRecord | RecordScreen | 中高 | 手机列表代替宽表；未知表格/新表单仍 fallback；领取动作未测 | P1 |
| 被 @ 记录 | sections / 可选 records | RecordScreen(status) | 中低 | 本次站点返回非正常记录列表，无法确认正常数据结构；不可宣称完成真实 @ 列表适配 | P1 |
| 进度分 | NativeProgress 明确分数 + 说明 | ProgressScreen；结构缺失时 RecordScreen / fallback | 部分适配（专用数值 UI） | 仅提取独立“当前进度分”标签紧邻数字的已确认模板；规则中的状态词不提升为账户状态。正常 status/timeline 未验证 | P1 |
| 签到 / 成长 | 专用 NativeGrowth | GrowthScreen | 高（读取） | 只读取状态；签到、领奖、颜色修改未测试 | P1 |
| 争夺奖励及动态控件 | 部分 tables / form | NativeSiteScreen | 低，fallback | 存在 toggle、奖励/游戏 Ajax 协议；不得把表单能显示算作完整支持 | P1/P2 |
| 分享榜 | NativeTable + NativeRecord | RecordScreen | 中高 | 已发现菜单外的同源内容；未知新结构回退 | P1 |
| FAQ / 规则 | 结构化 sections / links | FaqScreen | 专用 UI，未知表格 fallback | 定义型内容纵向排版，混合链接行保留说明及全部操作；其它未知表格保底，表单/暂不可用控件也保留 | P1 |
| 附件 / 展示 | sections / links / 安全下载边界 | NativeSiteScreen / 图片查看器 | 中低，部分 fallback | 实际展示入口已读；未执行下载，也未绕过付费/附件确认 | P1 |
| Settings | 本地状态 | SettingsScreen | 高 | 普通设置与高级连接配置分层 | P1 |
| 图片查看器 | 图片资源模型 | Android HorizontalPager / zoom | 高 | 保留单击显隐、双击、缩放、保存；下滑关闭暂缓，未声称手势真机验收 | P1 |
| 头像上传 ajaxface | 已识别并隔离 | 明确“头像上传暂未支持” | 未支持 | 独立 Ajax/upload 协议，不能拿正常资料表单冒充 | P2 |
| 咕咕镇 | 已知部分静态结构 | Generic fallback / 暂不支持 | 低 | 登录菜单入口携带无法确认的动态参数，未自动访问；战斗/背包/道具全部未执行 | P2 |
| 未知同源页面 | 保守 SiteDestination + NativeSitePage | NativeSiteScreen | fallback | 未知网络路由仍 CHANGE；新结构不得静默吞内容 | P2 |

## 上一轮真实结构修复（接管时已有）

1. 收藏链接的查询参数顺序与原选择器不同。改用路径/参数语义与主内容区域；收藏条目绑定各自 checkbox 的字段 ID，不用标题或行号猜测。
2. 用户资料、成长、银行、排行等功能页不再把侧栏主题误报为 topics。
3. 下一页必须属于同一列表/主题；站内其它主题的 page 链接不能被拿作当前页面 next；省略 page 的首页按第 1 页处理，避免重复请求第一页。
4. 私信正文按稳定 detail 行序（操作行之前的内容行）提取，不再选择“最长单元格”；长标题、短正文有合成回归。
5. 消息列表提取发送者、收件者、时间、已读状态和用户链接；服务端不提供的信息不伪造。
6. 表格单元格保留全部链接操作及 effect，不再只留下第一条链接而丢掉奖励动作。
7. 银行从稳定模板提取余额；括号规则进入说明区，不进入大数字。
8. 板块筛选只采集同板块、已知 READ 的分类/排序链接。
9. 消息跟踪实际使用 readscout 链接及额外选择列：列表适配与路由安全分离，能显示列表不代表自动放行未验证的详情协议。

## 交互与安全

- Topic 改为轻量行、两行标题、紧凑 metadata、divider；Feed header 缩小，去掉重复大标题。
- Reader 保留既有轻量楼层结构，约束长作者名；普通正文不包大卡片。
- 搜索、自动分页、下拉刷新、top-level 回顶与缓存、编辑器连续表情和设置分层保留并回归既有工作。
- 私信 inbox/outbox/tracking/管理 tabs；详情使用正文阅读页。收藏管理不再是裸 checkbox table。
- Editor 顶栏取消/发布，正文更轻量；提交结果未知的私信也阻止直接重发。
- 已知普通资料保存不多一次确认；密码、删除、转账、取款和未知操作仍确认。UI 风险判断与网络 READ 分类保持独立。
- 网络增加 READ 参数白名单、重复参数保护、嵌套伪端点保护；不把游戏动态参数视为安全 READ。
- 禁止读请求自动跳入 mutation；GET mutation 不自动重试；multipart 302 后清空 body，避免重发 POST。
- 修正 Android 消息导航快捷路径绕过保守分类的可能性；编辑器自身也校验入口。
- 修复源码打包器未排除根目录 `cookie.txt` 的缺口；Cookie、签名与环境文件不能进 source/APK archive。

## 控件分类与剩余适配

- A：严格 literal location 导航 → `SiteDestination`，始终先过 route policy；不执行 JS。
- B：普通表单 → `SiteForm`，fresh hidden fields / GBK / duplicate-name / multipart 的既有安全链路保留。
- C：本地选择/折叠、已知推荐协议 → selection mode、expandable UI、`rpc:recommend`；推荐网络请求不在巡检执行。
- D：头像 `ajaxface`、奖励/游戏 Ajax → 独立 protocol adapter，暂缓，不猜请求参数。
- E：旧网页弹窗偏好、无法确认的脚本 → 明确 deferred / manual verification required。

未知控件的原始自由文本和 JS 参数不写报告；分类 JSON 只在本地。它不是“所有 JS 控件都不支持”的计数，包含已识别的本地替代操作。

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

## 截图验收范围与限制

- 真实 capture 离线渲染覆盖 Feed、Boards、Topics、Reader、Messages、Message Detail、Profile、Favorites、Account、Settings、Editor、Bank、Growth、Ranking、Rating、@、FAQ，并额外覆盖修改资料、奖励和进度。
- 每屏 Light / Dark × 360dp / 412dp，保存在忽略目录。@ 屏展示的是本次实际站点异常/不可用响应，不代表正常 @ 数据已测。
- 共 21 个真实数据页面（含补充的消息跟踪）× 4 种规格 = 84 张本地截图；6 组额外合成边界用例 × 4 种规格 = 24 张边界截图。
- 合成 UI 测试保留；补长标题/长用户名、大量数据、空列表、首次 loading、整页 error、多字段 form、宽 table 的同规格渲染。
- Headless JVM 使用真实 Parser 和共享 Compose；rich HTML 转文本、头像用占位，不等同 Android Html renderer、IME、系统 predictive-back 或真实触摸手势验收。
- 未跑 Android emulator/真机手势测试。银行规则混入余额、收藏页错误底栏已由真实渲染发现并修复；不能宣称全部交互已达最终验收。

## 上一轮验证结果（历史记录，不作为本次新增）

- 最终 live 复测：**54 个内容样本 / 35 类内容视图**；加搜索 GET 跳转，共 **55 条 inventory 记录 / 36 类入口**。24 种无法确认或非只读的路由形态未访问。
- 最终控件分类产物：28 条脱敏分类（C 7、D 2、E 19）；A/B 支持路径也有离线测试，未在本次抽样的分类结果中出现。这个数是分类后去重条目，不是页面上全部 JS 按钮数。
- `:shared:desktopTest`：166 项，0 failures / errors；授权的 live 审计和私有截图分别独立运行。默认测试跳过需要本地授权的网络/真实截图项目；截图运行用于验证渲染而非执行服务器操作。
- `:androidApp:assembleDebug`、`:androidApp:lintDebug` 通过；lint 0 Error/Fatal、11 Warning。存在既有 Gradle/Compose 依赖弃用警告，不影响构建。
- `python -m unittest tools/test_probe_login.py tools/test_package.py`：8 项通过。修复原先从仓库根目录运行 unittest 的导入问题，没有运行账号密码 probe。
- 专用界面和 synthetic/offline 回归已落地；高风险服务器写操作保持未执行。
- `git status`、`git diff`、`git diff --check` 已检查；对工作区变更扫描本地 Cookie 值、实际账号/邮箱/私信文本、实际长 UID/TID/MID，匹配均为 0；私有文件未被 Git 跟踪，真实 PNG 无文本/Exif metadata。未自动提交。

## P1 接管复核（2026-10-26）

### 本次实际发现与修复

1. **专用界面吞内容**：Profile、资料设置、银行、私信详情、消息/收藏和成长界面存在额外 block/form/action 不可达的问题。加入用户可见的“页面补充信息”保底入口；资料设置只移除已编辑字段所在行，不再整张丢弃说明表。Profile 保留重复标签但值不同的资料、超过旧数量上限的字段及额外表格。
2. **记录部分成功时漏行**：排行表存在不能理解的合并行/页尾时，原先只显示能转为记录的行。现在整表保底；续页的表单、链接与不可用控件逐页保留，记录携带表级操作。
3. **FAQ/通用表格漏操作**：混合正文和链接的规则行不能因为带链接而被过滤；FAQ 定义型内容改为纵向正文与操作分离。保底单元格显示其它操作，取消 Parser 的 300 字静默裁剪和显示层四行截断；有正文的导航行不再直接删除，caption 中链接也保留。
4. **消息空态与身份去重**：阅读链接参数顺序不再影响 topics 提取；结构化 messages 有内容时不显示空态或错误的 0 条；空跟踪表按明确表头识别。跨页相同 MID 不因参数顺序变化重复。发送者/收件者与已读字段仍来自服务器，不自行猜测读取是否更新已读。
5. **fresh revalidation 静默提交部分选择**：新增 `SiteFormRevalidation.kt`。重新读取原来源页，核对目标、method、multipart、非易变隐藏操作、字段结构及全部所选 option；允许更新 verify/safeid，任一所选项目消失或协议变化则在写请求前整次拒绝。重复 checkbox 通过选项值重绑定，不以字段 ID 的行号跨页拼接。
6. **跨页管理的安全增量**：新增 `SourcePageSelection`，消息/收藏可在已加载来源页间切换并保留各自选择；每次只提交一个来源页的完整表单。提交后页码可能移动，清除选择、取消续页并废弃旧尾页缓存，要求重新核对。**没有实现或宣称跨页一次性/原子提交，也不会自动循环执行删除。**
7. **异步串页**：普通页面、编辑器、搜索和会话恢复增加请求版本/origin/active 检查；不配合取消的旧响应也不能回填。刷新跳到其它列表或续页退回首页时保留当前数据并显示错误，而非污染缓存/伪装加载完成。Feed 与 Account 分区分别保存滚动状态，不共用一个列表位置。
8. **消息删除后的返回**：假服务确认后重读原信箱来源页（含页码），不把回执写到信箱 cache alias；清除过期续页，Android 导航回到对应信箱。真实删除没有执行。
9. **私信结果未知后重发**：保留既有当前编辑器的阻止重发逻辑；网络层新增持久化内容摘要标记，关闭编辑器、重建服务或改变隐藏令牌不能绕过。只在已有安全存储里保存摘要与标记，不保存收件人/标题/正文；成功或明确拒绝清除，未知结果保持阻止。结果核对入口改去发件箱。**没有自动判定“未发送”或自动解除未知标记的协议，不能承诺自动恢复发送。**

### 新增/完善界面与完成度

- **新增专用 UI**：`BlocklistScreen`（绑定真实 banidinfo textarea 和原始 POST 表单）；`ProgressScreen`（明确的当前分数、显式状态及规则区）。屏蔽保存仍需确认，未真实执行。
- **完善既有专用 UI**：排行的已加载用户定位/本人标识、FAQ 正文、成长颜色入口、消息/收藏的来源页选择，以及任务页附加信息保底。这些界面主体是接管时已有，不计为新增整项功能。
- **部分适配**：进度没有经真实正常数据确认的 timeline；消息跟踪详情未验证；跨页管理只有逐来源页提交；私信未知结果没有自动消歧。已有服务器 mutation 的可用性不能由离线通过证明。
- **Generic fallback**：争夺奖励、未知表格/新表单、非正常 @ 响应、其它未知同源页面。奖励页面虽能解析普通表单，不等于奖励 Ajax 已支持。
- **未支持**：ajaxface 上传、奖励 Ajax adapter、咕咕镇动态功能、图片下滑关闭；不为覆盖率猜请求或引入未经设备验证的手势。

### 只读证据与路由分类

- Cookie 只从既有文件读取，真实巡检仍复用现有 Kotlin Parser、HTTP client、`AuditReadPolicy`；没有密码读取/登录 probe、搜索 POST 或 JS 执行。
- `readscout` 证据目前只有跟踪页中的入口与列表结构，不能证明详情没有副作用。银行日志仅观察到 `action=log` 导航，尚无服务端协议或独立只读保证。**两者仍为 CHANGE，巡检记录 manual verification required，未访问；没有扩大 READ 白名单，也没有放宽 UI 风险确认。**
- 银行实际表单只有转账与取款，未增加存款按钮。排行无可信资料链接时只允许本地用户名筛选，不补 UID。
- 本次已发现的 @ 入口仍非正常记录列表；没有发现可证实的新正常入口，未猜路由、未将异常页截图计为正常列表验收。
- 奖励页：普通链接经 route policy；普通表单保留原协议；本地 toggle/选择与独立 Ajax 控件分开记录。奖励/游戏 Ajax 没有 adapter，不猜参数，不执行探测。
- 结构证据存于私有 `protocol-shapes.json`（仅字段名称/类型、参数名、固定语义标签与计数），查询值仍全部 `<redacted>`。

### 本次验证与限制

- 最终真实只读复测：**54 个内容样本 / 35 类内容视图**，含搜索 GET 跳转共 **55 条 inventory / 36 类入口**；24 种路由形态继续跳过。本次在同范围上复核，不把样本总数计为新增覆盖量。
- 新增 `PriorityRegressionTest` 的最小合成 HTML / 假服务回归，覆盖屏蔽、进度、资料保底、FAQ/排行、来源页选择与 fresh token、不确定私信、取消竞态、分页跳回和删除返回；扩展既有分页去重与 UI 边界渲染。
- `:shared:desktopTest`：**185 项登记，179 通过，6 项本地授权测试默认跳过，0 failures/errors**；本次新增 17 项 P1 合成回归、2 项本地 opt-in 检查。授权 live 巡检另跑通过；私有离线渲染/结构/隐私及 P1 回归合跑 20 项通过。
- `:androidApp:assembleDebug`、`:androidApp:lintDebug` 通过；lint 0 Error/Fatal、11 Warning。构建通过不是设备产品交互验收。
- Python 单元测试：8 项通过；只运行测试，不运行账号密码 probe。
- 真实 capture 离线截图新增屏蔽页，范围为 **22 屏 × 浅/深色 × 360/412dp = 88 张**；正常 @ 数据仍不在覆盖范围。截图只存 `.secrets/site-audit/`，没有发布真实图片。
- 合成边界截图新增屏蔽、进度与 FAQ 混合长正文；人工检查了合成屏蔽/进度/FAQ 样例，并据此修正文链混排。真实截图的产出/非空检查不等于逐屏人工视觉验收，更不等于设备触摸验收。
- 私有 PNG 的 88 张尺寸/文件完整性检查通过，文本/Exif metadata chunk 为 0；inventory query 脱敏检查错误数为 0。81 个公开文本文件扫描：认证 Cookie、token、账号/邮箱/私信及实际长标识等匹配文件 0，私有文件被 Git 跟踪数 0。通用皮肤偏好词与源码词汇的 13 个非认证偏好误报单列，不输出原值，不把它们冒称为认证泄漏。
- `adb devices -l` 无连接设备。搜索 autofocus/IME Search、sheet 关闭焦点、BBCode 选区/连续表情、predictive back 取消/完成、图库 pinch/double tap/pager/显隐均**未完成 Android 设备验收**。源码保留相关实现，不据 JVM 截图宣称通过。
- Manifest 当前接管 orientation/screenSize 等配置变化；未验证旋转实机行为。进程死亡后重建不会恢复已加载 HTML/分页或编辑器草稿，仍是 P1，不能把 NavHost 的 route 恢复等同内容/草稿恢复。
- 未执行服务器操作：发布、回复、私信发送、删除、标记未读、收藏/移动/新建目录、推荐、购买/下载、签到/领奖、转账/取款/存款、资料/密码/屏蔽/头像修改、游戏操作及搜索签发。相关写路径仅假服务测试。

## 后续 P1 / P2（复核后）

1. **P1**：真机验证 IME/表情 sheet 焦点、predictive-back 取消/提交、每 tab scroll 恢复、旋转/进程重建；当前主要保证进程内页面/分页保留，不承诺已加载 HTML 持久化。
2. **P1**：搜索 POST 的单独授权验证；正常 @ 页面数据结构、屏蔽保存实测、进度的正常 status/timeline 和奖励记录进一步语义化。
3. **P1**：跨页一次性管理/部分失败恢复协议仍未实现；当前已支持来源页隔离选择和逐页提交，写后失效旧分页，不能把多个服务器表单的 ID/令牌合并。私信未知结果的安全消歧/解除标记仍待协议证据。
4. **P1**：银行记录确认只读后再扩 READ；只有站点提供存款入口时再适配；排行榜无用户链接时需要可信的用户定位方案。
5. **P2**：ajaxface、奖励 Ajax 协议、咕咕镇动态功能；独立只读协议调查和 synthetic fixtures 后再实现，不能靠执行未知脚本探测。
6. **P2**：图片在 scale=1 时的下滑关闭，须先真机确认与 pinch/pager 无冲突。

## P1 续办（基线 `30118c5`，2026-10-26）

本节只记该提交之后的工作。接管时工作区干净；没有重做或重新认领 BlocklistScreen、ProgressScreen、来源页选择、fresh token、请求版本保护或原有私信摘要标记。未提交、未推送。

### 实际增量

1. **管理操作恢复缺口**：此前只有来源列表读回成功才失效旧分页。现在收到响应、结果未知或异常时都会清除旧选择上下文、废弃尾页和缓存边界；写前提升请求版本，取消不合作的旧续页也不能回填。明确的 fresh-revalidation 拒绝（未写）不清除其它页的选择上下文。
2. **只读恢复 UI**：消息、收藏、屏蔽和通用表单增加“重新加载并核对”。核对前控制层禁止继续管理，保底表单不能绕过；核对必须得到同源、同列表且已认证的可识别列表/名单结构，失败、登录墙或异常响应不解锁。只读取原来源，不重放写请求，不自动循环处理下一页。读回成功只提示核对结果，不把任意服务器响应改写成成功。
3. **fresh revalidation 补边界**：增加来源、提交选项、重复 checkbox 值、只读字段变化和多选降单选的保守检查；不合并字段 ID、隐藏令牌或来自不同页的选择。此前已有的 fresh token 和任一选择失效整次拒绝逻辑继续保留。
4. **私信未知结果保护补洞**：摘要检查/写标记串行化，并在共享 store 上原子检查设置，防止同服务或共用 store 的两个服务并发重复发送。普通 READ 跳转（包括发件箱入口）及 textarea 回显不再被当成可靠成功/明确拒绝；没有真实回执协议证据时保留未知标记。取消也保留标记。未确认的私信附件在发请求前拒绝，不能绕过摘要链路。**代价是目前实际私信发送后的普通回执都会保持待核对，不能保证自动恢复发送。** 没有清除标记按钮，没有自动消歧协议，手动看过发件箱也不会自动解锁。
5. **消息详情**：操作行与回复入口改按路径/action 语义识别，兼容查询参数重排；正文含消息链接不再抢占后面的操作行。参与者和已读状态仍按站点实际结构，不推测读取产生的状态变化。
6. **进度规则误当状态**：原来的整页 span/b 状态词扫描会把规则示例中的“已完成/未领取”提升为账户状态。删除这个无正常模板证据的推断，仅保留已确认的独立标签邻接数值；状态词完整留在说明/fallback，不制造资格或时间线。

### 真实只读证据与未完成项

- 复用既有 Cookie、Kotlin Parser 和 audit；根目录无 `cookie.txt`，使用已有 `.secrets/<host>.cookies.txt`。没有密码读取、登录 probe、搜索 POST、脚本执行或任何服务器写操作。
- 本次 live 仍为 **54 内容样本 / 35 内容视图，55 inventory / 36 类入口**。27 种跳过形态中 CHANGE 24、EXTERNAL 3；与此前 24 种总跳过数不同，不能将它们记为新增支持。原始资料只在 `.secrets/site-audit/`。
- 已发现并通过 policy 的两条 `guanjianci.php?gjc=<redacted>` 抽样响应均为未认证页面、无记录表、无正常列表结构，不能宣称正常 @ 适配；没有猜测或访问其它未知 @ 协议。
- 跟踪列表实际 capture 解析出 29 条消息，发送者、收件者、时间和用户链接均非空；结构扫描找到 30 个含已知已读标记的表格行，未读计数 0。普通私信详情的 2 个实际样本都解析出回复入口。回复发送、读取是否改变服务端已读状态均未执行验证。
- `readscout` 仍只有跟踪列表入口证据，详情未访问；银行日志仍只有 `action=log` 链接。**READ 白名单与 UI 风险确认均未放宽**，两者仍 CHANGE / manual verification required。
- 进度仍只验证明确分数，正常 status/timeline 没有证据；奖励普通表单与 Ajax 分开，Ajax 未支持；银行仍只有真实转账/取款表单，不添加存款按钮；排行无资料链接不制造 UID。
- 本轮完善的是现有专用页的恢复 UI，不是新增整套专用屏幕。未知结构保留 Generic fallback；正常 @、跟踪详情、进度时间线及奖励 Ajax 仍未完成。
- 管理恢复限制是进程内的安全核对流程，**不是**跨页事务、逐项部分失败识别或进程死亡后恢复协议。当前仍不恢复已加载内容/分页/编辑器草稿；route 恢复不等于内容恢复。

### 验证

- 新增 **15 项**合成/假服务回归：`ManagementRecoveryTest` 7 项、`MessageReceiptSafetyTest` 7 项、`PriorityRegressionTest` 1 项；扩展既有 `UiEdgeRenderTest` 和私有结构检查。所有删除/移动/发送均仅假服务或本地拦截器。
- 最终必跑命令通过：JVM **200 项登记 / 194 通过 / 6 项默认跳过，0 failures/errors**；`:androidApp:assembleDebug`、`:androidApp:lintDebug` 通过，lint 0 Error/Fatal、11 Warning；Python 8 项通过，没有运行账号密码 probe。
- 本地 opt-in 的私有离线渲染/结构/隐私与 P1 回归合跑 **36 项通过，0 跳过/失败**；真实只读 audit 独立运行通过。两个 opt-in 开关均未在 CI 启用，未启用 `KF_LOGIN_TEST`。
- 私有 capture 仍是 **22 屏 × 4 规格 = 88 张**，不是新增页面覆盖量；PNG 尺寸/CRC 完整性错误数 0，文本/Exif metadata chunk 0。合成边界渲染新增消息/收藏恢复态共 **8 张**（边界目录现为 44 张）；人工查看消息浅色 360dp 和收藏深色 412dp 样例。没有宣称真实 88 张逐屏人工验收，更没有设备手势通过结论。
- 最终隐私扫描覆盖 83 个公开文本文件，认证 Cookie、令牌、账号/邮箱/私信、余额、实际长标识等匹配文件 0，私有文件被 Git 跟踪数 0，inventory query 值脱敏错误数 0；13 个非认证皮肤偏好词碰撞单列，不输出原值。`git status`、`git diff`、`git diff --check` 复查，无空白错误（仅既有 CRLF/LF 规范化提示）。
- `adb devices` 无连接设备。搜索 focus/IME/sheet、编辑器选区/表情/焦点、predictive back 取消/完成/草稿/提交中、tab 回顶与分区滚动、旋转/进程重建、图库全部仍未完成 Android 设备验收。JVM 渲染不证明这些手势通过。
- 未执行：发布、回复、私信发送、删除、标记状态、收藏/移动/新目录、推荐、购买/下载、签到/领奖、资金、资料/密码/屏蔽/头像修改、游戏 mutation、搜索签发。

剩余 P1：Android 设备验收及安全内容/草稿恢复；正常 @ 与进度状态结构证据；私信回执/消歧协议；跨页事务与可证明的逐项部分失败恢复；银行日志只读证据、已存在协议的写操作另行授权实测。P2（ajaxface、奖励 Ajax、动态游戏、scale=1 下滑关闭）继续暂缓。

## P1/P2 收尾复核（2026-10-26）

本节记录 `30118c5` 后的增量，覆盖上方旧的“剩余/暂缓”结论。**没有**执行任何真实副作用；Android 设备仍未连接。

### 实际增量与边界

1. **进程恢复**：`RecoveryStore` 只持久化 destination、origin、列表页 URL、anchor index/offset/key、搜索词、草稿内容和待核对的管理来源页。分页恢复上限 6 页（根页 + 5 页），失败即停，不自动轰炸；分页状态 3 天、草稿 7 天失效。草稿不含隐藏字段、verify/safeid、密码、银行/购买表单；附件仅保存名称/MIME/大小/slot，重启时明确提示重新选择，不保存 bytes。
2. **@**：`Mention(displayName, profileUrl, uid?)` 与 `ForumPost.mentions` 已落地；仅识别同源 profile/show 且 `uid` 合法的链接，邮箱/普通文本 `@` 不误判。发送时把正文中的 `@用户名` 合并到站点真实的 `diy_guanjianci` 关键词字段，不发明 BBCode。真实抽样未发现正常 @ 记录表，因此没有声称“真实 @ 列表适配完成”。
3. **状态时间线**：`StatusKind`/`StatusTimelineEntry`/`NativeStatusTimeline`；评分奖励按明确标签映射，无法判断一律 `UNKNOWN`；成长按 claimed/claim 入口显示。正常进度 status/timeline 仍无真实结构证据，未伪造。
4. **私信回执**：先读到已认证且结构可识别的发件箱作为基线，才会准备 POST；基线不可读时明确“本次未发送”并清理 marker。POST 前切换到持久化 `sending` marker。POST 后只有出现**新的**标题+收件人匹配行才 `Confirmed`。编辑器回显+明确拒绝词才 `Rejected`；其余为 `Uncertain`。发送前有收件人确认；成功文案不声称 UID。旧 `message_pending` 摘要保护继续有效。
5. **跨页事务**：新增 `BatchTransactionItem`/`BatchItemResult`/`BatchTransactionResult` 和 pending marker。逐来源页 fresh revalidation；删除按选项是否仍存在逐项判定。任一页 `Uncertain` 停止后续页并标 `NOT_ATTEMPTED`，不重放。收藏移动无法从列表证明目标分类，逐项 `Uncertain`。进程死亡后只标只读核对，不重放。
6. **银行日志**：`hack.php?H_name=bank&action=log`（以及 `to=1`）列入 READ；新增 `NativeBankLog`/交易记录 UI。只按真实表格列解析；金额方向只由页面 `to=1` 与转帐类型决定，缺失字段留空。余额和日志分开展示；不根据余额变化推断成功。真实只读 GET 已验证日志结构与“我的/对我的”两类记录。
7. **奖励协议**：真实页面/JS 中评分奖励为 `kf_fw_1wkfb.php?do=1&safeid&ptid` GET；争夺为 `kf_fw_ig_intel.php` POST `safeid`，返回 HTML 日志、失败为 `no`，并存在客户端“前进至结束”循环。Native adapter 只提供一次 GET/单步 POST；领取后必须读回匹配 `ptid` 的 timeline 状态，明确 `completed` 才 Confirmed；行消失/结构缺失/HTTP 200 均不确认。战斗日志只报 Uncertain，不自动循环。
8. **ajaxface**：真实资料页表单为 multipart `profile.php?action=ajaxface`，提交 `upload` 文件、`uploadurl[1]/[2]`、`facetype`，回调为 `facepath(...,'success',...)`。已实现 Photo Picker、本机中心裁剪 140×200 JPG、magic/MIME/大小校验、上传前 fresh form 复核和独立结果模型；上传后读回 Profile 匹配头像 URL，未匹配时不报资料保存成功。没有执行线上头像修改。
9. **咕咕镇**：真实入口经站点 302 到 `www.momozhen.com/fyg_login.php?...`，游戏有独立 Cookie。Native 适配使用独立游戏域/CookieJar，不转发论坛 Cookie；只读 `fyg_index.php`、`fyg_beach.php` 和 `fyg_read.php` 的 f=6/7/8 列表。`GuguScreen` 展示角色/冷却/装备/背包；战斗、使用道具、商店、WebView 均未提供。真实 capture 已验证解析，未执行游戏 mutation。
10. **图库**：`GalleryGesturePolicy` 隔离手势判定；scale≈1、单指、向下纵向比例和距离/向下速度阈值共同决定 dismiss；缩放/横向 pager 优先，向上 fling 不关闭。仅策略测试，无设备触摸验收。

### 验证（本轮）

- `:shared:desktopTest`：边界收尾后 **239 项登记 / 233 通过 / 6 项本地授权测试默认跳过 / 0 failures**。覆盖进程恢复、@、私信 baseline/receipt、跨页部分失败与取消恢复、银行日志、奖励状态读回、头像 fresh form/两阶段读回、咕咕只读 capture、图库上下方向与手势阈值、通用 mutation 2xx 不确认。
- `:androidApp:assembleDebug` 通过；`:androidApp:lintDebug` 通过，**0 Error/Fatal、12 Warning**（依赖版本/KTX/API 提示，无阻塞）。
- 真实只读证据：银行日志 GET（`action=log` 与 `to=1`）实际结构；评分奖励页面与实际脚本；资料修改 ajaxface 表单/JS；咕咕镇官方 302、角色页、沙滩与 f=6/7/8 只读列表。所有真实证据只进入 `.secrets/` 私有目录，本文件不含 URL 参数、账号、金额、UID/MID 或图片数据。
- 未执行：真实私信发送、银行转账/取款、奖励领取、头像上传、战斗/道具/商店、删除/购买、搜索签发。上述结果判定仅由 synthetic/fake service 或真实只读 capture 验证。
- `adb devices -l` 仍无设备；Android instrumentation/connected test、IME、焦点、predictive back、触摸、旋转、进程死亡设备复现、图库真机手势均未运行。详细人工清单位于 `docs/android-device-checklist.md`。
- 未恢复：表单字段/隐藏令牌、密码、银行表单、购买确认、附件 bytes、图库缩放/页码、BottomSheet 临时状态、真实消息已读状态、任何服务端回执。分页/草稿只在 origin-scoped 加密 store 中保存；不把 HTML/PageData 全量塞入 Android saved state。

### 边界复核补充

- 通用站点 mutation 的 2xx/redirect 只作为页面回执，带 `uncertainReceipt`，不直接报业务成功；无跳转响应仅在出现强、明确的拒绝短语时归为 `Rejected`。收藏必须重读主题并看到相反操作链接才确认。
- 跨页事务若 mutation 回执不是来源列表，会对原来源 URL 再做只读 GET；中断/进程死亡保留待核对来源，不重放。删除按逐项选项消失判定；移动目标无法读回证明时保留 Uncertain。
- 管理页的不确定来源也保存在加密 recovery state；只读核对成功才清除。logout 不丢失待核对标记，换 origin 则按 origin 隔离。
- 私信发件箱基线不可读时不发送；基线通过后立即持久化 `sending` 阶段。取消发生在发送前没有 marker，发送阶段取消则保留 marker。
- 成长签到不再因“没有链接/表单”推断已完成；只有明确状态词才是 completed，入口存在才是 available，否则 unknown。

### 边界复核补充

- 通用站点 mutation 的 2xx/redirect 现在只作为页面回执，带 `uncertainReceipt`，不再直接报业务成功；只有无跳转响应以强、明确拒绝短语开头时归为 `Rejected`。收藏必须重读主题并看到相反操作链接才确认。
- 私信先读取已认证且结构可识别的发件箱，再持久化 `sending` marker 并 POST；发件箱基线读不出时明确“本次未发送”并清理 marker。取消发生在基线 GET 时不会产生发送 marker；POST 阶段取消则保留 marker。
- 批量事务会读取 mutation response 中的来源列表；若不是来源列表则对原来源 URL 再做只读 GET。逐项删除只按选项消失判成功；移动不以普通列表回执猜目标目录。中断/进程死亡保留 pending source，成功只读核对后才逐页清除。
- Reward claim 只有匹配 `ptid` 的时间线行明确为 `completed` 才确认；页面结构缺失、行消失或仅 HTTP 200 均不确认。
- ajaxface 上传前重新 GET profile 表单并比对来源、action、method、multipart、字段结构与 facetype；上传后还要重新读取 Profile 并匹配新头像 URL，匹配不到时只报告“文件已上传，资料页保存未确认”。
- 成长 claim 不再以“没有链接/表单”推断已领取；只有明确完成文案，或真实 claim 入口存在时才分别标记 completed/available，其余为 unknown。
- 图库 dismiss 只响应向下 drag / 向下 velocity；向上 fling 不关闭。内容随拖动并缩小，背景 alpha 同步变化。
