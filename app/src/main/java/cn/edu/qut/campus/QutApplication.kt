package cn.edu.qut.campus

import android.app.Application
import cn.edu.qut.campus.data.local.AppDatabase
import cn.edu.qut.campus.data.local.AppPreferences

class QutApplication : Application() {

    companion object {
        lateinit var instance: QutApplication
            private set
    }

    val database by lazy { AppDatabase.getDatabase(this) }
    val preferences by lazy { AppPreferences(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
