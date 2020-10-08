package ie.gov.tracing.common

import com.facebook.react.bridge.ReadableMap
import ie.gov.tracing.Tracing
import ie.gov.tracing.storage.SharedPrefs

class Config {
    companion object {
        fun configure(params: ReadableMap) {
            try {
                Events.raiseEvent(Events.INFO, "Saving configuration...")
                SharedPrefs.setLong("exposureCheckFrequency", params.getInt("exposureCheckFrequency").toLong(), Tracing.context)
                SharedPrefs.setLong("storeExposuresFor", params.getInt("storeExposuresFor").toLong(), Tracing.context)
                SharedPrefs.setLong("fileLimit", params.getInt("fileLimit").toLong(), Tracing.context)
                SharedPrefs.setBoolean("analyticsOptin", params.getBoolean("analyticsOptin"), Tracing.context)
                SharedPrefs.setString("serverUrl", params.getString("serverURL")!!, Tracing.context)
                var keyServer = params.getString("keyServerUrl")!!
                if (keyServer.isEmpty()) {
                    keyServer = params.getString("serverURL")!!
                }
                var keyServerType = params.getString("keyServerType")!!
                if (keyServerType.isEmpty()) {
                    keyServerType = "nearform"
                }
                SharedPrefs.setString("keyServerUrl", keyServer, Tracing.context)
                SharedPrefs.setString("keyServerType", keyServerType, Tracing.context)
                SharedPrefs.setString("notificationTitle", params.getString("notificationTitle")!!, Tracing.context)
                SharedPrefs.setString("notificationDesc", params.getString("notificationDesc")!!, Tracing.context)
                // this is sensitive user data, our shared prefs class is uses EncryptedSharedPreferences and MasterKeys
                SharedPrefs.setString("refreshToken", params.getString("refreshToken")!!, Tracing.context)
                SharedPrefs.setString("authToken", params.getString("authToken")!!, Tracing.context)
                SharedPrefs.setString("callbackNumber", params.getString("callbackNumber")!!, Tracing.context)
            } catch(ex: Exception) {
                Events.raiseError("Error setting configuration: ", ex)
            }
        }
    }
}