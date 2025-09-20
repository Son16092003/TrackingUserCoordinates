package com.plcoding.backgroundlocationtracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            context?.let {
                val serviceIntent = Intent(it, LocationService::class.java).apply {
                    action = LocationService.ACTION_START
                }
                it.startService(serviceIntent)
            }
        }
    }
}
