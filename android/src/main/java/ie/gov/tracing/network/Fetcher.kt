package ie.gov.tracing.network


import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.Keep
import com.google.common.io.BaseEncoding
import com.google.gson.Gson
import ie.gov.tracing.Tracing
import ie.gov.tracing.common.Events
import ie.gov.tracing.common.ExposureConfig
import ie.gov.tracing.storage.ExpoSecureStoreInterop
import ie.gov.tracing.storage.ExposureEntity
import ie.gov.tracing.storage.SharedPrefs
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.apache.commons.io.FileUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Keep
data class Token(val token: String)

@Keep
data class Callback(val mobile: String, val closeContactDate: Long, val daysSinceExposure: Int, val payload: Map<String, Any>)

@Keep
data class Metric(val os: String, val event: String, val version: String, val payload: Map<String, Any>?)

@Keep
data class CallbackRecovery(val mobile: String, val code: String, val iso: String, val number: String)

private const val FILE_PATTERN = "/diag_keys/diagnosis_key_file_%s.zip"
private const val REFRESH = "/refresh"

object Fetcher {

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

    private fun getURL(endpoint: String, context: Context): URL {

        val serverUrl = SharedPrefs.getString("serverUrl", context)

        Events.raiseEvent(Events.INFO, "getURL serverUrl $serverUrl")
        Events.raiseEvent(Events.INFO, "getURL endpoint  $endpoint")

        return URL("${serverUrl}${endpoint}")
    }

    private fun getBearerAuthenticator(context: Context): BearerAuthenticator {

        return BearerAuthenticator(context)
    }

    private fun getAuthorizationInterceptor(context: Context): AuthorizationInterceptor {

        return AuthorizationInterceptor(context)
    }

    // based on https://github.com/MaxToyberman/react-native-ssl-pinning/blob/master/android/src/main/java/com/toyberman/Utils/OkHttpUtils.java#L160
    private fun getTrustManager(certs: Array<String>): X509TrustManager {
        var trustManager: X509TrustManager?

        val cf: CertificateFactory = CertificateFactory.getInstance("X.509")
        val keyStoreType: String = KeyStore.getDefaultType()
        val keyStore: KeyStore = KeyStore.getInstance(keyStoreType)
        keyStore.load(null, null)
        for (i in certs.indices) {
            val filename = certs[i]
            val caInput: InputStream = BufferedInputStream(Fetcher::class.java.getClassLoader()?.getResourceAsStream("assets/$filename.cer"))
            var ca: Certificate
            ca = caInput.use { cf.generateCertificate(it) }
            keyStore.setCertificateEntry(filename, ca)
        }
        val tmfAlgorithm: String = TrustManagerFactory.getDefaultAlgorithm()
        val tmf: TrustManagerFactory = TrustManagerFactory.getInstance(tmfAlgorithm)
        tmf.init(keyStore)
        val trustManagers: Array<TrustManager> = tmf.getTrustManagers()
        check(!(trustManagers.size != 1 || trustManagers[0] !is X509TrustManager)) { "Unexpected default trust managers:" + Arrays.toString(trustManagers) }
        trustManager = trustManagers[0] as X509TrustManager
        return trustManager

    }

    @JvmStatic
    fun refreshAuthToken(context: Context): String? {
        try {

            val url = getURL(REFRESH, context)

            var pin = true
            var authenticate = true

            val client = getOkClient(pin, authenticate, context)
            val request = Request.Builder()
                    .url(url)
                    .post("".toRequestBody())
                    .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val data = response.body!!.string()

                if (data.isEmpty()) {
                    return null
                }

                val tokenClass = Gson().fromJson(data, Token::class.java)
                if (tokenClass != null) {
                    SharedPrefs.setString("authToken", tokenClass.token, context)
                }
                return tokenClass.token
            }

        } catch (ex: Exception) {
            Events.raiseError("refresh token error", ex)
        }
        return null
    }

    fun getOkClient(pin: Boolean = true, authenticate: Boolean = true, context: Context): OkHttpClient {

        val authorizationInterceptor = getAuthorizationInterceptor(context)
        val bearerAuthenticator = getBearerAuthenticator(context)
        val builder = OkHttpClient.Builder()
        var usePinning = pin;

        val disableSSLPinning = SharedPrefs.getBoolean("disableSSLPinning", context);

        if (disableSSLPinning) {
            usePinning = false;
        }

        val enableOKHTTPLogging = SharedPrefs.getBoolean("enableOKHTTPLogging", context);

        if (enableOKHTTPLogging) {

            builder.addNetworkInterceptor { chain ->
                chain.proceed(
                        chain.request()
                                .newBuilder()
                                .header("User-Agent", "CovidGreenAndroid")
                                .build()
                )
            }

            val logging = HttpLoggingInterceptor()
            logging.apply { logging.level = HttpLoggingInterceptor.Level.BODY }
            builder.addInterceptor(logging)
        }

        if (authenticate) {
            builder.authenticator(bearerAuthenticator).addInterceptor(authorizationInterceptor)
        }

        if (usePinning) {
            var certList = SharedPrefs.getString("certList", context)
            val sslContext = SSLContext.getInstance("TLS")
            if (certList.isNullOrEmpty()) {
                certList = "cert1,cert2,cert3,cert4,cert5"
            }
            val certs = certList.split(",").toTypedArray()
            val trustManager = getTrustManager(certs);
            sslContext.init(null, arrayOf<TrustManager?>(trustManager), null)
            builder.sslSocketFactory(sslContext.getSocketFactory(), trustManager)
        }
        builder.readTimeout(60, TimeUnit.SECONDS)
        builder.connectTimeout(60, TimeUnit.SECONDS)
        val okHttpClient: OkHttpClient = builder.build()
        return okHttpClient
    }

    @JvmStatic
    fun downloadFile(filename: String, context: Context): File? {
        try {

            var pin = true
            var authenticate = true

            var keyServerUrl = SharedPrefs.getString("keyServerUrl", context)
            val serverUrl = SharedPrefs.getString("serverUrl", context)
            if (keyServerUrl.isEmpty()) {
                keyServerUrl = serverUrl
            }
            var keyServerType = SharedPrefs.getString("keyServerType", context)
            if (keyServerType.isEmpty()) {
                keyServerType = "nearform"
            }
            var fileUrl = "${keyServerUrl}/data/$filename"
            if (keyServerType == "google") {
                fileUrl = "${keyServerUrl}/$filename"
                authenticate = false
                pin = false
            }

            val url = URL(fileUrl)

            Events.raiseEvent(Events.INFO, "downloadFile - $url")

            val client = Fetcher.getOkClient(pin, authenticate, context)
            val request = Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/zip")
                    .get()
                    .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Events.raiseEvent(Events.INFO, "downloadFile - success: ${response.code}")
                    val keyFile = File(context.filesDir, String.format(FILE_PATTERN, uniq()))
                    if (response.body == null) {
                        return null
                    }
                    FileUtils.copyInputStreamToFile(response.body?.byteStream(), keyFile)
                    Events.raiseEvent(Events.INFO, "downloadFile save - success: $url")
                    return keyFile

                } else {
                    Events.raiseEvent(Events.ERROR, "fetch - HTTP error: ${response.code}")
                    return null
                }

            }

        } catch (ex: Exception) {
            Events.raiseError("download file error", ex)
        }
        return null

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

    @JvmStatic
    fun getToken(originalRequest: Request, context: Context): String {
        var token: String
        if (originalRequest.url.toString().endsWith(REFRESH)) {

            token = getRefreshToken(context)
//                 Events.raiseEvent(Events.INFO, "getToken - Is Refresh: $token")
        } else {
            // Events.raiseEvent(Events.INFO, "getToken - Not Refresh: $token")
            token = getAuthToken(context)

        }
        return token
    }

    @JvmStatic
    fun post(endpoint: String, body: String, context: Context): Boolean {

        try {
            var pin = true
            var authenticate = true
            val url = getURL(endpoint, context)
            val client = getOkClient(pin, authenticate, context)
            val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody())
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json; charset=UTF-8")
                    .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Events.raiseEvent(Events.INFO, "post - HTTP success: ${response.code}")
                    return true
                } else {
                    Events.raiseEvent(Events.ERROR, "post - HTTP error: ${response.code}")
                    return false
                }

            }

        } catch (ex: Exception) {
            Events.raiseError("post error", ex)
        }
        return false
    }

    @JvmStatic
    public fun fetch(endpoint: String, context: Context): String? {

        val url = getURL(endpoint, context)

        Events.raiseEvent(Events.INFO, "fetch - fetching from: $url")

        val pin = true
        val authenticate = true
        return Fetcher.fetchInternal(url, pin, authenticate, context)

    }

    @JvmStatic
    fun fetchKeyFile(endpoint: String, context: Context): String? {

        var pin = true
        var authenticate = true

        var serverUrl = SharedPrefs.getString("serverUrl", context)
        val keyServerUrl = SharedPrefs.getString("keyServerUrl", context)
        if (keyServerUrl.isNotEmpty()) {
            serverUrl = keyServerUrl
        }
        var keyServerType = SharedPrefs.getString("keyServerType", context)
        if (keyServerType.isEmpty()) {
            keyServerType = "nearform"
        }

        if (keyServerType == "google") {
            authenticate = false
            pin = false
        }

        val url = URL("${serverUrl}$endpoint")
        return Fetcher.fetchInternal(url, pin, authenticate, context)
    }

    @JvmStatic
    public fun fetchInternal(url: URL, pin: Boolean = true, authenticate: Boolean = true, context: Context): String? {
        try {

            Events.raiseEvent(Events.INFO, "fetch - fetching from: $url")

            val client = Fetcher.getOkClient(pin, authenticate, context)
            val request = Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Events.raiseEvent(Events.INFO, "fetch - success: ${response.code}")

                    return response.body?.string()

                } else {
                    Events.raiseEvent(Events.ERROR, "fetch - HTTP error: ${response.code}")
                    return null
                }

            }

        } catch (ex: Exception) {
            Events.raiseError("fetch error", ex)
        }
        return null
    }


    @JvmStatic
    fun triggerCallback(exposureEntity: ExposureEntity, context: Context, payload: Map<String, Any>) {
        try {
            var callbackNum = SharedPrefs.getString("callbackNumber", context)

            if (callbackNum.isEmpty()) {

                try {
                    val store = ExpoSecureStoreInterop(context)
                    val jsonStr = store.getItemImpl("cti.callBack")
                    val callBackData = Gson().fromJson(jsonStr, CallbackRecovery::class.java)

                    if (callBackData == null || callBackData.code.isEmpty() || callBackData.number.isEmpty()) {
                        Events.raiseEvent(Events.INFO, "triggerCallback - no callback recovery")
                        return;
                    }
                    callbackNum = callBackData.code + callBackData.number


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

            saveMetric("CALLBACK_REQUEST", context)
        } catch (ex: Exception) {
            Events.raiseError("triggerCallback - error", ex)
        }
    }

    @SuppressLint("CheckResult")
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


class BearerAuthenticator(
        private val context: Context,

        ) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val originalRequest = response.request;
        if (originalRequest.url.toString().endsWith(REFRESH)) {
            // it's a 401 on a refresh token
            return null
        }

        if (response.priorResponse != null) {
            return null // avoid looping
        } else {

            Fetcher.refreshAuthToken(context)

            var newToken = Fetcher.getToken(originalRequest, context)
            return originalRequest.newBuilder()
                    .header("Authorization", "Bearer ${newToken}")
                    .build()
        }
    }
}

class AuthorizationInterceptor(
        private val context: Context,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {

        val originalRequest = chain.request()
        // Events.raiseEvent(Events.INFO, "intercept - called ${originalRequest.url.toString()}")

        var token = Fetcher.getToken(originalRequest, context)

        val requestWithAuth = originalRequest.newBuilder()
                .header("Authorization", "Bearer ${token}")
                .build()
        return chain.proceed(requestWithAuth)
    }

}