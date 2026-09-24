package cn.edu.qut.campus.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.qut.campus.data.model.CampusPeriod
import cn.edu.qut.campus.data.model.Course
import cn.edu.qut.campus.data.repository.ScheduleRepository
import java.time.LocalDate

fun cleanClassroom(classroom: String): String {
    return classroom
        .replace("黄岛校区-", "")
        .replace("市北校区-", "")
        .replace("黄岛-", "")
        .replace("市北-", "")
        .trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    repository: ScheduleRepository,
    onNavigateToCalendar: () -> Unit
) {
    val courses by repository.coursesFlow.collectAsState(initial = emptyList())
    val currentWeek = remember { repository.calculateCurrentWeek() }
    var selectedWeek by remember { mutableStateOf(currentWeek) }
    var isDailyView by remember { mutableStateOf(false) }
    var selectedCoursesDetail by remember { mutableStateOf<List<Course>?>(null) }
    var currentCampus by remember { mutableStateOf(repository.prefs.campus.ifEmpty { "黄岛校区" }) }
    var showCampusDialog by remember { mutableStateOf(false) }

    val todayDayOfWeek = remember { LocalDate.now().dayOfWeek.value } // 1 (Mon) - 7 (Sun)
    val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "第 $selectedWeek 周",
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
                                        text = "本周",
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
                                .clickable { showCampusDialog = true }
                        ) {
                            Text(
                                text = "$currentCampus • 2026-2027-1",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "切换校区",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                actions = {
                    // 切换周/日视图
                    IconButton(onClick = { isDailyView = !isDailyView }) {
                        Icon(
                            imageVector = if (isDailyView) Icons.Default.ViewWeek else Icons.Default.ViewDay,
                            contentDescription = "切换视图"
                        )
                    }
                    // 跳转日历同步
                    IconButton(onClick = onNavigateToCalendar) {
                        Icon(Icons.Default.Event, contentDescription = "同步日历")
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
                onWeekSelected = { selectedWeek = it }
            )

            if (isDailyView) {
                // 每日时间轴视图
                DailyScheduleView(
                    courses = courses,
                    selectedWeek = selectedWeek,
                    todayDayOfWeek = todayDayOfWeek,
                    currentCampus = currentCampus,
                    onCoursesClick = { selectedCoursesDetail = it }
                )
            } else {
                // 经典周课表网格视图
                WeeklyGridView(
                    courses = courses,
                    selectedWeek = selectedWeek,
                    todayDayOfWeek = todayDayOfWeek,
                    dayNames = dayNames,
                    currentCampus = currentCampus,
                    onCoursesClick = { selectedCoursesDetail = it }
                )
            }
        }

        // 课程详情弹窗（支持单门与多门冲突课程并列查看）
        selectedCoursesDetail?.let { coursesDetail ->
            CourseDetailBottomSheet(
                courses = coursesDetail,
                selectedWeek = selectedWeek,
                currentCampus = currentCampus,
                onDismiss = { selectedCoursesDetail = null }
            )
        }

        // 切换校区对话框
        if (showCampusDialog) {
            AlertDialog(
                onDismissRequest = { showCampusDialog = false },
                title = { Text("切换就读校区") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "请选择当前就读校区，将自动更新课表作息与地点呈现：",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        CampusPeriod.ALL_CAMPUSES.forEach { cName ->
                            val isSelected = cName == currentCampus
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        repository.prefs.campus = cName
                                        currentCampus = cName
                                        showCampusDialog = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = cName,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (cName == "黄岛校区") "嘉陵江东路" else "抚顺路",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCampusDialog = false }) {
                        Text("关闭")
                    }
                }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (w in 1..20) {
            val isSelected = w == selectedWeek
            val isCurrent = w == currentWeek
            FilterChip(
                selected = isSelected,
                onClick = { onWeekSelected(w) },
                label = {
                    Text(
                        text = if (isCurrent) "第$w 周(今)" else "第$w 周",
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
    dayNames: List<String>,
    currentCampus: String,
    onCoursesClick: (List<Course>) -> Unit
) {
    val periods = listOf(1, 3, 5, 7, 9) // 代表 1-2节, 3-4节, 5-6节, 7-8节, 9-10节

    Column(modifier = Modifier.fillMaxSize()) {
        // 星期表头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                Text("节次", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        text = dayNames[day - 1],
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
            items(periods) { startPeriod ->
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

                    // 1-7 周一至周日课程格
                    for (day in 1..7) {
                        val cellCourses = courses.filter {
                            it.dayOfWeek == day &&
                            it.startPeriod <= endPeriod &&
                            it.endPeriod >= startPeriod &&
                            !it.isPractice
                        }
                        val activeCourses = cellCourses.filter { it.isActiveInWeek(selectedWeek) }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(1.5.dp)
                        ) {
                            if (activeCourses.isNotEmpty()) {
                                if (activeCourses.size == 1) {
                                    CourseCard(
                                        course = activeCourses.first(),
                                        isActive = true,
                                        onClick = { onCoursesClick(activeCourses) }
                                    )
                                } else {
                                    // 发生时间冲突（同一节有多门课程）
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { onCoursesClick(activeCourses) },
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        activeCourses.take(2).forEach { course ->
                                            MiniCourseCard(
                                                course = course,
                                                isActive = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    // 冲突提示红标
                                    Surface(
                                        color = Color(0xFFD32F2F),
                                        shape = RoundedCornerShape(bottomStart = 6.dp, topEnd = 6.dp),
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    ) {
                                        Text(
                                            text = if (activeCourses.size == 2) "冲突" else "${activeCourses.size}冲突",
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            } else if (cellCourses.isNotEmpty()) {
                                // 非本周课弱化透明显示
                                if (cellCourses.size == 1) {
                                    CourseCard(
                                        course = cellCourses.first(),
                                        isActive = false,
                                        onClick = { onCoursesClick(cellCourses) }
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { onCoursesClick(cellCourses) },
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        cellCourses.take(2).forEach { course ->
                                            MiniCourseCard(
                                                course = course,
                                                isActive = false,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    Surface(
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(bottomStart = 6.dp, topEnd = 6.dp),
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    ) {
                                        Text(
                                            text = "${cellCourses.size}门",
                                            color = Color.White,
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
    val bgBase = try {
        Color(android.graphics.Color.parseColor(course.colorHex))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }
    val bgColor = if (isActive) bgBase else bgBase.copy(alpha = 0.25f)
    val textColor = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

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
                        text = "重修",
                        color = if (isActive) Color.Yellow else textColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = cleanClassroom(course.classroom),
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
    val bgBase = try {
        Color(android.graphics.Color.parseColor(course.colorHex))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }
    val bgColor = if (isActive) bgBase else bgBase.copy(alpha = 0.25f)
    val textColor = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

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
                text = cleanClassroom(course.classroom),
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
    todayDayOfWeek: Int,
    currentCampus: String,
    onCoursesClick: (List<Course>) -> Unit
) {
    var activeDay by remember { mutableStateOf(todayDayOfWeek) }
    val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 星期选择栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (d in 1..7) {
                val isSelected = d == activeDay
                OutlinedButton(
                    onClick = { activeDay = d },
                    shape = RoundedCornerShape(10.dp),
                    colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Text(dayNames[d - 1], fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val dayCourses = courses.filter {
            it.dayOfWeek == activeDay && it.isActiveInWeek(selectedWeek) && !it.isPractice
        }.sortedBy { it.startPeriod }

        if (dayCourses.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Weekend,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("该日无课程安排，好好享受校园时光吧~", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(dayCourses) { course ->
                    val (sTime, eTime) = CampusPeriod.getTimeRange(course.startPeriod, course.endPeriod, currentCampus)
                    val cardColor = try {
                        Color(android.graphics.Color.parseColor(course.colorHex))
                    } catch (e: Exception) {
                        MaterialTheme.colorScheme.primary
                    }

                    val conflictingWithThis = dayCourses.filter { other ->
                        other != course &&
                        maxOf(course.startPeriod, other.startPeriod) <= minOf(course.endPeriod, other.endPeriod)
                    }
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
                                            color = Color(0xFFFFCDD2),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "时间冲突",
                                                color = Color(0xFFC62828),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${cleanClassroom(course.classroom)} • ${course.teacher}",
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
                                    text = "第${course.startPeriod}-${course.endPeriod}节",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
                // 冲突警告横幅
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
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
                            tint = Color(0xFFD32F2F),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "课程时间重叠安排（共 ${courses.size} 门）",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD32F2F),
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "第 $selectedWeek 周检测到以下课程上课时间存在重叠，请向任课教师确认上课或考试安排：",
                                fontSize = 12.sp,
                                color = Color(0xFFC62828),
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
                items(courses) { course ->
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val courseColor = try {
                        Color(android.graphics.Color.parseColor(course.colorHex))
                    } catch (e: Exception) {
                        MaterialTheme.colorScheme.primary
                    }
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
                        color = Color(0xFFFFCC80),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "重修课程",
                            color = Color(0xFFE65100),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            DetailItem(icon = Icons.Default.Room, label = "上课地点", value = "${cleanClassroom(course.classroom)} ($currentCampus)")
            DetailItem(icon = Icons.Default.Person, label = "任课教师", value = course.teacher.ifEmpty { "待定" })
            DetailItem(icon = Icons.Default.DateRange, label = "开课周次", value = course.weeksDescription)
            val (s, e) = CampusPeriod.getTimeRange(course.startPeriod, course.endPeriod, currentCampus)
            DetailItem(icon = Icons.Default.Schedule, label = "上课节次", value = "星期${course.dayOfWeek} 第${course.startPeriod}-${course.endPeriod}节 ($s ~ $e)")
            DetailItem(icon = Icons.Default.Stars, label = "课程学分", value = "${course.credit} 学分")
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
