# 实现结构

## 入口与界面

MainActivity.kt 使用 Jetpack Compose，包含周课表、今日、导入和设置。周课表使用 HorizontalPager 浏览相邻周。TimetableViewModel 负责解析预览、确认写入、课程编辑和提示状态。

## 领域规则

domain/Models.kt 定义 Lesson、Term、Period 和 ImportPreview，以及周次集合、冲突、字段校验。课程星期保持 ISO 编码（周一 1，周日 7）；展示顺序为 7、1、2、3、4、5、6。学期继续持久化第 1 周周一，计算周次时使用其前一天作为周日起点，兼容已有备份和数据库。

## 存储和导入

data/Records.java 定义 Room 实体和 DAO；TimetableRepository.kt 负责课程数据、学期、备份与事务。数据只保存在本机。

NeuExcelParser.kt 在本地读取 XLSX ZIP/XML，根据星期和节次标题定位，不依赖固定列号。教师、校区、教室和课程编号格式采用学校模板规则。实验课、课设默认使用合并格跨度；四节及以上的混合课程格中，普通课程标记待校对。无法识别的原文另列问题。

解析结果先形成 ImportPreview，确认后才写入数据库，可跳过疑点或选择替换、合并。

## 学校网页与文件接收

SchoolWebActivity.kt 管理用户自行登录的 WebView、子窗口和导航。默认从 WebVPN 移动导航开始，另有一号通入口。

SchoolExportCapture.kt 对学校 Excel 下载表单捕获实际 POST 参数，兼容动态同源 iframe 和 WebVPN 地址改写。登录和其他未适配表单保持网页原生流程。

SchoolBrowserDownload.kt 使用已登录 WebView 发起下载，保留表单 POST 参数，分块读入私有缓存，限制大小及超时并校验实际 XLSX。不会将 POST 改为 GET，不关闭 SSL 校验。取消或失败时清理缓存。

学校网页变化可能影响适配。其他学校需要单独处理认证、导出流程和 Excel 模板；通用领域模型、离线存储及展示可继续复用。

## 数据边界

.local/ 仅用于本机个人文件、原始抓包、旧设计和调试证据，不属于源码，不进入 Git 或 APK。docs/images/ 只放已检查可公开的图片。releases/ 只放最新测试包。APK 不携带真实样本。
