package ie.gov.tracing.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import ie.gov.tracing.common.Events

class SharedPrefs {
    companion object {
        private var INSTANCE: SharedPreferences? = null

        private fun getEncryptedSharedPrefs(context: Context): SharedPreferences? {
            try {
                if (INSTANCE != null) return INSTANCE!!

                val masterKeyAlias: String = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

                INSTANCE = EncryptedSharedPreferences.create(
                        "secret_shared_prefs",
                        masterKeyAlias,
                        context.applicationContext,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                return INSTANCE!!
            } catch(ex: Exception) {
                Events.raiseError("getEncryptedSharedPrefs", ex)
            }
            return null
        }

        @JvmStatic
        @JvmOverloads
        fun getLong(key: String, context: Context, default: Long = 0): Long {
            try {
                val preferences = getEncryptedSharedPrefs(context)
                return preferences!!.getLong(key, default)
            } catch(ex: Exception) {
                Events.raiseError("SharedPrefs.getLong", ex)
            }
            return 0
        }

        @JvmStatic
        fun setLong(key: String, num: Long, context: Context) {
            try {
                val preferences = getEncryptedSharedPrefs(context)
                val editor = preferences!!.edit()
                editor.putLong(key, num)
                editor.commit() // do not use apply
            } catch(ex: Exception) {
                Events.raiseError("SharedPrefs.setLong", ex)
            }
        }

        @JvmStatic
        fun getBoolean(key: String, context: Context): Boolean {
            try {
                val preferences = getEncryptedSharedPrefs(context)
                return preferences!!.getBoolean(key, false)
            } catch(ex: Exception) {
                Events.raiseError("SharedPrefs.getBoolean", ex)
            }
            return false
        }

        @JvmStatic
        fun setBoolean(key: String, bln: Boolean, context: Context) {
            try {
                val preferences = getEncryptedSharedPrefs(context)
                val editor = preferences!!.edit()
                editor.putBoolean(key, bln)
                editor.commit() // do not use apply
            } catch(ex: Exception) {
                Events.raiseError("SharedPrefs.setBoolean", ex)
            }
        }

        @JvmStatic
        fun getString(key: String, context: Context): String {
            try {
                val preferences = getEncryptedSharedPrefs(context)
                return preferences!!.getString(key, "") ?: return ""
            } catch(ex: Exception) {
                Events.raiseError("SharedPrefs.getString", ex)
            }
            return ""
        }

        @JvmStatic
        fun setString(key: String, str: String, context: Context) {
            try {
                val preferences = getEncryptedSharedPrefs(context)
                val editor = preferences!!.edit()
                editor.putString(key, str)
                editor.commit() // do not use apply
            } catch(ex: Exception) {
                Events.raiseError("SharedPrefs.setString", ex)
            }
        }

        @JvmStatic
        fun remove(key: String, context: Context) {
            try {
                val preferences = getEncryptedSharedPrefs(context)
                val editor = preferences!!.edit()
                editor.remove(key)
                editor.commit() // do not use apply
            } catch(ex: Exception) {
                Events.raiseError("SharedPrefs.remove", ex)
            }
        }

        fun clear(context: Context) {
            try {
                val preferences = getEncryptedSharedPrefs(context)
                val editor = preferences!!.edit()
                editor.clear()
                editor.commit() // do not use apply
            } catch(ex: Exception) {
                Events.raiseError("SharedPrefs.clear", ex)
            }
        }
    }
}