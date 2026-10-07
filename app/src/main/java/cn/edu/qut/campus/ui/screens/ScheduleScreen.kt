package cn.edu.qut.campus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.qut.campus.R
import cn.edu.qut.campus.data.model.CampusPeriod
import cn.edu.qut.campus.data.model.Course
import cn.edu.qut.campus.data.model.DAY_NAMES
import cn.edu.qut.campus.data.model.cleanClassroom
import cn.edu.qut.campus.data.model.dayNameOf
import cn.edu.qut.campus.data.repository.ScheduleRepository
import cn.edu.qut.campus.ui.components.CampusPickerDialog
import cn.edu.qut.campus.ui.viewmodel.ScheduleViewModel

// ---------------------------------------------------------------------------
// 文件级常量与纯函数。
// 放在组合之外：常量不再每次重组新建，颜色/地点解析也不再按卡片重复执行。
// ---------------------------------------------------------------------------

/** 学期展示周数（课表数据固定按 1-20 周展示） */
private const val WEEK_COUNT = 20

/**
 * 课表网格的大节起始节次：每 2 节为一个大节（1-2 / 3-4 / 5-6 / 7-8 / 9-10）。
 * 直接从校区作息表推导，避免「作息时间改了但网格仍然硬编码 1,3,5,7,9」的漂移。
 */
private val GRID_START_PERIODS: List<Int> =
    CampusPeriod.HUANGDAO_PERIODS.filterIndexed { index, _ -> index % 2 == 0 }.map { it.periodNumber }

/** 浅色课程卡片使用的深色前景（#4DD0E1 / #81C784 / #FFB74D 这类亮色底上白字几乎看不清） */
private val ON_LIGHT_COURSE_COLOR = Color(0xFF1F1F1F)

/** 解析课程颜色：hex 非法时回退主题色。用 remember 包住，避免每帧都走一遍异常捕获 */
@Composable
private fun rememberCourseColor(colorHex: String): Color {
    val fallback = MaterialTheme.colorScheme.primary
    return remember(colorHex, fallback) {
        try {
            Color(android.graphics.Color.parseColor(colorHex))
        } catch (e: Exception) {
            fallback
        }
    }
}

/** 按底色亮度挑前景色：亮底用深字、暗底用白字，深色 / AMOLED 主题下同样可读 */
private fun courseTextColorOn(bgColor: Color): Color =
    if (bgColor.luminance() > 0.6f) ON_LIGHT_COURSE_COLOR else Color.White

// 说明：去除教务校区前缀的字符串处理统一走公共实现
// cn.edu.qut.campus.data.model.cleanClassroom，本文件不再自带一份。

/** 课程身份：同名 + 同一天 + 同一节次视为同一门课（正方会把单双周不同教室拆成多条记录） */
private fun courseIdentity(course: Course): String =
    "${course.name}|${course.dayOfWeek}|${course.startPeriod}|${course.endPeriod}"

/** 一个课表格子的预计算结果：本周生效的课程、非本周课程、是否存在真实冲突 */
@Immutable
private data class ScheduleCell(
    val active: List<Course>,
    val inactive: List<Course>,
    val hasConflict: Boolean
)

private val EMPTY_CELL = ScheduleCell(emptyList(), emptyList(), false)

/**
 * 一次性构建「星期 → 大节起始节次 → 格子」索引。
 *
 * 原实现是在 35 个格子里各做 2 次全量 filter（含 weeksList.contains 线性扫描），
 * 现在是整份课表只遍历一遍、itemContent 里 O(1) 查表；冲突判定也在这里一次算完，
 * 不再随每个格子做 O(n²) 比较。
 */
private fun buildScheduleGrid(
    courses: List<Course>,
    selectedWeek: Int
): Map<Int, Map<Int, ScheduleCell>> {
    val buckets = HashMap<Int, HashMap<Int, MutableList<Course>>>()
    for (course in courses) {
        if (course.isPractice) continue
        if (course.dayOfWeek !in 1..7) continue
        for (start in GRID_START_PERIODS) {
            val end = start + 1
            if (course.startPeriod <= end && course.endPeriod >= start) {
                buckets.getOrPut(course.dayOfWeek) { HashMap() }
                    .getOrPut(start) { mutableListOf() }
                    .add(course)
            }
        }
    }

    val grid = HashMap<Int, Map<Int, ScheduleCell>>(buckets.size)
    for ((day, cells) in buckets) {
        val dayCells = HashMap<Int, ScheduleCell>(cells.size)
        for ((start, records) in cells) {
            val active = mutableListOf<Course>()
            val inactive = mutableListOf<Course>()
            // 同名校同节次的多条记录（单双周分教室）：本周只保留真正生效的那条，
            // 并且不判为冲突——否则每周都会误报一次「时间冲突」。
            for ((_, group) in records.groupBy { courseIdentity(it) }) {
                val activeInGroup = group.firstOrNull { it.isActiveInWeek(selectedWeek) }
                if (activeInGroup != null) active.add(activeInGroup) else inactive.add(group.first())
            }
            dayCells[start] = ScheduleCell(
                active = active,
                inactive = inactive,
                hasConflict = active.size > 1
            )
        }
        grid[day] = dayCells
    }
    return grid
}

/** 日视图的课程列表与冲突伙伴（一次算完，卡片里只做 O(1) 查表） */
private data class DaySchedule(
    val courses: List<Course>,
    val conflictPartners: Map<String, List<Course>>
)

private fun buildDaySchedule(courses: List<Course>, activeDay: Int, selectedWeek: Int): DaySchedule {
    val dayCourses = courses
        .filter { it.dayOfWeek == activeDay && it.isActiveInWeek(selectedWeek) && !it.isPractice }
        // 单双周不同教室产生的同课多条记录：本周只有一条生效，这里再按课程身份去重
        .distinctBy { courseIdentity(it) }
        .sortedBy { it.startPeriod }

    val partners = HashMap<String, List<Course>>()
    for (course in dayCourses) {
        val others = dayCourses.filter { other ->
            other.id != course.id &&
                other.name != course.name &&
                maxOf(course.startPeriod, other.startPeriod) <= minOf(course.endPeriod, other.endPeriod)
        }
        if (others.isNotEmpty()) partners[course.id] = others
    }
    return DaySchedule(dayCourses, partners)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    repository: ScheduleRepository,
    onNavigateToCalendar: () -> Unit
) {
    // 状态与「回到前台重算当前周」逻辑都在 ViewModel 里：
    // 原先用 remember / rememberSaveable，底部 Tab 切换会销毁本页，状态全部丢失
    val vm: ScheduleViewModel = viewModel(factory = ScheduleViewModel.factory(repository))

    val courses by vm.courses.collectAsStateWithLifecycle()
    val currentCampus by vm.currentCampus.collectAsStateWithLifecycle()
    // 学期名（如 2026-2027-1）由开学日期推导，不再写死
    val termLabel by vm.termLabel.collectAsStateWithLifecycle()

    // 以下关键 UI 状态原先用 rememberSaveable / remember：搬进 ViewModel 后由 ViewModel 持有，
    // 这里不再需要 rememberSaveable（切 Tab 回来也不再被打回默认值）。
    val currentWeek by vm.currentWeek.collectAsStateWithLifecycle()
    val selectedWeek by vm.selectedWeek.collectAsStateWithLifecycle()
    val isDailyView by vm.isDailyView.collectAsStateWithLifecycle()
    val showCampusDialog by vm.showCampusDialog.collectAsStateWithLifecycle()
    val selectedCoursesDetail by vm.selectedCoursesDetail.collectAsStateWithLifecycle()

    // 1 (周一) - 7 (周日)
    val todayDayOfWeek by vm.todayDayOfWeek.collectAsStateWithLifecycle()
    val activeDay by vm.activeDay.collectAsStateWithLifecycle()

    // 回到前台时重新推导「当前第几周 / 今天星期几」：
    // 否则 App 长期驻留后台（隔天甚至隔周再打开）周次会停留在打开那一刻。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.onResume()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.schedule_week_title, selectedWeek),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (selectedWeek == currentWeek) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.schedule_current_week),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                // 触控高度至少 48dp：这一行是切换校区的入口
                                .heightIn(min = 48.dp)
                                .clickable { vm.openCampusDialog() }
                        ) {
                            Text(
                                text = "$currentCampus • $termLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = stringResource(R.string.profile_switch_campus),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                actions = {
                    // 切换周/日视图
                    IconButton(onClick = { vm.toggleDailyView() }) {
                        Icon(
                            imageVector = if (isDailyView) Icons.Default.ViewWeek else Icons.Default.ViewDay,
                            contentDescription = if (isDailyView) {
                                stringResource(R.string.schedule_switch_to_week_view)
                            } else {
                                stringResource(R.string.schedule_switch_to_day_view)
                            }
                        )
                    }
                    // 跳转日历同步
                    IconButton(onClick = onNavigateToCalendar) {
                        Icon(Icons.Default.Event, contentDescription = stringResource(R.string.schedule_sync_calendar))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 周次快捷选择条 (1-20周)
            WeekSelectorBar(
                selectedWeek = selectedWeek,
                currentWeek = currentWeek,
                onWeekSelected = { vm.selectWeek(it) }
            )

            if (isDailyView) {
                // 每日时间轴视图
                DailyScheduleView(
                    courses = courses,
                    selectedWeek = selectedWeek,
                    activeDay = activeDay,
                    onDaySelected = { vm.selectDay(it) },
                    currentCampus = currentCampus,
                    onCoursesClick = { vm.openCourseDetail(it) }
                )
            } else {
                // 经典周课表网格视图
                WeeklyGridView(
                    courses = courses,
                    selectedWeek = selectedWeek,
                    todayDayOfWeek = todayDayOfWeek,
                    currentCampus = currentCampus,
                    onCoursesClick = { vm.openCourseDetail(it) }
                )
            }
        }

        // 课程详情弹窗（支持单门与多门冲突课程并列查看）
        selectedCoursesDetail?.let { coursesDetail ->
            CourseDetailBottomSheet(
                courses = coursesDetail,
                selectedWeek = selectedWeek,
                currentCampus = currentCampus,
                onDismiss = { vm.closeCourseDetail() }
            )
        }

        // 切换校区对话框：与「我的」页共用同一个组件，避免两份重复实现各自维护校区候选
        if (showCampusDialog) {
            CampusPickerDialog(
                current = currentCampus,
                onSelect = { vm.selectCampus(it) },
                onDismiss = { vm.closeCampusDialog() }
            )
        }
    }
}

// 周次选择滑动条
@Composable
fun WeekSelectorBar(
    selectedWeek: Int,
    currentWeek: Int,
    onWeekSelected: (Int) -> Unit
) {
    val listState = rememberLazyListState()

    // 进入页面 / 当前周变化（跨周回到前台）时，把「本周」自动滚到可见位置
    LaunchedEffect(currentWeek) {
        listState.animateScrollToItem((currentWeek - 1).coerceIn(0, WEEK_COUNT - 1))
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(WEEK_COUNT, key = { it }) { index ->
            val w = index + 1
            val isSelected = w == selectedWeek
            val isCurrent = w == currentWeek
            FilterChip(
                selected = isSelected,
                onClick = { onWeekSelected(w) },
                label = {
                    Text(
                        text = if (isCurrent) {
                            stringResource(R.string.schedule_week_chip_current, w)
                        } else {
                            stringResource(R.string.schedule_week_chip, w)
                        },
                        fontSize = 12.sp,
                        fontWeight = if (isSelected || isCurrent) FontWeight.Bold else FontWeight.Normal
                    )
                },
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

// 经典周课表网格
@Composable
fun WeeklyGridView(
    courses: List<Course>,
    selectedWeek: Int,
    todayDayOfWeek: Int,
    currentCampus: String,
    onCoursesClick: (List<Course>) -> Unit
) {
    // 星期 → 大节起始节次 → 格子：只在课表数据或所看周次变化时重建一次
    val scheduleGrid = remember(courses, selectedWeek) { buildScheduleGrid(courses, selectedWeek) }
    // 实践 / 短学期课程不进正常网格，单独在下方分区展示（否则会被 !isPractice 过滤到任何界面都看不见）
    val practiceCourses = remember(courses) {
        courses.filter { it.isPractice }.sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 星期表头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.schedule_header_period), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (day in 1..7) {
                val isToday = day == todayDayOfWeek
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = DAY_NAMES[day - 1],
                        fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (isToday) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)

        // 课表主体网格
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(GRID_START_PERIODS, key = { it }) { startPeriod ->
                val endPeriod = startPeriod + 1
                val (startTime, endTime) = CampusPeriod.getTimeRange(startPeriod, endPeriod, currentCampus)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                ) {
                    // 左侧节次与作息时间
                    Column(
                        modifier = Modifier
                            .width(36.dp)
                            .fillMaxHeight()
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("$startPeriod", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(startTime, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(endTime, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$endPeriod", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // 1-7 周一至周日课程格：只做 O(1) 查表，过滤与冲突判定已在 remember 块里算完
                    for (day in 1..7) {
                        val cell = scheduleGrid[day]?.get(startPeriod) ?: EMPTY_CELL

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(1.5.dp)
                        ) {
                            if (cell.hasConflict) {
                                // 同一节存在两门及以上不同课程：真实时间冲突
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable { onCoursesClick(cell.active) },
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    cell.active.take(2).forEach { course ->
                                        MiniCourseCard(
                                            course = course,
                                            isActive = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                // 冲突提示标（语义色，深色 / AMOLED 下不再是刺眼浅红块）
                                Surface(
                                    color = MaterialTheme.colorScheme.error,
                                    shape = RoundedCornerShape(bottomStart = 6.dp, topEnd = 6.dp),
                                    modifier = Modifier.align(Alignment.TopEnd)
                                ) {
                                    Text(
                                        text = if (cell.active.size == 2) {
                                            stringResource(R.string.schedule_conflict)
                                        } else {
                                            stringResource(R.string.schedule_conflict_count, cell.active.size)
                                        },
                                        color = MaterialTheme.colorScheme.onError,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                    )
                                }
                            } else if (cell.active.size == 1) {
                                CourseCard(
                                    course = cell.active.first(),
                                    isActive = true,
                                    onClick = { onCoursesClick(cell.active) }
                                )
                            } else if (cell.inactive.isNotEmpty()) {
                                // 非本周课弱化透明显示
                                if (cell.inactive.size == 1) {
                                    CourseCard(
                                        course = cell.inactive.first(),
                                        isActive = false,
                                        onClick = { onCoursesClick(cell.inactive) }
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { onCoursesClick(cell.inactive) },
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        cell.inactive.take(2).forEach { course ->
                                            MiniCourseCard(
                                                course = course,
                                                isActive = false,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(bottomStart = 6.dp, topEnd = 6.dp),
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.schedule_inactive_count, cell.inactive.size),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 8.sp,
                                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
            }

            // 实践环节分区：实践 / 短学期课程不占正常课表格子，但不能因此「消失」
            if (practiceCourses.isNotEmpty()) {
                item(key = "practice_section") {
                    PracticeSection(
                        practiceCourses = practiceCourses,
                        selectedWeek = selectedWeek,
                        onCoursesClick = onCoursesClick
                    )
                }
            }
        }
    }
}

/**
 * 实践 / 短学期课程分区。
 *
 * 这类课程（isPractice = true）在教务数据里没有可用的真实星期与节次，
 * 之前被周网格与日视图的 `!isPractice` 过滤后在任何界面都看不到（与 README「实践课不遗漏」矛盾）。
 * 这里整学期列出，本周生效的高亮并打上「本周」标记，字段缺失时显示「待定」。
 */
@Composable
fun PracticeSection(
    practiceCourses: List<Course>,
    selectedWeek: Int,
    onCoursesClick: (List<Course>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.schedule_practice_section),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.schedule_practice_count, practiceCourses.size),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        practiceCourses.forEach { course ->
            PracticeCourseCard(
                course = course,
                isThisWeek = course.isActiveInWeek(selectedWeek),
                onClick = { onCoursesClick(listOf(course)) }
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun PracticeCourseCard(
    course: Course,
    isThisWeek: Boolean,
    onClick: () -> Unit
) {
    val accentColor = rememberCourseColor(course.colorHex)
    // 文案只能取在组合里：remember 的 calculation lambda 不是 @Composable，不能直接调用 stringResource
    val tbdText = stringResource(R.string.common_tbd)
    val weeksText = remember(course.weeksDescription, tbdText) { course.weeksDescription.ifBlank { tbdText } }
    val teacherText = remember(course.teacher, tbdText) { course.teacher.ifBlank { tbdText } }
    val classroomText = remember(course.classroom, tbdText) { cleanClassroom(course.classroom).ifBlank { tbdText } }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isThisWeek) 0.6f else 0.3f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$weeksText • $teacherText",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.schedule_place_line, classroomText),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isThisWeek) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.schedule_current_week),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

// 课程小方块卡片
@Composable
fun CourseCard(
    course: Course,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val bgBase = rememberCourseColor(course.colorHex)
    val bgColor = if (isActive) bgBase else bgBase.copy(alpha = 0.25f)
    // 亮色课程卡（#4DD0E1 / #81C784 / #FFB74D 等）用深色字，暗色用白字
    val textColor = if (isActive) courseTextColorOn(bgBase) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val classroomText = remember(course.classroom) { cleanClassroom(course.classroom) }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = course.name,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp
            )

            Column {
                if (course.isRetake) {
                    Text(
                        text = stringResource(R.string.schedule_retake_badge),
                        // 固定黄色在浅色课程底上同样看不清，统一跟随对比度选出的前景色
                        color = textColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = classroomText,
                    color = textColor,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// 冲突时同一节紧凑迷你卡片
@Composable
fun MiniCourseCard(
    course: Course,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val bgBase = rememberCourseColor(course.colorHex)
    val bgColor = if (isActive) bgBase else bgBase.copy(alpha = 0.25f)
    val textColor = if (isActive) courseTextColorOn(bgBase) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val classroomText = remember(course.classroom) { cleanClassroom(course.classroom) }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = course.name,
                color = textColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = classroomText,
                color = textColor.copy(alpha = 0.9f),
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// 每日时间轴视图
@Composable
fun DailyScheduleView(
    courses: List<Course>,
    selectedWeek: Int,
    activeDay: Int,
    onDaySelected: (Int) -> Unit,
    currentCampus: String,
    onCoursesClick: (List<Course>) -> Unit
) {
    // 过滤 / 排序 / 冲突计算只在这里做一次，itemContent 里只查表
    val dayData = remember(courses, activeDay, selectedWeek) {
        buildDaySchedule(courses, activeDay, selectedWeek)
    }
    val dayCourses = dayData.courses
    // 实践 / 短学期课程：判定口径与周视图完全一致（isPractice == true，整学期列出、不按当天过滤）。
    // 这类课程 dayOfWeek 恒为 7、weeksList 可能为空，按天过滤会让它们在任何一天都不可见。
    val practiceCourses = remember(courses) {
        courses.filter { it.isPractice }.sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }))
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 星期选择栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (d in 1..7) {
                val isSelected = d == activeDay
                OutlinedButton(
                    onClick = { onDaySelected(d) },
                    shape = RoundedCornerShape(10.dp),
                    colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Text(DAY_NAMES[d - 1], fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (dayCourses.isEmpty() && practiceCourses.isEmpty()) {
            // 当天没有课程、也没有实践 / 短学期课程：沿用原来的居中空状态
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Weekend,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.schedule_day_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // 有内容时统一放进同一个 LazyColumn：当日课程与实践环节分区一起正常滚动
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 当天没有正课但整学期有实践环节时，仍然保留「该日无课程」提示
                if (dayCourses.isEmpty()) {
                    item(key = "day_empty_hint") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillParentMaxHeight(0.6f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Weekend,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(stringResource(R.string.schedule_day_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                items(dayCourses, key = { it.id }) { course ->
                    val (sTime, eTime) = CampusPeriod.getTimeRange(course.startPeriod, course.endPeriod, currentCampus)
                    val cardColor = rememberCourseColor(course.colorHex)
                    val classroomText = remember(course.classroom) { cleanClassroom(course.classroom) }

                    // 冲突伙伴已在 remember 块里算好，这里直接取
                    val conflictingWithThis = dayData.conflictPartners[course.id].orEmpty()
                    val hasConflict = conflictingWithThis.isNotEmpty()

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onCoursesClick(if (hasConflict) listOf(course) + conflictingWithThis else listOf(course))
                            },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(5.dp)
                                    .height(50.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(cardColor)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = course.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (hasConflict) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.schedule_time_conflict),
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "$classroomText • ${course.teacher}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "$sTime - $eTime",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.schedule_period_range, course.startPeriod, course.endPeriod),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 实践环节分区：复用周视图的同一套 PracticeSection / PracticeCourseCard，整学期列出、本周高亮
                if (practiceCourses.isNotEmpty()) {
                    item(key = "practice_section") {
                        PracticeSection(
                            practiceCourses = practiceCourses,
                            selectedWeek = selectedWeek,
                            onCoursesClick = onCoursesClick
                        )
                    }
                }
            }
        }
    }
}

// 课程详情 BottomSheet（支持多门冲突课程并列查看）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailBottomSheet(
    courses: List<Course>,
    selectedWeek: Int,
    currentCampus: String,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .navigationBarsPadding()
        ) {
            val isConflict = courses.size > 1

            if (isConflict) {
                // 冲突警告横幅（语义色：深色 / AMOLED 下不再是一大块浅红）
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.schedule_conflict_banner_title, courses.size),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.schedule_conflict_banner_desc, selectedWeek),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(courses, key = { it.id }) { course ->
                    SingleCourseDetailCard(course = course, currentCampus = currentCampus)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SingleCourseDetailCard(
    course: Course,
    currentCampus: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 提到 Column 作用域：下面 Row 内外都要用，避免作用域外引用
            val classroomText = remember(course.classroom) { cleanClassroom(course.classroom) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val courseColor = rememberCourseColor(course.colorHex)
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(courseColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = course.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (course.isRetake) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.schedule_retake_course),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val tbdText = stringResource(R.string.common_tbd)
            DetailItem(
                icon = Icons.Default.Room,
                label = stringResource(R.string.schedule_label_classroom),
                value = "$classroomText ($currentCampus)"
            )
            DetailItem(
                icon = Icons.Default.Person,
                label = stringResource(R.string.schedule_label_teacher),
                value = course.teacher.ifEmpty { tbdText }
            )
            DetailItem(
                icon = Icons.Default.DateRange,
                label = stringResource(R.string.schedule_label_weeks),
                value = course.weeksDescription.ifBlank { tbdText }
            )
            val (s, e) = CampusPeriod.getTimeRange(course.startPeriod, course.endPeriod, currentCampus)
            DetailItem(
                icon = Icons.Default.Schedule,
                label = stringResource(R.string.schedule_label_period),
                // 实践 / 短学期课程没有真实节次，避免显示按兜底作息算出的假时间
                value = if (course.isPractice) {
                    stringResource(R.string.schedule_practice_period_hint)
                } else {
                    stringResource(
                        R.string.schedule_period_detail,
                        dayNameOf(course.dayOfWeek),
                        course.startPeriod,
                        course.endPeriod,
                        s,
                        e
                    )
                }
            )
            DetailItem(
                icon = Icons.Default.Stars,
                label = stringResource(R.string.schedule_label_credit),
                value = stringResource(R.string.schedule_credit_value, course.credit)
            )
        }
    }
}

@Composable
fun DetailItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "$label：",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
