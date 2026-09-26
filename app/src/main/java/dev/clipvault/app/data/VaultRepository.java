package dev.clipvault.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.clipvault.app.nativecore.NativeClassifier;
import dev.clipvault.app.nativecore.TextAnalysis;

import net.zetetic.database.Logger;
import net.zetetic.database.NoopTarget;
import net.zetetic.database.sqlcipher.SQLiteDatabase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Thread-safe encrypted persistence boundary. All public operations are synchronous. */
public final class VaultRepository implements AutoCloseable {
    public static final int SCHEMA_VERSION = 3;
    private static final String CLIP_COLUMNS =
            "c.id,c.content,c.title,c.note,c.domain,c.first_captured_at,c.last_captured_at," +
            "c.capture_count,c.char_count,c.flags,c.favorite,c.pinned,c.collection_id,c.deleted_at";

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
        database.rawExecSQL("PRAGMA journal_mode = WAL");
        migrate();
    }

    private void migrate() {
        int version = userVersion();
        if (version > SCHEMA_VERSION) throw new IllegalStateException("Database was created by a newer app version");
        database.beginTransaction();
        try {
            if (version == 0) {
                createClipsV2();
            } else if (version == 1) {
                migrateV1ToV2();
            }
            if (version < 2) {
                createOrganizationSchema();
                createSearchSchema();
            }
            if (version < 3) {
                enableFtsSecureDelete();
                // v2 index may still hold terms of clips deleted before secure-delete existed.
                if (version == 2) rebuildSearchIndex();
                database.execSQL("PRAGMA user_version = 3");
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        if (version == 2) checkpointAndTruncateWal();
    }

    /**
     * PRAGMA secure_delete does not cover FTS5 shadow tables: a plain FTS5 delete only appends a
     * tombstone and keeps the original terms until a segment merge. This persistent FTS5 option
     * removes the terms immediately. Requires SQLite 3.42+, bundled by SQLCipher 4.19.
     */
    private void enableFtsSecureDelete() {
        database.execSQL("INSERT INTO clips_fts(clips_fts,rank) VALUES('secure-delete',1)");
    }

    /** Drops every FTS5 segment and re-indexes live rows; freed pages are zeroed by secure_delete. */
    private void rebuildSearchIndex() {
        database.execSQL("INSERT INTO clips_fts(clips_fts) VALUES('rebuild')");
    }

    /** Moves the rewritten pages into the main file so stale page images do not linger in the WAL. */
    private void checkpointAndTruncateWal() {
        try (Cursor cursor = database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", new String[0])) {
            cursor.moveToFirst();
        }
    }

    /** Exposed for security instrumentation tests; reports the persistent FTS5 option. */
    public synchronized boolean isFtsSecureDeleteEnabled() {
        ensureOpen();
        try (Cursor cursor = database.rawQuery(
                "SELECT v FROM clips_fts_config WHERE k='secure-delete'", new String[0])) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }

    private int userVersion() {
        try (Cursor cursor = database.rawQuery("PRAGMA user_version", new String[0])) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private void createClipsV2() {
        database.execSQL("CREATE TABLE clips (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "content TEXT NOT NULL," +
                "content_hash TEXT NOT NULL UNIQUE," +
                "created_at INTEGER NOT NULL," +
                "flags INTEGER NOT NULL," +
                "favorite INTEGER NOT NULL DEFAULT 0," +
                "title TEXT NOT NULL DEFAULT ''," +
                "note TEXT NOT NULL DEFAULT ''," +
                "domain TEXT NOT NULL DEFAULT ''," +
                "first_captured_at INTEGER NOT NULL," +
                "last_captured_at INTEGER NOT NULL," +
                "capture_count INTEGER NOT NULL DEFAULT 1," +
                "char_count INTEGER NOT NULL DEFAULT 0," +
                "pinned INTEGER NOT NULL DEFAULT 0," +
                "collection_id INTEGER," +
                "archived_at INTEGER," +
                "deleted_at INTEGER," +
                "deletion_reason TEXT)");
    }

    private void migrateV1ToV2() {
        database.execSQL("ALTER TABLE clips ADD COLUMN title TEXT NOT NULL DEFAULT ''");
        database.execSQL("ALTER TABLE clips ADD COLUMN note TEXT NOT NULL DEFAULT ''");
        database.execSQL("ALTER TABLE clips ADD COLUMN domain TEXT NOT NULL DEFAULT ''");
        database.execSQL("ALTER TABLE clips ADD COLUMN first_captured_at INTEGER NOT NULL DEFAULT 0");
        database.execSQL("ALTER TABLE clips ADD COLUMN last_captured_at INTEGER NOT NULL DEFAULT 0");
        database.execSQL("ALTER TABLE clips ADD COLUMN capture_count INTEGER NOT NULL DEFAULT 1");
        database.execSQL("ALTER TABLE clips ADD COLUMN char_count INTEGER NOT NULL DEFAULT 0");
        database.execSQL("ALTER TABLE clips ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
        database.execSQL("ALTER TABLE clips ADD COLUMN collection_id INTEGER");
        database.execSQL("ALTER TABLE clips ADD COLUMN archived_at INTEGER");
        database.execSQL("ALTER TABLE clips ADD COLUMN deleted_at INTEGER");
        database.execSQL("ALTER TABLE clips ADD COLUMN deletion_reason TEXT");
        database.execSQL("UPDATE clips SET first_captured_at=created_at,last_captured_at=created_at," +
                "char_count=length(content) WHERE first_captured_at=0 OR last_captured_at=0");
    }

    private void createOrganizationSchema() {
        database.execSQL("CREATE TABLE collections (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL COLLATE NOCASE UNIQUE," +
                "color_key TEXT NOT NULL DEFAULT 'violet'," +
                "sort_order INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL)");
        database.execSQL("CREATE TABLE tags (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "name_normalized TEXT NOT NULL UNIQUE," +
                "color_key TEXT NOT NULL DEFAULT 'violet'," +
                "created_at INTEGER NOT NULL)");
        database.execSQL("CREATE TABLE clip_tags (" +
                "clip_id INTEGER NOT NULL REFERENCES clips(id) ON DELETE CASCADE," +
                "tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE," +
                "PRIMARY KEY(clip_id,tag_id))");
        database.execSQL("CREATE TABLE capture_rules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "type TEXT NOT NULL," +
                "pattern TEXT NOT NULL," +
                "enabled INTEGER NOT NULL DEFAULT 1," +
                "match_count INTEGER NOT NULL DEFAULT 0," +
                "last_matched_at INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL)");
        database.execSQL("CREATE INDEX idx_clips_last ON clips(last_captured_at DESC,id DESC)");
        database.execSQL("CREATE INDEX idx_clips_flags ON clips(flags)");
        database.execSQL("CREATE INDEX idx_clips_state ON clips(deleted_at,pinned,favorite)");
        database.execSQL("CREATE INDEX idx_clips_collection ON clips(collection_id,deleted_at)");
        database.execSQL("CREATE INDEX idx_clips_domain ON clips(domain,deleted_at)");
    }

    private void createSearchSchema() {
        database.execSQL("CREATE VIRTUAL TABLE clips_fts USING fts5(" +
                "content,title,note,domain,content='clips',content_rowid='id',tokenize='unicode61')");
        database.execSQL("CREATE TRIGGER clips_ai AFTER INSERT ON clips BEGIN " +
                "INSERT INTO clips_fts(rowid,content,title,note,domain) " +
                "VALUES(new.id,new.content,new.title,new.note,new.domain); END");
        database.execSQL("CREATE TRIGGER clips_ad AFTER DELETE ON clips BEGIN " +
                "INSERT INTO clips_fts(clips_fts,rowid,content,title,note,domain) " +
                "VALUES('delete',old.id,old.content,old.title,old.note,old.domain); END");
        database.execSQL("CREATE TRIGGER clips_au AFTER UPDATE OF content,title,note,domain ON clips BEGIN " +
                "INSERT INTO clips_fts(clips_fts,rowid,content,title,note,domain) " +
                "VALUES('delete',old.id,old.content,old.title,old.note,old.domain); " +
                "INSERT INTO clips_fts(rowid,content,title,note,domain) " +
                "VALUES(new.id,new.content,new.title,new.note,new.domain); END");
        database.execSQL("INSERT INTO clips_fts(rowid,content,title,note,domain) " +
                "SELECT id,content,title,note,domain FROM clips");
    }

    public synchronized long insert(@NonNull String content, long capturedAt) {
        return insert(NativeClassifier.analyze(content), capturedAt);
    }

    public synchronized long insert(@NonNull TextAnalysis analysis, long capturedAt) {
        ensureOpen();
        if (analysis.normalized.isEmpty()) return -1;
        String hash = sha256(analysis.normalized);
        ContentValues values = new ContentValues();
        values.put("content", analysis.normalized);
        values.put("content_hash", hash);
        values.put("created_at", capturedAt);
        values.put("flags", analysis.flags);
        values.put("domain", analysis.domain);
        values.put("first_captured_at", capturedAt);
        values.put("last_captured_at", capturedAt);
        values.put("capture_count", 1);
        values.put("char_count", analysis.characterCount);
        long id = database.insertWithOnConflict("clips", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (id != -1) return id;

        database.execSQL("UPDATE clips SET created_at=MAX(created_at,?)," +
                        "last_captured_at=MAX(last_captured_at,?),first_captured_at=MIN(first_captured_at,?)," +
                        "capture_count=capture_count+1,flags=?,domain=?,char_count=?,deleted_at=NULL,deletion_reason=NULL " +
                        "WHERE content_hash=?",
                new Object[]{capturedAt, capturedAt, capturedAt, analysis.flags, analysis.domain,
                        analysis.characterCount, hash});
        try (Cursor cursor = database.rawQuery("SELECT id FROM clips WHERE content_hash=?", new String[]{hash})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    @NonNull
    public synchronized ClipPage query(@NonNull ClipQuery query) {
        ensureOpen();
        List<ClipItem> items = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        boolean hasSearch = !query.search.isEmpty();
        StringBuilder sql = new StringBuilder("SELECT ").append(CLIP_COLUMNS).append(" FROM clips c ");
        if (hasSearch) sql.append("JOIN clips_fts ON clips_fts.rowid=c.id ");
        sql.append(query.trash ? "WHERE c.deleted_at IS NOT NULL" : "WHERE c.deleted_at IS NULL");
        if (hasSearch) {
            sql.append(" AND clips_fts MATCH ?");
            arguments.add(toFtsQuery(query.search));
        }
        if (query.requiredFlag != 0) {
            sql.append(" AND (c.flags & ?) != 0");
            arguments.add(String.valueOf(query.requiredFlag));
        }
        if (query.favoritesOnly) sql.append(" AND c.favorite=1");
        if (query.pinnedOnly) sql.append(" AND c.pinned=1");
        if (!query.domain.isEmpty()) {
            sql.append(" AND (c.domain=? OR c.domain LIKE ?)");
            arguments.add(query.domain);
            arguments.add("%." + query.domain);
        }
        if (query.collectionId != null) {
            sql.append(" AND c.collection_id=?");
            arguments.add(String.valueOf(query.collectionId));
        }
        if (query.tagId != null) {
            sql.append(" AND EXISTS(SELECT 1 FROM clip_tags selected_tag WHERE selected_tag.clip_id=c.id AND selected_tag.tag_id=?)");
            arguments.add(String.valueOf(query.tagId));
        }
        if (query.fromTime != null) {
            sql.append(" AND c.last_captured_at>=?");
            arguments.add(String.valueOf(query.fromTime));
        }
        if (query.toTime != null) {
            sql.append(" AND c.last_captured_at<?");
            arguments.add(String.valueOf(query.toTime));
        }
        switch (query.sort) {
            case OLDEST:
                sql.append(" ORDER BY c.last_captured_at ASC,c.id ASC");
                break;
            case FREQUENT:
                sql.append(" ORDER BY c.capture_count DESC,c.last_captured_at DESC,c.id DESC");
                break;
            case LONGEST:
                sql.append(" ORDER BY c.char_count DESC,c.last_captured_at DESC,c.id DESC");
                break;
            case NEWEST:
            default:
                sql.append(" ORDER BY c.pinned DESC,c.last_captured_at DESC,c.id DESC");
                break;
        }
        sql.append(" LIMIT ? OFFSET ?");
        arguments.add(String.valueOf(query.limit + 1));
        arguments.add(String.valueOf(query.offset));
        try (Cursor cursor = database.rawQuery(sql.toString(), arguments.toArray(new String[0]))) {
            while (cursor.moveToNext()) items.add(readClip(cursor));
        }
        boolean more = items.size() > query.limit;
        if (more) items.remove(items.size() - 1);
        return new ClipPage(items, query.offset + items.size(), more);
    }

    /** Compatibility surface for the 1.x adapter; new UI uses {@link #query(ClipQuery)}. */
    @NonNull
    public synchronized List<ClipRecord> query(
            @NonNull String search, int requiredFlag, boolean favoritesOnly, int limit) {
        ClipPage page = query(ClipQuery.builder().search(search).requiredFlag(requiredFlag)
                .favoritesOnly(favoritesOnly).page(Math.min(limit, 200), 0).build());
        List<ClipRecord> records = new ArrayList<>(page.items.size());
        for (ClipItem item : page.items) {
            records.add(new ClipRecord(item.id, item.content, item.lastCapturedAt, item.flags, item.favorite));
        }
        return records;
    }

    @Nullable
    public synchronized ClipItem find(long id) {
        ensureOpen();
        try (Cursor cursor = database.rawQuery(
                "SELECT " + CLIP_COLUMNS + " FROM clips c WHERE c.id=?", new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? readClip(cursor) : null;
        }
    }

    public synchronized int count() {
        return scalarInt("SELECT COUNT(*) FROM clips WHERE deleted_at IS NULL");
    }

    @NonNull
    public synchronized VaultStats stats() {
        ensureOpen();
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        String sql = "SELECT " +
                "SUM(CASE WHEN deleted_at IS NULL THEN 1 ELSE 0 END)," +
                "SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END)," +
                "SUM(CASE WHEN deleted_at IS NULL AND favorite=1 THEN 1 ELSE 0 END)," +
                "SUM(CASE WHEN deleted_at IS NULL AND pinned=1 THEN 1 ELSE 0 END)," +
                "SUM(CASE WHEN deleted_at IS NULL AND (flags & ?) != 0 THEN 1 ELSE 0 END)," +
                "SUM(CASE WHEN deleted_at IS NULL AND last_captured_at>=? THEN 1 ELSE 0 END)," +
                "COALESCE(SUM(CASE WHEN deleted_at IS NULL THEN char_count ELSE 0 END),0)," +
                "COALESCE(SUM(CASE WHEN deleted_at IS NULL THEN capture_count-1 ELSE 0 END),0) FROM clips";
        try (Cursor cursor = database.rawQuery(sql, new String[]{
                String.valueOf(NativeClassifier.LINK), String.valueOf(start.getTimeInMillis())})) {
            if (!cursor.moveToFirst()) return new VaultStats(0, 0, 0, 0, 0, 0, 0, 0);
            return new VaultStats(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3),
                    cursor.getInt(4), cursor.getInt(5), cursor.getLong(6), cursor.getLong(7));
        }
    }

    public synchronized void setFavorite(long id, boolean favorite) {
        updateBoolean(id, "favorite", favorite);
    }

    public synchronized void setPinned(long id, boolean pinned) {
        updateBoolean(id, "pinned", pinned);
    }

    public synchronized void setFavorite(@NonNull List<Long> ids, boolean favorite) {
        updateBoolean(ids, "favorite", favorite);
    }

    public synchronized void setPinned(@NonNull List<Long> ids, boolean pinned) {
        updateBoolean(ids, "pinned", pinned);
    }

    public synchronized void setCollection(@NonNull List<Long> ids, @Nullable Long collectionId) {
        ensureOpen();
        database.beginTransaction();
        try {
            for (Long id : ids) {
                ContentValues values = new ContentValues();
                if (collectionId == null) values.putNull("collection_id"); else values.put("collection_id", collectionId);
                database.update("clips", values, "id=?", new String[]{String.valueOf(id)});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public synchronized boolean edit(long id, @NonNull String content, @NonNull String title, @NonNull String note) {
        ensureOpen();
        TextAnalysis analysis = NativeClassifier.analyze(content);
        if (analysis.normalized.isEmpty()) return false;
        String hash = sha256(analysis.normalized);
        database.beginTransaction();
        try {
            Long duplicate = null;
            try (Cursor cursor = database.rawQuery(
                    "SELECT id FROM clips WHERE content_hash=? AND id<>?", new String[]{hash, String.valueOf(id)})) {
                if (cursor.moveToFirst()) duplicate = cursor.getLong(0);
            }
            if (duplicate != null) {
                database.execSQL("UPDATE clips SET favorite=MAX(favorite,(SELECT favorite FROM clips WHERE id=?))," +
                                "pinned=MAX(pinned,(SELECT pinned FROM clips WHERE id=?))," +
                                "capture_count=capture_count+(SELECT capture_count FROM clips WHERE id=?)," +
                                "first_captured_at=MIN(first_captured_at,(SELECT first_captured_at FROM clips WHERE id=?))," +
                                "last_captured_at=MAX(last_captured_at,(SELECT last_captured_at FROM clips WHERE id=?)) WHERE id=?",
                        new Object[]{id, id, id, id, id, duplicate});
                database.execSQL("INSERT OR IGNORE INTO clip_tags(clip_id,tag_id) SELECT ?,tag_id FROM clip_tags WHERE clip_id=?",
                        new Object[]{duplicate, id});
                database.delete("clips", "id=?", new String[]{String.valueOf(id)});
            } else {
                ContentValues values = new ContentValues();
                values.put("content", analysis.normalized);
                values.put("content_hash", hash);
                values.put("title", title.trim());
                values.put("note", note.trim());
                values.put("domain", analysis.domain);
                values.put("flags", analysis.flags);
                values.put("char_count", analysis.characterCount);
                database.update("clips", values, "id=?", new String[]{String.valueOf(id)});
            }
            database.setTransactionSuccessful();
            return true;
        } finally {
            database.endTransaction();
        }
    }

    /** 1.x API now performs recoverable deletion. */
    public synchronized void delete(long id) {
        moveToTrash(Collections.singletonList(id), "manual", System.currentTimeMillis());
    }

    public synchronized int moveToTrash(@NonNull List<Long> ids, @NonNull String reason, long now) {
        ensureOpen();
        int changed = 0;
        database.beginTransaction();
        try {
            for (Long id : ids) {
                ContentValues values = new ContentValues();
                values.put("deleted_at", now);
                values.put("deletion_reason", reason);
                changed += database.update("clips", values, "id=? AND deleted_at IS NULL", new String[]{String.valueOf(id)});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return changed;
    }

    public synchronized int restore(@NonNull List<Long> ids) {
        ensureOpen();
        int changed = 0;
        database.beginTransaction();
        try {
            for (Long id : ids) {
                ContentValues values = new ContentValues();
                values.putNull("deleted_at");
                values.putNull("deletion_reason");
                changed += database.update("clips", values, "id=? AND deleted_at IS NOT NULL", new String[]{String.valueOf(id)});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return changed;
    }

    public synchronized int permanentlyDelete(@NonNull List<Long> ids) {
        ensureOpen();
        int changed = 0;
        database.beginTransaction();
        try {
            for (Long id : ids) changed += database.delete("clips", "id=?", new String[]{String.valueOf(id)});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return changed;
    }

    public synchronized int deleteOlderThan(long cutoff) {
        return softDeleteOlderThan(cutoff, System.currentTimeMillis());
    }

    public synchronized int softDeleteOlderThan(long cutoff, long now) {
        ensureOpen();
        ContentValues values = new ContentValues();
        values.put("deleted_at", now);
        values.put("deletion_reason", "retention");
        return database.update("clips", values,
                "last_captured_at<? AND pinned=0 AND deleted_at IS NULL", new String[]{String.valueOf(cutoff)});
    }

    public synchronized int purgeTrashOlderThan(long cutoff) {
        ensureOpen();
        return database.delete("clips", "deleted_at IS NOT NULL AND deleted_at<?", new String[]{String.valueOf(cutoff)});
    }

    public synchronized long createCollection(@NonNull String name, @NonNull String colorKey) {
        ensureOpen();
        ContentValues values = new ContentValues();
        values.put("name", name.trim());
        values.put("color_key", colorKey);
        values.put("sort_order", scalarInt("SELECT COALESCE(MAX(sort_order),-1)+1 FROM collections"));
        values.put("created_at", System.currentTimeMillis());
        return database.insertWithOnConflict("collections", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized void deleteCollection(long id) {
        ensureOpen();
        database.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.putNull("collection_id");
            database.update("clips", values, "collection_id=?", new String[]{String.valueOf(id)});
            database.delete("collections", "id=?", new String[]{String.valueOf(id)});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    @NonNull
    public synchronized List<CollectionRecord> collections() {
        ensureOpen();
        List<CollectionRecord> result = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                "SELECT x.id,x.name,x.color_key,x.sort_order,COUNT(c.id) FROM collections x " +
                        "LEFT JOIN clips c ON c.collection_id=x.id AND c.deleted_at IS NULL " +
                        "GROUP BY x.id ORDER BY x.sort_order,x.name", new String[0])) {
            while (cursor.moveToNext()) result.add(new CollectionRecord(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3), cursor.getInt(4)));
        }
        return result;
    }

    public synchronized long createTag(@NonNull String name, @NonNull String colorKey) {
        ensureOpen();
        String trimmed = name.trim();
        ContentValues values = new ContentValues();
        values.put("name", trimmed);
        values.put("name_normalized", trimmed.toLowerCase(Locale.ROOT));
        values.put("color_key", colorKey);
        values.put("created_at", System.currentTimeMillis());
        return database.insertWithOnConflict("tags", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    @NonNull
    public synchronized List<TagRecord> tags() {
        ensureOpen();
        List<TagRecord> result = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                "SELECT t.id,t.name,t.color_key,COUNT(ct.clip_id) FROM tags t " +
                        "LEFT JOIN clip_tags ct ON ct.tag_id=t.id GROUP BY t.id ORDER BY t.name", new String[0])) {
            while (cursor.moveToNext()) result.add(new TagRecord(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3)));
        }
        return result;
    }

    public synchronized void setTags(long clipId, @NonNull List<Long> tagIds) {
        ensureOpen();
        database.beginTransaction();
        try {
            database.delete("clip_tags", "clip_id=?", new String[]{String.valueOf(clipId)});
            for (Long tagId : tagIds) {
                database.execSQL("INSERT OR IGNORE INTO clip_tags(clip_id,tag_id) VALUES(?,?)",
                        new Object[]{clipId, tagId});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public synchronized void addTagToClips(@NonNull List<Long> clipIds, long tagId) {
        ensureOpen();
        database.beginTransaction();
        try {
            for (Long clipId : clipIds) {
                database.execSQL("INSERT OR IGNORE INTO clip_tags(clip_id,tag_id) VALUES(?,?)",
                        new Object[]{clipId, tagId});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public synchronized long createRule(@NonNull CaptureRule.Type type, @NonNull String pattern) {
        ensureOpen();
        if (!CaptureRuleEngine.isValid(type, pattern)) throw new IllegalArgumentException("Invalid capture rule");
        ContentValues values = new ContentValues();
        values.put("type", type.name());
        values.put("pattern", pattern.trim());
        values.put("created_at", System.currentTimeMillis());
        return database.insert("capture_rules", null, values);
    }

    public synchronized void setRuleEnabled(long id, boolean enabled) {
        ensureOpen();
        ContentValues values = new ContentValues();
        values.put("enabled", enabled ? 1 : 0);
        database.update("capture_rules", values, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void deleteRule(long id) {
        ensureOpen();
        database.delete("capture_rules", "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void recordRuleMatch(long id, long now) {
        ensureOpen();
        database.execSQL("UPDATE capture_rules SET match_count=match_count+1,last_matched_at=? WHERE id=?",
                new Object[]{now, id});
    }

    @NonNull
    public synchronized List<CaptureRule> rules() {
        ensureOpen();
        List<CaptureRule> result = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                "SELECT id,type,pattern,enabled,match_count,last_matched_at FROM capture_rules ORDER BY id", new String[0])) {
            while (cursor.moveToNext()) {
                try {
                    result.add(new CaptureRule(cursor.getLong(0), CaptureRule.Type.valueOf(cursor.getString(1)),
                            cursor.getString(2), cursor.getInt(3) == 1, cursor.getLong(4), cursor.getLong(5)));
                } catch (IllegalArgumentException ignored) {
                    // Ignore rules created by a newer app while preserving the row.
                }
            }
        }
        return result;
    }

    @NonNull
    public synchronized List<ClipItem> allClipsForBackup() {
        ensureOpen();
        List<ClipItem> result = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                "SELECT " + CLIP_COLUMNS + " FROM clips c ORDER BY c.id", new String[0])) {
            while (cursor.moveToNext()) result.add(readClip(cursor));
        }
        return result;
    }

    @NonNull
    public synchronized List<ClipTagLink> allClipTagLinks() {
        ensureOpen();
        List<ClipTagLink> result = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                "SELECT clip_id,tag_id FROM clip_tags ORDER BY clip_id,tag_id", new String[0])) {
            while (cursor.moveToNext()) result.add(new ClipTagLink(cursor.getLong(0), cursor.getLong(1)));
        }
        return result;
    }

    public synchronized void runInTransaction(@NonNull Runnable operation) {
        ensureOpen();
        database.beginTransaction();
        try {
            operation.run();
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public synchronized void clearForRestore() {
        ensureOpen();
        database.delete("clip_tags", null, null);
        database.delete("clips", null, null);
        database.delete("tags", null, null);
        database.delete("collections", null, null);
        database.delete("capture_rules", null, null);
    }

    public synchronized long mergeImportedClip(@NonNull BackupClipData clip) {
        ensureOpen();
        TextAnalysis analysis = NativeClassifier.analyze(clip.content);
        String hash = sha256(analysis.normalized);
        long id = -1;
        try (Cursor cursor = database.rawQuery("SELECT id FROM clips WHERE content_hash=?", new String[]{hash})) {
            if (cursor.moveToFirst()) id = cursor.getLong(0);
        }
        if (id == -1) id = insert(analysis, clip.lastCapturedAt);
        if (id == -1) return -1;
        ClipItem current = find(id);
        long importedFirst = clip.firstCapturedAt > 0 ? clip.firstCapturedAt : clip.lastCapturedAt;
        ContentValues values = new ContentValues();
        values.put("title", current != null && !current.title.isEmpty() ? current.title : clip.title);
        values.put("note", current != null && !current.note.isEmpty() ? current.note : clip.note);
        values.put("first_captured_at", current == null ? importedFirst : Math.min(current.firstCapturedAt, importedFirst));
        values.put("last_captured_at", current == null ? clip.lastCapturedAt : Math.max(current.lastCapturedAt, clip.lastCapturedAt));
        values.put("created_at", current == null ? clip.lastCapturedAt : Math.max(current.lastCapturedAt, clip.lastCapturedAt));
        values.put("capture_count", current == null ? Math.max(1, clip.captureCount)
                : Math.max(current.captureCount, Math.max(1, clip.captureCount)));
        values.put("favorite", clip.favorite || current != null && current.favorite ? 1 : 0);
        values.put("pinned", clip.pinned || current != null && current.pinned ? 1 : 0);
        if (clip.deletedAt == null || current != null && current.deletedAt == null) values.putNull("deleted_at");
        else values.put("deleted_at", clip.deletedAt);
        database.update("clips", values, "id=?", new String[]{String.valueOf(id)});
        return id;
    }

    @Override
    public synchronized void close() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    private void ensureOpen() {
        if (database == null || !database.isOpen()) {
            throw new IllegalStateException("Vault is locked");
        }
    }

    private void updateBoolean(long id, String column, boolean value) {
        ensureOpen();
        ContentValues values = new ContentValues();
        values.put(column, value ? 1 : 0);
        database.update("clips", values, "id=?", new String[]{String.valueOf(id)});
    }

    private void updateBoolean(@NonNull List<Long> ids, String column, boolean value) {
        ensureOpen();
        database.beginTransaction();
        try {
            for (Long id : ids) updateBoolean(id, column, value);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private int scalarInt(String sql) {
        ensureOpen();
        try (Cursor cursor = database.rawQuery(sql, new String[0])) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    @NonNull
    private static ClipItem readClip(Cursor cursor) {
        Long collection = cursor.isNull(12) ? null : cursor.getLong(12);
        Long deleted = cursor.isNull(13) ? null : cursor.getLong(13);
        return new ClipItem(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                cursor.getString(3), cursor.getString(4), cursor.getLong(5), cursor.getLong(6),
                cursor.getInt(7), cursor.getInt(8), cursor.getInt(9), cursor.getInt(10) == 1,
                cursor.getInt(11) == 1, collection, deleted);
    }

    @NonNull
    private static String toFtsQuery(@NonNull String search) {
        String[] terms = search.trim().split("\\s+");
        StringBuilder output = new StringBuilder();
        for (String term : terms) {
            String clean = term.replace("\"", "\"\"").trim();
            if (clean.isEmpty()) continue;
            if (output.length() > 0) output.append(" AND ");
            output.append('"').append(clean).append("\"*");
        }
        return output.length() == 0 ? "\"\"" : output.toString();
    }

    @NonNull
    public static String contentHash(@NonNull String value) {
        return sha256(NativeClassifier.normalize(value));
    }

    @NonNull
    private static String sha256(@NonNull String value) {
        byte[] input = value.getBytes(StandardCharsets.UTF_8);
        byte[] digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(input);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) builder.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } finally {
            Arrays.fill(input, (byte) 0);
            if (digest != null) Arrays.fill(digest, (byte) 0);
        }
    }
}
