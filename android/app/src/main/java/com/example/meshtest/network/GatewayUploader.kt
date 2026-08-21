package com.example.meshtest.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.meshtest.model.SosPacket
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object GatewayUploader {
    private const val TAG = "GatewayUploader"

    /**
     * Checks if the device has an active, working Internet connection (Wi-Fi or Cellular).
     */
    fun hasInternetConnection(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking internet connectivity", e)
            false
        }
    }

    /**
     * Uploads the SOS packet to the Cloud Gateway Backend endpoint: POST /api/sos
     */
    fun uploadSos(
        serverBaseUrl: String,
        packet: SosPacket,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        thread {
            try {
                val cleanUrl = if (serverBaseUrl.endsWith("/")) serverBaseUrl.dropLast(1) else serverBaseUrl
                val fullUrl = "$cleanUrl/api/sos"
                Log.d(TAG, "Attempting upload to: $fullUrl")

                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.doInput = true

                val jsonPayload = packet.toJsonString()
                val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
                writer.write(jsonPayload)
                writer.flush()
                writer.close()

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "Upload SUCCESS ($responseCode): $responseText")
                    onSuccess("Uploaded to Cloud ($responseCode)")
                } else {
                    val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                    Log.e(TAG, "Upload FAILED ($responseCode): $errText")
                    onError("Server error $responseCode: $errText")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Network exception during upload", e)
                onError("Upload failed: ${e.localizedMessage ?: "Unknown network error"}")
            }
        }
    }
}