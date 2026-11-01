# 绯月 · KF Mobile

非官方 Android 客户端，Kotlin Multiplatform + Compose Material 3，与站点运营方无隶属关系。

## 安装

本地构建后在 `artifacts/KF-Mobile-<版本>-debug.apk` 生成安装包；发布包请从 GitHub Releases 下载。Debug 包名为 `work.miaola.kf.mobile.debug`，Release 包名为 `work.miaola.kf.mobile`，两者签名不同但可同时安装，应用数据互不共享。

## 使用

- 登录：输入用户名密码；账号密码只提交给当前显示的域名，不会保存密码。
- 切换服务器：我的 → 偏好与连接 → 当前服务器；自定义域名和在线配置位于「高级」。每个域名的登录状态独立保存。
- 浏览：主要列表下拉刷新，接近底部自动加载下一页；再次点击当前底栏标签回到顶部。
- 搜索：独立搜索页面，键盘搜索键提交，作者条件在「筛选」中设置。
- 发帖 / 回复 / 私信：正文旁的格式工具栏支持 BBCode、连续插入表情及附件，点顶栏「发布」或「发送」直接提交。
- 付费资源：帖子内直接购买，确认前会复核售价。
- 图片：点开后全屏查看，支持缩放、左右切换和保存原图，单击隐藏或显示工具栏。
- 消息、资料、作者详情（`show.php`）、收藏、银行、排行、签到、评分、奖励、奖励子页、咕咕镇：专用列表或阅读界面；评分入口确认后进入站点自己的评分表单，提交前回读同一会话并复核令牌与字段；奖励子页的物品使用/出售/打开/装备/熔炼、商店购买与光环购买只把页面真实脚本控件变成逐项确认动作，熔炼等破坏性操作沿用站点确认文案；阅读页底部的“页面操作”提供推荐与页面实际渲染的管理动作，管理动作逐项确认。咕咕镇的道具使用菜单、礼物、翻牌、许愿池、宝石工坊、属性分配、光环天赋、满级提升、单场骰子战争与改名读取同一独立游戏会话，只把页面实际提供的可信脚本控件变成逐项确认动作；站点禁用改名输入时不显示改名动作，骰子战争不会跟随网页连续战斗。
- 消息支持按来源页导出与清空；清空前会确认，导出不会把网页回执当作文件。
- 其它同源页面由统一原生渲染器按真实结构呈现 sections、表格、链接与表单，写入控件逐项确认后提交。

咕咕镇的动作全部来自页面真实渲染并完整匹配已捕获协议的控件，客户端不构造页面未提供的请求，也不以网页代替原生结果。

## 构建

先安装 JDK 21 与 Android SDK，并按需设置环境变量。PowerShell 示例：

```powershell
$env:JAVA_HOME = 'C:\Path\To\jdk21'
$env:ANDROID_HOME = 'C:\Path\To\AndroidSdk'
# 可选：自定义 Gradle 缓存目录
# $env:GRADLE_USER_HOME = 'C:\Path\To\GradleUserHome'

.\gradlew.bat :shared:desktopTest :androidApp:assembleDebug :androidApp:lintDebug
python tools/package.py
```

可选的线上写回核对只针对已确认的测试账号，默认跳过；账号名与当前登录名不一致时会在发出写请求前拒绝。`KF_LIVE_WRITE_TEST` 覆盖签到（仅在状态明确为 `AVAILABLE` 时发送）和一次可撤销的收藏添加→删除往返（读回收藏列表与 Reader 后结束，单次请求、不重试）：

```powershell
$env:KF_LIVE_WRITE_TEST = 'true'
$env:KF_TEST_ACCOUNT = '<测试账号>'
.\gradlew.bat :shared:desktopTest --tests 'work.miaola.kf.LiveSiteTest'
Remove-Item Env:KF_LIVE_WRITE_TEST
Remove-Item Env:KF_TEST_ACCOUNT
```

`KF_LIVE_FULL_WRITE_TEST` 另行门控已授权的完整写回核对：光环购买（读回 KFB/贡献）、站点皮肤更换与恢复、给自己发送一条测试私信并在收件箱读回后删除、评分入口建立评分会话，以及一条**不可撤销**的评分最终提交。评分入口测试只进入站点评分页并核对原生表单；最终提交测试只选择标题带体积且标注 `[自购]` 的未评分行，复核同一会话的 `safeid`/`ptid` 后提交一次，回读“已经有评分记录”且表单消失。最终提交会在站点留下真实评分记录，默认跳过；运行前必须由账号本人明确批准：

```powershell
$env:KF_LIVE_FULL_WRITE_TEST = 'true'
$env:KF_TEST_ACCOUNT = '<测试账号>'
.\gradlew.bat :shared:desktopTest --tests 'work.miaola.kf.LiveWriteCoverageTest'
Remove-Item Env:KF_LIVE_FULL_WRITE_TEST
Remove-Item Env:KF_TEST_ACCOUNT
```

如只想执行评分最终提交，使用 `--tests 'work.miaola.kf.LiveWriteCoverageTest.ratingSubmissionHasLiveSideEffect'`；仪表盘当前没有标题带体积且标注 `[自购]` 的未评分行时，测试不发送请求并结束。

`local.properties` 保存本机 SDK 路径，不进入源码包。`.secrets/` 是开发登录调试目录，已忽略。

本地只读站点巡检、真实数据离线渲染、覆盖矩阵与验证范围见 [docs/native-coverage.md](docs/native-coverage.md)。巡检优先使用已有 `cookie.txt`，其次 `.secrets/<host>.cookies.txt`，不要求账号密码；真实 HTML 和截图只留在 `.secrets/site-audit/`。

## 发布与签名

Release APK 使用独立密钥签名，不复用 debug 签名。Release 构建启用 R8 全模式代码收缩、优化、混淆与资源收缩，并保留 `androidApp/proguard-rules.pro` 中的 kotlinx.serialization 序列化器规则。密钥、密码和 `keystore.properties` 均被 `.gitignore` 排除，不要提交。首次生成密钥并配置本地签名：

```powershell
keytool -genkeypair -keystore .secrets/kf-release.jks -storetype PKCS12 `
  -alias kf-release -keyalg RSA -keysize 4096 -sigalg SHA256withRSA `
  -validity 10000 -dname "CN=KF Mobile Release, OU=Android, O=miaola, C=CN"
Copy-Item keystore.properties.example keystore.properties
# 编辑 keystore.properties，填入与密钥一致的两次密码
.\gradlew.bat :androidApp:assembleRelease
```

产物位于 `androidApp/build/outputs/apk/release/androidApp-release.apk`，混淆映射位于同目录旁的 `androidApp/build/outputs/mapping/release/`。

`androidApp/build.gradle.kts` 优先读取 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 环境变量，未设置时回退到根目录的 `keystore.properties`；两者都没有时 release 构建不配置签名。可用 `apksigner verify --print-certs <apk>` 核对签名证书；工作流同时固定校验发布证书的 SHA-256 指纹，更换密钥时需同步更新 `.github/workflows/release.yml` 中的期望值。

`.github/workflows/release.yml` 在推送 `v*` 标签（或手动触发）时运行测试、签名 release 构建与 lint，校验 APK 签名证书，上传 `KF-Mobile-<版本>-release.apk`、`SHA256SUMS.txt` 和 R8 混淆映射文件（映射文件只作为 CI artifact 保留，不放入公开 Release），并在标签构建时创建或更新对应 GitHub Release。发布前需在仓库 Actions Secrets 中配置：

- `RELEASE_KEYSTORE_BASE64`：密钥文件的 Base64 单行文本
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`（当前为 `kf-release`）
- `RELEASE_KEY_PASSWORD`

可用 `gh` 设置；密码与 Base64 值不要出现在命令历史或日志中：

```powershell
gh secret set RELEASE_KEYSTORE_BASE64 < .secrets/kf-release.jks.b64
gh secret set RELEASE_KEYSTORE_PASSWORD   # 交互输入
gh secret set RELEASE_KEY_ALIAS           # 当前为 kf-release
gh secret set RELEASE_KEY_PASSWORD        # 交互输入
```

标签名必须与 `androidApp/build.gradle.kts` 中的 `versionName` 一致（当前为 `v0.3.1`），否则工作流失败。发布流程：

```powershell
git tag -a v0.3.1 -m "KF Mobile 0.3.1"
git push origin v0.3.1
```

请备份 `.secrets/kf-release.jks` 和密码；密钥丢失后无法以同一应用身份为已安装版本提供更新。

## 隐私

- 不保存账号密码；登录 Cookie 只保存在本机平台加密存储中。
- `.secrets/`、Cookie 文件、`local.properties`、签名文件、构建产物与打包生成的 `artifacts/` 均被 `.gitignore` 排除。
- 仓库中的测试数据与界面预览均为合成内容，不包含真实账号资料。

## 许可证

本项目使用 [Mozilla Public License 2.0](LICENSE)（MPL-2.0）。
