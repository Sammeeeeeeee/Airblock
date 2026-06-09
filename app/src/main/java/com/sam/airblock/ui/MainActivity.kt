package com.sam.airblock.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sam.airblock.data.Settings
import com.sam.airblock.data.SettingsStore
import com.sam.airblock.engine.Gates
import com.sam.airblock.engine.UpdateService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

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
                    onRequestLocation = {
                        locationPermission.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    },
                    onRequestBackgroundLocation = {
                        startActivity(
                            Intent(
                                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null)
                            )
                        )
                    },
                    onRequestUsageAccess = {
                        startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    onRequestLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onRequestUsageAccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var radiusNm by remember { mutableStateOf(50f) }
    var intervalSec by remember { mutableStateOf(15) }
    var homeLat by remember { mutableStateOf("") }
    var homeLon by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val s = SettingsStore.read(context)
        radiusNm = s.radiusNm.toFloat()
        intervalSec = s.intervalSec
        homeLat = s.homeLat?.toString() ?: ""
        homeLon = s.homeLon?.toString() ?: ""
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
                    homeLat = homeLat.toDoubleOrNull(),
                    homeLon = homeLon.toDoubleOrNull(),
                )
            )
            UpdateService.start(context, tickNow = true)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Airblock") }) }) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ElevatedCard {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Airblock needs precise location (set to “Allow all the time”) to find " +
                            "the nearest plane, and Usage access so it only refreshes while " +
                            "your home screen is visible — never while another app is open.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onRequestLocation, modifier = Modifier.fillMaxWidth()) {
                        Text("1. Grant precise location")
                    }
                    OutlinedButton(
                        onClick = onRequestBackgroundLocation,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("2. Set location to “Allow all the time”")
                    }
                    val hasUsage = remember { Gates(context).hasUsageAccess() }
                    OutlinedButton(
                        onClick = onRequestUsageAccess,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (hasUsage) "3. Usage access ✓" else "3. Grant usage access")
                    }
                }
            }

            ElevatedCard {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    Text("Search radius: ${radiusNm.roundToInt()} nm",
                        style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = radiusNm,
                        onValueChange = { radiusNm = it },
                        onValueChangeFinished = { save() },
                        valueRange = 5f..250f,
                    )
                    Text("Refresh every", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(15, 30, 60).forEachIndexed { i, sec ->
                            SegmentedButton(
                                selected = intervalSec == sec,
                                onClick = { intervalSec = sec; save() },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                            ) { Text("${sec}s") }
                        }
                    }
                }
            }

            ElevatedCard {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    Text("Home location (fallback)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Used only when the phone has no location fix (e.g. right after a reboot).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = homeLat,
                            onValueChange = { homeLat = it; save() },
                            label = { Text("Latitude") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = homeLon,
                            onValueChange = { homeLon = it; save() },
                            label = { Text("Longitude") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                }
            }

            ElevatedCard {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(4.dp)) {
                    Text("Data & photos", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Live aircraft data from adsb.lol (community ADS-B network). " +
                            "Aircraft photos via the Planespotters.net API — © their " +
                            "respective photographers.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
