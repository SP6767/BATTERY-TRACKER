package dev.celltrace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.celltrace.data.BatteryRecord
import dev.celltrace.data.BatteryRecorder
import dev.celltrace.data.CellTraceDatabase
import dev.celltrace.monitor.BatteryMonitorService
import dev.celltrace.shizuku.ShizukuBridge
import dev.celltrace.shizuku.BatteryStatsParser
import dev.celltrace.shizuku.BatteryAppStats
import dev.celltrace.usage.UsageRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF101318)
private val Panel = Color(0xFF1B222B)
private val Accent = Color(0xFF9CE6C0)
private val Muted = Color(0xFF9AA6B2)

class MainActivity : ComponentActivity() {
    private val db by lazy { CellTraceDatabase.get(this) }
    private val usage by lazy { UsageRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Ink, surface = Panel, primary = Accent,
                    onBackground = Color.White, onSurface = Color.White
                )
            ) {
                var monitoring by remember { mutableStateOf(BatteryRecorder.monitoringEnabled(this)) }
                var usageGranted by remember { mutableStateOf(usage.hasUsageAccess()) }
                var usageText by remember { mutableStateOf("Grant Usage Access to calculate screen-on time and app foreground time.") }
                var shizukuText by remember { mutableStateOf(ShizukuBridge.batteryStatsDiagnostic()) }
                var batteryStatsText by remember { mutableStateOf("No BatteryStats snapshot loaded yet.") }
                var batteryStatsRows by remember { mutableStateOf(emptyList<BatteryAppStats>()) }
                val scope = rememberCoroutineScope()
                val records by db.batteryDao().observeRecent().collectAsState(initial = emptyList())

                LaunchedEffect(Unit) {
                    val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    val sticky = registerReceiver(null, filter)
                    if (sticky != null) dev.celltrace.data.BatteryRecorder.capture(this@MainActivity, sticky)
                }

                CellTraceScreen(
                    records = records,
                    monitoring = monitoring,
                    usageGranted = usageGranted,
                    usageText = usageText,
                    shizukuText = shizukuText,
                    batteryStatsText = batteryStatsText,
                    batteryStatsRows = batteryStatsRows,
                    onLoadBatteryStats = {
                        scope.launch {
                            batteryStatsText = "Reading Android BatteryStats through Shizuku…"
                            try {
                                val raw = ShizukuBridge.fetchChargedCheckin(this@MainActivity)
                                val snapshot = BatteryStatsParser.parse(raw)
                                batteryStatsRows = snapshot.apps
                                val capacity = snapshot.batteryCapacityMah?.let { "%.0f mAh".format(it) } ?: "not reported"
                                val window = snapshot.batteryRealtimeMs?.let { formatDuration(it) } ?: "not reported"
                                batteryStatsText = "BatteryStats window: $window · Capacity: $capacity · Parsed ${snapshot.rawLineCount} rows" +
                                    if (snapshot.warnings.isEmpty()) "" else "\n" + snapshot.warnings.joinToString("\n")
                            } catch (t: Throwable) {
                                batteryStatsText = "BatteryStats read failed: ${t.message ?: t.javaClass.simpleName}"
                            }
                        }
                    },
                    onMonitoringChange = { enabled ->
                        monitoring = enabled
                        BatteryRecorder.setMonitoringEnabled(this, enabled)
                        val intent = Intent(this, BatteryMonitorService::class.java)
                        if (enabled) {
                            androidx.core.content.ContextCompat.startForegroundService(this, intent)
                        } else {
                            stopService(intent)
                        }
                    },
                    onUsageAccess = {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    onRefreshUsage = {
                        usageGranted = usage.hasUsageAccess()
                        if (usageGranted) {
                            val now = System.currentTimeMillis()
                            val chargeAnchor = BatteryRecorder.chargeAnchor(this).takeIf { it > 0 }
                                ?: (now - 24L * 60 * 60 * 1000)
                            val fullAnchor = BatteryRecorder.fullAnchor(this).takeIf { it > 0 }
                                ?: chargeAnchor
                            val chargeResult = usage.query(chargeAnchor, now)
                            val fullResult = if (fullAnchor == chargeAnchor) chargeResult else usage.query(fullAnchor, now)
                            usageText = "Since last charge: ${formatDuration(chargeResult.screenOnMillis)}\n" +
                                "Since last 100%: ${formatDuration(fullResult.screenOnMillis)}\n" +
                                "Foreground apps in charge window: ${chargeResult.foregroundByPackage.size}\n" +
                                chargeResult.foregroundByPackage.entries.sortedByDescending { it.value }.take(6)
                                    .joinToString("\n") { "${appLabel(it.key)} · ${formatDuration(it.value)}" }
                        } else {
                            usageText = "Usage Access is not enabled yet."
                        }
                    },
                    onShizuku = {
                        if (ShizukuBridge.isAvailable() && !ShizukuBridge.hasPermission()) {
                            ShizukuBridge.requestPermission()
                        }
                        shizukuText = ShizukuBridge.batteryStatsDiagnostic()
                    }
                )
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun formatDuration(ms: Long): String {
        val mins = ms / 60000
        return "${mins / 60}h ${mins % 60}m"
    }
}

@Composable
private fun CellTraceScreen(
    records: List<BatteryRecord>,
    monitoring: Boolean,
    usageGranted: Boolean,
    usageText: String,
    shizukuText: String,
    batteryStatsText: String,
    batteryStatsRows: List<BatteryAppStats>,
    onLoadBatteryStats: () -> Unit,
    onMonitoringChange: (Boolean) -> Unit,
    onUsageAccess: () -> Unit,
    onRefreshUsage: () -> Unit,
    onShizuku: () -> Unit
) {
    val latest = records.firstOrNull()
    val pct = latest?.level ?: 0
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Ink),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BatteryChargingFull, null, tint = Accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(9.dp))
                Column {
                    Text("CELLTRACE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("Battery history · realme GT 7T", color = Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$pct", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                        Text("%", color = Accent, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.weight(1f))
                        Text(if (latest?.charging == true) "CHARGING" else "ON BATTERY", color = Accent, fontWeight = FontWeight.Bold)
                    }
                    BatterySparkline(records.reversed())
                    Text("Latest observed battery level", color = Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Live battery logger", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = monitoring, onCheckedChange = onMonitoringChange)
                    }
                    Text(
                        if (monitoring) "Monitoring is enabled. Android keeps a small ongoing notification."
                        else "Monitoring is off. Events are captured while the app is open; enable monitoring for background logging.",
                        color = Muted, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item {
            Text("SCREEN TIME & APP ACTIVITY", color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Screen-on time", fontWeight = FontWeight.Bold)
                    Text("Since last charge and since last 100% are calculated from Android's screen-interactive events.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text(usageText, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRefreshUsage) { Text("Refresh stats") }
                        OutlinedButton(onClick = onUsageAccess) { Text(if (usageGranted) "Usage access ✓" else "Grant access") }
                    }
                }
            }
        }
        item {
            Text("SHIZUKU / ADVANCED BATTERY STATS", color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Optional system access", fontWeight = FontWeight.Bold)
                    Text(shizukuText, color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onShizuku) { Text("Check / grant") }
                        Button(onClick = onLoadBatteryStats, enabled = shizukuText.contains("permission is granted")) {
                            Text("Read BatteryStats")
                        }
                    }
                    Text(batteryStatsText, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (batteryStatsRows.isNotEmpty()) {
            item {
                Text("PER-APP BATTERY ATTRIBUTION", color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("Android estimates · since last charge. Foreground and background times are separate from power attribution.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            items(batteryStatsRows.take(15), key = { "uid-${it.uid}" }) { app ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(app.displayName, fontWeight = FontWeight.SemiBold)
                        val power = app.estimatedMah?.let { "%.3f mAh estimated".format(it) } ?: "Power estimate unavailable"
                        val rate = app.estimatedPercentPerHour?.let { " · %.3f%%/h".format(it) } ?: ""
                        Text(power + rate, color = Accent, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Foreground: ${app.foregroundMs?.let { formatMs(it) } ?: "—"} · Background running: ${app.backgroundRunningMs?.let { formatMs(it) } ?: "—"}",
                            color = Muted, style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, tint = Accent)
                Spacer(Modifier.width(8.dp))
                Text("1% BATTERY LOG", color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
        if (records.isEmpty()) {
            item { Text("No battery transitions recorded yet. Keep monitoring enabled and use your phone normally.", color = Muted) }
        } else {
            items(records, key = { it.id }) { record ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${record.level}%", fontWeight = FontWeight.Bold, color = if (record.charging) Accent else Color.White)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (record.charging) "Charging" else "Discharging", fontWeight = FontWeight.Medium)
                        Text(SimpleDateFormat("dd MMM · HH:mm:ss", Locale.getDefault()).format(Date(record.observedAt)), color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (record.inferred) Text("INFERRED", color = Color(0xFFFFC46B), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Text("CellTrace stores data on this device. Battery level transitions missed while Android stops the service cannot be reconstructed exactly. Inferred steps share the time Android next reported a level.", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BatterySparkline(records: List<BatteryRecord>) {
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        if (records.size < 2) return@Canvas
        val min = records.minOf { it.level }.toFloat()
        val max = records.maxOf { it.level }.toFloat().coerceAtLeast(min + 1f)
        val points = records.mapIndexed { index, item ->
            Offset(
                x = size.width * index / (records.size - 1).coerceAtLeast(1),
                y = size.height - ((item.level - min) / (max - min)) * size.height
            )
        }
        for (i in 0 until points.lastIndex) {
            drawLine(Accent, points[i], points[i + 1], strokeWidth = 3.dp.toPx())
        }
    }
}
