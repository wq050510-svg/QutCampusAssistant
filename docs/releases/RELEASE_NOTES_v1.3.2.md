# 青理校园助手 v1.3.2 发布日志

> 本次修复了你实测反馈的**桌面小组件内容被截断**问题，并完成两项重构：ViewModel 状态恢复（SavedStateHandle）与错误类型化（AppError）。

---

## 一、修复：桌面小组件「多节课时内容显示不完全 / 被压缩」

**现象**：一天有多节课时，小组件底部的内容被裁掉（第二门课只露出半行）。

**根因**（已在真机复现并定位）：小组件布局是「固定高度 + 固定两块课程行」，可见行数**与桌面实际分配的高度无关**。
真机实测你这台 OnePlus PHB110 上该实例只分到 **250×120dp**（`dumpsys appwidget`），而旧内容需要「顶栏 46dp + 卡片1 46dp + 卡片2 40dp + 还有N门课 16dp + 根 padding 20dp ≈ 170dp+」，
容器只会把子视图硬塞进被压缩的空间，超出部分由桌面裁掉 → 就是你看到的截断。

**修复**：
- **按真实可用高度自适应**：读取 `AppWidgetManager` 的 `OPTION_APPWIDGET_MIN_HEIGHT` / `MAX_HEIGHT`（为 0 时回退清单声明的 `minHeight`），
  据此决定显示门数：≥180dp → 3 门、≥145dp → 2 门、≥80dp → 1 门。阈值由布局常量推导，不写魔数。
- **布局改为不裁切**：根布局 `match_parent`、内部全部 `wrap_content`；每行固定 **32dp**、顶栏固定 **34dp**；
  所有文字单行 + `ellipsize`，行高固定因此**绝不会因为换行挤掉下一行**；隐藏行一律 `View.GONE`（`INVISIBLE` 仍会占高）。
- **「还有 N 门课」不再额外占一行**，而是占用最后一行课程位，课程区总高恒定不溢出。
- 新增 **`onAppWidgetOptionsChanged`**：手工拖动改变尺寸后立即重算，不必等 30 分钟刷新周期。
- 空状态插画由 72dp 降到固定 36dp；新增 `minResizeHeight=40dp` 允许压得更小；`minHeight` 保持 120dp（2 格高度仍可添加）。
- **顺带修掉两个只有真机才会暴露的问题**：
  1. 布局用裸 `View` 控件画分隔线，而 **RemoteViews 不允许该类**，真机上小组件直接显示「载入窗口小部件时出现问题」
     （日志：`Class not allowed to be inflated android.view.View`）→ 改用无子 View 的 `LinearLayout`。
  2. 时间列此前传两行文本（`10:05\n11:55`）但布局是单行，Android 补了省略号显示成「10:05…」→ 现在只传开始时间。

**验收方式**：在**你自己的手机、你桌面上已存在的那个 120dp 实例、你的真实课表**上对比修复前后（见 release 附件截图）。

---

## 二、ViewModel 状态恢复（SavedStateHandle）

- 课表 / 成绩 / 考试的 ViewModel 接入 `SavedStateHandle`，持久化：**当前查看的周次、周/日视图、选中的星期、成绩学期筛选与去重开关、考试折叠状态**。
  → 切 Tab、旋屏**以及进程被系统回收后的冷启动**都不再丢（此前进程死亡后这些会回到默认值）。
- **真机验证**：选中第 8 周 → `am kill` 杀掉进程（PID 由 21300 变为空、重启后为 13371，确认进程确实重建）→ 恢复后仍显示**第 8 周**。
- 为让恢复真正生效，做了两处必要调整（已注释说明）：
  1. `onResume()` 只在**跨天**时才把日视图的选中日重置为今天——否则每次回到前台都会冲掉刚恢复的选择（跨天刷新的原有行为保留）；
  2. 成绩页「选中学期不在列表则回落全部学期」的守卫增加「本地已有成绩」条件——否则冷启动首帧 `stateIn` 的初始空列表会把恢复出的学期清掉。

---

## 三、错误类型化（AppError）

- 新增 `data/error/AppError.kt`：把异常与教务系统的真实失败文案归类为
  `Network / Timeout / SessionExpired / InvalidCredentials / ServerDataMissing / SecureConnection / Unknown` 七类。
  分类规则先按异常类型（`UnknownHostException` / `ConnectException` / `SocketTimeoutException` / `SSLException`），
  再按文案关键字；**已收集 `ZhengFangClient` 里全部 18 条真实失败文案并逐条跑过分类**。
- 新增 `ui/components/ErrorMessage.kt`：错误类型 → 字符串资源（数据层不引用 `R.`，保持分层）。
- 删除 `StateViews.kt` 里硬编码文案的 `readableSyncError`；ViewModel 暴露 `AppError?` 而不是 `String?`，UI 统一用 `error.text()`。
- 新增 7 条 `error_*` 资源；`Unknown` 复用已有的 `common_network_error`，未重复建键。
- 有意保留的差异：旧文案「网络连接被拒绝，可能是校园网需要重新认证」目前归入通用 Network 文案（需要保留专用提示的话加一条资源即可）。

---

## 四、其它

- 新增 `MANUAL_TEST_CHECKLIST.md`：**桌面小组件与系统日历联动的真机手测清单**（这两块依赖 Launcher 与系统 `CalendarProvider`，无法自动化验证），
  含逐项预期结果、证据要求与反馈模板。
- 版本：`versionCode 6` / `versionName 1.3.2`。

---

## 已知限制

- **错误类型化的 UI 渲染未能在真机验证**：需要以错误凭据登录（会清掉已登录的教务数据），而用系统代理制造网络失败对该 App 的 keep-alive 连接无效。
  分类逻辑与资源映射已通过编译与逐条代码走查；如需真机确认，可在**未登录的机器**上用错误密码登录观察提示。
- **跨天冷启动**（进程被回收且期间已跨天）仍会显示上次保存的星期；若要严格「回到今天」，需要额外持久化一个日期基准来判断。
- `fallbackToDestructiveMigration()` 仍在：以后改表结构必须自己写 Migration，否则会静默删库。
- release 仍使用 debug 密钥签名（为兼容老用户覆盖安装）；正式发布需自备 keystore，换密钥后老用户必须先卸载。
