package com.plcoding.backgroundlocationtracking

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.*
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
    private lateinit var repository: LocationRepository
    private lateinit var policyManager: PolicyManager
    private var locationClient: LocationClient? = null
    private var locationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 Service created | hashCode=${this.hashCode()}")

        repository = LocationRepository(applicationContext)
        policyManager = PolicyManager(this)

        // ✅ Khởi tạo FusedLocationProviderClient
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(applicationContext)
            locationClient = DefaultLocationClient(applicationContext, fusedClient)
            Log.d(TAG, "✅ Using DefaultLocationClient with internal fallback")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to init DefaultLocationClient", e)
            locationClient = null
        }

        // 🔄 Đồng bộ dữ liệu pending khi service khởi động
        serviceScope.launch {
            try {
                Log.d(TAG, "🔄 Sync pending locations on start...")
                repository.syncPendingLocations()
                Log.d(TAG, "✅ Pending locations synced")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Sync failed on start", e)
                enqueueSyncWorker()
            }
        }

        // 🧩 Áp dụng chính sách Device Owner (nếu có)
        if (policyManager.isAdminActive()) {
            try {
                policyManager.blockUninstall(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    policyManager.blockLocationPermissionChanges()
                }
                policyManager.enforceLocationPolicy()
                Log.d(TAG, "✅ Device Owner policies enforced")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Policy enforcement failed", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "▶️ onStartCommand | action=${intent?.action}, startId=$startId")

        when (intent?.action) {
            ACTION_START -> {
                val userName = intent.getStringExtra("userName")
                    ?: getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        .getString("userName", "unknown_user")
                    ?: "unknown_user"
                startTracking(userName)
            }
            ACTION_STOP -> stopTracking()
            else -> Log.w(TAG, "⚠️ Unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    private fun startTracking(userName: String) {
        if (locationJob != null) {
            Log.d(TAG, "ℹ️ Tracking already running, ignore duplicate start")
            return
        }

        Log.d(TAG, "📍 Start tracking location for user=$userName")

        val silentNotification = createSilentNotification()
        startForeground(NOTIFICATION_ID, silentNotification)

        // Bắt đầu nhận vị trí mỗi 5 giây
        locationJob = locationClient?.getLocationUpdates(2000L)
            ?.catch { e ->
                Log.e(TAG, "❌ Error receiving location: ${e.message}", e)
            }
            ?.onEach { location ->
                handleNewLocation(location, userName)
            }
            ?.launchIn(serviceScope)
            ?: run {
                Log.e(TAG, "❌ LocationClient is null, cannot start tracking")
                null
            }
    }

    private var lastLocation: Location? = null // Lưu vị trí gần nhất đã gửi

    private suspend fun handleNewLocation(location: Location, userName: String) {
        val lat = location.latitude
        val lon = location.longitude
        val accuracy = location.accuracy
        val timestamp = System.currentTimeMillis()

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        // ⚡ Nếu chưa có vị trí trước đó → lưu luôn
        if (lastLocation == null) {
            Log.d(TAG, "📍 First location fix: lat=$lat, lon=$lon, acc=$accuracy")
            lastLocation = location
            repository.saveOrUploadLocation(lat, lon, timestamp, deviceId, userName)
            enqueueSyncWorker()
            sendLocationToUI(lat, lon, userName)
            return
        }

        // 📏 Tính khoảng cách di chuyển (m)
        val movedDistance = lastLocation!!.distanceTo(location)
        val threshold = maxOf(20f, 2 * accuracy)

        Log.d(TAG, "📏 movedDistance=${"%.2f".format(movedDistance)}m, threshold=${"%.2f".format(threshold)}m")

        // ✅ Chỉ gửi nếu di chuyển đủ xa
        if (movedDistance >= threshold) {
            Log.d(TAG, "✅ Significant movement detected → send location")
            lastLocation = location
            sendLocationToUI(lat, lon, userName)
            uploadOrSaveLocation(lat, lon, userName)
        } else {
            Log.d(TAG, "⏸️ Movement below threshold (${movedDistance}m < ${threshold}m), skip upload")
        }
    }


    private fun sendLocationToUI(lat: Double, lon: Double, userName: String) {
        val intent = Intent("LOCATION_UPDATE").apply {
            putExtra("latitude", lat)
            putExtra("longitude", lon)
            putExtra("userName", userName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "📤 Broadcast sent: lat=$lat, lon=$lon, user=$userName")
    }

    private fun uploadOrSaveLocation(lat: Double, lon: Double, userName: String) {
        serviceScope.launch {
            try {
                val timestamp = System.currentTimeMillis()
                val deviceId = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ANDROID_ID
                )

                repository.saveOrUploadLocation(lat, lon, timestamp, deviceId, userName)
                Log.d(TAG, "💾 Location saved/uploaded successfully")

                // 🔁 Worker mới sẽ thay thế worker cũ → tránh delay, đảm bảo realtime
                enqueueSyncWorker()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Save/upload failed", e)
                enqueueSyncWorker()
            }
        }
    }

    private fun stopTracking() {
        locationJob?.cancel()
        locationJob = null
        stopForeground(true)
        stopSelf()
        Log.d(TAG, "🛑 Tracking stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        locationJob?.cancel()
        serviceScope.cancel()
        Log.d(TAG, "💀 Service destroyed")
    }

    private fun createSilentNotification(): Notification {
        return NotificationCompat.Builder(this, "location")
            .setSmallIcon(R.drawable.ic_notification_placeholder)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    /**
     * ✅ Worker an toàn và realtime:
     * - REPLACE: luôn chạy job mới nhất (job cũ hủy nhưng dữ liệu vẫn lưu Room)
     * - LINEAR backoff: retry đều đặn mỗi phút
     * - Tự chạy lại khi có mạng
     */
    private fun enqueueSyncWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                1,
                java.util.concurrent.TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "LocationSyncWork",
            ExistingWorkPolicy.REPLACE, // ⚡ realtime, không mất dữ liệu
            request
        )

        Log.d(TAG, "✅ SyncWorker enqueued (auto-trigger on network available)")
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "LocationService"
    }
}
