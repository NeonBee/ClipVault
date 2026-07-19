package dev.clipvault.app.backup

data class BackupManifest(
    val formatVersion: Int,
    val createdAt: Long,
    val clipCount: Int,
    val collectionCount: Int,
    val tagCount: Int,
    val ruleCount: Int,
)

data class ImportResult(
    val importedClips: Int,
    val importedCollections: Int,
    val importedTags: Int,
    val importedRules: Int,
)
