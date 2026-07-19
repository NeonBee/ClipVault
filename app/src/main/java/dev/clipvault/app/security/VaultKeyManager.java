package dev.clipvault.app.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class VaultKeyManager {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String WRAPPING_ALIAS = "clipvault.biometric.wrap.v1";
    private static final String PREFS = "vault_key_envelope";
    private static final String KEY_CIPHERTEXT = "wrapped_database_key";
    private static final String KEY_IV = "wrapping_iv";
    private static final int DATABASE_KEY_BYTES = 32;

    private final SharedPreferences preferences;
    private final SecureRandom secureRandom = new SecureRandom();

    public VaultKeyManager(@NonNull Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isProvisioned() {
        return preferences.contains(KEY_CIPHERTEXT) && preferences.contains(KEY_IV);
    }

    @NonNull
    public Cipher createEnrollmentCipher() throws GeneralSecurityException {
        SecretKey key = getOrCreateWrappingKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher;
    }

    @NonNull
    public byte[] finishEnrollment(@NonNull Cipher authenticatedCipher) throws GeneralSecurityException {
        byte[] databaseKey = new byte[DATABASE_KEY_BYTES];
        secureRandom.nextBytes(databaseKey);
        byte[] wrapped = null;
        try {
            wrapped = authenticatedCipher.doFinal(databaseKey);
            boolean committed = preferences.edit()
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                    .putString(KEY_IV, Base64.encodeToString(authenticatedCipher.getIV(), Base64.NO_WRAP))
                    .commit();
            if (!committed) throw new GeneralSecurityException("Could not persist the database key envelope");
            return databaseKey;
        } catch (GeneralSecurityException | RuntimeException error) {
            Arrays.fill(databaseKey, (byte) 0);
            throw error;
        } finally {
            if (wrapped != null) Arrays.fill(wrapped, (byte) 0);
        }
    }

    @NonNull
    public Cipher createUnlockCipher() throws GeneralSecurityException {
        if (!isProvisioned()) throw new GeneralSecurityException("Vault is not provisioned");
        KeyStore keyStore = loadAndroidKeyStore();
        SecretKey key = (SecretKey) keyStore.getKey(WRAPPING_ALIAS, null);
        if (key == null) throw new GeneralSecurityException("Biometric wrapping key is missing");

        byte[] iv = Base64.decode(preferences.getString(KEY_IV, ""), Base64.NO_WRAP);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return cipher;
        } finally {
            Arrays.fill(iv, (byte) 0);
        }
    }

    @NonNull
    public byte[] finishUnlock(@NonNull Cipher authenticatedCipher) throws GeneralSecurityException {
        byte[] wrapped = Base64.decode(preferences.getString(KEY_CIPHERTEXT, ""), Base64.NO_WRAP);
        try {
            byte[] databaseKey = authenticatedCipher.doFinal(wrapped);
            if (databaseKey.length != DATABASE_KEY_BYTES) {
                Arrays.fill(databaseKey, (byte) 0);
                throw new GeneralSecurityException("Unexpected database key size");
            }
            return databaseKey;
        } finally {
            Arrays.fill(wrapped, (byte) 0);
        }
    }

    public void destroyBiometricEnvelope() throws GeneralSecurityException {
        // Synchronous durability matters before deleting the Keystore entry.
        //noinspection ApplySharedPref
        preferences.edit().clear().commit();
        KeyStore keyStore = loadAndroidKeyStore();
        if (keyStore.containsAlias(WRAPPING_ALIAS)) keyStore.deleteEntry(WRAPPING_ALIAS);
    }

    @NonNull
    private SecretKey getOrCreateWrappingKey() throws GeneralSecurityException {
        KeyStore keyStore = loadAndroidKeyStore();
        SecretKey existing = (SecretKey) keyStore.getKey(WRAPPING_ALIAS, null);
        if (existing != null) return existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                WRAPPING_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }

    @NonNull
    private static KeyStore loadAndroidKeyStore() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            return keyStore;
        } catch (Exception error) {
            throw new GeneralSecurityException("Could not load Android Keystore", error);
        }
    }
}
