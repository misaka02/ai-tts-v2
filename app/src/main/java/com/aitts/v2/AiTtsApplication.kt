package com.aitts.v2

import android.app.Application
import com.aitts.v2.di.AppContainer

class AiTtsApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.logger.info("APP", "AI-TTS v2 初始化完成 (offlineIncluded=${container.offlineIncluded})")
    }
}
