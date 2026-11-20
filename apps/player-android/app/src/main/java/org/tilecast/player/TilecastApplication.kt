package org.tilecast.player

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.webkit.WebView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.tilecast.player.work.HeartbeatWorker
import org.tilecast.player.runtime.TcMediaBridge
import java.util.concurrent.TimeUnit

class TilecastApplication : Application() {
    internal val playerActivityVisibility = PlayerActivityVisibility()

    // The process-scoped renderer and its WebView may outlive Activities.
    // Catch eject at application scope, before vold kills processes still
    // holding file descriptors on the departing USB volume.
    private val usbEjectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val path = intent?.data?.path ?: return
            if (intent.action in setOf(Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_UNMOUNTED,
                    Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_REMOVED)) {
                TcMediaBridge.onExternalVolumeEject(path)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        WebView.setWebContentsDebuggingEnabled(false)
        registerReceiver(usbEjectReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        })
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("tilecast-heartbeat", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
