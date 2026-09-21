package com.example.itantra.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.ui.theme.ITantraColors

data class BatteryState(
    val levelPercent: Int,
    val isCharging: Boolean
)

@Composable
fun rememberBatteryState(): BatteryState {
    val context = LocalContext.current

    // Initial sticky intent read
    val initialStatus = remember(context) {
        val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, iFilter)
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 85
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        BatteryState(pct, charging)
    }

    var batteryState by remember { mutableStateOf(initialStatus) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    if (pct >= 0) {
                        batteryState = BatteryState(pct, charging)
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (ignored: Exception) {
            }
        }
    }

    return batteryState
}

fun batteryIconFor(level: Int, isCharging: Boolean): ImageVector {
    return if (isCharging) {
        Icons.Filled.BatteryChargingFull
    } else when {
        level >= 90 -> Icons.Filled.BatteryFull
        level >= 75 -> Icons.Filled.Battery6Bar
        level >= 60 -> Icons.Filled.Battery5Bar
        level >= 45 -> Icons.Filled.Battery4Bar
        level >= 30 -> Icons.Filled.Battery3Bar
        level >= 15 -> Icons.Filled.Battery2Bar
        else -> Icons.Filled.BatteryAlert
    }
}

fun batteryColorFor(level: Int, isCharging: Boolean): Color {
    return if (isCharging) {
        ITantraColors.StatusSuccess
    } else when {
        level <= 15 -> ITantraColors.StatusDanger
        level <= 30 -> ITantraColors.StatusWarning
        else -> ITantraColors.StatusSuccess
    }
}

@Composable
fun TacticalBatteryPill(
    battery: BatteryState = rememberBatteryState(),
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(ITantraColors.CanvasBg, RoundedCornerShape(6.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = batteryIconFor(battery.levelPercent, battery.isCharging),
            contentDescription = if (battery.isCharging) "Charging" else "Battery",
            tint = batteryColorFor(battery.levelPercent, battery.isCharging),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${battery.levelPercent}%",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = ITantraColors.TextHeadline
        )
    }
}
