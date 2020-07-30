// Modified from https://github.com/expo/expo/blob/master/packages/expo-secure-store/android/src/main/java/expo/modules/securestore/SecureStoreModule.java
package ie.gov.tracing.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public class ExpoSecureStoreInterop {

    private static final String SHARED_PREFERENCES_NAME = "SecureStore";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";

    private static final String SCHEME_PROPERTY = "scheme";

    private KeyStore mKeyStore;
    private AESEncrypter mAESEncrypter;

    private Context mContext;
    public ExpoSecureStoreInterop(Context context) {

        mContext = context;
        mAESEncrypter = new AESEncrypter();
    }

    public String getItemImpl(String key) {
        // We use a SecureStore-specific shared preferences file, which lets us do things like enumerate
        // its entries or clear all of them
        SharedPreferences prefs = getSharedPreferences();
        if (prefs.contains(key)) {
            return readJSONEncodedItem(key,prefs);
        } else {
           return "";
        }
    }

    private String readJSONEncodedItem(String key, SharedPreferences prefs) {
        String encryptedItemString = prefs.getString(key, null);
        JSONObject encryptedItem;
        try {
            encryptedItem = new JSONObject(encryptedItemString);
        } catch (JSONException e) {
            return "";
        }

        String scheme = encryptedItem.optString(SCHEME_PROPERTY);
        if (scheme == null) {
            return "";
        }

        String value;
        try {
            switch (scheme) {
                case AESEncrypter.NAME:

                    KeyStore keyStore = getKeyStore();
                    String keystoreAlias = mAESEncrypter.getKeyStoreAlias();
                    KeyStore.SecretKeyEntry secretKeyEntry = (KeyStore.SecretKeyEntry) keyStore.getEntry(keystoreAlias, null);

                    value = mAESEncrypter.decryptItem(encryptedItem, secretKeyEntry);
                    break;
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }

        return value;
    }

    /**
     * We use a shared preferences file that's scoped to both the experience and SecureStore. This
     * lets us easily list or remove all the entries for an experience.
     */
    private SharedPreferences getSharedPreferences() {
        return mContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private KeyStore getKeyStore() throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException {
        if (mKeyStore == null) {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);
            mKeyStore = keyStore;
        }
        return mKeyStore;
    }


    /**
     * An encrypter that stores a symmetric key (AES) in the Android keystore. It generates a new IV
     * each time an item is written to prevent many-time pad attacks. The IV is stored with the
     * encrypted item.
     * <p>
     * AES with GCM is supported on Android 10+ but storing an AES key in the keystore is supported
     * on only Android 23+. If you generate your own key instead of using the Android keystore (like
     * the hybrid encrypter does) you can use the encyption and decryption methods of this class.
     */
    protected static class AESEncrypter  {
        public static final String NAME = "aes";

        private static final String DEFAULT_ALIAS = "key_v1";
        private static final String AES_CIPHER = "AES/GCM/NoPadding";
        private static final int AES_KEY_SIZE_BITS = 256;

        private static final String CIPHERTEXT_PROPERTY = "ct";
        private static final String IV_PROPERTY = "iv";
        private static final String GCM_AUTHENTICATION_TAG_LENGTH_PROPERTY = "tlen";

        public String getKeyStoreAlias() {
            String baseAlias =  DEFAULT_ALIAS;
            return AES_CIPHER + ":" + baseAlias;
        }

        public String decryptItem(JSONObject encryptedItem, KeyStore.SecretKeyEntry secretKeyEntry) throws
                GeneralSecurityException, JSONException {

            String ciphertext = encryptedItem.getString(CIPHERTEXT_PROPERTY);
            String ivString = encryptedItem.getString(IV_PROPERTY);
            int authenticationTagLength = encryptedItem.getInt(GCM_AUTHENTICATION_TAG_LENGTH_PROPERTY);
            byte[] ciphertextBytes = Base64.decode(ciphertext, Base64.DEFAULT);
            byte[] ivBytes = Base64.decode(ivString, Base64.DEFAULT);

            GCMParameterSpec gcmSpec = new GCMParameterSpec(authenticationTagLength, ivBytes);
            Cipher cipher = Cipher.getInstance(AES_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, secretKeyEntry.getSecretKey(), gcmSpec);
            byte[] plaintextBytes = cipher.doFinal(ciphertextBytes);

            return new String(plaintextBytes, StandardCharsets.UTF_8);
        }
    }
}
