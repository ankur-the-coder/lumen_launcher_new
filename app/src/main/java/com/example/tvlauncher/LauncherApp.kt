package com.example.tvlauncher

import android.app.Application

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Wall.reload(this)
    }
}
