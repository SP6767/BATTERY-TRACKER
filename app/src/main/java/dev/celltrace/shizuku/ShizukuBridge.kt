package dev.celltrace.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ShizukuBridge {
    fun isAvailable(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun requestPermission(requestCode: Int = 7001) {
        if (isAvailable() && !hasPermission()) Shizuku.requestPermission(requestCode)
    }

    fun batteryStatsDiagnostic(): String = when {
        !isAvailable() -> "Shizuku service is not running."
        !hasPermission() -> "Shizuku permission has not been granted."
        else -> "Shizuku is connected and permission is granted. Ready to read BatteryStats."
    }

    suspend fun fetchChargedCheckin(context: Context): String = withContext(Dispatchers.IO) {
        check(isAvailable()) { "Start Shizuku first." }
        check(hasPermission()) { "Grant CellTrace permission in Shizuku." }

        suspendCancellableCoroutine { continuation ->
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, BatteryStatsUserService::class.java)
            )
                .daemon(false)
                .processNameSuffix("batterystats")
                .tag("celltrace-batterystats-v1")

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    try {
                        val service = IBatteryStatsService.Stub.asInterface(binder)
                        val result = service.dumpChargedCheckin()
                        if (continuation.isActive) continuation.resume(result)
                    } catch (t: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(t)
                    } finally {
                        try { Shizuku.unbindUserService(args, this, true) } catch (_: Throwable) { }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Shizuku user service disconnected."))
                    }
                }
            }

            try {
                Shizuku.bindUserService(args, connection)
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
            continuation.invokeOnCancellation {
                try { Shizuku.unbindUserService(args, connection, true) } catch (_: Throwable) { }
            }
        }
    }
}
