package dev.clipvault.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;

import dev.clipvault.app.nativecore.NativeClassifier;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.Logger;
import net.zetetic.database.NoopTarget;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class VaultRepository implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
    private SQLiteDatabase database;

    public VaultRepository(@NonNull Context context, @NonNull byte[] databaseKey) {
        if (databaseKey.length != 32) throw new IllegalArgumentException("Database key must be 256 bits");
        System.loadLibrary("sqlcipher");
        Logger.setTarget(new NoopTarget());
        File file = context.getDatabasePath("clipvault.db");
        database = SQLiteDatabase.openOrCreateDatabase(file, databaseKey, null, null, null);
        database.rawExecSQL("PRAGMA cipher_memory_security = ON");
        database.rawExecSQL("PRAGMA secure_delete = ON");
        database.rawExecSQL("PRAGMA foreign_keys = ON");
        migrate();
    }

    private void migrate() {
        int version = 0;
        try (Cursor cursor = database.rawQuery("PRAGMA user_version", new String[0])) {
            if (cursor.moveToFirst()) version = cursor.getInt(0);
        }
        if (version > SCHEMA_VERSION) throw new IllegalStateException("Database was created by a newer app version");
        if (version == 0) {
            database.beginTransaction();
            try {
                database.execSQL("CREATE TABLE clips (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "content TEXT NOT NULL," +
                        "content_hash TEXT NOT NULL UNIQUE," +
                        "created_at INTEGER NOT NULL," +
                        "flags INTEGER NOT NULL," +
                        "favorite INTEGER NOT NULL DEFAULT 0)");
                database.execSQL("CREATE INDEX idx_clips_created ON clips(created_at DESC)");
                database.execSQL("CREATE INDEX idx_clips_flags ON clips(flags)");
                database.execSQL("PRAGMA user_version = " + SCHEMA_VERSION);
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }
    }

    public synchronized long insert(@NonNull String content, long createdAt) {
        ensureOpen();
        String normalized = NativeClassifier.normalize(content);
        if (normalized.isEmpty()) return -1;
        int flags = NativeClassifier.classify(normalized);
        String hash = sha256(normalized);

        ContentValues values = new ContentValues();
        values.put("content", normalized);
        values.put("content_hash", hash);
        values.put("created_at", createdAt);
        values.put("flags", flags);
        long id = database.insertWithOnConflict(
                "clips", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (id == -1) {
            ContentValues refresh = new ContentValues();
            refresh.put("created_at", createdAt);
            refresh.put("flags", flags);
            database.update("clips", refresh, "content_hash = ?", new String[]{hash});
            try (Cursor cursor = database.rawQuery(
                    "SELECT id FROM clips WHERE content_hash = ?", new String[]{hash})) {
                if (cursor.moveToFirst()) id = cursor.getLong(0);
            }
        }
        return id;
    }

    @NonNull
    public synchronized List<ClipRecord> query(
            @NonNull String search, int requiredFlag, boolean favoritesOnly, int limit) {
        ensureOpen();
        String escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<ClipRecord> records = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, content, created_at, flags, favorite FROM clips " +
                        "WHERE content LIKE ? ESCAPE '\\'");
        List<String> arguments = new ArrayList<>();
        arguments.add("%" + escaped + "%");
        if (requiredFlag != 0) {
            sql.append(" AND (flags & ?) != 0");
            arguments.add(String.valueOf(requiredFlag));
        }
        if (favoritesOnly) {
            sql.append(" AND favorite = 1");
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        arguments.add(String.valueOf(Math.max(1, Math.min(limit, 2_000))));

        try (Cursor cursor = database.rawQuery(sql.toString(), arguments.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                records.add(new ClipRecord(
                        cursor.getLong(0), cursor.getString(1), cursor.getLong(2),
                        cursor.getInt(3), cursor.getInt(4) == 1));
            }
        }
        return records;
    }

    public synchronized int count() {
        ensureOpen();
        try (Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM clips", new String[0])) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized void setFavorite(long id, boolean favorite) {
        ensureOpen();
        ContentValues values = new ContentValues();
        values.put("favorite", favorite ? 1 : 0);
        database.update("clips", values, "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized void delete(long id) {
        ensureOpen();
        database.delete("clips", "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized int deleteOlderThan(long cutoff) {
        ensureOpen();
        return database.delete("clips", "created_at < ?", new String[]{String.valueOf(cutoff)});
    }

    @Override
    public synchronized void close() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    private void ensureOpen() {
        if (database == null || !database.isOpen()) throw new IllegalStateException("Vault is locked");
    }

    @NonNull
    private static String sha256(@NonNull String value) {
        byte[] input = value.getBytes(StandardCharsets.UTF_8);
        byte[] digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(input);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) builder.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } finally {
            Arrays.fill(input, (byte) 0);
            if (digest != null) Arrays.fill(digest, (byte) 0);
        }
    }
}
