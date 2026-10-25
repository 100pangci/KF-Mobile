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
