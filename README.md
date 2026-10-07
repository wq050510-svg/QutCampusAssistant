# 青理校园助手 (QUT Campus Assistant)
> 专为青岛理工大学学生定制的现代化校园综合服务 App（支持 Android 8.0+ 及现代 Material You）

当前版本：**v1.3.1**（versionCode 5）｜ 详见 [RELEASE_NOTES_v1.3.1.md](RELEASE_NOTES_v1.3.1.md) 与 [RELEASE_NOTES_v1.3.0.md](RELEASE_NOTES_v1.3.0.md)

---

## 项目特色与核心功能清单

### 1. 教务极速直连（纯原生，无需跳转网页）
- **原生 RSA/ECB/PKCS1Padding 密码加密**：直接调用教务公钥接口加密后登录；
  拿不到公钥或加密失败时**会中止登录并提示**，绝不降级为明文提交。
- **双通道登录**：统一身份认证（sso.qut.edu.cn）与正方教务直接登录。
- **密码本地加密存储**：学号/密码经 Android Keystore 的 AES/GCM 密钥加密后落盘，不使用明文 SharedPreferences。
- **离线秒开机制**：同步后数据由 Jetpack Room 本地持久化，无网状态可查看课表/成绩/考试。

### 2. 双视图智能课表
- **周课表网格视图**：支持 1~20 周横向切周，进入页面自动定位当前周；
- **按周次精确匹配**：非本周课程弱化显示，单双周、多段周次的课程按各自周次判定；
- **多小节连上合并**：1-2 节、3-4 节、5-6 节、9-10 节自动合并为大卡片展示；
- **实践/短学期课程独立分区**：不再被过滤掉，单独展示课程名、周次与教师；
- **同课同色柔和马卡龙**：按课程名称哈希分配护眼莫兰迪色系，并按底色亮度自动选择文字颜色；
- 点击卡片弹出 BottomSheet，详细显示教室、教师、学分、周次范围及重修标识。
- **每日时间轴视图**：聚焦当天课程，展示上课起止时间。

### 3. 手机系统日历智能联动（带闹钟提醒）
- **独立日历账户隔离**：在系统日历中注册独立的 `青岛理工大学课表` 账户，一键同步或一键清空，不污染个人日程；
- **精准映射校区作息**：将全学期各周课程展开为公历事件，精确到分钟；
- **统一的提前提醒设置**：提醒时长在「我的」与「日历」两处共用同一份设置（10/15/20/30 分钟）。

### 4. 桌面小组件（极简透明插画样式）
- **已支持昨日 / 今日 / 明日三态切换**，偏移量持久化，进程被杀不会重置；
- **跨零点自动回到"今天"**，不会继续显示昨天的课表；
- 无课时呈现手绘文具插画与提示；有课时展示教学楼/教室、起止时间，超过两门课提示"还有 N 门课"；
- 数据读取失败时明确提示"课表读取失败，点击刷新"，不再谎报"今天没有课哦"。

### 5. 考试安排与座位号直通车
- 抓取期末/补考安排，展示科目、起止时间、考场地点与**座位号**；
- **按考试时间排序**，未结束的最近一场排在最前；页面内可直接刷新。

### 6. 历年成绩单与学业画像
- 成绩栏目展示历年考试成绩（重修后成绩自动替换更新）；
- 学业画像呈现学院官方审定的平均学分绩点（GPA）、修读总学分、模块达成与未通过课程预警；
- **只展示教务真实返回的数据**：字段缺失时显示 `—` 与刷新提示，不用编造的默认值填充。

### 7. 深色模式（AMOLED 纯黑优化）
- 深度适配系统深色主题，深色为纯黑背景，护眼且省电；
- 界面颜色统一走 Material 3 语义色，不再有深色下刺眼的写死浅色块。

---

## 工程目录结构

```
QutCampusAssistant/
 ├── app/
 │    ├── src/main/
 │    │    ├── AndroidManifest.xml          # 权限、组件与备份规则
 │    │    ├── java/cn/edu/qut/campus/
 │    │    │    ├── QutApplication.kt        # 全局 Application（数据库/偏好/仓库单例 + 启动预热）
 │    │    │    ├── data/
 │    │    │    │    ├── model/              # 数据实体 (Course, Exam, Grade, User, CampusPeriod, AcademicProgress)
 │    │    │    │    ├── network/            # 网络客户端与 RSA 加密 (ZhengFangClient)
 │    │    │    │    ├── local/              # Room (AppDatabase/DAO) + 偏好 (AppPreferences) + 凭据加密 (CredentialCipher)
 │    │    │    │    └── repository/         # 数据仓库层 (ScheduleRepository)
 │    │    │    ├── service/                 # 系统日历写入服务 (CalendarSyncManager)
 │    │    │    ├── widget/                  # 桌面小组件 (ScheduleWidgetProvider，经典 RemoteViews 实现)
 │    │    │    └── ui/
 │    │    │         ├── components/         # 公共组件 (StateViews 三态、CampusPickerDialog)
 │    │    │         ├── theme/              # Material 3 主题、莫兰迪配色与深色模式
 │    │    │         ├── viewmodel/          # 页面状态持有者 (Schedule/Grades/Exams ViewModel)
 │    │    │         ├── screens/            # 登录、课表、日历、考试、成绩、学业、我的
 │    │    │         └── MainActivity.kt     # 入口 Activity
 │    │    └── res/                          # 图标（自适应 launcher）、小组件布局、主题、字符串
 │    ├── build.gradle.kts                   # App 模块构建脚本（R8/资源压缩/签名）
 │    └── proguard-rules.pro                 # R8 keep 规则（Gson/Room/AppWidget）
 ├── gradle/libs.versions.toml                # 统一依赖版本库
 ├── keystore.properties                      # 正式签名凭据（可选，已在 .gitignore 中）
 ├── build.gradle.kts / settings.gradle.kts
 └── gradle.properties                        # Gradle 性能与 R8 开关
```

---

## 构建与安装

### 1. 命令行构建（推荐）

```powershell
# Debug 包（包名带 .debug 后缀，可与正式包共存）
.\gradlew :app:assembleDebug

# Release 包（开启 R8 混淆与资源压缩，产物在 app\build\outputs\apk\release\）
.\gradlew :app:assembleRelease
```

未提供 `keystore.properties` 时，release 会回退使用本机 debug 密钥签名 —— 这是为了让已经安装 v1.2.0 的用户能**直接覆盖安装**。
若要使用正式密钥，在工程根目录新建 `keystore.properties`（该文件已被 `.gitignore` 忽略）：

```properties
storeFile=D:\\keys\\qut-release.jks
storePassword=你的store密码
keyAlias=qut-release
keyPassword=你的key密码
```

生成正式密钥（**务必离线备份，丢失后无法再更新已发布的包**）：

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore qut-release.jks `
  -alias qut-release -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12 `
  -dname "CN=QutCampusAssistant, O=QUT Campus, L=Qingdao, ST=Shandong, C=CN"
```

### 2. Android Studio
1. 用 Android Studio 打开本目录；
2. 手机开启【USB 调试】并连接；
3. 点击绿色 **Run** 即可安装（注意：debug 变体包名为 `cn.edu.qut.campus.debug`，与正式包互不影响）。

### 3. 桌面小组件
长按桌面（或双指捏合）→ 【小部件/微件】→ 找到【青理校园助手】→ 添加【今日课表(极简透明)】。

---

## 免责声明
本应用为**非官方**的第三方工具，仅用于个人便捷查询自己的课表、成绩与考试信息；所有数据均实时来自学校教务系统，最终以教务系统为准。
账号密码仅用于在本机直连学校系统，不会上传到任何第三方服务器；退出登录会清除本地全部缓存与凭据。
