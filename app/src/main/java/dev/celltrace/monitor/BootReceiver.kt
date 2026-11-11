package dev.celltrace.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.celltrace.data.BatteryRecorder

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && BatteryRecorder.monitoringEnabled(context)) {
            ContextCompat.startForegroundService(context, Intent(context, BatteryMonitorService::class.java))
        }
    }
}
