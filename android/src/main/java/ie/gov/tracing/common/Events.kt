package ie.gov.tracing.common

import android.content.pm.ApplicationInfo
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationStatusCodes
import ie.gov.tracing.Tracing
import ie.gov.tracing.storage.SharedPrefs
import java.io.PrintWriter
import java.io.StringWriter

// central logging and events
object Events {
        const val INFO = "info"
        const val ERROR = "error"
        const val STATUS = "status" // start status
        const val ON_STATUS_CHANGED = "onStatusChanged" // tracing api status
        const val ON_EXPOSURE = "exposure"

        private const val TAG = "RN_ENService"

        private fun raiseEvent(map: ReadableMap) {
            Tracing.reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)?.emit("exposureEvent", map)
        }

        private fun allowed(eventName: String): Boolean {
            var isDebuggable = false
            try {
                isDebuggable = 0 != Tracing.currentContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
            } catch (ex: Exception) {
            }

            if (eventName == "ERROR" && !isDebuggable) return false // allow all events in debug
            // if(eventName == ON_STATUS_CHANGED || eventName == ON_EXPOSURE) return true // allow these debug or release

            return true
        }

    @JvmStatic
    fun raiseEvent(eventName: String, eventValue: String?): Boolean {
        Log.d(TAG, "$eventName: $eventValue")
        if (!allowed(eventName)) return false
        val map = Arguments.createMap()
        try {
            map.putString(eventName, eventValue)
            raiseEvent(map)
            return true
        } catch (ex: Exception) {
            Log.d(TAG, "$map")
        }
        return false
    }

    @JvmStatic
    fun raiseEvent(eventName: String, eventValue: ReadableMap?): Boolean {
        Log.d(TAG, eventName)
        if (!allowed(eventName)) return false
        if (eventValue == null) return false
        val map = Arguments.createMap()
        val eventMap = WritableNativeMap()
        eventMap.merge(eventValue)
        try {
            map.putMap(eventName, eventMap)
            raiseEvent(map)
            return true
        } catch (ex: Exception) {
            Log.d(TAG, "$map")
        }
        return false
    }

    @JvmStatic
    fun raiseError(message: String, ex: Exception) {
        Log.e(TAG, "Error: $message: $ex")
        try {
            if (allowed(ERROR)) { // if debugging allow stacktrace
                var sw = StringWriter()
                val pw = PrintWriter(sw)
                ex.printStackTrace(pw)
                Log.e(TAG, "$message: $sw")

                if (ex is ApiException) {
                    SharedPrefs.setString("lastApiError", ExposureNotificationStatusCodes.getStatusCodeString(ex.statusCode), Tracing.currentContext)
                }
                SharedPrefs.setString("lastError", "$ex - $sw", Tracing.currentContext)
                raiseEvent(ERROR, "$message: $ex - $sw")
            } else { // otherwise just log generic message
                raiseEvent(ERROR, "$message: $ex")
            }
        } catch (ex: Exception) {
            Log.e(TAG, ex.toString())
        }
    }

}