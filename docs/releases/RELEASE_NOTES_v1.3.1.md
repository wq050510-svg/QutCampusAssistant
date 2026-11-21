# 青理校园助手 v1.3.1 发布日志

> v1.3.0 之后的**收尾优化**：以工程质量与可维护性为主，另有一处用户可见改进。功能行为与 v1.3.0 保持一致。

---

## 一、可见改进

- **每日时间轴视图补上「实践/短学期课程」分区**。
  v1.3.0 只在周视图网格下方加了实践环节分区，切到日视图后实践课又会"消失"。现在日视图同样按整学期列出实践课并高亮本周，
  与周视图口径完全一致（实践课 `dayOfWeek` 恒为 7、`weeksList` 可能为空，按天过滤会让它们永久不可见，因此两处都按整学期列出）；
  实践课列表为空时不渲染空占位。
- **「切换就读校区」弹窗合并为同一个组件**。课表页与「我的」页此前各维护一份几乎相同的弹窗，
  课表页那份还写死了 `cName == "黄岛校区"` 的判断；现在两处共用 `ui/components/CampusPickerDialog`，
  校区候选统一来自 `CampusPeriod.ALL_CAMPUSES`，将来新增校区不会再漏改。

## 二、架构：课表 / 成绩 / 考试三页引入 ViewModel 分层

新增 `ui/viewmodel/ScheduleViewModel.kt`、`GradesViewModel.kt`、`ExamsViewModel.kt`，把状态与同步逻辑从 composable 搬进 ViewModel：

- **状态不再因切 Tab 丢失**：`MainNavigation` 用 `when(currentTab)` 切换页面，离开组合状态就销毁。
  此前周次、周/日视图、选中的星期、成绩筛选与去重开关、错误态全部被打回默认值；现在切 Tab、旋屏、系统回收重建都能保留。
- **同步任务不会因页面离开组合而被取消**：切到别的 Tab 时，正在进行的成绩/考试同步可以正常跑完。
- **职责清晰**：composable 只负责渲染与转发事件；`Snackbar`/`Toast` 仍留在页面层，ViewModel 只暴露错误文案与一次性成功事件
  （成绩页「同步成功」改为一次性事件，旋屏不会重复弹）。
- 课表页内联的校区弹窗（约 55 行）替换为公共组件，消除重复实现。

## 三、文案外置（可维护性）

界面中文文案**全部**外置到 `res/values/strings.xml`：**11 条 → 239 条**，按模块前缀分组：

`common_ / nav_ / state_ / login_ / profile_ / academic_ / calendar_ / schedule_ / grades_ / exams_ / widget_`

- 覆盖登录、课表、成绩、考试、学业、我的、系统日历联动、桌面小组件与日历同步服务；
- `contentDescription`、`AlertDialog`、`Snackbar`、`Toast` 一并处理；
- 带变量的文案改用位置占位符，例如 `grades_failed_count="%1$d 门"`、
  `calendar_sync_success="同步成功！已将 %1$d 节%2$s课程写入手机日历，并开启提前 %3$d 分钟提醒"`；
- 同文共用一条资源（如"同步失败：%1$s"、"关闭"、"黄岛校区"），不重复建键；
- 后续要改文案只需改 `strings.xml`，不必再翻 Kotlin 代码。

**有意保留硬编码**的部分（不是遗漏）：

| 内容 | 原因 |
|---|---|
| `contains("选修")`、`"正常考试"`、`contains("重修")`、`loginType == "sso"/"zf"` | 与教务返回数据/偏好值比对的关键字，不是展示文案，翻译会直接导致逻辑失效 |
| `" • "`、`" | "`、`"$label："`、`"$year-$semester"` 等 | 纯 ASCII 结构拼接与格式，非语言文案 |
| `AppPreferences.DEFAULT_CAMPUS` 等数据默认值 | 数据层默认值，会被传给 `CampusPeriod.getTimeRange` 等数据 API |
| `readableSyncError()` 里的网络错误文案 | 数据层没有 `Context`，为此传 Context 会破坏分层；如需多语言应改为返回错误类型、由 UI 层映射 |

## 四、顺手修掉的小问题

- 成绩页筛选的「全部学期」哨兵值提取为 `GradesViewModel.SEMESTER_ALL`，避免同一字面量在多处比较时被人改坏；
- 考试页校区 Flow 补上空串兜底（此前偏好为空时，副标题会渲染出尾随的「 • 」）；
- `ExamCard` 的默认校区改用 `AppPreferences.DEFAULT_CAMPUS` 单一来源；
- 清理三处失效的 `AnimatedVisibility` import 与一条失效字符串资源（校区弹窗合并后 `schedule_campus_dialog_desc` 不再被引用）。

## 五、验证

- `assembleRelease` / `assembleDebug` 均构建通过；
- 真机 OnePlus PHB110（Android 13）覆盖安装、启动渲染无异常；
- 文案外置后所有 `R.string.*` 引用均在 `strings.xml` 中存在（编译期强校验），无孤立引用；
- R8 产物复核：Gson 模型类名、Room `AppDatabase_Impl`、小组件 Provider 均保留，未使用图标类与 Glance 全部裁掉。

## 六、已知限制（未做，非本次范围）

- ViewModel 未接 `SavedStateHandle`：切 Tab / 旋屏不丢状态，但**进程被系统回收后的冷启动**仍会回到默认周次与视图；
- ViewModel 为 Activity 作用域，退出登录不会重置「本会话已自动同步」标记（登录时会全量同步并刷新 `lastSyncAt`，可见行为等价）；
- 桌面小组件、系统日历写入、以及需要真实教务账号的数据链路仍需在真机上人工验证。
