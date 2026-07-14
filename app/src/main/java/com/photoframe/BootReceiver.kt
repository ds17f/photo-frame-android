package com.photoframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Relaunches the slideshow after a device reboot (DESIGN §10) so a frame on a shelf comes back
 * unattended. On API 29+ a background activity start needs the "Display over other apps"
 * (SYSTEM_ALERT_WINDOW) permission — the Schedule settings screen requests it. Honors the
 * `startOnBoot` setting.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val app = appContext as PhotoFrameApp
                if (!app.container.scheduleSettingsRepository.current().startOnBoot) return@launch
                val launch = appContext.packageManager
                    .getLaunchIntentForPackage(appContext.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launch != null) {
                    runCatching { appContext.startActivity(launch) }
                        .onFailure { Log.w(TAG, "Boot relaunch failed (overlay permission?)", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON", // some OEMs (incl. Fire) use this
        )
    }
}
