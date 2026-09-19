package com.ysdev.floatingrecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionHelper {
    const val REQ_ALL_PERMISSIONS = 1000
    const val REQ_MEDIA_PROJ = 2000

    fun getMissingPermissions(c: Context): List<String> {
        val list = ArrayList<String>()

        val audioCheck = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO)
        if (audioCheck != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val notifCheck = ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
            if (notifCheck != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val media = "android.permission.READ_MEDIA_AUDIO"
            try {
                val mediaCheck = ContextCompat.checkSelfPermission(c, media)
                if (mediaCheck != PackageManager.PERMISSION_GRANTED) {
                    list.add(media)
                }
            } catch (_: Exception) {}
        }

        if (Build.VERSION.SDK_INT <= 32) {
            val writeCheck = ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (writeCheck != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        return list
    }

    fun hasOverlay(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(c)
        }
        return true
    }

    fun hasRecord(c: Context): Boolean {
        val result = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO)
        return result == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotif(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val result = ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
            return result == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun hasStorage(c: Context): Boolean {
        if (Build.VERSION.SDK_INT > 32) {
            return true
        }
        val result = ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return result == PackageManager.PERMISSION_GRANTED
    }

    fun overlayIntent(c: Context): Intent {
        return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.packageName))
    }
}
