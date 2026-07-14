package com.photoframe.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoframe.PhotoFrameApp
import com.photoframe.settings.QuietHours
import com.photoframe.settings.ScheduleSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalTime

enum class SleepReason { QUIET_HOURS, UNPLUGGED }

/** Whether the frame should "fake off" right now, and why (DESIGN §10). */
data class PowerState(val asleep: Boolean = false, val reason: SleepReason? = null)

/**
 * Computes the frame's power mode (DESIGN §10) from the schedule settings, the live charging state,
 * and a tap-to-wake window — re-evaluated on a 30s tick so quiet-hours boundaries and the wake
 * window expire on their own. A tap ([wakeTemporarily]) overrides fake-off for 5 minutes.
 */
class PowerViewModel(app: Application) : AndroidViewModel(app) {

    private val scheduleRepo = (app as PhotoFrameApp).container.scheduleSettingsRepository
    private val wakeUntil = MutableStateFlow(0L)

    private val chargingFlow: Flow<Boolean> = callbackFlow {
        val ctx = getApplication<Application>()
        fun currentlyCharging(): Boolean {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { trySend(currentlyCharging()) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(currentlyCharging())
        awaitClose { ctx.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    /** Fires immediately then every 30s so time-based transitions are picked up without user input. */
    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    val state: StateFlow<PowerState> = combine(
        scheduleRepo.settings,
        chargingFlow,
        wakeUntil,
        ticker,
    ) { settings, charging, wake, _ ->
        compute(settings, charging, wake)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PowerState())

    fun wakeTemporarily() {
        wakeUntil.value = System.currentTimeMillis() + WAKE_MS
    }

    private fun compute(settings: ScheduleSettings, charging: Boolean, wakeUntilMillis: Long): PowerState {
        val quiet = QuietHours.isQuiet(LocalTime.now(), settings)
        val unplugged = settings.chargingFakeOffEnabled && !charging
        if (!quiet && !unplugged) return PowerState(false, null)
        if (System.currentTimeMillis() < wakeUntilMillis) return PowerState(false, null) // tapped awake
        return PowerState(true, if (quiet) SleepReason.QUIET_HOURS else SleepReason.UNPLUGGED)
    }

    companion object {
        private const val TICK_MS = 30_000L
        private const val WAKE_MS = 5 * 60 * 1000L
    }
}
