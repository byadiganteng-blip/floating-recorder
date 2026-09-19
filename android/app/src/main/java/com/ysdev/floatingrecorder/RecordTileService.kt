package com.ysdev.floatingrecorder

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class RecordTileService : TileService() {

    override fun onClick() {
        super.onClick()
        try {
            val intent = Intent(this, FloatingRecorderService::class.java).apply {
                action = FloatingRecorderService.ACTION_TOGGLE_AUDIO
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: Throwable) {
            Logger.e("Tile", "onClick: " + e.message)
        }
    }
}
