package ie.gov.tracing

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender.SendIntentException
import android.content.pm.PackageInfo
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.location.LocationManagerCompat
import androidx.work.await
import com.facebook.react.bridge.*
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationStatusCodes
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey
import com.google.common.io.BaseEncoding
import ie.gov.tracing.common.Config
import ie.gov.tracing.common.Events
import ie.gov.tracing.nearby.ExposureNotificationRepeater
import ie.gov.tracing.nearby.ExposureNotificationClientWrapper
import ie.gov.tracing.nearby.ExposureNotificationHelper
import ie.gov.tracing.nearby.ProvideDiagnosisKeysWorker
import ie.gov.tracing.nearby.StateUpdatedWorker
import ie.gov.tracing.nearby.ExposureNotificationHelper.Callback
import ie.gov.tracing.nearby.RequestCodes
import ie.gov.tracing.storage.ExposureNotificationDatabase
import ie.gov.tracing.storage.ExposureNotificationRepository
import ie.gov.tracing.storage.SharedPrefs
import ie.gov.tracing.storage.SharedPrefs.Companion.getLong
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.*
import kotlin.concurrent.schedule

object Tracing {
    class Listener: ActivityEventListener {
        override fun onNewIntent(intent: Intent?) {}

        override fun onActivityResult(activty: Activity?, requestCode: Int, resultCode: Int, data: Intent?) {
            try {
                Events.raiseEvent(Events.INFO, "onActivityResult - requestCode: $requestCode, resultCode: $resultCode")
                if (requestCode == RequestCodes.REQUEST_CODE_START_EXPOSURE_NOTIFICATION) {
                    if (resultCode == Activity.RESULT_OK) {
                        Events.raiseEvent(Events.INFO, "onActivityResult - START_EXPOSURE_NOTIFICATION_OK")
                        // call authorize again to resolve the promise if isEnabled set
                        if(Tracing.status == Tracing.STATUS_STARTING) {
                            // call start after authorization if starting,
                            // fulfils promise after successful start
                            Tracing.start(null)
                        } else {
                            // in order for isAuthorised to work, we must start
                            // start failure will resolve authorisation permissions promise
                            Tracing.authoriseExposure(null)
                        }
                    } else {
                        Events.raiseEvent(Events.INFO, "onActivityResult - START_EXPOSURE_NOTIFICATION_FAILED: $resultCode")

                        if(Tracing.status == Tracing.STATUS_STARTING) {
                            Tracing.resolutionPromise?.resolve(false)
                        } else {
                            Tracing.resolutionPromise?.resolve("denied")
                        }
                    }
                    return
                }

                if (requestCode == RequestCodes.REQUEST_CODE_GET_TEMP_EXPOSURE_KEY_HISTORY) {
                    if (resultCode == Activity.RESULT_OK) {
                        Events.raiseEvent(Events.INFO, "onActivityResult - GET_TEMP_EXPOSURE_KEY_HISTORY_OK")
                        Tracing.getDiagnosisKeys(null)
                    } else {
                        Tracing.resolutionPromise?.reject(Throwable("Rejected"))
                        Events.raiseEvent(Events.ERROR, "onActivityResult - GET_TEMP_EXPOSURE_KEY_HISTORY_FAILED: $resultCode")
                    }
                    return
                }

                if (requestCode == RequestCodes.PLAY_SERVICES_UPDATE) {
                    if (resultCode == Activity.RESULT_OK) {
                        val gps = GoogleApiAvailability.getInstance()
                        val result = gps.isGooglePlayServicesAvailable(Tracing.context.applicationContext)
                        Tracing.base.playServicesVersion = gps.getApkVersion(Tracing.context.applicationContext)
                        Events.raiseEvent(Events.INFO, "triggerUpdate - version after activity: ${Tracing.base.playServicesVersion}")

                        if(result == ConnectionResult.SUCCESS) {
                            Events.raiseEvent(Events.INFO, "triggerUpdate - update successful")
                            Tracing.resolutionPromise?.resolve("success")
                        } else {
                            Events.raiseEvent(Events.ERROR, "triggerUpdate - update failed: $result")
                            Tracing.resolutionPromise?.resolve("failed")
                        }
                    } else {
                        Events.raiseEvent(Events.INFO, "triggerUpdate - update cancelled")
                        Tracing.resolutionPromise?.resolve("cancelled")
                    }
                }

            } catch (ex: Exception) {
                Events.raiseError("onActivityResult", ex)
                Tracing.resolutionPromise?.resolve(false)
            }
        }
    }

    class BleStatusReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            var newExposureDisabledReason = Tracing.exposureDisabledReason
            var newStatus = Tracing.exposureStatus
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent.action) {
                if (isBluetoothAvailable()) {
                    if (newExposureDisabledReason == "bluetooth") {
                        newExposureDisabledReason = ""
                        newStatus = Tracing.EXPOSURE_STATUS_ACTIVE
                    }
                } else {
                    newExposureDisabledReason = "bluetooth"
                    newStatus = Tracing.EXPOSURE_STATUS_DISABLED
                }
            }
            Events.raiseEvent(Events.INFO, "bleStatusUpdate - $intent.action, $doesSupportENS")
            if (doesSupportENS) {
                Tracing.setExposureStatus(newStatus, newExposureDisabledReason, true)
            }            
        }
    }

    private fun isBluetoothAvailable(): Boolean {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled) {
            return false
        }

        if (isLocationEnableRequired()) {
            return false
        }
        return true
    }

    lateinit var context: Context
        lateinit var currentContext: Context
        lateinit var base: ExposureNotificationModule
        lateinit var reactContext: ReactApplicationContext

        const val STATUS_STARTED = "STARTED"
        const val STATUS_STARTING = "STARTING"
        const val STATUS_STOPPED = "STOPPED"
        const val STATUS_PAUSED = "PAUSED"

        private const val EXPOSURE_STATUS_UNKNOWN = "unknown"
        private const val EXPOSURE_STATUS_ACTIVE = "active"
        private const val EXPOSURE_STATUS_DISABLED = "disabled"
        //const val EXPOSURE_STATUS_RESTRICTED = "restricted"
        private const val EXPOSURE_STATUS_UNAVAILABLE = "unavailable"

        private const val STATUS_STOPPING = "STOPPING"

        var status = STATUS_STOPPED
        var exposureStatus = EXPOSURE_STATUS_UNAVAILABLE
        var exposureDisabledReason = "starting"
        var doesSupportENS = false
        var hasCheckedENS = false

        private lateinit var exposureWrapper: ExposureNotificationClientWrapper

        var resolutionPromise: Promise? = null
        var startPromise: Promise? = null

        @JvmStatic
        fun setExposureStatus(status: String, reason: String = "", scheduleCheck: Boolean = false) {
            var changed = false
            if (exposureStatus != status) {
                exposureStatus = status
                changed = true
            }
            if (exposureDisabledReason != reason) {
                exposureDisabledReason = reason
                changed = true
            }
            if (changed) {
                if (scheduleCheck) {
                    Timer("DelayedENSCheck", false).schedule(300) {
                        Events.raiseEvent(Events.ON_STATUS_CHANGED, getExposureStatus(null))
                    }
                } else {
                    Events.raiseEvent(Events.ON_STATUS_CHANGED, getExposureStatus(null))
                }
            }                
        }

        @JvmStatic
        fun updateExposureServiceStatus(serviceStatus: Boolean) {
            Events.raiseEvent(Events.INFO, "ENS Service Status enabled: " + serviceStatus + ", app status: " + exposureStatus)
            if (!serviceStatus && exposureStatus == EXPOSURE_STATUS_ACTIVE) {
                stop()
            } else if ((exposureStatus == EXPOSURE_STATUS_DISABLED) && serviceStatus) {
                start(null)

            }
            Events.raiseEvent(Events.INFO, "ENS Service Status updated status " + exposureStatus)
        }

        private val authorisationCallback: Callback = object : Callback {
            override fun onFailure(t: Throwable) {
                try {
                    // set current status, pre/post resolution
                    if (t is ApiException) {
                        handleApiException(t)
                    } else {
                        Events.raiseError("authorisation error:", Exception(t))
                    }

                    // if authorizationPromise is set, we're trying this post resolution
                    if (resolutionPromise != null) {
                        if(status == STATUS_STARTING)
                            resolutionPromise!!.resolve(false)
                        else {
                            if (t is ApiException) {
                                if (t.statusCode == ExposureNotificationStatusCodes.FAILED_REJECTED_OPT_IN ||
                                        t.statusCode == ExposureNotificationStatusCodes.FAILED_UNAUTHORIZED)
                                    resolutionPromise!!.resolve("denied")
                                else if (t.statusCode == ExposureNotificationStatusCodes.FAILED_NOT_SUPPORTED)
                                    resolutionPromise!!.resolve("unavailable")
                                else
                                    resolutionPromise!!.resolve("blocked")
                            } else {
                                resolutionPromise!!.resolve("denied")
                            }
                        }
                        return
                    }

                    resolutionPromise = startPromise
                    if (t is ApiException && t.statusCode ==
                            ExposureNotificationStatusCodes.RESOLUTION_REQUIRED) {
                        Events.raiseEvent(Events.INFO, "authorisationCallback - resolution required")
                        try {
                            // opens dialog box to allow exposure tracing
                            Events.raiseEvent(Events.INFO, "authorisationCallback - start resolution")
                            t.status.startResolutionForResult(base.activity,
                                    RequestCodes.REQUEST_CODE_START_EXPOSURE_NOTIFICATION)
                        } catch (ex: SendIntentException) {
                            Events.raiseError("authorisationCallback - error starting resolution, " +
                                    "open settings to resolve", ex)
                            if(status == STATUS_STARTING)
                                resolutionPromise!!.resolve(false)
                            else {
                                resolutionPromise!!.resolve("denied")
                            }
                        }
                    } else {
                        Events.raiseEvent(Events.INFO, "authorisationCallback - " +
                                "no resolution required, open settings to authorise")
                        if(status == STATUS_STARTING)
                            resolutionPromise!!.resolve(false)
                        else {
                            resolutionPromise!!.resolve("denied")
                        }
                    }
                } catch (ex: Exception) {
                    Events.raiseError("authorisationCallback.onFailure", ex)
                    if(status == STATUS_STARTING)
                        resolutionPromise!!.resolve(false)
                    else {
                        resolutionPromise!!.resolve("denied")
                    }
                }
            }

            override fun onSuccess(message: String) {
                try {
                    Events.raiseEvent(Events.INFO, "authorisationCallback - success")
                    if (status == STATUS_STARTING) {
                        setNewStatus(STATUS_STARTED)
                        startPromise?.resolve(true)
                    } else {
                        startPromise!!.resolve("granted")
                    }
                } catch (ex: Exception) {
                    Events.raiseError("authorisationCallback.onSuccess", ex)
                    if (status == STATUS_STARTING) {
                        startPromise?.resolve(false)
                    } else {
                        startPromise!!.resolve("denied")
                    }
                }

            }
        }

        @JvmStatic
        fun init(appContext: ReactApplicationContext, baseJavaModule: ExposureNotificationModule) {
            try {
                // don't use these contexts for background stuff like workers/services
                base = baseJavaModule
                reactContext = appContext
                context = reactContext.applicationContext
                exposureWrapper = ExposureNotificationClientWrapper.get(context)
                reactContext.addActivityEventListener(Listener())
                currentContext = context // this is overridden depending on path into codebase

                scheduleCheckExposure()

                val br: BroadcastReceiver = BleStatusReceiver()
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                    addAction(Intent.EXTRA_INTENT)
                }
                context.registerReceiver(br, filter)                
            } catch (ex: Exception) {
                Events.raiseError("init", ex)
            }
        }

        @JvmStatic
        fun isENSSupported(): Boolean {
            if (hasCheckedENS) {
                return doesSupportENS
            } else {
                Events.raiseEvent(Events.INFO, "isENSSupported: triggering check")
                isSupported(null)
                return doesSupportENS;
            }
        }

        private fun setNewStatus(newStatus: String) {
            status = newStatus
            if (status === STATUS_STARTED) {
                setExposureStatus(EXPOSURE_STATUS_ACTIVE)
            } else if (status === STATUS_STOPPED) {
                setExposureStatus(EXPOSURE_STATUS_DISABLED, "exposure")
            }
            Events.raiseEvent(Events.STATUS, status)
        }

        @JvmStatic
        fun start(promise: Promise?) {
            try {
                setNewStatus(STATUS_STARTING)
                SharedPrefs.setBoolean("servicePaused", false, context)
                if (promise != null) {
                    resolutionPromise = null
                    startPromise = promise
                }
                ExposureNotificationHelper(authorisationCallback).startExposure()
            } catch (ex: Exception) {
                Events.raiseError("start", ex)
                promise?.resolve(false)
            }
        }

        @JvmStatic
        fun pause(promise: Promise?) {
            try {
                setNewStatus(STATUS_STOPPING)
                SharedPrefs.setBoolean("servicePaused", true, context)
                val permissionHelperCallback: Callback = object : Callback {
                    override fun onFailure(t: Throwable) {
                        Events.raiseError("paused", Exception(t))
                        setNewStatus(STATUS_PAUSED)
                        promise?.resolve(true)
                    }

                    override fun onSuccess(status: String) {
                        Events.raiseEvent(Events.INFO, "exposure tracing $status")
                        ProvideDiagnosisKeysWorker.stopScheduler()
                        setNewStatus(STATUS_PAUSED)
                        promise?.resolve(true)
                    }
                }
                ExposureNotificationHelper(permissionHelperCallback).stopExposure()
            } catch (ex: Exception) {
                Events.raiseError("paused", ex)
                promise?.resolve(false)
            }
        }

        @JvmStatic
        fun stop() {
            try {
                setNewStatus(STATUS_STOPPING)
                SharedPrefs.setBoolean("servicePaused", false, context)
                val permissionHelperCallback: Callback = object : Callback {
                    override fun onFailure(t: Throwable) {
                        Events.raiseError("stop", Exception(t))
                        setNewStatus(STATUS_STOPPED)
                    }

                    override fun onSuccess(status: String) {
                        Events.raiseEvent(Events.INFO, "exposure tracing $status")
                        ProvideDiagnosisKeysWorker.stopScheduler()
                        setNewStatus(STATUS_STOPPED)
                    }
                }
                ExposureNotificationHelper(permissionHelperCallback).stopExposure()
            } catch (ex: Exception) {
                Events.raiseError("stop", ex)
            }
        }

        @JvmStatic
        fun exposureEnabled(promise: Promise) {
            try {
                exposureWrapper.isEnabled
                        .addOnSuccessListener { enabled: Boolean? ->
                            if (enabled != null) {
                                promise.resolve(enabled)
                            } else {
                                Events.raiseEvent(Events.INFO, "exposureEnabled: null")
                                promise.resolve(false)
                            }
                        }
                        .addOnFailureListener { ex ->
                            Events.raiseError("exposureEnabled - onFailure", ex)
                            handleApiException(ex)
                            promise.resolve(false)
                        }
            } catch (ex: Exception) {
                Events.raiseError("exposureEnabled - exception", ex)
                promise.resolve(false)
            }
        }

        @JvmStatic
        fun configure(params: ReadableMap) {
            try {
                val oldCheckFrequency = getLong("exposureCheckFrequency", context, 180)
                Config.configure(params)
                val newCheckFrequency = getLong("exposureCheckFrequency", context, 180)
                Events.raiseEvent(Events.INFO, "old: $oldCheckFrequency, new: $newCheckFrequency")
                if(newCheckFrequency != oldCheckFrequency) {
                    scheduleCheckExposure()
                }
            } catch (ex: Exception) {
                Events.raiseError("configure", ex)
            }
        }

        @JvmStatic
        fun checkExposure(skipTimeCheck: Boolean = false) {
            ProvideDiagnosisKeysWorker.startOneTimeWorkRequest(skipTimeCheck)
        }

        @RequiresApi(Build.VERSION_CODES.O)
        @JvmStatic
        fun simulateExposure(timeDelay: Long = 0, numDays: Int = 3) {
            StateUpdatedWorker.simulateExposure(timeDelay, numDays)
        }

        @JvmStatic
        fun version(runningContext: Context?): WritableMap {
            var versionName: String
            var versionCode: String
            var currentContext: Context
            if (runningContext != null) {
                currentContext = runningContext
            } else {
                currentContext = context
            }

            try {
                val pinfo: PackageInfo = currentContext.getPackageManager().getPackageInfo(currentContext.getPackageName(), 0)
                versionName = pinfo.versionName
                versionCode = PackageInfoCompat.getLongVersionCode(pinfo).toString()           
            } catch (e: Exception) {
                versionName = "unknown"
                versionCode = "unknown"
            }
            val data = Arguments.createMap()
            data.putString("version", versionName)
            data.putString("build", versionCode)
            data.putString("display", "$versionName.$versionCode")
            
            return data
        }

        private fun getExposureKeyAsMap(tek: TemporaryExposureKey): WritableMap {
            val result: WritableMap = Arguments.createMap()
            result.putString("keyData", BaseEncoding.base64().encode(tek.keyData))
            result.putInt("rollingPeriod", tek.rollingPeriod)
            result.putInt("rollingStartNumber", tek.rollingStartIntervalNumber)
            result.putInt("transmissionRiskLevel", tek.transmissionRiskLevel) // app should overwrite

            return result
        }

        // get the diagnosis keys for this user for the past 14 days (config)
        @JvmStatic
        fun getDiagnosisKeys(promise: Promise?) {
            try {
                if (promise != null) { // called from client
                    resolutionPromise = promise
                }
                exposureWrapper.temporaryExposureKeyHistory
                        .addOnSuccessListener {
                            if (it != null) {
                                // convert the keys into a structure we can convert to json
                                val result: WritableArray = Arguments.createArray()

                                for (temporaryExposureKey in it) {
                                    result.pushMap(getExposureKeyAsMap(temporaryExposureKey))
                                }

                                Events.raiseEvent(Events.INFO, "getDiagnosisKeys - exposure key retrieval success, #keys: ${it.size}")
                                resolutionPromise?.resolve(result)
                            } else {
                                Events.raiseEvent(Events.INFO, "getDiagnosisKeys - exposure key retrieval success - no keys")
                                resolutionPromise?.resolve(Arguments.createArray())
                            }
                        }
                        .addOnFailureListener { ex ->
                            if (ex is ApiException && ex.statusCode == ExposureNotificationStatusCodes.RESOLUTION_REQUIRED) {
                                Events.raiseEvent(Events.INFO, "getDiagnosisKeys - exposure api exception: " +
                                        ExposureNotificationStatusCodes.getStatusCodeString(ex.statusCode))
                                if (promise != null) { // ask permission, if failed and no promise set as param
                                    Events.raiseEvent(Events.INFO, "getDiagnosisKeys - ask user for permission")
                                    try {
                                        ex.status.startResolutionForResult(base.activity,
                                                RequestCodes.REQUEST_CODE_GET_TEMP_EXPOSURE_KEY_HISTORY)

                                        // we will need to resolve promise and attempt to get the keys again
                                        // promise will be resolved if successful in success listener
                                    } catch (ex: Exception) {
                                        resolutionPromise?.resolve(Arguments.createArray())
                                        Events.raiseError("getDiagnosisKeys - exposure api exception", ex)
                                    }
                                } else {
                                    resolutionPromise?.resolve(Arguments.createArray())
                                    Events.raiseError("getDiagnosisKeys - failed post-resolution, not trying again", ex)
                                }
                            } else {
                                resolutionPromise?.resolve(Arguments.createArray())
                                Events.raiseError("getDiagnosisKeys - general exception", ex)
                            }
                        }
            } catch (ex: Exception) {
                Events.raiseError("getDiagnosisKeys", ex)
                promise?.resolve(Arguments.createArray())
            }
        }

        @JvmStatic
        fun isAuthorised(promise: Promise) {
            Events.raiseEvent(Events.INFO, "Checking isAuthorised");
            try {
                exposureWrapper.isEnabled
                        .addOnSuccessListener { enabled: Boolean? ->

                            if (enabled == true) {
                                Events.raiseEvent(Events.INFO, "isAuthorised: granted")
                                promise.resolve("granted")
                            } else {
                                Events.raiseEvent(Events.INFO, "isAuthorised: denied")
                                promise.resolve("blocked")
                            }
                        }
                        .addOnFailureListener { ex ->
                            Events.raiseError("isAuthorised - onFailure", ex)
                            handleApiException(ex)
                            promise.resolve("blocked")
                        }
            } catch (ex: Exception) {
                Events.raiseError("isAuthorised - exception", ex)
                promise.resolve("blocked")
            }
        }

        @JvmStatic
        fun authoriseExposure(promise: Promise?) {
            // the only way to authorise is to call start, we just resolve promise differently
            try {
                if (promise != null) {
                    resolutionPromise = null
                    startPromise = promise
                }
                ExposureNotificationHelper(authorisationCallback).startExposure()
            } catch (ex: Exception) {
                Events.raiseError("authorizeExposure", ex)
                promise?.resolve("unavailable")
            }
        }

        @JvmStatic
        fun canSupport(promise: Promise) {
            val apiResult = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            Events.raiseEvent(Events.INFO, "GMS - isAvailable: $apiResult")
            promise.resolve(apiResult == ConnectionResult.SUCCESS)
        }

        @JvmStatic
        fun isSupported(promise: Promise?) = runBlocking<Unit> {
            Events.raiseEvent(Events.INFO, "isSupported - Checking if ENS supported")
            launch {

                try {
                    val apiResult = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                    Events.raiseEvent(Events.INFO, "isSupported - isGooglePlayServicesAvailable: $apiResult")
                    if (apiResult == ConnectionResult.SUCCESS) {
                        val version = ExposureNotificationHelper.getDeviceENSVersion().await()
                        Events.raiseEvent(Events.INFO, "isSupported - getDeviceENSVersion: $version")
                        doesSupportENS = true
                        hasCheckedENS = true
                        promise?.resolve(true)
                    } else if (apiResult == ConnectionResult.SERVICE_INVALID || apiResult == ConnectionResult.SERVICE_DISABLED || apiResult == ConnectionResult.SERVICE_MISSING) {
                        promise?.resolve(false)
                        doesSupportENS = false
                        hasCheckedENS = true
                        base.setApiError(apiResult)
                    }
                } catch (ex: Exception) {
                    Events.raiseError("isSupported - Exception", ex)
                    promise?.resolve(false)
                    doesSupportENS = false
                    hasCheckedENS = true
                    base.setApiError(1)
                }
            }
        }

        @JvmStatic
        fun triggerUpdate(promise: Promise) = runBlocking<Unit> {
            launch {
                try {
                    Events.raiseEvent(Events.INFO, "triggerUpdate - trigger update")
                    val gps = GoogleApiAvailability.getInstance()
                    base.playServicesVersion = gps.getApkVersion(context.applicationContext)
                    Events.raiseEvent(Events.INFO, "triggerUpdate - version: ${base.playServicesVersion}")
                    val result = gps.isGooglePlayServicesAvailable(context.applicationContext)
                    Events.raiseEvent(Events.INFO, "triggerUpdate - result: $result")
                    if (result == ConnectionResult.SUCCESS) {
                        promise.resolve("already_installed")
                    }
                    if (result == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED) { // requires update
                        resolutionPromise = promise
                        gps.getErrorDialog(base.activity,
                                ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
                                RequestCodes.PLAY_SERVICES_UPDATE)?.show()
                    } else {
                        promise.resolve("unknown result: $result")
                    }
                } catch (ex: Exception) {
                    Events.raiseError("triggerUpdate - exception", ex)
                    promise.resolve("error")
                }
            }
        }

        @JvmStatic
        fun deleteData(promise: Promise) = runBlocking<Unit> {
            launch {
                try {
                    SharedPrefs.clear(context)
                    // just in case nuke fails
                    ExposureNotificationRepository(context).deleteAllExposureEntitiesAsync().await()
                    ExposureNotificationRepository(context).deleteAllTokensAsync().await()
                    // drop db
                    ExposureNotificationDatabase.nukeDatabase(context)
                    // cleanup any pending notification
                    cancelNotifications()
                    promise.resolve(true)
                } catch (ex: Exception) {
                    Events.raiseError("deleteData", ex)
                    promise.resolve(false)
                }
            }
        }

        @JvmStatic
        fun deleteExposureData(promise: Promise) = runBlocking<Unit> {
            launch {
                try {
                    ExposureNotificationRepository(context).deleteAllExposureEntitiesAsync().await()
                    promise.resolve(true)
                } catch (ex: Exception) {
                    Events.raiseError("deleteExposureData", ex)
                    promise.resolve(false)
                }
            }
        }

        private fun scheduleCheckExposure() {
            try {
                // stop and re-start with config value
                ProvideDiagnosisKeysWorker.startScheduler()
            } catch (ex: Exception) {
                Events.raiseError("scheduleCheckExposure", ex)
            }
        }

        @JvmStatic
        fun getCloseContacts(promise: Promise) = runBlocking<Unit> {
            launch {
                try {
                    val exposures = ExposureNotificationRepository(context).allExposureEntitiesAsync.await()

                    val result: WritableArray = Arguments.createArray()

                    for (exposure in exposures) {
                        // asynchronously update our summary table while we receive notifications
                        val ads: List<String> = exposure.attenuationDurations().split(",")
                        val attenuationDurations = Arguments.createArray()
                        if (ads.isNotEmpty()) {
                            for (i in ads.indices) {
                                attenuationDurations.pushInt(ads[i].toInt())
                            }
                        }

                        val exp: WritableMap = Arguments.createMap()
                        exp.putDouble("exposureAlertDate", exposure.createdTimestampMs.toDouble())
                        exp.putDouble("exposureDate", exposure.exposureContactDate.toDouble())
                        exp.putArray("attenuationDurations", attenuationDurations)
                        exp.putInt("daysSinceLastExposure", exposure.daysSinceLastExposure())
                        exp.putInt("matchedKeyCount", exposure.matchedKeyCount())
                        exp.putInt("maxRiskScore", exposure.maximumRiskScore())
                        exp.putInt("maxRiskScoreFullRange", exposure.maximumRiskScore())
                        exp.putInt("riskScoreSumFullRange", exposure.summationRiskScore())

                        if (exposure.windowData.size > 0) {
                            val windows: WritableArray = Arguments.createArray()
                            exposure.windowData.forEach {
                                val win: WritableMap = Arguments.createMap()
                                win.putDouble("date", it.date.toDouble())
                                win.putInt("calibrationConfidence", it.calibrationConfidence)
                                win.putInt("diagnosisReportType", it.diagnosisReportType)
                                win.putInt("infectiousness", it.infectiousness)
                                val buckets: WritableArray = Arguments.createArray()
                                it.scanData.buckets.forEach {
                                    buckets.pushInt(it)
                                }
                                win.putArray("buckets", buckets)
                                val weightedBuckets: WritableArray = Arguments.createArray()
                                it.scanData.weightedBuckets.forEach {
                                    weightedBuckets.pushInt(it)
                                }
                                win.putArray("weightedBuckets", weightedBuckets)
                                win.putInt("numScans", it.scanData.numScans)
                                win.putBoolean("exceedsThreshold", it.scanData.exceedsThresholds)
                                windows.pushMap(win)
                            }
                            exp.putArray("windows", windows)
                        }

                        result.pushMap(exp)
                    }

                    promise.resolve(result)
                } catch (ex: Exception) {
                    Events.raiseError("getCloseContacts", ex)
                    promise.resolve(Arguments.createArray())
                }
            }
        }

       /**
         * When it comes to Location and BLE, there are the following conditions:
         * - Location on is only necessary to use bluetooth for Android M+.
         * - Starting with Android S, there may be support for locationless BLE scanning
         * => We only go into an error state if these conditions require us to have location on, but
         * it is not activated on device.
         */
        private fun isLocationEnableRequired(): Boolean {
            val locationManager: LocationManager = Tracing.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return (!exposureWrapper.deviceSupportsLocationlessScanning()
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !LocationManagerCompat.isLocationEnabled(locationManager))
        }

        @JvmStatic
        fun getExposureStatus(promise: Promise? = null): ReadableMap = runBlocking {
            val result: WritableMap = Arguments.createMap()
            val typeData: WritableArray = Arguments.createArray()
            var enabled = false
            
            try {
                enabled = ExposureNotificationHelper.isEnabled().await()
            } catch(ex: Exception) {
                Events.raiseError("Error reading ENS status", ex)
            }
            val isPaused = SharedPrefs.getBoolean("servicePaused", context)
            if (doesSupportENS && isPaused && !enabled) {
                exposureStatus = EXPOSURE_STATUS_DISABLED
                exposureDisabledReason = "paused"
            } else if (doesSupportENS && enabled) {
                exposureStatus = EXPOSURE_STATUS_ACTIVE
                exposureDisabledReason = ""
            }
            try {
                if (doesSupportENS && !isBluetoothAvailable()) {
                    exposureStatus = EXPOSURE_STATUS_DISABLED
                    exposureDisabledReason = "bluetooth"
                } else if (doesSupportENS && exposureDisabledReason == "bluetooth") {
                    exposureStatus = EXPOSURE_STATUS_ACTIVE
                    exposureDisabledReason = ""
                }

                result.putString("state", exposureStatus)
                typeData.pushString(exposureDisabledReason)
                result.putArray("type", typeData)
                promise?.resolve(result)
            } catch (ex: Exception) {
                Events.raiseError("getExposureStatus", ex)
                result.putString("state", EXPOSURE_STATUS_UNKNOWN)
                typeData.pushString("error")
                result.putArray("type", typeData)
                promise?.resolve(result)
            }
            result
        }

        @JvmStatic
        fun getLogData(promise: Promise) {
            val map = Arguments.createMap()

            map.putInt("installedPlayServicesVersion", base.playServicesVersion)
            map.putBoolean("nearbyApiSupported", !base.nearbyNotSupported())

            map.putDouble("lastIndex", getLong("since", context).toDouble())
            map.putString("lastRun", SharedPrefs.getString("lastRun", context))
            map.putString("lastError", SharedPrefs.getString("lastError", context))
            map.putString("lastApiError", SharedPrefs.getString("lastApiError", context))

            promise.resolve(map)
        }

        @JvmStatic
        fun getConfigData(promise: Promise) {
            val map = Arguments.createMap()

            map.putString("token", SharedPrefs.getString("authToken", context))
            map.putString("refreshToken", SharedPrefs.getString("refreshToken", context))
            map.putString("keyServerType", SharedPrefs.getString("keyServerType", context))
            map.putString("keyServerUrl", SharedPrefs.getString("keyServerUrl", context))
            map.putString("serverUrl", SharedPrefs.getString("serverUrl", context))
            map.putBoolean("analyticsOptin", SharedPrefs.getBoolean("analyticsOptin", context))
            map.putInt("lastExposureIndex", SharedPrefs.getLong("since", context).toInt())
            map.putString("lastUpdated", SharedPrefs.getString("lastUpdated", context))
            
            promise.resolve(map)
        }

        @JvmStatic
        fun cancelNotifications() {
            val notificationManager = NotificationManagerCompat
                .from(context)
            notificationManager.cancel(RequestCodes.CLOSE_CONTACT)

            ExposureNotificationRepeater.cancel( context )
        }

        fun handleApiException(ex: Exception) {
            if (ex is ApiException) {
                Events.raiseEvent(Events.ERROR, "handle api exception: ${ExposureNotificationStatusCodes.getStatusCodeString(ex.statusCode)}")
                
                when (ex.statusCode) {
                    ExposureNotificationStatusCodes.RESOLUTION_REQUIRED -> setExposureStatus(EXPOSURE_STATUS_DISABLED, "resolution")
                    ExposureNotificationStatusCodes.SIGN_IN_REQUIRED -> setExposureStatus(EXPOSURE_STATUS_DISABLED, "signin_required")
                    ExposureNotificationStatusCodes.FAILED_BLUETOOTH_DISABLED -> setExposureStatus(EXPOSURE_STATUS_DISABLED, "bluetooth")
                    ExposureNotificationStatusCodes.FAILED_NOT_SUPPORTED -> setExposureStatus(EXPOSURE_STATUS_DISABLED, "not_supported")
                    ExposureNotificationStatusCodes.FAILED_REJECTED_OPT_IN -> setExposureStatus(EXPOSURE_STATUS_DISABLED, "rejected")
                    ExposureNotificationStatusCodes.FAILED_SERVICE_DISABLED -> setExposureStatus(EXPOSURE_STATUS_DISABLED, "disabled")
                    ExposureNotificationStatusCodes.FAILED_UNAUTHORIZED -> setExposureStatus(EXPOSURE_STATUS_DISABLED, "unauthorised")
                    ExposureNotificationStatusCodes.FAILED_DISK_IO -> setExposureStatus(EXPOSURE_STATUS_DISABLED, "disk")
                    ExposureNotificationStatusCodes.FAILED_TEMPORARILY_DISABLED -> setExposureStatus(EXPOSURE_STATUS_DISABLED, "temporarily_disabled")
                    ExposureNotificationStatusCodes.FAILED -> setExposureStatus(EXPOSURE_STATUS_DISABLED, "failed")
                    ExposureNotificationStatusCodes.API_NOT_CONNECTED -> {
                        setExposureStatus(EXPOSURE_STATUS_UNAVAILABLE, "not_connected")
                        base.setApiError(ex.statusCode)
                    }
                }
            }
        }
    }
