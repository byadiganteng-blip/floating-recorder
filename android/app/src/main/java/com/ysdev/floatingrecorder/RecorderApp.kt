package com.ysdev.floatingrecorder
import android.app.Application
class RecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        Logger.i("App", "Floating Recorder v2 started")
    }
}
