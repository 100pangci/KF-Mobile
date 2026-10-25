# 绯月 · KF Mobile

非官方 Android 客户端，Kotlin Multiplatform + Compose Material 3，与站点运营方无隶属关系。

## 安装

本地构建后在 `artifacts/KF-Mobile-<版本>-debug.apk` 生成安装包；发布包请从 GitHub Releases 下载。支持直接覆盖旧版。

## 使用

- 登录：输入用户名密码；账号密码只提交给当前显示的域名，不会保存密码。
- 切换服务器：我的 → 偏好与连接 → 当前服务器；自定义域名和在线配置位于「高级」。每个域名的登录状态独立保存。
- 浏览：主要列表下拉刷新，接近底部自动加载下一页；再次点击当前底栏标签回到顶部。
- 搜索：独立搜索页面，键盘搜索键提交，作者条件在「筛选」中设置。
- 发帖 / 回复 / 私信：正文旁的格式工具栏支持 BBCode、连续插入表情及附件，点顶栏「发布」或「发送」直接提交。
- 付费资源：帖子内直接购买，确认前会复核售价。
- 图片：点开后全屏查看，支持缩放、左右切换和保存原图，单击隐藏或显示工具栏。
- 消息、资料、收藏、银行、排行：专用列表或阅读界面；其它站点功能保留通用兼容界面，并非全部已完成专项适配。

部分账户功能中的「暂不可用」说明列出仍未支持的少数交互（咕咕镇战斗、头像上传）。它们不会自动打开网页。

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

`local.properties` 保存本机 SDK 路径，不进入源码包。`.secrets/` 是开发登录调试目录，已忽略。

本地只读站点巡检、真实数据离线渲染、覆盖矩阵及未完成项见 [docs/native-coverage.md](docs/native-coverage.md)。巡检优先使用已有 `cookie.txt`，其次 `.secrets/<host>.cookies.txt`，不要求账号密码；真实 HTML 和截图只留在 `.secrets/site-audit/`。

## 隐私

- 不保存账号密码；登录 Cookie 只保存在本机平台加密存储中。
- `.secrets/`、Cookie 文件、`local.properties`、签名文件、构建产物与打包生成的 `artifacts/` 均被 `.gitignore` 排除。
- 仓库中的测试数据与界面预览均为合成内容，不包含真实账号资料。

## 许可证

本项目使用 [Mozilla Public License 2.0](LICENSE)（MPL-2.0）。
