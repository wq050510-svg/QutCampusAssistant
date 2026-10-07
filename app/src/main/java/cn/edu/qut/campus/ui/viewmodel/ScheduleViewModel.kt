package cn.edu.qut.campus.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.qut.campus.data.local.AppPreferences
import cn.edu.qut.campus.data.model.Course
import cn.edu.qut.campus.data.repository.ScheduleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/**
 * 课表页状态持有者。
 *
 * 原先这些状态都是 ScheduleScreen 里的 remember / rememberSaveable：MainNavigation 用
 * when(currentTab) 切页，页面一离开组合状态就全丢（周次、周/日视图、选中的星期、课程详情弹窗都会被打回默认值）。
 * 搬进 ViewModel 后，切 Tab / 旋屏（配置变更）都不再丢。
 *
 * 这里同时接管「回到前台重算当前周 / 今天星期几」的逻辑（原先写在 LifecycleEventEffect 里），
 * 页面只负责在 ON_RESUME 时转发一次 [onResume]。
 *
 * 用户的选择（当前周次 / 周-日视图 / 选中星期）额外写进 [SavedStateHandle]：
 * ViewModel 只能扛住配置变更与切 Tab，进程被系统回收后仍需恢复，
 * 这部分状态此前靠 rememberSaveable 保住，搬进 ViewModel 后必须补回。
 */
class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** 课表数据（Room → Flow）。WhileSubscribed：页面不可见 5s 后停止订阅数据库 */
    val courses: StateFlow<List<Course>> = repository.coursesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前校区：prefs 里可能是空串，此时兜底到黄岛校区（与其它页面一致） */
    val currentCampus: StateFlow<String> = repository.prefs.campusFlow
        .map { it.ifEmpty { AppPreferences.DEFAULT_CAMPUS } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            repository.prefs.campus.ifEmpty { AppPreferences.DEFAULT_CAMPUS }
        )

    /** 学期名（如 2026-2027-1）由开学日期推导，不再写死 */
    private val _termLabel = MutableStateFlow(repository.prefs.termLabel)
    val termLabel: StateFlow<String> = _termLabel.asStateFlow()

    // ------------------------------------------------------------------
    // 关键 UI 状态。
    // 原先用 rememberSaveable（旋屏不丢、切 Tab 仍会丢），搬进 ViewModel 后不再需要；
    // 其中「用户选择」类状态还写进 SavedStateHandle，进程回收后冷启动也能恢复。
    // ------------------------------------------------------------------

    /** 构造时算一次的当前周：同时作为 [selectedWeek] 的持久化默认值，避免重复推导 */
    private val initialCurrentWeek = repository.calculateCurrentWeek()

    /** 当前教学周（按开学日期推导，回到前台会重算） */
    private val _currentWeek = MutableStateFlow(initialCurrentWeek)
    val currentWeek: StateFlow<Int> = _currentWeek.asStateFlow()

    /**
     * 当前查看的周次：默认跟随本周。
     * 用 SavedStateHandle 持久化：进程被回收后冷启动仍停留在用户上次看的周次。
     */
    val selectedWeek: StateFlow<Int> =
        savedStateHandle.getStateFlow(KEY_SELECTED_WEEK, initialCurrentWeek)

    /** 周视图 / 每日时间轴视图（持久化） */
    val isDailyView: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(KEY_IS_DAILY_VIEW, false)

    /** 1 (周一) - 7 (周日)；今天星期几不持久化，由 [onResume] 重算 */
    private val _todayDayOfWeek = MutableStateFlow(LocalDate.now().dayOfWeek.value)
    val todayDayOfWeek: StateFlow<Int> = _todayDayOfWeek.asStateFlow()

    /** 日视图选中的星期（持久化） */
    val activeDay: StateFlow<Int> =
        savedStateHandle.getStateFlow(KEY_ACTIVE_DAY, _todayDayOfWeek.value)


    /** 切换校区对话框 */
    private val _showCampusDialog = MutableStateFlow(false)
    val showCampusDialog: StateFlow<Boolean> = _showCampusDialog.asStateFlow()

    /** 课程详情弹窗选中的课程（支持单门与多门冲突课程并列查看） */
    private val _selectedCoursesDetail = MutableStateFlow<List<Course>?>(null)
    val selectedCoursesDetail: StateFlow<List<Course>?> = _selectedCoursesDetail.asStateFlow()

    fun selectWeek(week: Int) {
        savedStateHandle[KEY_SELECTED_WEEK] = week
    }

    fun toggleDailyView() {
        savedStateHandle[KEY_IS_DAILY_VIEW] = !isDailyView.value
    }

    fun selectDay(day: Int) {
        savedStateHandle[KEY_ACTIVE_DAY] = day
    }

    fun openCourseDetail(courses: List<Course>) {
        // 冲突/非本周课点击时可能带重复记录，先按 id 去重（与原实现一致）
        _selectedCoursesDetail.value = courses.distinctBy { it.id }
    }

    fun closeCourseDetail() {
        _selectedCoursesDetail.value = null
    }

    fun openCampusDialog() {
        _showCampusDialog.value = true
    }

    fun closeCampusDialog() {
        _showCampusDialog.value = false
    }

    /** 切换就读校区：写 prefs 会同步更新 campusFlow，界面随之刷新 */
    fun selectCampus(campus: String) {
        repository.prefs.campus = campus
    }

    /**
     * 回到前台时重新推导「当前第几周 / 今天星期几」：
     * 否则 App 长期驻留后台（隔天甚至隔周再打开）周次会停留在打开那一刻。
     * 页面在 LifecycleEventEffect(ON_RESUME) 里转发调用。
     *
     * 注意：LifecycleEventEffect 在本页重新进入组合时（含切 Tab 回来、进程回收后重建）
     * 会立即补发一次 ON_RESUME。因此「把选中星期重置为今天」只在**跨天**时才做，
     * 否则刚恢复出来的 activeDay 会被立刻冲掉，持久化形同虚设。
     */
    fun onResume() {
        val refreshedWeek = repository.calculateCurrentWeek()
        val wasFollowingCurrentWeek = selectedWeek.value == _currentWeek.value
        _currentWeek.value = refreshedWeek
        if (wasFollowingCurrentWeek) savedStateHandle[KEY_SELECTED_WEEK] = refreshedWeek
        val refreshedToday = LocalDate.now().dayOfWeek.value
        val dayChanged = refreshedToday != _todayDayOfWeek.value
        _todayDayOfWeek.value = refreshedToday
        if (dayChanged) savedStateHandle[KEY_ACTIVE_DAY] = refreshedToday
        // 学期参数可能在「我的」里被改过，回到前台顺带刷新学期展示名
        _termLabel.value = repository.prefs.termLabel
    }

    companion object {
        // SavedStateHandle 的 key：进程被系统回收后冷启动要还原用户上次的选择
        private const val KEY_SELECTED_WEEK = "schedule_selected_week"
        private const val KEY_IS_DAILY_VIEW = "schedule_is_daily_view"
        private const val KEY_ACTIVE_DAY = "schedule_active_day"

        fun factory(repository: ScheduleRepository): ViewModelProvider.Factory = viewModelFactory {
            // createSavedStateHandle() 只能在这里调用：ComponentActivity 作为
            // ViewModelStoreOwner 原生提供 SavedStateRegistryOwner，故无需额外配置
            initializer { ScheduleViewModel(repository, createSavedStateHandle()) }
        }
    }
}
