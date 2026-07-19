package dev.clipvault.app.backup

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonWriter
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.data.BackupClipData
import dev.clipvault.app.data.CaptureRule
import dev.clipvault.app.data.ClipItem
import dev.clipvault.app.data.ClipTagLink
import dev.clipvault.app.data.CollectionRecord
import dev.clipvault.app.data.TagRecord
import dev.clipvault.app.data.VaultRepository
import dev.clipvault.app.ui.settings.AccentPalette
import dev.clipvault.app.ui.settings.AppLanguage
import dev.clipvault.app.ui.settings.SettingsRepository
import dev.clipvault.app.ui.settings.ThemeMode
import dev.clipvault.app.ui.settings.ThemeSettings
import kotlinx.coroutines.flow.first
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.CharBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, streaming and authenticated offline backup container. */
class VaultBackupManager(private val context: Context) {
    companion object {
        private val MAGIC = byteArrayOf(0x43, 0x56, 0x4C, 0x54, 0x32) // CVLT2
        private const val CONTAINER_VERSION = 1
        private const val PAYLOAD_VERSION = 2
        private const val MEMORY_KIB = 65_536
        private const val ITERATIONS = 3
        private const val PARALLELISM = 1
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val KEY_BYTES = 32
        const val MIN_PASSPHRASE_LENGTH = 12
    }

    suspend fun exportVault(uri: Uri, passphrase: CharArray, repository: VaultRepository): BackupManifest =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase must contain at least 12 characters" }
            val clips = repository.allClipsForBackup()
            val collections = repository.collections()
            val tags = repository.tags()
            val links = repository.allClipTagLinks()
            val rules = repository.rules()
            val theme = SettingsRepository(context).settings.first()
            val manifest = BackupManifest(PAYLOAD_VERSION, System.currentTimeMillis(), clips.size,
                collections.size, tags.size, rules.size)
            val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
            val key = deriveKey(passphrase, salt)
            try {
                val header = createHeader(salt, nonce)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(header)
                context.contentResolver.openOutputStream(uri, "w")?.use { raw ->
                    raw.write(header)
                    CipherOutputStream(raw, cipher).use { encrypted ->
                        JsonWriter(OutputStreamWriter(encrypted, StandardCharsets.UTF_8)).use { writer ->
                            writePayload(writer, manifest, clips, collections, tags, links, rules, theme)
                        }
                    }
                } ?: error("Could not open backup destination")
                manifest
            } finally {
                key.fill(0)
                salt.fill(0)
                nonce.fill(0)
                passphrase.fill('\u0000')
            }
        }

    suspend fun inspect(uri: Uri, passphrase: CharArray): BackupManifest =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                readPayload(uri, passphrase).manifest
            } finally {
                passphrase.fill('\u0000')
            }
        }

    suspend fun importVault(
        uri: Uri,
        passphrase: CharArray,
        repository: VaultRepository,
        replace: Boolean,
    ): ImportResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = try {
            readPayload(uri, passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
        val collectionMap = mutableMapOf<Long, Long>()
        val tagMap = mutableMapOf<Long, Long>()
        val clipMap = mutableMapOf<Long, Long>()
        repository.runInTransaction {
            if (replace) repository.clearForRestore()
            payload.collections.forEach { source ->
                var id = repository.createCollection(source.name, source.colorKey)
                if (id == -1L) id = repository.collections().first { it.name.equals(source.name, true) }.id
                collectionMap[source.id] = id
            }
            payload.tags.forEach { source ->
                var id = repository.createTag(source.name, source.colorKey)
                if (id == -1L) id = repository.tags().first { it.name.equals(source.name, true) }.id
                tagMap[source.id] = id
            }
            payload.clips.forEach { source ->
                val target = repository.mergeImportedClip(source.data)
                if (target != -1L) {
                    clipMap[source.data.sourceId] = target
                    source.collectionId?.let { collectionMap[it] }?.let { repository.setCollection(listOf(target), it) }
                }
            }
            payload.links.groupBy { it.clipId }.forEach { (sourceClip, sourceLinks) ->
                val targetClip = clipMap[sourceClip] ?: return@forEach
                repository.setTags(targetClip, sourceLinks.mapNotNull { tagMap[it.tagId] })
            }
            payload.rules.forEach { rule ->
                val id = repository.createRule(rule.type, rule.pattern)
                if (!rule.enabled && id != -1L) repository.setRuleEnabled(id, false)
            }
        }
        context.getSharedPreferences(ClipVaultApp.PREFS, Context.MODE_PRIVATE).edit()
            .putInt(ClipVaultApp.PREF_RETENTION_MONTHS, payload.settings.retentionMonths)
            .putInt(ClipVaultApp.PREF_CUSTOM_RETENTION_DAYS, payload.settings.customRetentionDays)
            .putBoolean(ClipVaultApp.PREF_SKIP_SENSITIVE, payload.settings.skipSensitive).apply()
        SettingsRepository(context).apply {
            setThemeMode(payload.settings.theme.mode)
            setDynamicColor(payload.settings.theme.dynamicColor)
            setAccent(payload.settings.theme.accentPalette)
            setReducedMotion(payload.settings.theme.reducedMotion)
            setLanguage(payload.settings.theme.language)
            setFontScale(payload.settings.theme.fontScale)
        }
        ImportResult(payload.clips.size, collectionMap.size, tagMap.size, payload.rules.size)
    }

    private fun writePayload(
        writer: JsonWriter,
        manifest: BackupManifest,
        clips: List<ClipItem>,
        collections: List<CollectionRecord>,
        tags: List<TagRecord>,
        links: List<ClipTagLink>,
        rules: List<CaptureRule>,
        theme: ThemeSettings,
    ) {
        writer.beginObject()
        writer.name("manifest").beginObject()
        writer.name("version").value(manifest.formatVersion.toLong())
        writer.name("createdAt").value(manifest.createdAt)
        writer.name("clipCount").value(manifest.clipCount.toLong())
        writer.name("collectionCount").value(manifest.collectionCount.toLong())
        writer.name("tagCount").value(manifest.tagCount.toLong())
        writer.name("ruleCount").value(manifest.ruleCount.toLong())
        writer.endObject()
        writer.name("settings").beginObject()
        val preferences = context.getSharedPreferences(ClipVaultApp.PREFS, Context.MODE_PRIVATE)
        writer.name("retentionMonths").value(preferences.getInt(ClipVaultApp.PREF_RETENTION_MONTHS, -1).toLong())
        writer.name("customRetentionDays").value(preferences.getInt(ClipVaultApp.PREF_CUSTOM_RETENTION_DAYS, 0).toLong())
        writer.name("skipSensitive").value(preferences.getBoolean(ClipVaultApp.PREF_SKIP_SENSITIVE, true))
        writer.name("themeMode").value(theme.mode.name)
        writer.name("dynamicColor").value(theme.dynamicColor)
        writer.name("accent").value(theme.accentPalette.name)
        writer.name("reducedMotion").value(theme.reducedMotion)
        writer.name("language").value(theme.language.name)
        writer.name("fontScale").value(theme.fontScale.toDouble())
        writer.endObject()
        writer.name("collections").beginArray()
        collections.forEach { item ->
            writer.beginObject().name("id").value(item.id).name("name").value(item.name)
                .name("color").value(item.colorKey).name("order").value(item.sortOrder.toLong()).endObject()
        }
        writer.endArray()
        writer.name("tags").beginArray()
        tags.forEach { item ->
            writer.beginObject().name("id").value(item.id).name("name").value(item.name)
                .name("color").value(item.colorKey).endObject()
        }
        writer.endArray()
        writer.name("clips").beginArray()
        clips.forEach { item ->
            writer.beginObject()
            writer.name("id").value(item.id)
            writer.name("content").value(item.content)
            writer.name("title").value(item.title)
            writer.name("note").value(item.note)
            writer.name("first").value(item.firstCapturedAt)
            writer.name("last").value(item.lastCapturedAt)
            writer.name("count").value(item.captureCount.toLong())
            writer.name("favorite").value(item.favorite)
            writer.name("pinned").value(item.pinned)
            writer.name("collectionId"); if (item.collectionId == null) writer.nullValue() else writer.value(item.collectionId)
            writer.name("deletedAt"); if (item.deletedAt == null) writer.nullValue() else writer.value(item.deletedAt)
            writer.endObject()
        }
        writer.endArray()
        writer.name("clipTags").beginArray()
        links.forEach { writer.beginArray().value(it.clipId).value(it.tagId).endArray() }
        writer.endArray()
        writer.name("rules").beginArray()
        rules.forEach { item ->
            writer.beginObject().name("type").value(item.type.name).name("pattern").value(item.pattern)
                .name("enabled").value(item.enabled).endObject()
        }
        writer.endArray()
        writer.endObject()
    }

    private fun readPayload(uri: Uri, passphrase: CharArray): BackupPayload {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase must contain at least 12 characters" }
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = DataInputStream(raw)
            val parsed = readHeader(input)
            val key = deriveKey(passphrase, parsed.salt)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, parsed.nonce))
                cipher.updateAAD(parsed.encoded)
                CipherInputStream(input, cipher).use { decrypted ->
                    JsonReader(InputStreamReader(decrypted, StandardCharsets.UTF_8)).use { reader ->
                        return parsePayload(reader)
                    }
                }
            } finally {
                key.fill(0)
                parsed.salt.fill(0)
                parsed.nonce.fill(0)
            }
        }
        error("Could not open backup")
    }

    private fun parsePayload(reader: JsonReader): BackupPayload {
        var manifest: BackupManifest? = null
        val clips = mutableListOf<ImportedClip>()
        val collections = mutableListOf<CollectionRecord>()
        val tags = mutableListOf<TagRecord>()
        val links = mutableListOf<ClipTagLink>()
        val rules = mutableListOf<CaptureRule>()
        var settings = BackupSettings()
        reader.beginObject()
        while (reader.hasNext()) when (reader.nextName()) {
            "manifest" -> manifest = readManifest(reader)
            "settings" -> settings = readSettings(reader)
            "collections" -> readCollections(reader, collections)
            "tags" -> readTags(reader, tags)
            "clips" -> readClips(reader, clips)
            "clipTags" -> readLinks(reader, links)
            "rules" -> readRules(reader, rules)
            else -> reader.skipValue()
        }
        reader.endObject()
        val checked = requireNotNull(manifest) { "Backup manifest is missing" }
        require(checked.formatVersion in 1..PAYLOAD_VERSION) { "Unsupported backup version" }
        return BackupPayload(checked, clips, collections, tags, links, rules, settings)
    }

    private fun readSettings(reader: JsonReader): BackupSettings {
        var retention = -1; var custom = 0; var skip = true; var mode = ThemeMode.SYSTEM
        var dynamic = true; var accent = AccentPalette.VIOLET; var reduced = false; var language = AppLanguage.SYSTEM
        var fontScale = 1f
        reader.beginObject()
        while (reader.hasNext()) when (reader.nextName()) {
            "retentionMonths" -> retention = reader.nextInt()
            "customRetentionDays" -> custom = reader.nextInt()
            "skipSensitive" -> skip = reader.nextBoolean()
            "themeMode" -> mode = enumValueOr(reader.nextString(), ThemeMode.SYSTEM)
            "dynamicColor" -> dynamic = reader.nextBoolean()
            "accent" -> accent = enumValueOr(reader.nextString(), AccentPalette.VIOLET)
            "reducedMotion" -> reduced = reader.nextBoolean()
            "language" -> language = enumValueOr(reader.nextString(), AppLanguage.SYSTEM)
            "fontScale" -> fontScale = reader.nextDouble().toFloat().coerceIn(0.85f, 1.30f)
            else -> reader.skipValue()
        }
        reader.endObject()
        return BackupSettings(retention, custom, skip, ThemeSettings(mode, dynamic, accent, reduced, language, fontScale))
    }

    private fun readManifest(reader: JsonReader): BackupManifest {
        var version = 0; var createdAt = 0L; var clips = 0; var collections = 0; var tags = 0; var rules = 0
        reader.beginObject()
        while (reader.hasNext()) when (reader.nextName()) {
            "version" -> version = reader.nextInt()
            "createdAt" -> createdAt = reader.nextLong()
            "clipCount" -> clips = reader.nextInt()
            "collectionCount" -> collections = reader.nextInt()
            "tagCount" -> tags = reader.nextInt()
            "ruleCount" -> rules = reader.nextInt()
            else -> reader.skipValue()
        }
        reader.endObject()
        return BackupManifest(version, createdAt, clips, collections, tags, rules)
    }

    private fun readCollections(reader: JsonReader, output: MutableList<CollectionRecord>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var id = 0L; var name = ""; var color = "violet"; var order = 0
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "id" -> id = reader.nextLong(); "name" -> name = reader.nextString()
                "color" -> color = reader.nextString(); "order" -> order = reader.nextInt()
                else -> reader.skipValue()
            }
            reader.endObject(); if (name.isNotBlank()) output += CollectionRecord(id, name, color, order, 0)
        }
        reader.endArray()
    }

    private fun readTags(reader: JsonReader, output: MutableList<TagRecord>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var id = 0L; var name = ""; var color = "violet"
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "id" -> id = reader.nextLong(); "name" -> name = reader.nextString()
                "color" -> color = reader.nextString(); else -> reader.skipValue()
            }
            reader.endObject(); if (name.isNotBlank()) output += TagRecord(id, name, color, 0)
        }
        reader.endArray()
    }

    private fun readClips(reader: JsonReader, output: MutableList<ImportedClip>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var id = 0L; var content = ""; var title = ""; var note = ""; var first = 0L; var last = 0L
            var count = 1; var favorite = false; var pinned = false; var collectionId: Long? = null; var deletedAt: Long? = null
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "id" -> id = reader.nextLong(); "content" -> content = reader.nextString()
                "title" -> title = reader.nextString(); "note" -> note = reader.nextString()
                "first" -> first = reader.nextLong(); "last" -> last = reader.nextLong()
                "count" -> count = reader.nextInt(); "favorite" -> favorite = reader.nextBoolean()
                "pinned" -> pinned = reader.nextBoolean()
                "collectionId" -> collectionId = nullableLong(reader)
                "deletedAt" -> deletedAt = nullableLong(reader)
                else -> reader.skipValue()
            }
            reader.endObject()
            if (content.isNotBlank()) output += ImportedClip(
                BackupClipData(id, content, title, note, first, last, count, favorite, pinned, deletedAt), collectionId)
        }
        reader.endArray()
    }

    private fun readLinks(reader: JsonReader, output: MutableList<ClipTagLink>) {
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginArray(); val clipId = reader.nextLong(); val tagId = reader.nextLong()
            while (reader.hasNext()) reader.skipValue()
            reader.endArray(); output += ClipTagLink(clipId, tagId)
        }
        reader.endArray()
    }

    private fun readRules(reader: JsonReader, output: MutableList<CaptureRule>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var type = CaptureRule.Type.CONTAINS; var pattern = ""; var enabled = true
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "type" -> type = runCatching { CaptureRule.Type.valueOf(reader.nextString()) }.getOrDefault(CaptureRule.Type.CONTAINS)
                "pattern" -> pattern = reader.nextString(); "enabled" -> enabled = reader.nextBoolean()
                else -> reader.skipValue()
            }
            reader.endObject(); if (pattern.isNotBlank()) output += CaptureRule(0, type, pattern, enabled, 0, 0)
        }
        reader.endArray()
    }

    private fun nullableLong(reader: JsonReader): Long? =
        if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); null } else reader.nextLong()

    private inline fun <reified T : Enum<T>> enumValueOr(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val password = ByteArray(encoded.remaining()).also(encoded::get)
        val output = ByteArray(KEY_BYTES)
        try {
            val generator = Argon2BytesGenerator()
            generator.init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt).withMemoryAsKB(MEMORY_KIB).withIterations(ITERATIONS)
                .withParallelism(PARALLELISM).build())
            generator.generateBytes(password, output)
            return output
        } finally {
            password.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    private fun createHeader(salt: ByteArray, nonce: ByteArray): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC); output.writeInt(CONTAINER_VERSION); output.writeInt(MEMORY_KIB)
            output.writeInt(ITERATIONS); output.writeInt(PARALLELISM)
            output.writeInt(salt.size); output.write(salt); output.writeInt(nonce.size); output.write(nonce)
        }
        bytes.toByteArray()
    }

    private fun readHeader(input: DataInputStream): ParsedHeader {
        val magic = ByteArray(MAGIC.size); input.readFully(magic); require(magic.contentEquals(MAGIC)) { "Not a ClipVault backup" }
        val version = input.readInt(); require(version == CONTAINER_VERSION) { "Unsupported container version" }
        val memory = input.readInt(); val iterations = input.readInt(); val parallelism = input.readInt()
        require(memory == MEMORY_KIB && iterations == ITERATIONS && parallelism == PARALLELISM) { "Unsupported KDF parameters" }
        val saltSize = input.readInt(); require(saltSize == SALT_BYTES); val salt = ByteArray(saltSize); input.readFully(salt)
        val nonceSize = input.readInt(); require(nonceSize == NONCE_BYTES); val nonce = ByteArray(nonceSize); input.readFully(nonce)
        return ParsedHeader(salt, nonce, createHeader(salt, nonce))
    }

    private data class ParsedHeader(val salt: ByteArray, val nonce: ByteArray, val encoded: ByteArray)
    private data class ImportedClip(val data: BackupClipData, val collectionId: Long?)
    private data class BackupPayload(
        val manifest: BackupManifest,
        val clips: List<ImportedClip>,
        val collections: List<CollectionRecord>,
        val tags: List<TagRecord>,
        val links: List<ClipTagLink>,
        val rules: List<CaptureRule>,
        val settings: BackupSettings,
    )
    private data class BackupSettings(
        val retentionMonths: Int = -1,
        val customRetentionDays: Int = 0,
        val skipSensitive: Boolean = true,
        val theme: ThemeSettings = ThemeSettings(),
    )
}
