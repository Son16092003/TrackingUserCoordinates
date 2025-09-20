package com.plcoding.backgroundlocationtracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.LocationServices
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "🚀 Service created")
        locationClient = DefaultLocationClient(
            applicationContext,
            LocationServices.getFusedLocationProviderClient(applicationContext)
        )
        repository = LocationRepository(applicationContext)

        createNotificationChannel()

        // ✅ Thử sync pending ngay khi service khởi động
        serviceScope.launch {
            try {
                Log.d("LocationService", "🔄 Trying to sync pending locations on service start...")
                repository.syncPendingLocations()
                Log.d("LocationService", "✅ Pending locations synced on service start")
            } catch (e: Exception) {
                Log.e("LocationService", "❌ Failed to sync pending locations", e)
                enqueueSyncWorker()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "▶️ onStartCommand called with action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
            else -> Log.w("LocationService", "⚠️ Unknown action received: ${intent?.action}")
        }
        return START_STICKY
    }

    private fun startTracking() {
        Log.d("LocationService", "📍 Start tracking location...")
        val notification = NotificationCompat.Builder(this, "location")
            .setContentTitle("Tracking location...")
            .setContentText("Location: null")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        locationClient.getLocationUpdates(5000L)
            .catch { e ->
                Log.e("LocationService", "❌ Error while receiving location updates", e)
            }
            .onEach { location ->
                val lat = location.latitude
                val lon = location.longitude
                Log.d("LocationService", "📍 Got location update: latitude=$lat, longitude=$lon")

                // Update notification
                val updatedNotification = notification.setContentText(
                    "Location: (${"%.5f".format(lat)}, ${"%.5f".format(lon)})"
                )
                notificationManager.notify(1, updatedNotification.build())

                // Broadcast to UI
                sendLocationToUI(lat, lon)

                // Save or upload location
                uploadOrSaveLocation(lat, lon)
            }
            .launchIn(serviceScope)

        startForeground(1, notification.build())
        Log.d("LocationService", "✅ Foreground service started")
    }

    private fun sendLocationToUI(lat: Double, lon: Double) {
        Log.d("LocationService", "📤 Sending location to UI: $lat,$lon")
        val intent = Intent("LOCATION_UPDATE")
        intent.putExtra("latitude", lat)
        intent.putExtra("longitude", lon)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun uploadOrSaveLocation(lat: Double, lon: Double) {
        serviceScope.launch {
            try {
                val timestamp = System.currentTimeMillis()
                Log.d("LocationService", "💾 Handling location -> saveOrUpload: $lat,$lon,$timestamp")
                repository.saveOrUploadLocation(lat, lon, timestamp)
                Log.d("LocationService", "✅ Location handled (uploaded or saved): $lat,$lon,$timestamp")

                enqueueSyncWorker()
            } catch (e: Exception) {
                Log.e("LocationService", "❌ Failed to handle location", e)
                enqueueSyncWorker()
            }
        }
    }

    private fun stopTracking() {
        stopForeground(true)
        stopSelf()
        Log.d("LocationService", "🛑 Tracking stopped, service stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d("LocationService", "💀 Service destroyed and coroutine scope cancelled")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "location",
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d("LocationService", "🔔 Notification channel created")
        }
    }

    /**
     * Worker sẽ sync pending locations khi có mạng trở lại
     */
    private fun enqueueSyncWorker() {
        Log.d("LocationService", "📌 Enqueuing sync worker with NetworkType.CONNECTED")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "LocationSyncWork",
            ExistingWorkPolicy.KEEP, // giữ worker cũ, không enqueue thêm nếu đang chạy
            request
        )

        Log.d("LocationService", "✅ Unique SyncWorker enqueued (will sync when online)")
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }
}
