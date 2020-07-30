package ie.gov.tracing.network

import android.content.Context
import androidx.annotation.Keep
import com.google.common.io.BaseEncoding
import com.google.gson.Gson
import ie.gov.tracing.Tracing
import ie.gov.tracing.common.Events
import ie.gov.tracing.storage.ExpoSecureStoreInterop
import ie.gov.tracing.storage.ExposureEntity
import ie.gov.tracing.storage.SharedPrefs
import org.apache.commons.io.FileUtils
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.*

@Keep
data class Token(val token: String)

@Keep
data class Callback(val mobile: String, val closeContactDate: Long, val payload: Map<String, Any>)

@Keep
data class Metric(val os: String, val event: String, val version: String, val payload: Map<String, Any>?)

@Keep
data class CallbackRecovery(val mobile:String, val code: String, val iso: String,val number: String)


class Fetcher {

    companion object {
        private const val FILE_PATTERN = "/diag_keys/diagnosis_key_file_%s.zip"

        fun token(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return BaseEncoding.base64().encode(bytes)
        }

        private fun uniq(): String? {
            val bytes = ByteArray(4)
            SecureRandom().nextBytes(bytes)
            return BaseEncoding.base32().lowerCase().omitPadding().encode(bytes)
        }

        fun downloadFile(filename: String, context: Context): File? {
            try {
                val serverUrl = SharedPrefs.getString("serverUrl", context)
                val authToken = SharedPrefs.getString("authToken", context)
                val fileUrl = "${serverUrl}/data/$filename"
                Events.raiseEvent(Events.INFO, "downloadFile - $fileUrl")
                val url = URL(fileUrl)
                val urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.setRequestProperty("Authorization", "Bearer $authToken")
                urlConnection.setRequestProperty("Accept", "application/zip")

                val keyFile = File(context.filesDir, String.format(FILE_PATTERN, uniq()))

                if (urlConnection.responseCode != HttpURLConnection.HTTP_OK) {
                    Events.raiseEvent(Events.ERROR, "downloadFile - failed: $fileUrl, response code: ${urlConnection.responseCode}")
                    urlConnection.disconnect()
                    return null
                }

                FileUtils.copyInputStreamToFile(urlConnection.inputStream, keyFile)
                urlConnection.disconnect()
                Events.raiseEvent(Events.INFO, "downloadFile - success: $fileUrl")
                return keyFile

            } catch (ex: Exception) {
                Events.raiseError("downloadFile", ex)
                return null
            }
        }

        private fun getNewAuthToken(context: Context): String? {
            try {
                val serverUrl = SharedPrefs.getString("serverUrl", context)
                val refreshToken = SharedPrefs.getString("refreshToken", context)

                val url = URL("${serverUrl}/refresh")
                val urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.doOutput = true
                urlConnection.setRequestProperty("Authorization", "Bearer $refreshToken")
                urlConnection.requestMethod = "POST"

                if (urlConnection.responseCode != HttpURLConnection.HTTP_OK) {
                    urlConnection.disconnect()
                    return null
                }
                val data = urlConnection.inputStream.bufferedReader().use { it.readText() }
                urlConnection.disconnect()
                val token = Gson().fromJson(data, Token::class.java)
                return token.token
            } catch(ex: Exception) {
                Events.raiseError("refresh token error", ex)
            }
            return null
        }

        private fun post(endpoint: String, body: String, context: Context): Boolean {
            try {
                val serverUrl = SharedPrefs.getString("serverUrl", context)
                val authToken = SharedPrefs.getString("authToken", context)
                Events.raiseEvent(Events.INFO, "post - sending data to: " +
                        "${serverUrl}$endpoint, body: $body")

                val url = URL("${serverUrl}$endpoint")
                val urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.doOutput = true
                urlConnection.setRequestProperty("Authorization", "Bearer $authToken")
                urlConnection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                urlConnection.setRequestProperty("Accept", "application/json")
                urlConnection.requestMethod = "POST"

                urlConnection.outputStream.write(body.toByteArray(Charsets.UTF_8))
                urlConnection.outputStream.flush()
                urlConnection.outputStream.close()

                if (urlConnection.responseCode != HttpURLConnection.HTTP_OK &&
                        urlConnection.responseCode != HttpURLConnection.HTTP_NO_CONTENT) {
                    Events.raiseEvent(Events.ERROR, "post - HTTP error: ${urlConnection.responseCode}")
                    urlConnection.disconnect()
                    return false
                }

                Events.raiseEvent(Events.INFO, "post - success: ${urlConnection.responseCode}")
                urlConnection.disconnect()
                return true
            } catch(ex: Exception) {
                Events.raiseError("post error", ex)
            }
            return false
        }

        @JvmStatic
        fun fetch(endpoint: String, retry: Boolean = false, context: Context): String? {
            try {
                val serverUrl = SharedPrefs.getString("serverUrl", context)
                val authToken = SharedPrefs.getString("authToken", context)

                Events.raiseEvent(Events.INFO, "fetch - fetching from: ${serverUrl}$endpoint")
                val url = URL("${serverUrl}$endpoint")
                val urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.setRequestProperty("Authorization", "Bearer $authToken")

                Events.raiseEvent(Events.INFO, "fetch - response: ${urlConnection.responseCode}")

                if (urlConnection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    // disconnect immediately
                    urlConnection.disconnect()

                    // if this is a retry, do not refresh token again
                    if (retry) {
                        Events.raiseEvent(Events.ERROR, "fetch - Unauthorized")
                        return null
                    }

                    Events.raiseEvent(Events.ERROR, "fetch - Unauthorized, refreshing token...")
                    val newAuthToken = getNewAuthToken(context)

                    return if (newAuthToken != null) {
                        SharedPrefs.setString("authToken", newAuthToken, context)
                        // recursively call again with retry set
                        fetch(endpoint, true, context)
                    } else {
                        Events.raiseEvent(Events.ERROR, "fetch - Unauthorized")
                        null
                    }
                }

                if (urlConnection.responseCode != HttpURLConnection.HTTP_OK) {
                    Events.raiseEvent(Events.ERROR, "fetch - HTTP error: ${urlConnection.responseCode}")
                    urlConnection.disconnect()
                    return null
                }

                val data = urlConnection.inputStream.bufferedReader().use { it.readText() }
                urlConnection.disconnect()
                Events.raiseEvent(Events.INFO, "fetch - success")
                return data
            } catch (ex: Exception) {
                Events.raiseError("fetch error", ex)
                return null
            }
        }

        @JvmStatic
        fun triggerCallback(exposureEntity: ExposureEntity, context: Context, payload: Map<String, Any>) {
            try {
                val notificationSent = SharedPrefs.getLong("notificationSent", context)
                var callbackNum = SharedPrefs.getString("callbackNumber", context)


                if (notificationSent > 0) {
                    Events.raiseEvent(Events.INFO, "triggerCallback - notification " +
                            "already sent: " + notificationSent)
                    return
                }

                if (callbackNum.isEmpty()) {

                    try {
                        val store = ExpoSecureStoreInterop(context)
                        val jsonStr = store.getItemImpl("cti.callBack")
                        val callBackData = Gson().fromJson(jsonStr, CallbackRecovery::class.java)

                        if(callBackData.code == null || callBackData.number == null){
                            Events.raiseEvent(Events.INFO, "triggerCallback - no callback recovery")
                            return;
                        }
                        callbackNum = callBackData.code +  callBackData.number


                    } catch (exExpo: Exception) {
                        Events.raiseError("ExpoSecureStoreInterop", exExpo)
                    }

                    if (callbackNum.isEmpty()) {

                        Events.raiseEvent(Events.INFO, "triggerCallback - no callback number " +
                                "set, not sending callback")
                        return
                    }
                }

                val dayInMs = 1000 * 60 * 60 * 24
                val notificationDate = exposureEntity.createdTimestampMs
                val daysSinceExposure = notificationDate - (exposureEntity.daysSinceLastExposure() * dayInMs)

                Events.raiseEvent(Events.INFO, "triggerCallback - sending: ${Date(daysSinceExposure)}")
                val callbackParams = Callback(callbackNum, daysSinceExposure, payload)
                val success = post("/callback", Gson().toJson(callbackParams), context)

                if (!success) {
                    Events.raiseEvent(Events.ERROR, "triggerCallback - failed")
                    return
                }

                Events.raiseEvent(Events.INFO, "triggerCallback - success")
                SharedPrefs.setLong("notificationSent", System.currentTimeMillis(), context)

                saveMetric("CALLBACK_REQUEST", context)
            } catch(ex: Exception) {
                Events.raiseError("triggerCallback - error", ex)
            }
        }

        @JvmStatic
        fun saveMetric(event: String, context: Context, payload: Map<String, Any>? = null) {
            try {
                val analytics = SharedPrefs.getBoolean("analyticsOptin", context)
                val version = SharedPrefs.getString("version", context)

                if(!analytics) {
                    Events.raiseEvent(Events.INFO, "saveMetric - not saving, no opt in")
                    return
                }

                val metric = Metric("android", event, version, payload)

                val success = post("/metrics", Gson().toJson(metric), context)

                if (!success) {
                    Events.raiseEvent(Events.ERROR, "saveMetric - failed")
                    return
                }

                Events.raiseEvent(Events.INFO, "saveMetric - success")
            } catch(ex: Exception) {
                Events.raiseError("triggerCallback - error", ex)
            }
        }
    }
}