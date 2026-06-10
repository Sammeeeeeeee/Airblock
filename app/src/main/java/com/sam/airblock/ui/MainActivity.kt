package com.sam.airblock.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.coroutineScope
import com.sam.airblock.widget.AirblockWidgetReceiver
import androidx.compose.material3.Switch
import androidx.compose.ui.text.font.FontFamily
import com.sam.airblock.R
import com.sam.airblock.data.EventLog
import com.sam.airblock.data.NetMode
import com.sam.airblock.data.Settings
import com.sam.airblock.data.SettingsStore
import com.sam.airblock.data.WidgetState
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.engine.Gates
import com.sam.airblock.engine.UpdateService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class PermissionsState(
    val fine: Boolean = false,
    val background: Boolean = false,
    val usage: Boolean = false,
) {
    val allGranted get() = fine && background && usage
}

class MainActivity : ComponentActivity() {

    private var perms by mutableStateOf(PermissionsState())
    private var widgetPlaced by mutableStateOf(false)

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPerms()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opening the app is a foreground context — always allowed to (re)start the engine
        UpdateService.start(this, tickNow = true)
        lifecycle.coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (SettingsStore.read(this@MainActivity).logEnabled)
                EventLog.append(this@MainActivity, "app opened")
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            MaterialTheme(
                colorScheme = if (dark) dynamicDarkColorScheme(ctx)
                else dynamicLightColorScheme(ctx)
            ) {
                SettingsScreen(
                    perms = perms,
                    widgetPlaced = widgetPlaced,
                    onGrantLocation = {
                        requestPerms.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        )
                    },
                    onGrantBackground = {
                        startActivity(
                            Intent(
                                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null)
                            )
                        )
                    },
                    onGrantUsage = {
                        startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
    }

    private fun refreshPerms() {
        fun granted(p: String) =
            checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        perms = PermissionsState(
            fine = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            usage = Gates(this).hasUsageAccess(),
        )
        widgetPlaced = getSystemService(AppWidgetManager::class.java)
            .getAppWidgetIds(ComponentName(this, AirblockWidgetReceiver::class.java))
            .isNotEmpty()
    }
}

@Composable
private fun SettingsScreen(
    perms: PermissionsState,
    widgetPlaced: Boolean,
    onGrantLocation: () -> Unit,
    onGrantBackground: () -> Unit,
    onGrantUsage: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var radiusNm by remember { mutableStateOf(50f) }
    var intervalSec by remember { mutableStateOf(15) }
    var logEnabled by remember { mutableStateOf(false) }
    var wifiMode by remember { mutableStateOf(NetMode.NORMAL) }
    var dataMode by remember { mutableStateOf(NetMode.NORMAL) }

    LaunchedEffect(Unit) {
        val s = SettingsStore.read(context)
        radiusNm = s.radiusNm.toFloat()
        intervalSec = s.intervalSec
        logEnabled = s.logEnabled
        wifiMode = s.wifiMode
        dataMode = s.dataMode
        loaded = true
    }

    fun save() {
        if (!loaded) return
        scope.launch {
            SettingsStore.write(
                context,
                Settings(
                    radiusNm = radiusNm.roundToInt().coerceIn(5, 250),
                    intervalSec = intervalSec,
                    logEnabled = logEnabled,
                    wifiMode = wifiMode,
                    dataMode = dataMode,
                )
            )
            UpdateService.start(context, tickNow = true)
        }
    }

    val scrollState = rememberScrollState()
    var setupSectionY by remember { mutableStateOf(0) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
        ) {
            var showLog by remember { mutableStateOf(false) }
            Spacer(Modifier.height(40.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Airblock ✈",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Nearest-plane widget",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showLog = true }) {
                    Icon(
                        painterResource(R.drawable.ic_console), "Activity log",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            if (showLog) {
                LogDialog(
                    logEnabled = logEnabled,
                    onToggle = { on ->
                        logEnabled = on
                        save()
                        if (!on) EventLog.clear(context)
                    },
                    onDismiss = { showLog = false },
                )
            }

            // ---- Setup-required banner: a new user must not have to guess
            // what to do — point straight at the permission rows below.
            if (!perms.allGranted) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch { scrollState.animateScrollTo(setupSectionY) }
                        },
                ) {
                    Row(
                        Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Info, null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Give required permissions to start",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                "Airblock can't refresh yet — tap to finish setup.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward, null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ---- All-set banner (only while no widget is placed yet) ------
            if (perms.allGranted && !widgetPlaced) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val awm = context.getSystemService(AppWidgetManager::class.java)
                            if (awm.isRequestPinAppWidgetSupported) {
                                awm.requestPinAppWidget(
                                    ComponentName(context, AirblockWidgetReceiver::class.java),
                                    null, null,
                                )
                            }
                        },
                ) {
                    Row(
                        Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Check, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "All set — tap to add the Airblock widget",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ---- Live status (mirrors the widget's top-right badge) -------
            val widgetState by WidgetStateStore.flow(context)
                .collectAsState(initial = WidgetState())
            SectionLabel("Status")
            StatusCard(widgetState, onRefresh = {
                scope.launch {
                    WidgetStateStore.update(context) { it.copy(refreshing = true) }
                    UpdateService.start(context, tickNow = true)
                }
            })
            Spacer(Modifier.height(8.dp))
            NetworkCard(widgetState, intervalSec, wifiMode, dataMode)
            Spacer(Modifier.height(24.dp))

            // ---- Permissions ---------------------------------------------
            SectionLabel(
                "Setup",
                Modifier.onGloballyPositioned {
                    setupSectionY = it.positionInParent().y.roundToInt()
                },
            )
            PermissionRow(
                icon = Icons.Filled.LocationOn,
                title = "Precise location",
                rationale = "Finds the aircraft nearest to you. Airblock only reads the " +
                    "phone's already-cached fix — no GPS battery drain.",
                granted = perms.fine,
                onClick = onGrantLocation,
            )
            Spacer(Modifier.height(8.dp))
            PermissionRow(
                icon = Icons.Filled.Settings,
                title = "Location all the time",
                rationale = "Lets the widget refresh while the app is closed. In App info " +
                    "→ Permissions → Location, choose “Allow all the time”.",
                granted = perms.background,
                onClick = onGrantBackground,
            )
            Spacer(Modifier.height(8.dp))
            PermissionRow(
                icon = Icons.Filled.Info,
                title = "Usage access",
                rationale = "Pauses refreshes whenever your home screen isn't visible — " +
                    "this is what keeps Airblock's battery use near zero.",
                granted = perms.usage,
                onClick = onGrantUsage,
            )
            Spacer(Modifier.height(24.dp))

            // ---- Tuning ---------------------------------------------------
            SectionLabel("Tuning")
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Search radius",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${radiusNm.roundToInt()} nm",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = {
                            radiusNm = (radiusNm - 5f).coerceAtLeast(5f); save()
                        }) {
                            Icon(painterResource(R.drawable.ic_remove), "decrease radius")
                        }
                        Slider(
                            value = radiusNm,
                            onValueChange = { radiusNm = it },
                            onValueChangeFinished = { save() },
                            valueRange = 5f..250f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        FilledTonalIconButton(onClick = {
                            radiusNm = (radiusNm + 5f).coerceAtMost(250f); save()
                        }) {
                            Icon(painterResource(R.drawable.ic_add), "increase radius")
                        }
                    }
                    Text(
                        "Default refresh",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(15, 30, 60).forEachIndexed { i, sec ->
                            SegmentedButton(
                                selected = intervalSec == sec,
                                onClick = { intervalSec = sec; save() },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                            ) { Text("${sec}s") }
                        }
                    }
                    NetModeRow(R.drawable.ic_wifi, "On Wi-Fi", wifiMode,
                        normalLabel = "Default (${intervalSec}s)") { wifiMode = it; save() }
                    NetModeRow(R.drawable.ic_cell, "On mobile data", dataMode,
                        normalLabel = "Default (${intervalSec}s)") { dataMode = it; save() }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ---- Attribution ----------------------------------------------
            SectionLabel("Data & photos")
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Live aircraft data from adsb.lol, the community ADS-B network. " +
                        "Aircraft photos via the Planespotters.net API — © their " +
                        "respective photographers. Airline logos via Kiwi.com.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatusCard(state: WidgetState, onRefresh: () -> Unit) {
    // Tick once a second so the age readout is precise and live
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val ageSec = if (state.updatedAt > 0) ((now - state.updatedAt) / 1000).toInt() else -1
    fun age(): String = when {
        ageSec < 0 -> "never"
        ageSec < 120 -> "$ageSec s ago"
        else -> "${ageSec / 60} min ago"
    }
    // Schedule-aware staleness — 9-min-old data is HEALTHY on the 10-min plan
    val staleDeadline = if (state.staleAfterMs > 0) state.staleAfterMs
    else state.updatedAt + 120_000
    val isStaleNow = state.updatedAt > 0 && now > staleDeadline

    data class StatusUi(val icon: Int, val title: String, val detail: String,
        val container: Color, val content: Color)

    val cs = MaterialTheme.colorScheme
    val ui = when {
        state.refreshing -> StatusUi(
            R.drawable.ic_sync, "Refreshing…",
            // Live stage from the engine: location → nearest aircraft → route/photo
            "${state.refreshStage ?: "Starting…"} Previous update: ${age()}.",
            cs.primaryContainer, cs.onPrimaryContainer)
        state.pausedReason != null -> StatusUi(
            R.drawable.ic_battery_saver, "Paused — ${state.pausedReason}",
            (if (state.pausedReason == "battery saver")
                "Refreshes resume automatically when battery saver turns off. "
            else
                "Refreshes resume automatically when the network or its setting changes. ") +
                "Last update: ${age()}.",
            cs.tertiaryContainer, cs.onTertiaryContainer)
        state.errorCount > 0 -> StatusUi(
            R.drawable.ic_warning, "Refresh failing (×${state.errorCount})",
            "Last error: ${state.lastError ?: "network error"}. Last successful " +
                "update: ${age()}. Retrying — old data stays on the widget meanwhile.",
            cs.errorContainer, cs.onErrorContainer)
        ageSec < 0 -> StatusUi(
            R.drawable.ic_flight, "Waiting for first refresh",
            "Add the widget to your home screen, or tap it to refresh now.",
            cs.surfaceContainerHigh, cs.onSurfaceVariant)
        isStaleNow -> StatusUi(
            R.drawable.ic_clock, "Data is stale",
            "Last update ${age()} (schedule: ${state.modeLabel ?: "normal"}). " +
                "The widget only refreshes while your home screen is visible.",
            cs.surfaceContainerHigh, cs.onSurfaceVariant)
        else -> StatusUi(
            R.drawable.ic_flight, "Up to date",
            listOfNotNull(state.callsign, "updated ${age()}").joinToString(" · "),
            cs.secondaryContainer, cs.onSecondaryContainer)
    }

    Surface(
        shape = RoundedCornerShape(20.dp), // M3 large-increased: row-level card
        color = ui.container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(ui.icon), null,
                tint = ui.content,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.title, style = MaterialTheme.typography.titleMedium,
                    color = ui.content)
                Text(ui.detail, style = MaterialTheme.typography.bodySmall,
                    color = ui.content)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = onRefresh, enabled = !state.refreshing) {
                Icon(
                    painterResource(R.drawable.ic_sync), "refresh now",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Live "what is the engine doing right now" stat pair: current network and
 * countdown to the next expected refresh.
 */
@Composable
private fun NetworkCard(
    state: WidgetState,
    intervalSec: Int,
    wifiMode: NetMode,
    dataMode: NetMode,
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var transport by remember { mutableStateOf<String?>(null) }
    var dataSaver by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val gates = Gates(context)
                transport = gates.networkTransport()
                dataSaver = gates.dataSaverOn()
            }
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val mode = when {
        transport == null || dataSaver -> NetMode.OFF
        transport == "wifi" -> wifiMode
        transport == "cell" -> dataMode
        else -> maxOf(wifiMode, dataMode)
    }
    val netLabel = when (transport) {
        "wifi" -> "Wi-Fi"
        "cell" -> "Mobile data"
        null -> "Offline"
        else -> "Other"
    }
    val netIcon = if (transport == "cell") R.drawable.ic_cell else R.drawable.ic_wifi
    val intervalMs = when (mode) {
        NetMode.SLOW -> 10L * 60 * 1000
        NetMode.OFF -> null
        NetMode.NORMAL -> intervalSec * 1000L
    }
    val nextLabel = when {
        dataSaver -> "Data Saver"
        intervalMs == null -> "Off"
        state.updatedAt == 0L -> "—"
        else -> {
            val secs = ((state.updatedAt + intervalMs - now) / 1000).coerceAtLeast(0)
            if (secs >= 90) "${(secs + 30) / 60} min" else "${secs}s"
        }
    }
    val progress = if (intervalMs != null && state.updatedAt > 0)
        ((now - state.updatedAt).toFloat() / intervalMs).coerceIn(0f, 1f) else null

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row {
                StatCell(netIcon, netLabel, "Network", Modifier.weight(1f))
                StatCell(R.drawable.ic_clock, nextLabel, "Next refresh", Modifier.weight(1f))
            }
            progress?.let {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun StatCell(icon: Int, value: String, label: String, modifier: Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(icon), null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetModeRow(
    icon: Int,
    label: String,
    mode: NetMode,
    normalLabel: String,
    onChange: (NetMode) -> Unit,
) {
    // Indented under "Default refresh" — visually a sub-option of it
    Column(
        Modifier.padding(start = 16.dp),
        Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(icon), null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            // The first option names the default rate, so the override
            // relationship is self-evident. Labels use a smaller single-line
            // style: at the default size "Default (15s)" wraps on narrow
            // screens, making only that segment taller than the others.
            val options = listOf(
                NetMode.NORMAL to normalLabel,
                NetMode.SLOW to "10 min",
                NetMode.OFF to "Off",
            )
            options.forEachIndexed { i, (value, text) ->
                SegmentedButton(
                    selected = mode == value,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Full-screen activity-log console, opened from the header icon. */
@Composable
private fun LogDialog(
    logEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "close",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        "Activity log",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = logEnabled, onCheckedChange = onToggle)
                }
                Text(
                    "What the engine did and when. Turning the log off deletes all records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                )
                if (logEnabled) {
                    var events by remember { mutableStateOf(listOf<String>()) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            events = kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.IO) {
                                EventLog.read(context, limit = 200)
                            }
                            delay(2000)
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        events.forEach { e ->
                            Text(
                                e,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace),
                                color = if ("FAILED" in e) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (events.isEmpty()) {
                            Text("No activity yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                } else {
                    Text(
                        "Log is off.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    rationale: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    // Ungranted rows are RED — a missing permission means the widget cannot
    // work at all, so it must read as "action required", not as a neutral card.
    Surface(
        shape = RoundedCornerShape(20.dp), // M3 large-increased: row-level card
        color = if (granted) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted, onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    tint = if (granted) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (granted) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Icon(
                    Icons.Filled.Check, "granted",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward, "grant",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
