package me.fss.orbal

import android.app.Application
import me.fss.orbal.di.AppContainer

class OrbalApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
