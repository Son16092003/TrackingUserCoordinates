package com.plcoding.backgroundlocationtracking

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TrackingScreen(locationViewModel: LocationViewModel) {

    // Lấy state trực tiếp từ ViewModel
    val isTracking = locationViewModel.isTracking
    val latitude = locationViewModel.latitude
    val longitude = locationViewModel.longitude

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Trạng thái tracking
            Text(
                text = if (isTracking) "Đang tracking" else "Chưa tracking ⏸",
                style = MaterialTheme.typography.h5
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Tọa độ realtime
            if (isTracking) {
                Text(
                    text = "Vĩ độ (Latitude): %.5f".format(latitude),
                    style = MaterialTheme.typography.body1
                )
                Text(
                    text = "Kinh độ (Longitude): %.5f".format(longitude),
                    style = MaterialTheme.typography.body1
                )
            } else {
                Text(
                    text = "Chưa có dữ liệu vị trí",
                    style = MaterialTheme.typography.body1
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Nút hướng dẫn (nếu muốn thêm Start/Stop, ở đây chỉ hiển thị thông tin)
            Text(
                text = "App sẽ tự động tracking khi được cấp quyền.",
                style = MaterialTheme.typography.body2
            )
        }
    }
}
