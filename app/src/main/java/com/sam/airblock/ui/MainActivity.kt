package com.sam.airblock.ui

import android.Manifest
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sam.airblock.data.Settings
import com.sam.airblock.data.SettingsStore
import com.sam.airblock.engine.Gates
import com.sam.airblock.engine.UpdateService
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

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPerms()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opening the app is a foreground context — always allowed to (re)start the engine
        UpdateService.start(this, tickNow = true)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            MaterialTheme(
                colorScheme = if (dark) dynamicDarkColorScheme(ctx)
                else dynamicLightColorScheme(ctx)
            ) {
                SettingsScreen(
                    perms = perms,
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
    }
}

@Composable
private fun SettingsScreen(
    perms: PermissionsState,
    onGrantLocation: () -> Unit,
    onGrantBackground: () -> Unit,
    onGrantUsage: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var radiusNm by remember { mutableStateOf(50f) }
    var intervalSec by remember { mutableStateOf(15) }

    LaunchedEffect(Unit) {
        val s = SettingsStore.read(context)
        radiusNm = s.radiusNm.toFloat()
        intervalSec = s.intervalSec
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
                )
            )
            UpdateService.start(context, tickNow = true)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(40.dp))
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
            Spacer(Modifier.height(24.dp))

            // ---- All-set banner ------------------------------------------
            if (perms.allGranted) {
                Surface(
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
                            "All set — add the 4×2 Airblock widget to your home screen",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ---- Permissions ---------------------------------------------
            SectionLabel("Setup")
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
                color = MaterialTheme.colorScheme.surfaceVariant,
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
                    Slider(
                        value = radiusNm,
                        onValueChange = { radiusNm = it },
                        onValueChangeFinished = { save() },
                        valueRange = 5f..250f,
                    )
                    Text(
                        "Refresh every",
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
                    Text(
                        "Only while the widget is on screen — never in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            // ---- Attribution ----------------------------------------------
            SectionLabel("Data & photos")
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Live aircraft data from adsb.lol, the community ADS-B network. " +
                        "Aircraft photos via the Planespotters.net API — © their " +
                        "respective photographers.",
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
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
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
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (granted) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
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
                        else MaterialTheme.colorScheme.surface,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    tint = if (granted) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (granted) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Icon(
                    Icons.Filled.Check, "granted",
                    tint = MaterialTheme.colorScheme.primary,
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
