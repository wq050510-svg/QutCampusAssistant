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
import cn.edu.qut.campus.data.local.AppPreferences
import cn.edu.qut.campus.data.model.Exam
import cn.edu.qut.campus.data.repository.ScheduleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.regex.Pattern

/**
 * 自动同步节流窗口：距上次成功同步未超过该时长时，切回考试 Tab 不再重复请求教务系统。
 */
private const val AUTO_SYNC_THROTTLE_MS = 30 * 60 * 1000L

/**
 * 容错解析考试时间 "2026-09-09(14:00-15:50)" 用于排序。
 * 解析失败返回 null，调用方保持原顺序，绝不抛异常。
 */
private fun examStartTimeOrNull(examTime: String): LocalDateTime? = try {
    val dateMatcher = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(examTime)
    if (!dateMatcher.find()) null
    else {
        val dateStr = dateMatcher.group(1)
        val timeMatcher = Pattern.compile("(\\d{2}:\\d{2})").matcher(examTime)
        val startTimeStr = if (timeMatcher.find()) timeMatcher.group(1) else "00:00"
        LocalDateTime.parse("${dateStr}T${startTimeStr}:00")
    }
} catch (e: Exception) {
    null
}

/**
 * 稳定排序：只有解析成功的考试参与排序，解析失败的原样留在末尾，
 * 从而保证「解析失败时保持原顺序」。解析结果预先算好，避免比较器里反复解析。
 */
private fun sortExamsByTime(exams: List<Exam>, descending: Boolean): List<Exam> {
    val withTime = exams.map { it to examStartTimeOrNull(it.examTime) }
    val parsed = withTime.filter { it.second != null }
    val unparsed = withTime.filter { it.second == null }
    val sorted = parsed.sortedWith(
        (if (descending) {
            compareByDescending<Pair<Exam, LocalDateTime?>> { it.second }
        } else {
            compareBy<Pair<Exam, LocalDateTime?>> { it.second }
        }).thenBy { it.first.id }
    ).map { it.first }
    return sorted + unparsed.map { it.first }
}

/**
 * 考试页状态持有者。
 *
 * 原先这些状态都是 ExamsScreen 里的 remember / rememberSaveable：MainNavigation 用
 * when(currentTab) 切页，页面一离开组合状态就丢，切回来会重新走一遍自动同步。
 * 搬进 ViewModel 后，切 Tab / 旋屏（配置变更）都不再丢，且同步任务不会因为页面离开组合被取消。
 *
 * 只存放状态与业务调用，Snackbar 仍由页面负责：这里只暴露错误类型。
 *
 * 「已结束考试」的折叠状态额外写进 [SavedStateHandle]：
 * ViewModel 只能扛住配置变更与切 Tab，进程被回收后冷启动仍需恢复（原先靠 rememberSaveable）。
 */
class ExamsViewModel(
    private val repository: ScheduleRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** 考试列表（Room → Flow）。WhileSubscribed：页面不可见 5s 后停止订阅数据库 */
    val exams: StateFlow<List<Exam>> = repository.examsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 校区用 Flow 读取，切换校区后本页立即跟着刷新（原来是一次性快照）。
     * prefs 里可能是空串，这里兜底到默认校区，避免副标题渲染出尾随的「 • 」。
     */
    val campus: StateFlow<String> = repository.prefs.campusFlow
        .map { it.ifEmpty { AppPreferences.DEFAULT_CAMPUS } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            repository.prefs.campus.ifEmpty { AppPreferences.DEFAULT_CAMPUS }
        )

    private val _isSyncing = MutableStateFlow(false)

    /** 是否正在同步（右上角按钮转圈 / 按钮禁用都用它） */
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncError = MutableStateFlow<AppError?>(null)

    /** 同步失败原因（页面用 LaunchedEffect 消费成 Snackbar，并在失败态卡片里展示） */
    val syncError: StateFlow<AppError?> = _syncError.asStateFlow()

    private val _syncSucceeded = MutableStateFlow(false)
    private val _syncTriggered = MutableStateFlow(false)

    // 三态互斥：syncing(加载中) / syncError(失败) / syncSucceeded+空列表(确实没有考试)
    val syncInProgress: StateFlow<Boolean> =
        combine(_isSyncing, _syncTriggered, _syncSucceeded, _syncError) { syncing, triggered, succeeded, error ->
            syncing || (triggered && !succeeded && error == null)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 历史已结束考试的折叠展开状态（持久化） */
    val showFinishedExams: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(KEY_SHOW_FINISHED_EXAMS, false)

    /** 未结束的考试按时间正序（最近的排最前）。排序结果由 ViewModel 暴露，页面只渲染 */
    val upcomingExams: StateFlow<List<Exam>> = exams
        .map { list -> sortExamsByTime(list.filter { !it.isFinished }, descending = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 已结束的考试按时间倒序（最新的排最前） */
    val finishedExams: StateFlow<List<Exam>> = exams
        .map { list -> sortExamsByTime(list.filter { it.isFinished }, descending = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 本次 App 会话内是否已经自动同步过。
     * 原先用 rememberSaveable（切 Tab 会丢，只是靠 lastSyncAt 节流兜住），
     * 这里改成 ViewModel 私有标记，效果是同一个会话内只自动同步一次。
     */
    private var autoSyncDone = false

    fun toggleFinishedExams() {
        savedStateHandle[KEY_SHOW_FINISHED_EXAMS] = !showFinishedExams.value
    }

    /**
     * 考试页原先没有任何刷新入口，只有登录流程会写入考试数据（现在有了显式刷新）；
     * 进入页面时自动补一次（只补一次），避免登录后换设备/清库看不到考试。
     */
    fun autoSyncIfNeeded() {
        if (autoSyncDone) return
        autoSyncDone = true
        val localEmpty = exams.value.isEmpty()
        val stale = System.currentTimeMillis() - repository.prefs.lastSyncAt > AUTO_SYNC_THROTTLE_MS
        if (localEmpty || stale) {
            _syncTriggered.value = true
            sync()
        }
    }

    /** 手动同步：右上角刷新 / 失败重试 / 空态按钮 */
    fun refresh() = sync()

    private fun sync() {
        if (syncInProgress.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                val res = repository.syncExams()
                if (res.isSuccess) {
                    _syncError.value = null
                    _syncSucceeded.value = true
                    repository.prefs.lastSyncAt = System.currentTimeMillis()
                } else {
                    _syncError.value = res.exceptionOrNull().toAppError()
                }
            } catch (e: CancellationException) {
                // 协程取消不是同步失败，必须原样抛出
                throw e
            } catch (e: Exception) {
                _syncError.value = e.toAppError()
            } finally {
                _isSyncing.value = false
            }
        }
    }

    companion object {
        // SavedStateHandle 的 key：进程被系统回收后冷启动要还原折叠状态
        private const val KEY_SHOW_FINISHED_EXAMS = "exams_show_finished"

        fun factory(repository: ScheduleRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ExamsViewModel(repository, createSavedStateHandle()) }
        }
    }
}
