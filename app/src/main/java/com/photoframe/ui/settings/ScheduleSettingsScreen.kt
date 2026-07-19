package com.photoframe.ui.settings

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import com.photoframe.ui.tv.rememberInitialFocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoframe.settings.ScheduleSettings
import com.photoframe.settings.toClockLabel

/**
 * Schedule / kiosk settings (DESIGN §10): quiet hours (fake-off overnight), the optional
 * unplugged fake-off rule, and boot auto-start incl. the "display over other apps" permission
 * needed to relaunch after reboot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSettingsScreen(
    settings: ScheduleSettings,
    onBack: () -> Unit,
    onUpdate: ((ScheduleSettings) -> ScheduleSettings) -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedule") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.focusRequester(rememberInitialFocusRequester()),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SectionHeader("Quiet hours")
            SwitchRow("Scheduled off", settings.quietHoursEnabled) { on ->
                onUpdate { it.copy(quietHoursEnabled = on) }
            }
            if (settings.quietHoursEnabled) {
                TimeRow("From", settings.quietStartMinute) { minute ->
                    onUpdate { it.copy(quietStartMinute = minute) }
                }
                TimeRow("Until", settings.quietEndMinute) { minute ->
                    onUpdate { it.copy(quietEndMinute = minute) }
                }
                Text(
                    "The screen goes black and dims to minimum during these hours; a tap wakes it " +
                        "for 5 minutes. Sync pauses too.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Power")
            SwitchRow("Fake-off when unplugged", settings.chargingFakeOffEnabled) { on ->
                onUpdate { it.copy(chargingFakeOffEnabled = on) }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Start on boot")
            SwitchRow("Relaunch after reboot", settings.startOnBoot) { on ->
                onUpdate { it.copy(startOnBoot = on) }
            }
            if (settings.startOnBoot) {
                val canOverlay = Settings.canDrawOverlays(context)
                if (canOverlay) {
                    Text(
                        "\"Display over other apps\" is granted — the frame will relaunch after a reboot.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "To relaunch after a reboot, allow \"Display over other apps\" for this app.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Grant permission") }
                }
            }
        }
    }
}

@Composable
private fun TimeRow(label: String, minuteOfDay: Int, onPicked: (Int) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 16.sp)
        Button(onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute -> onPicked(hour * 60 + minute) },
                minuteOfDay / 60,
                minuteOfDay % 60,
                false,
            ).show()
        }) { Text(minuteOfDay.toClockLabel()) }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 16.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
