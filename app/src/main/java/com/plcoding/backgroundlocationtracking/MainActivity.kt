package com.plcoding.backgroundlocationtracking

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.plcoding.backgroundlocationtracking.ui.theme.BackgroundLocationTrackingTheme

class MainActivity : ComponentActivity() {

    private val locationViewModel: LocationViewModel by viewModels()

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val lat = it.getDoubleExtra("latitude", 0.0)
                val lon = it.getDoubleExtra("longitude", 0.0)
                Log.d("MainActivity", "📡 Received broadcast: latitude=$lat, longitude=$lon")

                locationViewModel.updateLocation(lat, lon)
                locationViewModel.setTracking(true)
                Log.d("MainActivity", "✅ ViewModel updated with new location")
            }
        }
    }

    private val requestForegroundLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            Log.d("MainActivity", "🔑 Foreground permission request result: $perms")

            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                perms[Manifest.permission.POST_NOTIFICATIONS] ?: false else true

            if (fine || coarse) {
                Log.d("MainActivity", "✅ Foreground location permission granted")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.d("MainActivity", "⚠️ Requesting background location permission")
                    requestBackgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    startLocationService()
                }
            } else {
                Log.w("MainActivity", "❌ Foreground location permission denied")
                Toast.makeText(this, "Cần cấp quyền vị trí để tiếp tục!", Toast.LENGTH_LONG).show()
            }
        }

    private val requestBackgroundLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.d("MainActivity", "✅ Background location permission granted")
                startLocationService()
            } else {
                Log.w("MainActivity", "❌ Background location permission denied")
                Toast.makeText(this, "Cần cấp quyền background location để tracking!", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "🎬 onCreate called")
        setContent {
            BackgroundLocationTrackingTheme {
                TrackingScreen(locationViewModel)
            }
        }

        if (!hasAllPermissions()) {
            Log.d("MainActivity", "⚠️ Missing permissions, requesting foreground permissions")
            askForegroundPermissions()
        } else {
            Log.d("MainActivity", "✅ All permissions granted, starting service")
            startLocationService()
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "📡 Registering locationReceiver")
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(locationReceiver, IntentFilter("LOCATION_UPDATE"))
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "📴 Unregistering locationReceiver")
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(locationReceiver)
    }

    private fun askForegroundPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)

        Log.d("MainActivity", "🔑 Asking for foreground permissions: $permissions")
        requestForegroundLauncher.launch(permissions.toTypedArray())
    }

    private fun hasAllPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        else true
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        else true

        Log.d("MainActivity", "🔍 Permission check: fine=$fine, coarse=$coarse, notif=$notif, background=$background")
        return (fine || coarse) && notif && background
    }

    private fun startLocationService() {
        Log.d("MainActivity", "🚀 Starting LocationService...")
        Intent(applicationContext, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            startService(this)
        }
        Toast.makeText(this, "Location tracking started", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "✅ LocationService started")
    }
}
