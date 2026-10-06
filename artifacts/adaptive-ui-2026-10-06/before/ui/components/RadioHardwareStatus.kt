package com.example.itantra.ui.components

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** A live hardware reading for Diagnostics. Unknown/permission states are never shown as ready. */
@Composable
fun rememberBluetoothHardwareStatus(): String {
    val context = LocalContext.current

    fun readStatus(): String {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return "Not supported on this phone"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return "Nearby devices permission needed"
        return try {
            if (adapter.isEnabled) "On" else "Off — turn on in Android settings"
        } catch (_: SecurityException) {
            "Nearby devices permission needed"
        }
    }

    var status by remember(context) { mutableStateOf(readStatus()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) status = readStatus()
            }
        }
        val registered = try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            true
        } catch (_: SecurityException) {
            false
        }
        onDispose {
            if (registered) context.unregisterReceiver(receiver)
        }
    }
    return status
}
