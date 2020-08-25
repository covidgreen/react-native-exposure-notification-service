package ie.gov.tracing.common

import android.content.pm.ApplicationInfo
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationStatusCodes
import ie.gov.tracing.Tracing
import ie.gov.tracing.storage.SharedPrefs
import java.io.PrintWriter
import java.io.StringWriter
import java.util.HashMap
import ie.gov.tracing.network.Fetcher
import android.content.Context

// central logging and events
class Events {
    companion object {
        const val INFO = "info"
        const val ERROR = "error"
        const val STATUS = "status" // start status
        const val ON_STATUS_CHANGED = "onStatusChanged" // tracing api status
        const val ON_EXPOSURE = "exposure"

        private const val TAG = "RNExposureNotificationService"

        private fun raiseEvent(map: ReadableMap) {
            Tracing.reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)?.emit("exposureEvent", map)
        }

        private fun allowed(eventName: String): Boolean {
            var isDebuggable = false
            try {
                isDebuggable = 0 != Tracing.currentContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
            } catch (ex: Exception) {}

            if (isDebuggable) return true // allow all events in debug
            if(eventName == ON_STATUS_CHANGED || eventName == ON_EXPOSURE) return true // allow these debug or release

            return false
        }

        @JvmStatic
        fun raiseEvent(eventName: String, eventValue: String?): Boolean {
            Log.d(TAG, eventValue)
            if(!allowed(eventName)) return false
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
            if(!allowed(eventName)) return false
            val map = Arguments.createMap()
            try {
                map.putMap(eventName, eventValue)
                raiseEvent(map)
                return true
            } catch (ex: Exception) {
                Log.d(TAG, "$map")
            }
            return false
        }

        @JvmStatic
        fun raiseError(message: String, ex: Exception) {
            raiseError(message, ex, null)
        }

        @JvmStatic
        fun raiseError(message: String, ex: Exception, context: Context?) {
            try {
                if (context != null) {
                    var payload: HashMap<String, Any> = HashMap<String, Any>();
                    payload.put("description", "$message: $ex");
                    Fetcher.saveMetric("LOG_ERROR", context, payload);
                }

                if(allowed(ERROR)) { // if debugging allow stacktrace
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

                    Log.e(TAG, "error: $ex")
                }
            } catch (ex: Exception) {
                Log.e(TAG, ex.toString())
            }
        }
    }
}