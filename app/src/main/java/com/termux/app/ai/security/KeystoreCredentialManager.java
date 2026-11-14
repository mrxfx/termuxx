package com.termux.app.ai.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.model.AIProviderType;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Calendar;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.security.auth.x500.X500Principal;

/**
 * Encrypts and stores user-provided AI API keys using the Android Keystore.
 * - API 23+: AES-256-GCM symmetric key protected in AndroidKeyStore.
 * - API 21-22: RSA-2048 KeyPair in AndroidKeyStore.
 * - JVM test fallback: if AndroidKeyStore is absent (e.g., Robolectric unit tests without hardware keystore),
 *   falls back to deterministic obfuscated local encryption so unit tests can exercise lifecycle safely.
 */
public class KeystoreCredentialManager {

    private static final String PREFS_NAME = "termux_ai_encrypted_credentials";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String AES_KEY_ALIAS = "termux_ai_aes_master_key_v1";
    private static final String RSA_KEY_ALIAS = "termux_ai_rsa_master_key_v1";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding";
    private static final int GCM_TAG_BITS = 128;

    private static final String KEY_PREFIX_CIPHERTEXT = "cipher_";
    private static final String KEY_PREFIX_IV = "iv_";
    private static final String KEY_PREFIX_MASK = "mask_";
    private static final String KEY_PREFIX_MODE = "mode_";

    private static final String MODE_AES_GCM = "aes_gcm";
    private static final String MODE_RSA = "rsa";
    private static final String MODE_TEST_XOR = "jvm_test";

    private final Context mContext;
    private final SharedPreferences mPrefs;

    public KeystoreCredentialManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized void saveApiKey(@NonNull AIProviderType provider, @Nullable String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            removeApiKey(provider);
            return;
        }
        String trimmed = apiKey.trim();
        String masked = SensitiveDataRedactor.maskKeyForDisplay(trimmed);
        String name = provider.name();

        if (isAndroidKeyStoreAvailable()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    SecretKey secretKey = getOrCreateAesKey();
                    Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey);
                    byte[] iv = cipher.getIV();
                    byte[] encrypted = cipher.doFinal(trimmed.getBytes(StandardCharsets.UTF_8));
                    mPrefs.edit()
                        .putString(KEY_PREFIX_CIPHERTEXT + name, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                        .putString(KEY_PREFIX_IV + name, Base64.encodeToString(iv, Base64.NO_WRAP))
                        .putString(KEY_PREFIX_MASK + name, masked)
                        .putString(KEY_PREFIX_MODE + name, MODE_AES_GCM)
                        .apply();
                    return;
                } else {
                    PublicKey publicKey = getOrCreateRsaPublicKey();
                    Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
                    cipher.init(Cipher.ENCRYPT_MODE, publicKey);
                    byte[] encrypted = cipher.doFinal(trimmed.getBytes(StandardCharsets.UTF_8));
                    mPrefs.edit()
                        .putString(KEY_PREFIX_CIPHERTEXT + name, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                        .remove(KEY_PREFIX_IV + name)
                        .putString(KEY_PREFIX_MASK + name, masked)
                        .putString(KEY_PREFIX_MODE + name, MODE_RSA)
                        .apply();
                    return;
                }
            } catch (Exception ignored) {
                // Fall through only when running in non-Keystore JVM test environment.
                if (!isRobolectricEnvironment()) {
                    throw new IllegalStateException("Failed to encrypt API key with Android Keystore");
                }
            }
        } else if (!isRobolectricEnvironment()) {
            throw new IllegalStateException("AndroidKeyStore is unavailable on this device");
        }

        byte[] raw = trimmed.getBytes(StandardCharsets.UTF_8);
        byte[] xor = xorBytes(raw, (mContext.getPackageName() + ":" + name).getBytes(StandardCharsets.UTF_8));
        mPrefs.edit()
            .putString(KEY_PREFIX_CIPHERTEXT + name, Base64.encodeToString(xor, Base64.NO_WRAP))
            .remove(KEY_PREFIX_IV + name)
            .putString(KEY_PREFIX_MASK + name, masked)
            .putString(KEY_PREFIX_MODE + name, MODE_TEST_XOR)
            .apply();
    }

    @Nullable
    public synchronized String getApiKey(@NonNull AIProviderType provider) {
        String name = provider.name();
        String cipherText = mPrefs.getString(KEY_PREFIX_CIPHERTEXT + name, null);
        if (cipherText == null || cipherText.isEmpty()) return null;

        String mode = mPrefs.getString(KEY_PREFIX_MODE + name, MODE_AES_GCM);
        try {
            byte[] encrypted = Base64.decode(cipherText, Base64.NO_WRAP);
            if (MODE_AES_GCM.equals(mode) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                String ivBase64 = mPrefs.getString(KEY_PREFIX_IV + name, null);
                if (ivBase64 == null) return null;
                byte[] iv = Base64.decode(ivBase64, Base64.NO_WRAP);
                SecretKey secretKey = getOrCreateAesKey();
                Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
                return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            } else if (MODE_RSA.equals(mode)) {
                PrivateKey privateKey = getRsaPrivateKey();
                if (privateKey == null) return null;
                Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, privateKey);
                return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            } else if (MODE_TEST_XOR.equals(mode) && isRobolectricEnvironment()) {
                byte[] plain = xorBytes(encrypted, (mContext.getPackageName() + ":" + name).getBytes(StandardCharsets.UTF_8));
                return new String(plain, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    public synchronized boolean hasApiKey(@NonNull AIProviderType provider) {
        String name = provider.name();
        String cipherText = mPrefs.getString(KEY_PREFIX_CIPHERTEXT + name, null);
        return cipherText != null && !cipherText.isEmpty();
    }

    @NonNull
    public synchronized String getMaskedApiKey(@NonNull AIProviderType provider) {
        String name = provider.name();
        String masked = mPrefs.getString(KEY_PREFIX_MASK + name, "");
        return masked != null ? masked : "";
    }

    public synchronized void removeApiKey(@NonNull AIProviderType provider) {
        String name = provider.name();
        mPrefs.edit()
            .remove(KEY_PREFIX_CIPHERTEXT + name)
            .remove(KEY_PREFIX_IV + name)
            .remove(KEY_PREFIX_MASK + name)
            .remove(KEY_PREFIX_MODE + name)
            .apply();
    }

    private boolean isAndroidKeyStoreAvailable() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
            ks.load(null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRobolectricEnvironment() {
        return "robolectric".equalsIgnoreCase(Build.FINGERPRINT)
            || "robolectric".equalsIgnoreCase(Build.PRODUCT);
    }

    private SecretKey getOrCreateAesKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        if (ks.containsAlias(AES_KEY_ALIAS)) {
            KeyStore.Entry entry = ks.getEntry(AES_KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
            AES_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build();
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }

    @SuppressWarnings("deprecation")
    private PublicKey getOrCreateRsaPublicKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        if (!ks.containsAlias(RSA_KEY_ALIAS)) {
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 25);
            KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(mContext)
                .setAlias(RSA_KEY_ALIAS)
                .setSubject(new X500Principal("CN=" + RSA_KEY_ALIAS))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.getTime())
                .setEndDate(end.getTime())
                .build();
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", KEYSTORE_PROVIDER);
            kpg.initialize(spec);
            kpg.generateKeyPair();
        }
        return ks.getCertificate(RSA_KEY_ALIAS).getPublicKey();
    }

    private PrivateKey getRsaPrivateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(RSA_KEY_ALIAS, null);
        if (entry instanceof KeyStore.PrivateKeyEntry) {
            return ((KeyStore.PrivateKeyEntry) entry).getPrivateKey();
        }
        return null;
    }

    private static byte[] xorBytes(byte[] data, byte[] key) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return out;
    }
}
