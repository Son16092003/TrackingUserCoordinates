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
                    "📡 [Broadcast] Nhận được tọa độ mới → lat=$lat, lon=$lon, user=$userName"
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d("MainActivity", "🎬 onCreate() — Bắt đầu khởi tạo MainActivity")

        // Lấy deviceId duy nhất của thiết bị
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown_device"
        Log.d("MainActivity", "💡 DeviceId = $deviceId")

        // Kiểm tra trạng thái Device Owner
        val policyManager = PolicyManager(this)
        if (policyManager.isAdminActive()) {
            Log.d("MainActivity", "✅ Device Owner đang hoạt động")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                policyManager.blockLocationPermissionChanges()
                Log.d("MainActivity", "🔒 Đã chặn người dùng thay đổi quyền vị trí")
            }

            policyManager.enforceLocationPolicy()
            Log.d("MainActivity", "📍 Đã ép chính sách yêu cầu quyền vị trí")
        } else {
            Log.w("MainActivity", "⚠️ Ứng dụng chưa được set Device Owner — không thể ép quyền")
        }

        // Khởi tạo giao diện người dùng
        handleUI()
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "▶️ onStart(): Đăng ký lắng nghe broadcast LOCATION_UPDATE")
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(locationReceiver, IntentFilter("LOCATION_UPDATE"))
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "🛑 onStop(): Hủy đăng ký broadcast LOCATION_UPDATE")
        LocalBroadcastManager.getInstance(this).unregisterReceiver(locationReceiver)
    }

    /**
     * Xử lý UI nhập username
     */
    private fun handleUI() {
        val etUserName = findViewById<EditText>(R.id.etUserName)
        val btnSubmit = findViewById<Button>(R.id.btnSubmit)

        Log.d("MainActivity", "🧩 Khởi tạo UI nhập tên người dùng")

        val savedName = prefs.getString("userName", null)
        if (!savedName.isNullOrEmpty()) {
            // Nếu đã có userName → khóa lại UI
            etUserName.setText(savedName)
            etUserName.isEnabled = false
            btnSubmit.isEnabled = false
            Log.d("MainActivity", "✅ userName đã lưu trước đó: $savedName")

            // Bắt đầu tracking ngay
            startLocationService(savedName)
        } else {
            Log.d("MainActivity", "⚠️ userName chưa có — yêu cầu nhập mới")

            btnSubmit.setOnClickListener {
                val name = etUserName.text.toString().trim()
                if (name.isNotEmpty()) {
                    prefs.edit().putString("userName", name).apply()
                    Log.d("MainActivity", "📤 userName được lưu: $name")
                    Toast.makeText(this, "Xin chào $name", Toast.LENGTH_SHORT).show()

                    // Bắt đầu LocationService
                    startLocationService(name)

                    // Ẩn icon app khỏi launcher
                    val pm = packageManager
                    pm.setComponentEnabledSetting(
                        ComponentName(this, MainActivity::class.java),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.d("MainActivity", "🚫 Đã ẩn icon ứng dụng khỏi launcher")

                    // Đóng Activity
                    Log.d("MainActivity", "🏁 Đóng MainActivity sau khi lưu userName")
                    finish()
                } else {
                    Toast.makeText(this, "Tên không được để trống!", Toast.LENGTH_SHORT).show()
                    Log.w("MainActivity", "⚠️ Người dùng bấm Submit nhưng chưa nhập tên")
                }
            }
        }
    }

    /**
     * Bắt đầu service tracking
     */
    private fun startLocationService(userName: String) {
        Log.d("MainActivity", "🚀 startLocationService() — user=$userName, device=$deviceId")

        val intent = Intent(applicationContext, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            putExtra("userName", userName)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d("MainActivity", "📌 Sử dụng startForegroundService() cho Android O+")
            startForegroundService(intent)
        } else {
            Log.d("MainActivity", "📌 Sử dụng startService() cho Android < O")
            startService(intent)
        }
    }
}
