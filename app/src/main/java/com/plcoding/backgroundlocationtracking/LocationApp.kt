package com.plcoding.backgroundlocationtracking

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.plcoding.backgroundlocationtracking.admin.PolicyManager

class LocationApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationApp", "🚀 App started")

        // 1️⃣ Notification Channel cho LocationService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "location",
                "Background Location",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            Log.d("LocationApp", "✅ Silent NotificationChannel created")
        }

        // 2️⃣ Init PolicyManager
        val policyManager = PolicyManager(this)

        // 3️⃣ Nếu app là Device Owner thì áp chính sách
        if (policyManager.isAdminActive()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // ⚠️ Chỉ bật nếu bạn muốn test chặn uninstall thật sự
                    policyManager.blockUninstall(true)
                    policyManager.blockLocationPermissionChanges()
                }
                policyManager.enforceLocationPolicy()

                Log.d("LocationApp", "✅ Policies enforced for Device Owner")
            } catch (e: Exception) {
                Log.e("LocationApp", "❌ Failed to enforce policies", e)
            }

            // 4️⃣ Kiểm tra userName
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedName = prefs.getString("userName", null)

            if (!savedName.isNullOrEmpty()) {
                // Nếu đã có userName → start service
                startLocationService(savedName)
            } else {
                // Nếu chưa có → để MainActivity nhập
                Log.w("LocationApp", "⚠️ userName chưa có → chờ MainActivity nhập")
            }
        } else {
            Log.w("LocationApp", "⚠️ Device Admin not active → policies not applied")
        }
    }

    private fun startLocationService(userName: String) {
        val serviceIntent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            putExtra("userName", userName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.d("LocationApp", "▶️ LocationService auto-started with user=$userName")
    }
}
