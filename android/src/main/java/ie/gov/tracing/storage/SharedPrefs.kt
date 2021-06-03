package ie.gov.tracing.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.KeyPairGeneratorSpec
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import ie.gov.tracing.common.Events
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.*
import javax.security.auth.x500.X500Principal

class SharedPrefs {
    companion object {
        private var INSTANCE: SharedPreferences? = null

        private fun createMasterKey(context: Context): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            } else {
                val alias = "your_alias"
                val start: Calendar = GregorianCalendar()
                val end: Calendar = GregorianCalendar()
                end.add(Calendar.YEAR, 30)

                val spec =
                        KeyPairGeneratorSpec.Builder(context)
                                .setAlias(alias)
                                .setSubject(X500Principal("CN=$alias"))
                                .setSerialNumber(
                                        BigInteger.valueOf(
                                                Math.abs(alias.hashCode()).toLong()
                                        )
                                )
                                .setStartDate(start.time).setEndDate(end.time)
                                .build()

                val kpGenerator: KeyPairGenerator = KeyPairGenerator.getInstance(
                        "RSA",
                        "AndroidKeyStore"
                )
                kpGenerator.initialize(spec)
                val kp: KeyPair = kpGenerator.generateKeyPair()
                kp.public.toString()
            }
        }

        private fun getEncryptedSharedPrefs(context: Context): SharedPreferences? {
            try {
                if (INSTANCE != null) return INSTANCE!!

                INSTANCE = EncryptedSharedPreferences.create(
                        "secret_shared_prefs",
                        createMasterKey(context.applicationContext),
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
