package com.plcoding.backgroundlocationtracking

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.LocationServices
import com.plcoding.backgroundlocationtracking.admin.PolicyManager
import com.plcoding.backgroundlocationtracking.data.LocationRepository
import com.plcoding.backgroundlocationtracking.worker.LocationSyncWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class LocationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationClient: LocationClient
    private lateinit var repository: LocationRepository
    private lateinit var policyManager: PolicyManager
    private var locationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "🚀 Service created | hashCode=${this.hashCode()}")

        locationClient = DefaultLocationClient(
            applicationContext,
            LocationServices.getFusedLocationProviderClient(applicationContext)
        )

        repository = LocationRepository(applicationContext)
        policyManager = PolicyManager(this)

        // Sync pending locations ngay khi service start
        serviceScope.launch {
            try {
                Log.d("LocationService", "🔄 Sync pending locations on service start...")
                repository.syncPendingLocations()
                Log.d("LocationService", "✅ Pending locations synced")
            } catch (e: Exception) {
                Log.e("LocationService", "❌ Failed to sync pending locations", e)
                enqueueSyncWorker()
            }
        }

        // Ép các policy nếu Device Owner
        if (policyManager.isAdminActive()) {
            try {
                policyManager.blockUninstall(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    policyManager.blockLocationPermissionChanges()
                }
                policyManager.enforceLocationPolicy()
                Log.d("LocationService", "✅ Device Owner policies enforced")
            } catch (e: Exception) {
                Log.e("LocationService", "❌ Failed to enforce policies", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "▶️ onStartCommand | action=${intent?.action}, startId=$startId")

        when (intent?.action) {
            ACTION_START -> {
                // Lấy userName từ intent hoặc SharedPreferences
                val userName = intent.getStringExtra("userName")
                    ?: getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        .getString("userName", "unknown_user")
                    ?: "unknown_user"

                startTracking(userName)
            }
            ACTION_STOP -> stopTracking()
            else -> Log.w("LocationService", "⚠️ Unknown action received: ${intent?.action}")
        }
        return START_STICKY
    }

    private fun startTracking(userName: String) {
        if (locationJob != null) return

        Log.d("LocationService", "📍 Start tracking location for user=$userName")

        val silentNotification = createSilentNotification()

        locationJob = locationClient.getLocationUpdates(5000L)
            .catch { e -> Log.e("LocationService", "❌ Error receiving location", e) }
            .onEach { location ->
                val lat = location.latitude
                val lon = location.longitude
                Log.d("LocationService", "📍 Got location update: lat=$lat, lon=$lon")

                sendLocationToUI(lat, lon, userName)
                uploadOrSaveLocation(lat, lon, userName)
            }
            .launchIn(serviceScope)

        startForeground(NOTIFICATION_ID, silentNotification)
        Log.d("LocationService", "✅ Foreground service started (silent notify)")
    }

    private fun sendLocationToUI(lat: Double, lon: Double, userName: String) {
        val intent = Intent("LOCATION_UPDATE").apply {
            putExtra("latitude", lat)
            putExtra("longitude", lon)
            putExtra("userName", userName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d("LocationService", "📤 Sent broadcast: lat=$lat, lon=$lon, user=$userName")
    }

    private fun uploadOrSaveLocation(lat: Double, lon: Double, userName: String) {
        serviceScope.launch {
            try {
                val timestamp = System.currentTimeMillis()
                val deviceId = Settings.Secure.getString(
                    applicationContext.contentResolver,
                    Settings.Secure.ANDROID_ID
                )

                repository.saveOrUploadLocation(lat, lon, timestamp, deviceId, userName)
                Log.d("LocationService", "💾 Location saved/uploaded: lat=$lat, lon=$lon, user=$userName")

                enqueueSyncWorker()
            } catch (e: Exception) {
                Log.e("LocationService", "❌ Failed to save/upload location", e)
                enqueueSyncWorker()
            }
        }
    }

    private fun stopTracking() {
        locationJob?.cancel()
        locationJob = null
        stopForeground(true)
        stopSelf()
        Log.d("LocationService", "🛑 Tracking stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        locationJob?.cancel()
        serviceScope.cancel()
        Log.d("LocationService", "💀 Service destroyed")
    }

    /**
     * 🔇 Notification ẩn (silent, dùng channel "location" đã tạo trong LocationApp)
     */
    private fun createSilentNotification(): Notification {
        return NotificationCompat.Builder(this, "location")
            .setSmallIcon(R.drawable.ic_notification_placeholder) // icon trong suốt 1x1
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSilent(true)
            .setOngoing(true) // giữ notification như service bắt buộc
            .build()
    }

    private fun enqueueSyncWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                15,
                java.util.concurrent.TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "LocationSyncWork",
            ExistingWorkPolicy.KEEP,
            request
        )
        Log.d("LocationService", "✅ SyncWorker enqueued id=${request.id}")
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_ID = 1
    }
}
