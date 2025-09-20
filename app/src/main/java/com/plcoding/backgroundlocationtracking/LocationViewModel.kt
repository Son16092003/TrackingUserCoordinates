package com.plcoding.backgroundlocationtracking

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class LocationViewModel : ViewModel() {

    // Biến backing private để tránh trùng setter JVM
    private var _isTracking by mutableStateOf(false)
    val isTracking: Boolean get() = _isTracking

    var latitude by mutableStateOf(0.0)
        private set
    var longitude by mutableStateOf(0.0)
        private set

    // Cập nhật tọa độ
    fun updateLocation(lat: Double, lon: Double) {
        latitude = lat
        longitude = lon
    }

    // Cập nhật trạng thái tracking
    fun setTracking(tracking: Boolean) {
        _isTracking = tracking
    }
}
