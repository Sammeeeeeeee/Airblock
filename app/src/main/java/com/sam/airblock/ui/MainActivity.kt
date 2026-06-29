// M3 Expressive uses spring-physics motion, shape morphing and grouped cards
// throughout; most of those APIs are still flagged experimental in 1.5.0-alpha.
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.toShape
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.coroutineScope
import com.sam.airblock.R
import com.sam.airblock.data.AeroApi
import com.sam.airblock.data.AeroPrefs
import com.sam.airblock.data.AeroStore
import com.sam.airblock.data.EventLog
import com.sam.airblock.data.NetMode
import com.sam.airblock.data.SecureKeyStore
import com.sam.airblock.data.Settings
import com.sam.airblock.data.SettingsStore
import com.sam.airblock.data.WidgetState
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.engine.Gates
import com.sam.airblock.engine.UpdateService
import com.sam.airblock.widget.AirblockWidgetReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class PermissionsState(
    val fine: Boolean = false,
    val background: Boolean = false,
    val usage: Boolean = false,
    /** Battery set to "unrestricted" — optional, NOT part of allGranted. */
    val unrestricted: Boolean = false,
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
        // (Re)start the engine so refreshes resume, but do NOT force a tick:
        // opening the app — e.g. tapping the photo/route on the widget — should
        // not itself trigger a fetch. Only the widget's refresh tap, the in-app
        // refresh button and pull-to-refresh request new data.
        UpdateService.start(this, tickNow = false)
        lifecycle.coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (SettingsStore.read(this@MainActivity).logEnabled)
                EventLog.append(this@MainActivity, "app opened")
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            // MaterialExpressiveTheme = dynamic color + the expressive defaults:
            // springy MotionScheme, emphasized type styles and the M3E shapes.
            MaterialExpressiveTheme(
                colorScheme = if (dark) dynamicDarkColorScheme(ctx)
                else dynamicLightColorScheme(ctx),
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
                    onGrantUnrestricted = {
                        // Direct system dialog ("Allow Airblock to always run in
                        // the background?") — one tap instead of a settings dive
                        startActivity(
                            @Suppress("BatteryLife")
                            Intent(
                                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName"),
                            )
                        )
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
            unrestricted = getSystemService(android.os.PowerManager::class.java)
                .isIgnoringBatteryOptimizations(packageName),
        )
        widgetPlaced = getSystemService(AppWidgetManager::class.java)
            .getAppWidgetIds(ComponentName(this, AirblockWidgetReceiver::class.java))
            .isNotEmpty()
    }
}

// ---- Expressive grouped-list shapes -----------------------------------------
// New Google apps render related rows as one visual unit: large outer corners,
// small inner corners, hairline gaps. These are those shapes.
private val GroupSingle = RoundedCornerShape(24.dp)
private val GroupTop = RoundedCornerShape(24.dp, 24.dp, 6.dp, 6.dp)
private val GroupMiddle = RoundedCornerShape(6.dp)
private val GroupBottom = RoundedCornerShape(6.dp, 6.dp, 24.dp, 24.dp)
private val GroupGap = 3.dp

@Composable
private fun SettingsScreen(
    perms: PermissionsState,
    widgetPlaced: Boolean,
    onGrantLocation: () -> Unit,
    onGrantBackground: () -> Unit,
    onGrantUsage: () -> Unit,
    onGrantUnrestricted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var radiusNm by remember { mutableStateOf(50f) }
    var intervalSec by remember { mutableStateOf(15) }
    var logEnabled by remember { mutableStateOf(false) }
    var wifiMode by remember { mutableStateOf(NetMode.NORMAL) }
    var dataMode by remember { mutableStateOf(NetMode.NORMAL) }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val s = SettingsStore.read(context)
        radiusNm = s.radiusNm.toFloat()
        intervalSec = s.intervalSec
        logEnabled = s.logEnabled
        wifiMode = s.wifiMode
        dataMode = s.dataMode
        loaded = true
    }

    // tickNow only when the change makes refreshes FASTER (or changes the
    // data, like radius) — slowing down must not trigger a pointless fetch
    fun save(tickNow: Boolean = true) {
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
            UpdateService.start(context, tickNow = tickNow)
        }
    }

    BackHandler(enabled = showLog) { showLog = false }

    val widgetState by WidgetStateStore.flow(context)
        .collectAsState(initial = WidgetState())
    // The engine auto-refreshes every ~15 s, flipping widgetState.refreshing.
    // The pull-to-refresh indicator must ONLY react to an actual pull, never to
    // those background ticks — otherwise the loader pops up at the top whatever
    // the scroll position. So drive it from a local flag set only on pull, and
    // cleared the moment the engine's refresh finishes.
    var pullRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(widgetState.refreshing) {
        if (!widgetState.refreshing) pullRefreshing = false
    }
    fun refreshNow() {
        scope.launch {
            WidgetStateStore.update(context) { it.copy(refreshing = true) }
            UpdateService.start(context, tickNow = true)
        }
    }
    fun onPullRefresh() {
        pullRefreshing = true
        refreshNow()
    }

    // A radius change only needs a fetch when it could change the ANSWER:
    // no plane currently shown, or the shown plane is outside the new radius
    fun radiusNeedsTick(newNm: Float): Boolean =
        widgetState.status != WidgetState.Status.OK ||
            (widgetState.distanceKm ?: 0.0) > newNm * 1.852

    val scrollState = rememberScrollState()
    var setupSectionY by remember { mutableStateOf(0) }
    val pageMotion = MaterialTheme.motionScheme
    // The activity log is its own PAGE, pushed in like a forward navigation —
    // the main screen slides away left underneath it
    AnimatedContent(
        targetState = showLog,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(pageMotion.defaultSpatialSpec()) { it } +
                    fadeIn(pageMotion.defaultEffectsSpec()))
                    .togetherWith(
                        slideOutHorizontally(pageMotion.defaultSpatialSpec()) { -it / 3 } +
                            fadeOut(pageMotion.defaultEffectsSpec()))
            } else {
                (slideInHorizontally(pageMotion.defaultSpatialSpec()) { -it / 3 } +
                    fadeIn(pageMotion.defaultEffectsSpec()))
                    .togetherWith(
                        slideOutHorizontally(pageMotion.defaultSpatialSpec()) { it } +
                            fadeOut(pageMotion.defaultEffectsSpec()))
            }
        },
        label = "page",
        // Surface-coloured backdrop: during the slide the gap between pages
        // exposed the raw window background — a white flash in light theme
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) { logPage ->
        if (logPage) {
            LogScreen(
                logEnabled = logEnabled,
                onToggle = { on ->
                    logEnabled = on
                    save(tickNow = false)
                    if (!on) EventLog.clear(context)
                },
                onBack = { showLog = false },
            )
        } else {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        val ptrState = rememberPullToRefreshState()
        PullToRefreshBox(
            // Only an actual pull lights the indicator — not the 15 s auto-ticks
            isRefreshing = pullRefreshing,
            onRefresh = ::onPullRefresh,
            state = ptrState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                // The expressive shape-morphing indicator. It animates from the
                // pull gesture (state.distanceFraction) and sits pinned at the
                // top, below the status bar — content scrolls under it.
                PullToRefreshDefaults.LoadingIndicator(
                    state = ptrState,
                    isRefreshing = pullRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                )
            },
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            val motion = MaterialTheme.motionScheme
            Spacer(Modifier.height(12.dp))
            // Header: title + console button — scrolls with the content
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Airblock ✈",
                        style = MaterialTheme.typography.displaySmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Nearest-plane widget",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(
                    onClick = { showLog = true },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(painterResource(R.drawable.ic_console), "Activity log")
                }
            }
            Spacer(Modifier.height(16.dp))

            // ---- Setup-required banner: a new user must not have to guess
            // what to do — point straight at the permission rows below.
            AnimatedVisibility(
                visible = !perms.allGranted,
                enter = fadeIn(motion.defaultEffectsSpec()) +
                    expandVertically(motion.defaultSpatialSpec()),
                exit = fadeOut(motion.defaultEffectsSpec()) +
                    shrinkVertically(motion.defaultSpatialSpec()),
            ) {
                Column {
                    Surface(
                        onClick = {
                            scope.launch { scrollState.animateScrollTo(setupSectionY) }
                        },
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
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
                                    style = MaterialTheme.typography.titleMediumEmphasized,
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
            }

            // ---- All-set banner (only while no widget is placed yet) ------
            AnimatedVisibility(
                visible = perms.allGranted && !widgetPlaced,
                enter = fadeIn(motion.defaultEffectsSpec()) +
                    expandVertically(motion.defaultSpatialSpec()),
                exit = fadeOut(motion.defaultEffectsSpec()) +
                    shrinkVertically(motion.defaultSpatialSpec()),
            ) {
                Column {
                    Surface(
                        onClick = {
                            val awm = context.getSystemService(AppWidgetManager::class.java)
                            if (awm.isRequestPinAppWidgetSupported) {
                                awm.requestPinAppWidget(
                                    ComponentName(context, AirblockWidgetReceiver::class.java),
                                    null, null,
                                )
                            }
                        },
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
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
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // ---- Live status (mirrors the widget's top-right badge) -------
            SectionLabel("Status")
            var statusExpanded by remember { mutableStateOf(false) }
            StatusCard(
                widgetState,
                expanded = statusExpanded,
                onToggle = { statusExpanded = !statusExpanded },
                onRefresh = ::refreshNow,
            )
            Spacer(Modifier.height(GroupGap))
            NetworkCard(widgetState, intervalSec, wifiMode, dataMode)
            Spacer(Modifier.height(24.dp))

            // ---- Permissions: front and centre until granted, then parked
            // below Tuning once setup is done -------------------------------
            val setupSection: @Composable () -> Unit = {
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
                    shape = GroupTop,
                    onClick = onGrantLocation,
                )
                Spacer(Modifier.height(GroupGap))
                PermissionRow(
                    icon = Icons.Filled.Settings,
                    title = "Location all the time",
                    rationale = "Lets the widget refresh while the app is closed. In App info " +
                        "→ Permissions → Location, choose “Allow all the time”.",
                    granted = perms.background,
                    shape = GroupMiddle,
                    onClick = onGrantBackground,
                )
                Spacer(Modifier.height(GroupGap))
                PermissionRow(
                    icon = Icons.Filled.Info,
                    title = "Usage access",
                    rationale = "Pauses refreshes whenever your home screen isn't visible — " +
                        "this is what keeps Airblock's battery use near zero.",
                    granted = perms.usage,
                    shape = GroupMiddle,
                    onClick = onGrantUsage,
                )
                Spacer(Modifier.height(GroupGap))
                BatteryRow(
                    granted = perms.unrestricted,
                    onClick = onGrantUnrestricted,
                )
                Spacer(Modifier.height(24.dp))
            }
            if (!perms.allGranted) setupSection()

            // ---- Tuning ---------------------------------------------------
            SectionLabel("Tuning")
            Surface(
                shape = GroupTop,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
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
                            style = MaterialTheme.typography.titleLargeEmphasized,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(
                            onClick = {
                                radiusNm = (radiusNm - 5f).coerceAtLeast(5f)
                                save(tickNow = radiusNeedsTick(radiusNm))
                            },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(painterResource(R.drawable.ic_remove), "decrease radius")
                        }
                        Slider(
                            value = radiusNm,
                            onValueChange = { radiusNm = it },
                            onValueChangeFinished = { save(tickNow = radiusNeedsTick(radiusNm)) },
                            valueRange = 5f..250f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        FilledTonalIconButton(
                            onClick = {
                                radiusNm = (radiusNm + 5f).coerceAtMost(250f)
                                save(tickNow = radiusNeedsTick(radiusNm))
                            },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(painterResource(R.drawable.ic_add), "increase radius")
                        }
                    }
                }
            }
            Spacer(Modifier.height(GroupGap))
            Surface(
                shape = GroupBottom,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Default refresh",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ConnectedToggleRow(
                        options = listOf(15, 30, 60).map { it to "${it}s" },
                        selected = intervalSec,
                        onSelect = {
                            val faster = it < intervalSec
                            intervalSec = it; save(tickNow = faster)
                        },
                    )
                    NetModeRow(R.drawable.ic_wifi, "On Wi-Fi", wifiMode,
                        normalLabel = "Default (${intervalSec}s)") {
                        val faster = it.ordinal < wifiMode.ordinal
                        wifiMode = it; save(tickNow = faster)
                    }
                    NetModeRow(R.drawable.ic_cell, "On mobile data", dataMode,
                        normalLabel = "Default (${intervalSec}s)") {
                        val faster = it.ordinal < dataMode.ordinal
                        dataMode = it; save(tickNow = faster)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ---- Real flight times (AeroAPI) ------------------------------
            SectionLabel("Flight times")
            FlightTimesCard()
            Spacer(Modifier.height(24.dp))

            if (perms.allGranted) setupSection()

            // ---- Attribution ----------------------------------------------
            SectionLabel("Data & photos")
            Surface(
                shape = GroupSingle,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Live aircraft data from adsb.lol, the community ADS-B network. " +
                        "Aircraft photos via the Planespotters.net API — © their " +
                        "respective photographers. Airline logos via Kiwi.com. " +
                        "Aircraft silhouettes by ADS-B Radar (adsb-radar.com). " +
                        "Aircraft & manufacturer logos via Wikimedia Commons. " +
                        "Interesting-aircraft tags from sdr-enthusiasts/plane-alert-db. " +
                        "Scheduled & actual flight times via FlightAware AeroAPI (optional).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            Spacer(Modifier.height(24.dp))

            // ---- Troubleshooting: deliberately last — a recovery tool,
            // not part of everyday use -------------------------------------
            SectionLabel("Troubleshooting")
            RestartRow {
                scope.launch { UpdateService.forceFullRestart(context) }
            }
            Spacer(Modifier.height(32.dp))
        }
        }
        }
        }
    }
}

/**
 * The M3 Expressive connected button group — replaces SegmentedButton rows.
 * Checked segments morph to a full pill; pressing squishes the neighbours.
 */
@Composable
private fun <T> ConnectedToggleRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    ButtonGroup(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEachIndexed { i, (value, label) ->
            val interaction = remember { MutableInteractionSource() }
            ToggleButton(
                checked = selected == value,
                onCheckedChange = { onSelect(value) },
                interactionSource = interaction,
                modifier = Modifier
                    .weight(1f)
                    .animateWidth(interaction),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.toggleButtonColors(
                    // The group sits inside a surfaceContainerLow card — the
                    // default surfaceContainer would blend into it
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                // Slim padding: the "Default (15s)" label must fit a ⅓ segment
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/**
 * Tuning card for the optional AeroAPI flight-times feature: paste or clear the
 * key (stored encrypted on-device), switch it on/off, and watch the free-quota
 * spend. The switch trips itself off automatically once the monthly allowance
 * is gone, after which the widget falls back to its ETA estimate.
 */
@Composable
private fun FlightTimesCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val aero by AeroStore.flow(context).collectAsState(initial = AeroPrefs())
    var hasKey by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasKey = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SecureKeyStore.hasAeroKey(context)
        }
    }

    // Free /account/usage poll — refreshes the authoritative spend and may trip
    // the switch off if the budget is already gone.
    fun pollUsage() {
        scope.launch {
            checking = true
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { AeroApi(context).usageCostUsd() }
            }
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.util.Date())
            result.fold(
                onSuccess = { AeroStore.recordUsage(context, it, "Checked $hm") },
                onFailure = {
                    AeroStore.recordUsage(context, null,
                        "Check failed — ${it.message ?: "error"}")
                },
            )
            checking = false
        }
    }

    fun setEnabled(on: Boolean) {
        if (on && !hasKey) { showKeyDialog = true; return }
        scope.launch {
            AeroStore.setEnabled(context, on)
            if (on) pollUsage()
        }
    }

    fun saveKey(raw: String) {
        val key = raw.trim()
        if (key.isEmpty()) return
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SecureKeyStore.setAeroKey(context, key)
            }
            hasKey = true
            showKeyDialog = false
            AeroStore.setEnabled(context, true)
            pollUsage()
        }
    }

    fun clearKey() {
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SecureKeyStore.clearAeroKey(context)
            }
            hasKey = false
            AeroStore.setEnabled(context, false)
        }
    }

    Surface(
        shape = GroupSingle,
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Real flight times",
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                    )
                    Text(
                        "Show FlightAware's scheduled and actual arrival instead of the " +
                            "estimated ETA. Uses your feeder's free AeroAPI quota; when it's " +
                            "off or used up, the widget falls back to the ETA estimate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = aero.enabled,
                    enabled = hasKey,
                    onCheckedChange = ::setEnabled,
                )
            }

            if (!hasKey) {
                Button(
                    onClick = { showKeyDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add AeroAPI key")
                }
                Text(
                    "Stored encrypted in this device's keystore — never in the cloud, the " +
                        "app's backups, or the widget.",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            } else {
                // Pace-based quota meter: GREEN while on or ahead of the even
                // monthly burn rate, RED once the current rate is projected to
                // exhaust the allowance before the month ends (or it's spent).
                val cal = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"))
                val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                val elapsedFrac = ((cal.get(java.util.Calendar.DAY_OF_MONTH) - 1) +
                    cal.get(java.util.Calendar.HOUR_OF_DAY) / 24.0) / daysInMonth
                val overPace = aero.exhausted() ||
                    aero.requestCount > AeroStore.HARD_LIMIT * elapsedFrac
                val meterColor = if (overPace) cs.error else Color(0xFF2E7D32)
                val countFrac = (aero.requestCount.toDouble() / AeroStore.HARD_LIMIT)
                    .toFloat().coerceIn(0f, 1f)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = cs.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                // Primary: FlightAware's own billed figure (note:
                                // their /account/usage total lags, so this can sit
                                // at $0.00 for a while after calls).
                                Text(
                                    "$%.2f of $%.2f used"
                                        .format(aero.lastCostUsd ?: 0.0, AeroStore.BUDGET_USD),
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                    color = cs.onSurface,
                                )
                                // Secondary, small: our own live estimate (each
                                // call is $0.005) + the request count, pace-coloured.
                                Text(
                                    "~$%.2f · %d / %d requests".format(
                                        aero.requestCount * AeroStore.PER_QUERY_USD,
                                        aero.requestCount, AeroStore.HARD_LIMIT),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = meterColor,
                                )
                            }
                            FilledTonalIconButton(
                                onClick = ::pollUsage,
                                enabled = !checking,
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_sync), "check usage",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        LinearWavyProgressIndicator(
                            progress = { countFrac },
                            color = meterColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (aero.exhausted()) {
                    Text(
                        "Monthly free usage used up — paused until next month.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showKeyDialog = true }) { Text("Replace key") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = ::clearKey) { Text("Remove key") }
                }
            }
        }
    }

    if (showKeyDialog) {
        AeroKeyDialog(onDismiss = { showKeyDialog = false }, onSave = ::saveKey)
    }
}

/** Paste-the-key dialog; input is masked and never echoed back. */
@Composable
private fun AeroKeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(24.dp), Arrangement.spacedBy(16.dp)) {
                Text(
                    "AeroAPI key",
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Paste your FlightAware AeroAPI key. It is stored encrypted in this " +
                        "device's Android keystore only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("x-apikey") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: WidgetState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) {
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
            // Keep showing the aircraft we know about; the clock only appears
            // when there is genuinely no plane to draw
            if (state.status == WidgetState.Status.OK)
                com.sam.airblock.util.AircraftIcons.iconFor(state.typeCode, state.category)
            else R.drawable.ic_clock,
            "Data is stale",
            "Last update ${age()} (schedule: ${state.modeLabel ?: "normal"}). " +
                "The widget only refreshes while your home screen is visible.",
            cs.surfaceContainerHigh, cs.onSurfaceVariant)
        else -> StatusUi(
            // The current aircraft's silhouette (ADS-B Radar icon set);
            // iconFor falls back to a generic airliner when the type is unknown
            if (state.status == WidgetState.Status.NO_AIRCRAFT) R.drawable.ic_flight
            else com.sam.airblock.util.AircraftIcons.iconFor(state.typeCode, state.category),
            "Up to date",
            listOfNotNull(
                state.callsign
                    ?: "no aircraft nearby".takeIf {
                        state.status == WidgetState.Status.NO_AIRCRAFT },
                "updated ${age()}",
            ).joinToString(" · "),
            cs.secondaryContainer, cs.onSecondaryContainer)
    }

    // Status flips (ok → refreshing → stale…) glide between container colors
    // on the motion scheme's effect springs instead of snapping
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(ui.container, motion.defaultEffectsSpec())
    val content by animateColorAsState(ui.content, motion.defaultEffectsSpec())

    Surface(
        onClick = onToggle, // tap anywhere on the pill → full status detail
        shape = GroupTop,
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize(motion.defaultSpatialSpec())) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // While the engine works, the icon hands over to the expressive
            // shape-morphing loading indicator
            AnimatedContent(
                targetState = state.refreshing,
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) +
                        scaleIn(motion.defaultSpatialSpec()))
                        .togetherWith(fadeOut(motion.defaultEffectsSpec()) +
                            scaleOut(motion.defaultSpatialSpec()))
                },
                label = "status icon",
            ) { refreshing ->
                if (refreshing) {
                    LoadingIndicator(
                        color = content,
                        modifier = Modifier.size(32.dp),
                    )
                } else {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(ui.icon), null,
                            tint = content,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.title, style = MaterialTheme.typography.titleMediumEmphasized,
                    color = content)
                Text(ui.detail, style = MaterialTheme.typography.bodySmall,
                    color = content)
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onRefresh,
                enabled = !state.refreshing,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    painterResource(R.drawable.ic_sync), "refresh now",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // The checklist appears live while refreshing, and ON TAP at any
        // time — showing the last refresh's per-stage outcome plus the
        // schedule and error detail.
        AnimatedVisibility(
            visible = (state.refreshing || expanded) && state.stages.isNotEmpty(),
            enter = fadeIn(motion.defaultEffectsSpec()) +
                expandVertically(motion.defaultSpatialSpec()),
            exit = fadeOut(motion.defaultEffectsSpec()) +
                shrinkVertically(motion.defaultSpatialSpec()),
        ) {
            Column(
                Modifier.padding(start = 22.dp, end = 16.dp, bottom = 16.dp),
                Arrangement.spacedBy(10.dp),
            ) {
                state.stages.forEach { stage -> StageRow(stage, content) }
                if (expanded && !state.refreshing) {
                    state.modeLabel?.let {
                        StatusDetailRow("Schedule", it, content)
                    }
                    state.photoCredit?.let {
                        StatusDetailRow("Photo", "© $it / Planespotters", content)
                    }
                    state.lastError?.takeIf { state.errorCount > 0 }?.let {
                        StatusDetailRow("Last error", it, content)
                    }
                    StatusDetailRow(
                        "Fresh until",
                        if (state.staleAfterMs > 0)
                            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date(state.staleAfterMs))
                        else "—",
                        content,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun StatusDetailRow(label: String, value: String, content: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = content.copy(alpha = 0.7f),
            modifier = Modifier.width(86.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )
    }
}

/** Last-resort fix for a wedged widget: rebuild render sessions + engine. */
@Composable
private fun RestartRow(onRestart: () -> Unit) {
    Surface(
        onClick = onRestart,
        shape = GroupSingle,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_sync), null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Force full restart",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "If the widget ever sticks: rebuilds its render sessions and the engine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One row of the refresh checklist inside the status card. */
@Composable
private fun StageRow(stage: WidgetState.Stage, content: Color) {
    val pending = stage.state == WidgetState.Stage.PENDING
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (stage.state) {
                WidgetState.Stage.RUNNING -> LoadingIndicator(
                    color = content,
                    modifier = Modifier.size(24.dp),
                )
                WidgetState.Stage.DONE -> Icon(
                    Icons.Filled.Check, "done",
                    tint = content,
                    modifier = Modifier.size(18.dp),
                )
                WidgetState.Stage.FAILED -> Icon(
                    painterResource(R.drawable.ic_warning), "failed",
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
                else -> Box(
                    Modifier
                        .size(7.dp)
                        .background(content.copy(alpha = 0.35f), CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            stage.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (pending) content.copy(alpha = 0.55f) else content,
        )
        when {
            stage.state == WidgetState.Stage.FAILED -> StageTag("failed", content)
            stage.cached && stage.state == WidgetState.Stage.DONE ->
                StageTag("cached", content)
        }
    }
}

/** Tiny tonal pill after a checklist label ("cached", "failed"). */
@Composable
private fun StageTag(text: String, content: Color) {
    Spacer(Modifier.width(8.dp))
    Surface(
        shape = CircleShape,
        color = content.copy(alpha = 0.14f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
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
        shape = GroupBottom,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row {
                StatCell(netIcon, netLabel, "Network", Modifier.weight(1f))
                StatCell(R.drawable.ic_clock, nextLabel, "Next refresh", Modifier.weight(1f))
            }
            progress?.let {
                Spacer(Modifier.height(12.dp))
                // Wavy countdown to the next refresh; the wave swells as the
                // refresh gets closer, then flattens again
                LinearWavyProgressIndicator(
                    progress = { it },
                    amplitude = { p -> p },
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
                style = MaterialTheme.typography.titleMediumEmphasized,
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

@Composable
private fun NetModeRow(
    icon: Int,
    label: String,
    mode: NetMode,
    normalLabel: String,
    onChange: (NetMode) -> Unit,
) {
    // Header indented under "Default refresh" — visually a sub-option of it.
    // The button group itself stays full-width so its segments are exactly
    // as wide as the Default refresh row's (and the long label fits).
    Column(Modifier, Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        ConnectedToggleRow(
            options = listOf(
                NetMode.NORMAL to normalLabel,
                NetMode.SLOW to "10 min",
                NetMode.OFF to "Off",
            ),
            selected = mode,
            onSelect = onChange,
        )
    }
}

/** The activity-log console as its own pushed page. */
@Composable
private fun LogScreen(
    logEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        run {
            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "back")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Activity log",
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = logEnabled, onCheckedChange = onToggle)
                }
                Text(
                    "What the engine did and when. Turning the log off deletes all records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
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
        style = MaterialTheme.typography.titleSmallEmphasized,
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
    shape: Shape,
    onClick: () -> Unit,
) {
    // Ungranted rows are RED — a missing permission means the widget cannot
    // work at all, so it must read as "action required", not as a neutral card.
    val motion = MaterialTheme.motionScheme
    val containerColor by animateColorAsState(
        if (granted) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.errorContainer,
        motion.defaultEffectsSpec(),
    )
    Surface(
        onClick = onClick,
        enabled = !granted,
        shape = shape,
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Granted permissions wear the M3 Expressive "verified"-style
            // scalloped badge; pending ones stay a plain alert circle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        if (granted) MaterialShapes.Cookie9Sided.toShape()
                        else MaterialShapes.Circle.toShape(),
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
                    style = MaterialTheme.typography.titleMediumEmphasized,
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

/**
 * Optional setup row: battery → "Unrestricted". Unlike the rows above, this is
 * NOT required — Airblock works without it — but aggressive OEM battery
 * managers (see dontkillmyapp.com) can still kill the engine; unrestricted
 * makes the widget reliable on those phones. Neutral colors, never red.
 */
@Composable
private fun BatteryRow(granted: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Surface(
        onClick = onClick,
        enabled = !granted,
        shape = GroupBottom,
        color = if (granted) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val onColor = if (granted) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        if (granted) MaterialShapes.Cookie9Sided.toShape()
                        else MaterialShapes.Circle.toShape(),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_battery_saver), null,
                    tint = if (granted) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Battery — unrestricted",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = onColor,
                    )
                    StageTag("optional", onColor)
                }
                Text(
                    "Some phones kill background apps anyway; unrestricted keeps the " +
                        "widget reliable. Airblock's own gating keeps real usage near zero.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) onColor
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Why? dontkillmyapp.com",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/"))
                            )
                        },
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
