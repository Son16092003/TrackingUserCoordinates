package com.plcoding.backgroundlocationtracking

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class LocationViewModel : ViewModel() {

    private var _isTracking by mutableStateOf(false)
    val isTracking: Boolean get() = _isTracking

    var latitude by mutableStateOf(0.0)
        private set
    var longitude by mutableStateOf(0.0)
        private set

    var userName by mutableStateOf("unknown_user")
        private set
    var deviceId by mutableStateOf("unknown_device")
        private set

    /**
     * Update vị trí
     */
    fun updateLocation(lat: Double, lon: Double) {
        latitude = lat
        longitude = lon
    }

    /**
     * Update trạng thái tracking
     */
    fun setTracking(tracking: Boolean) {
        _isTracking = tracking
    }

    /**
     * Set thông tin user/device ID
     */
    fun setUserInfo(name: String, device: String) {
        userName = name
        deviceId = device
    }

    /**
     * Init từ SharedPreferences (nếu đã lưu trước đó)
     */
    fun initUserFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("userName", null)
        val savedDevice = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown_device"

        if (!savedName.isNullOrEmpty()) {
            setUserInfo(savedName, savedDevice)
        }
    }
}
