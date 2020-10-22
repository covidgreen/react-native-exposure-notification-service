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
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers

@Keep
data class Token(val token: String)

@Keep
data class Callback(val mobile: String, val closeContactDate: Long, val  daysSinceExposure: Int, val payload: Map<String, Any>)

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
                var keyServerUrl = SharedPrefs.getString("keyServerUrl", context)
                val serverUrl = SharedPrefs.getString("serverUrl", context)
                if (keyServerUrl.isEmpty()) {
                    keyServerUrl = serverUrl
                }
                var keyServerType = SharedPrefs.getString("keyServerType", context)
                if (keyServerType.isEmpty()) {
                    keyServerType = "nearform"
                }
                val authToken = getAuthToken(context)
                var fileUrl = "${keyServerUrl}/data/$filename"
                if (keyServerType == "google") {
                    fileUrl = "${keyServerUrl}/$filename"
                } 
                Events.raiseEvent(Events.INFO, "downloadFile - $fileUrl")
                val url = URL(fileUrl)
                val urlConnection = url.openConnection() as HttpURLConnection
                if (keyServerType == "nearform") {
                    urlConnection.setRequestProperty("Authorization", "Bearer $authToken")
                }
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

        private fun getRefreshToken(context: Context): String {
            var token = SharedPrefs.getString("refreshToken", context)
            if (token.isEmpty()) {
                try {
                    val store = ExpoSecureStoreInterop(context)
                    token = store.getItemImpl("refreshToken")
                } catch (exExpo: Exception) {
                    Events.raiseError("ExpoSecureStoreInterop  refreshToken", exExpo)
                }
            }
            return token
        }

        private fun getAuthToken(context: Context): String {
            var token = SharedPrefs.getString("authToken", context)
            if (token.isEmpty()) {
                try {
                    val store = ExpoSecureStoreInterop(context)
                    token = store.getItemImpl("token")
                } catch (exExpo: Exception) {
                    Events.raiseError("ExpoSecureStoreInterop  refreshToken", exExpo)
                }
            }
            return token
        }


        private fun getNewAuthToken(context: Context): String? {
            try {
                val serverUrl = SharedPrefs.getString("serverUrl", context)
                val refreshToken = getRefreshToken(context)

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
                val authToken = getAuthToken(context)
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
        fun fetch(endpoint: String, retry: Boolean = false, keyFile: Boolean = false, context: Context): String? {
            try {
                var serverUrl = SharedPrefs.getString("serverUrl", context)
                val keyServerUrl = SharedPrefs.getString("keyServerUrl", context)
                if (keyFile && keyServerUrl.isNotEmpty()) {
                    serverUrl = keyServerUrl
                }
                var keyServerType = SharedPrefs.getString("keyServerType", context)
                if (keyServerType.isEmpty()) {
                    keyServerType = "nearform"
                }
                val authToken = getAuthToken(context)

                Events.raiseEvent(Events.INFO, "fetch - fetching from: ${serverUrl}$endpoint")
                val url = URL("${serverUrl}$endpoint")
                val urlConnection = url.openConnection() as HttpURLConnection
                if ((keyServerType == "nearform" && keyFile) || !keyFile) {
                    urlConnection.setRequestProperty("Authorization", "Bearer $authToken")
                }                
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
                        fetch(endpoint, true, keyFile, context)
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
                var callbackNum = SharedPrefs.getString("callbackNumber", context)

                if (callbackNum.isEmpty()) {

                    try {
                        val store = ExpoSecureStoreInterop(context)
                        val jsonStr = store.getItemImpl("cti.callBack")
                        if (jsonStr.isEmpty()) {
                            Events.raiseEvent(Events.INFO, "triggerCallback - no callback recovery")
                            return;
                        }
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

                val calendar = Calendar.getInstance()
                calendar.add(Calendar.DAY_OF_YEAR, 0 - exposureEntity.daysSinceLastExposure())
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val daysSinceExposure = calendar.time.time

                Events.raiseEvent(Events.INFO, "triggerCallback - sending: ${daysSinceExposure} ${Date(daysSinceExposure)}")
                val callbackParams = Callback(callbackNum, daysSinceExposure, exposureEntity.daysSinceLastExposure(), payload)
                val success = post("/callback", Gson().toJson(callbackParams), context)

                if (!success) {
                    Events.raiseEvent(Events.ERROR, "triggerCallback - failed")
                    return
                }
                Events.raiseEvent(Events.INFO, "triggerCallback - success")

            } catch(ex: Exception) {
                Events.raiseError("triggerCallback - error", ex)
            }
        }

        @JvmStatic
        fun saveMetric(event: String, context: Context, payload: Map<String, Any>? = null) {
            try {
                val analytics = SharedPrefs.getBoolean("analyticsOptin", context)
                val version = Tracing.version(context).getString("display").toString()

                if (!analytics) {
                    Events.raiseEvent(Events.INFO, "saveMetric - not saving, no opt in")
                    return
                }

                val metric = Metric("android", event, version, payload)

                Single.fromCallable {
                    return@fromCallable Fetcher.post("/metrics", Gson().toJson(metric), context)
                }
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({ success ->
                            Events.raiseEvent(if (success) Events.INFO else Events.ERROR, "saveMetric - ${if (success) "success" else "failed"}")
                        }, {
                            Events.raiseError("saveMetric - error - background", java.lang.Exception(it))
                        })

            } catch (ex: Exception) {
                Events.raiseError("saveMetric - error", ex)
            }
        }
    }
}