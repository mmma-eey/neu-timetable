# 开发与构建

## 环境

- Android Studio，Android SDK Platform 36、Build-Tools 36.0.0。
- AGP 9.0.1，Gradle 9.1.0，Kotlin / Compose compiler 2.2.10。
- 推荐 Android Studio 自带 JBR 21 或更高且与 Gradle 9.1 兼容的 JDK；本地使用 JBR 25 验证。
- 最低运行版本 Android 8（API 26）。不需要安装 Google Play 服务。

将 JAVA_HOME 指向相应 JDK，确保 Android Studio 的 SDK 路径配置正确。local.properties 是本机配置，不提交。

## 构建

Windows PowerShell，在仓库根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Linux / macOS：

```bash
sh ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

首次需要联网下载 Wrapper 和依赖。若需代理，请在本机 Gradle 配置中设置，不提交账号和密码。

安装包生成于 app/build/outputs/apk/debug/app-debug.apk。版本信息在 app/build.gradle.kts；正式分发需自行保管发布密钥，不能提交密钥，也不能丢失覆盖升级使用的签名。

## 测试

单元测试使用匿名构造的 Excel 数据验证星期日列、合并格跨度、混合课程疑点、周次和冲突规则，不依赖私人课表。

另有一个可选的本地真实模板检查，不提供原件时会跳过。可通过以下方式指定你自己的样本：

```powershell
.\gradlew.bat :app:testDebugUnitTest -PcourseFixture=C:/private/neu-timetable.xlsx
```

也可使用 COURSE_FIXTURE 环境变量，或放到被 Git 忽略的 .local/private/neu-timetable.xlsx。不要将私人样本加入仓库。

WebView 集成测试使用合成网页和学校域名模拟响应，不需要学校密码：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

仅在自己允许测试的模拟器或测试设备上执行。学校网站的真实登录、动态表单与 WebVPN 兼容性仍需人工验证。

## 发布

1. 构建、测试并检查差异。
2. 检查 APK 不包含私人课表、账号或会话。
3. 将最新测试 APK 放到 releases/，移除旧包，更新 SHA256SUMS.txt、README 和版本记录。
4. 公开提交前检查 git status 与 git diff --cached，确认 .local/、local.properties、密钥和原始抓包未被跟踪。
