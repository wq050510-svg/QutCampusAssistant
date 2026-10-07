package cn.edu.qut.campus

import android.app.Application
import cn.edu.qut.campus.data.local.AppDatabase
import cn.edu.qut.campus.data.local.AppPreferences
import cn.edu.qut.campus.data.repository.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class QutApplication : Application() {

    companion object {
        lateinit var instance: QutApplication
            private set
    }

    /** 应用级协程作用域：用于启动预热等与界面生命周期无关的轻量任务 */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.getDatabase(this) }
    val preferences by lazy { AppPreferences(this) }

    /**
     * 全进程唯一的仓库实例。
     * 之前 MainActivity 里 `by lazy` 的仓库会随 Activity 重建而重建，
     * 且首次访问发生在首帧组合期（Room 构建 + SharedPreferences 读盘 + 学业 JSON 解析），
     * 属于实打实的掉帧来源。
     */
    val repository by lazy { ScheduleRepository() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 预热：把数据库构建、偏好读盘与本地 JSON 解析提前到后台线程完成
        applicationScope.launch {
            runCatching {
                preferences.darkModeOption
                preferences.campus
                repository
            }
        }
    }
}
