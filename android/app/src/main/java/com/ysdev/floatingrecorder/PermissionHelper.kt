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

        // 1. RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.RECORD_AUDIO)
        }

        // 2. POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 3. Storage (Android 6-12)
        if (Build.VERSION.SDK_INT <= 32) {
            if (ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // 4. READ_MEDIA_AUDIO (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            val readAudio = "android.permission.READ_MEDIA_AUDIO"
            try {
                if (ContextCompat.checkSelfPermission(c, readAudio)
                    != PackageManager.PERMISSION_GRANTED) {
                    list.add(readAudio)
                }
            } catch (e: Exception) {
                Logger.w("Perm", "READ_MEDIA_AUDIO check failed: " + e.message)
            }
        }

        return list
    }

    fun hasOverlay(c: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(c)
        } else {
            true
        }
    }

    fun hasRecord(c: Context): Boolean {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotif(c: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasStorage(c: Context): Boolean {
        if (Build.VERSION.SDK_INT > 32) return true
        return ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
    }

    fun overlayIntent(c: Context): Intent {
        return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + c.packageName))
    }
}
