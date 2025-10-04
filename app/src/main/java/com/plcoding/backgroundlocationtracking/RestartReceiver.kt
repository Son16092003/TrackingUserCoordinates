package com.plcoding.backgroundlocationtracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("RestartReceiver", "📢 onReceive called with action=${intent?.action}")

        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            context?.let { ctx ->
                val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val userName = prefs.getString("userName", null)

                Log.d("RestartReceiver", "🔍 Checking SharedPreferences → userName=$userName")

                if (!userName.isNullOrEmpty()) {
                    // Nếu userName đã lưu → start LocationService
                    Log.d("RestartReceiver", "✅ userName exists, starting LocationService: $userName")
                    val serviceIntent = Intent(ctx, LocationService::class.java).apply {
                        action = LocationService.ACTION_START
                        putExtra("userName", userName)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(serviceIntent)
                        Log.d("RestartReceiver", "▶️ startForegroundService called")
                    } else {
                        ctx.startService(serviceIntent)
                        Log.d("RestartReceiver", "▶️ startService called")
                    }
                } else {
                    // Nếu chưa có userName → bật activity để nhập
                    Log.w("RestartReceiver", "⚠️ userName missing, launching MainActivity")

                    // Bật activity nếu đang disabled
                    val pm = ctx.packageManager
                    pm.setComponentEnabledSetting(
                        android.content.ComponentName(ctx, MainActivity::class.java),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.d("RestartReceiver", "ℹ️ MainActivity component enabled")

                    val activityIntent = Intent(ctx, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    ctx.startActivity(activityIntent)
                    Log.d("RestartReceiver", "🚀 MainActivity started for user input")
                }
            }
        } else {
            Log.d("RestartReceiver", "ℹ️ Ignored action: ${intent?.action}")
        }
    }
}
