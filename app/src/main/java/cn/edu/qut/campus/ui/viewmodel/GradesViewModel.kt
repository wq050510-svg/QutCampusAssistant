package cn.edu.qut.campus.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.qut.campus.data.error.AppError
import cn.edu.qut.campus.data.error.toAppError
import cn.edu.qut.campus.data.model.Grade
import cn.edu.qut.campus.data.repository.ScheduleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 自动同步节流窗口：距上次成功同步未超过该时长且本地已有缓存时，
 * 切回成绩 Tab 不再重复请求教务系统（避免每次切 Tab 都白等一次网络往返）。
 */
private const val AUTO_SYNC_THROTTLE_MS = 30 * 60 * 1000L

/**
 * 成绩页状态持有者。
 *
 * 原先这些状态都是 GradesScreen 里的 remember / rememberSaveable：MainNavigation 用
 * when(currentTab) 切页，页面一离开组合状态就丢，切回来会重新走一遍自动同步。
 * 搬进 ViewModel 后，切 Tab / 旋屏（配置变更）都不再丢，且同步任务不会因为页面离开组合被取消。
 *
 * 只存放状态与业务调用，Toast / Snackbar 仍由页面负责：这里只暴露错误类型与一次性成功事件。
 *
 * 用户的筛选选择（当前学期 / 去重开关）额外写进 [SavedStateHandle]：
 * ViewModel 只能扛住配置变更与切 Tab，进程被回收后冷启动仍需恢复（原先靠 rememberSaveable）。
 */
class GradesViewModel(
    private val repository: ScheduleRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** 成绩列表（Room → Flow）。WhileSubscribed：页面不可见 5s 后停止订阅数据库 */
    val grades: StateFlow<List<Grade>> = repository.gradesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)

    /** 是否正在刷新（右上角按钮转圈 / 按钮禁用都用它） */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _syncError = MutableStateFlow<AppError?>(null)

    /** 同步失败原因（页面用 LaunchedEffect 消费成 Snackbar，并在失败态卡片里展示） */
    val syncError: StateFlow<AppError?> = _syncError.asStateFlow()

    private val _syncSucceeded = MutableStateFlow(false)
    private val _syncTriggered = MutableStateFlow(false)

    // 三态互斥：syncing(加载中) / syncError(失败) / syncSucceeded+空列表(确实没有数据)
    // 同步中（含本次进入还没跑过自动同步的首帧，避免先闪一帧空状态）
    val syncInProgress: StateFlow<Boolean> =
        combine(_isRefreshing, _syncTriggered, _syncSucceeded, _syncError) { refreshing, triggered, succeeded, error ->
            refreshing || (triggered && !succeeded && error == null)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _syncSuccessEvent = MutableStateFlow(false)

    /** 一次性「成绩同步成功」事件：页面消费后调用 [consumeSyncSuccess]，旋屏不会重复弹 */
    val syncSuccessEvent: StateFlow<Boolean> = _syncSuccessEvent.asStateFlow()

    /** 重修覆盖去重模式（默认启用：重修通过后只保留通过后的最高成绩）；持久化 */
    val isDeduplicated: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(KEY_IS_DEDUPLICATED, true)

    /** 当前选中的学期筛选（[SEMESTER_ALL] 表示不筛选）；持久化 */
    val selectedSemester: StateFlow<String> =
        savedStateHandle.getStateFlow(KEY_SELECTED_SEMESTER, SEMESTER_ALL)

    /**
     * 本次 App 会话内是否已经自动同步过。
     * 原先用 rememberSaveable：切 Tab 会丢（只是靠 lastSyncAt 节流兜住），这里改成 ViewModel 私有标记，
     * 效果是同一个会话内只自动同步一次。
     */
    private var autoSyncDone = false

    fun consumeSyncSuccess() {
        _syncSuccessEvent.value = false
    }

    fun toggleDeduplicated() {
        savedStateHandle[KEY_IS_DEDUPLICATED] = !isDeduplicated.value
    }

    fun selectSemester(semester: String) {
        savedStateHandle[KEY_SELECTED_SEMESTER] = semester
    }

    /**
     * 自动同步策略：本次进入只自动同步一次，
     * 且距上次成功同步未超过节流窗口且本地已有数据时不再重复打教务系统。
     * 页面在进入组合时调用一次即可（重复调用会被 [autoSyncDone] 挡住）。
     */
    fun autoSyncIfNeeded() {
        if (autoSyncDone) return
        autoSyncDone = true
        val localEmpty = grades.value.isEmpty()
        val stale = System.currentTimeMillis() - repository.prefs.lastSyncAt > AUTO_SYNC_THROTTLE_MS
        if (localEmpty || stale) {
            _syncTriggered.value = true
            sync(showSuccessToast = false)
        }
    }

    /** 手动同步：右上角刷新 / 失败重试 / 空态按钮 */
    fun refresh() = sync(showSuccessToast = true)

    private fun sync(showSuccessToast: Boolean) {
        if (syncInProgress.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val res = repository.syncGrades()
                val err = res.exceptionOrNull()
                if (res.isSuccess) {
                    _syncError.value = null
                    _syncSucceeded.value = true
                    repository.prefs.lastSyncAt = System.currentTimeMillis()
                    if (showSuccessToast) {
                        _syncSuccessEvent.value = true
                    }
                } else {
                    _syncError.value = err.toAppError()
                }
            } catch (e: CancellationException) {
                // 协程取消不是「同步失败」，必须原样抛出，否则会把页面销毁误报成网络错误
                throw e
            } catch (e: Exception) {
                _syncError.value = e.toAppError()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    companion object {
        /**
         * 学期筛选的「全部学期」哨兵值：与 [Grade.academicYear] 拼出的学期 key 做比较，
         * 属于数据层比较值，不落字符串资源；页面渲染时用 R.string.grades_semester_all 显示。
         */
        const val SEMESTER_ALL = "全部学期"

        // SavedStateHandle 的 key：进程被系统回收后冷启动要还原用户上次的筛选
        private const val KEY_SELECTED_SEMESTER = "grades_selected_semester"
        private const val KEY_IS_DEDUPLICATED = "grades_is_deduplicated"

        fun factory(repository: ScheduleRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { GradesViewModel(repository, createSavedStateHandle()) }
        }
    }
}
