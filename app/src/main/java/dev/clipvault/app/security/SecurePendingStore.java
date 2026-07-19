package dev.clipvault.app.security;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecurePendingStore {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ENCRYPTION_ALIAS = "clipvault.pending.aes.v1";
    private static final String LOOKUP_ALIAS = "clipvault.pending.hmac.v1";
    private static final int MAX_PENDING = 2_000;

    public static final class PendingClip {
        public final long id;
        @NonNull public final String content;
        public final long createdAt;

        PendingClip(long id, @NonNull String content, long createdAt) {
            this.id = id;
            this.content = content;
            this.createdAt = createdAt;
        }
    }

    private final PendingDatabase helper;

    public SecurePendingStore(@NonNull Context context) {
        helper = new PendingDatabase(context.getApplicationContext());
    }

    public synchronized boolean add(@NonNull String plaintext, long createdAt) {
        if (plaintext.isEmpty()) return false;
        byte[] clear = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            SecretKey encryptionKey = getOrCreateEncryptionKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey);
            cipher.updateAAD(longBytes(createdAt));
            byte[] encrypted = cipher.doFinal(clear);
            byte[] lookup = hmac(clear);

            SQLiteDatabase database = helper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("created_at", createdAt);
            values.put("iv", cipher.getIV());
            values.put("payload", encrypted);
            values.put("lookup_tag", lookup);
            long result = database.insertWithOnConflict(
                    "pending_clips", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            database.execSQL(
                    "DELETE FROM pending_clips WHERE id IN (SELECT id FROM pending_clips ORDER BY created_at DESC LIMIT -1 OFFSET ?)",
                    new Object[]{MAX_PENDING});
            java.util.Arrays.fill(encrypted, (byte) 0);
            java.util.Arrays.fill(lookup, (byte) 0);
            return result != -1;
        } catch (GeneralSecurityException error) {
            return false;
        } finally {
            java.util.Arrays.fill(clear, (byte) 0);
        }
    }

    @NonNull
    public synchronized List<PendingClip> readBatch(int limit) {
        if (limit <= 0) return Collections.emptyList();
        List<PendingClip> clips = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT id, created_at, iv, payload FROM pending_clips ORDER BY created_at ASC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            SecretKey key = getOrCreateEncryptionKey();
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                long createdAt = cursor.getLong(1);
                byte[] iv = cursor.getBlob(2);
                byte[] payload = cursor.getBlob(3);
                try {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                    cipher.updateAAD(longBytes(createdAt));
                    byte[] clear = cipher.doFinal(payload);
                    try {
                        clips.add(new PendingClip(id, new String(clear, StandardCharsets.UTF_8), createdAt));
                    } finally {
                        java.util.Arrays.fill(clear, (byte) 0);
                    }
                } catch (GeneralSecurityException ignored) {
                    deleteIds(Collections.singletonList(id));
                }
            }
        } catch (GeneralSecurityException ignored) {
            return Collections.emptyList();
        }
        return clips;
    }

    public synchronized void deleteIds(@NonNull List<Long> ids) {
        if (ids.isEmpty()) return;
        SQLiteDatabase database = helper.getWritableDatabase();
        database.beginTransaction();
        try {
            for (Long id : ids) database.delete("pending_clips", "id = ?", new String[]{String.valueOf(id)});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public synchronized int count() {
        try (Cursor cursor = helper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pending_clips", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized int purgeOlderThan(long cutoff) {
        return helper.getWritableDatabase().delete(
                "pending_clips", "created_at < ?", new String[]{String.valueOf(cutoff)});
    }

    @NonNull
    private static SecretKey getOrCreateEncryptionKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        SecretKey key = (SecretKey) keyStore.getKey(ENCRYPTION_ALIAS, null);
        if (key != null) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ENCRYPTION_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    @NonNull
    private static SecretKey getOrCreateLookupKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        SecretKey key = (SecretKey) keyStore.getKey(LOOKUP_ALIAS, null);
        if (key != null) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                LOOKUP_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        return generator.generateKey();
    }

    private static byte[] hmac(byte[] clear) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(getOrCreateLookupKey());
        return mac.doFinal(clear);
    }

    private static KeyStore loadKeyStore() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            return keyStore;
        } catch (Exception error) {
            throw new GeneralSecurityException("Could not load Android Keystore", error);
        }
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static final class PendingDatabase extends SQLiteOpenHelper {
        PendingDatabase(Context context) {
            super(context, "pending_encrypted.db", null, 1);
            setWriteAheadLoggingEnabled(true);
        }

        @Override
        public void onCreate(SQLiteDatabase database) {
            database.execSQL("CREATE TABLE pending_clips (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "created_at INTEGER NOT NULL," +
                    "iv BLOB NOT NULL," +
                    "payload BLOB NOT NULL," +
                    "lookup_tag BLOB NOT NULL UNIQUE)");
            database.execSQL("CREATE INDEX idx_pending_created ON pending_clips(created_at)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
            // Schema version 1.
        }
    }
}
