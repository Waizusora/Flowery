package com.waizusora.flowery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.Locale

/**
 * Persistent catalogue of USB Audio Class descriptor profiles.
 *
 * Standard USB descriptors do not reliably identify the silicon chipset. Records are
 * therefore grouped by VID/PID and a descriptor fingerprint, not by an assumed chip name.
 */
internal class UsbDacProfileDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    data class StoredProfile(
        val profileKey: String,
        val descriptorFingerprint: String,
        val descriptorHex: String,
        val seenCount: Long,
        val totalProfiles: Int,
        val previouslyObserved: Boolean,
        val lastSeenEpochMs: Long
    ) {
        fun diagnosticSummary(): String = buildString {
            appendLine("Database profile key: $profileKey")
            appendLine("Descriptor SHA-256: $descriptorFingerprint")
            appendLine("Observed connections: $seenCount")
            appendLine("Profiles stored in local database: $totalProfiles")
            appendLine("Previously observed descriptor profile: $previouslyObserved")
            appendLine("Last observed (epoch ms): $lastSeenEpochMs")
            appendLine("Raw configuration descriptor bytes (hex):")
            appendLine(descriptorHex)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PROFILES (
                profile_key TEXT PRIMARY KEY NOT NULL,
                vendor_id INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                manufacturer TEXT,
                product TEXT,
                descriptor_fingerprint TEXT NOT NULL,
                descriptor_hex TEXT NOT NULL,
                descriptor_report TEXT NOT NULL,
                first_seen_epoch_ms INTEGER NOT NULL,
                last_seen_epoch_ms INTEGER NOT NULL,
                seen_count INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_usb_dac_vid_pid ON $TABLE_PROFILES(vendor_id, product_id)"
        )
        db.execSQL(
            "CREATE INDEX idx_usb_dac_fingerprint ON $TABLE_PROFILES(descriptor_fingerprint)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema changes must preserve previously captured descriptor evidence.
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_usb_dac_fingerprint ON $TABLE_PROFILES(descriptor_fingerprint)"
            )
        }
    }

    fun record(
        vendorId: Int,
        productId: Int,
        manufacturer: String?,
        product: String?,
        rawDescriptors: ByteArray,
        descriptorReport: String
    ): StoredProfile {
        require(rawDescriptors.isNotEmpty()) { "USB configuration descriptors must not be empty." }
        val fingerprint = sha256(rawDescriptors)
        val vidPid = "%04X:%04X".format(Locale.ROOT, vendorId, productId)
        val profileKey = "$vidPid:$fingerprint"
        val rawHex = rawDescriptors.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        val now = System.currentTimeMillis()

        var previouslyObserved = false
        var nextSeenCount = 1L
        writableDatabase.beginTransaction()
        try {
            val existing = readableDatabase.rawQuery(
                "SELECT first_seen_epoch_ms, seen_count FROM $TABLE_PROFILES WHERE profile_key = ?",
                arrayOf(profileKey)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getLong(1) else null
            }
            previouslyObserved = existing != null
            val firstSeen = existing?.first ?: now
            nextSeenCount = (existing?.second ?: 0L) + 1L
            val values = ContentValues().apply {
                put("profile_key", profileKey)
                put("vendor_id", vendorId)
                put("product_id", productId)
                put("manufacturer", manufacturer)
                put("product", product)
                put("descriptor_fingerprint", fingerprint)
                put("descriptor_hex", rawHex)
                put("descriptor_report", descriptorReport)
                put("first_seen_epoch_ms", firstSeen)
                put("last_seen_epoch_ms", now)
                put("seen_count", nextSeenCount)
            }
            writableDatabase.insertWithOnConflict(
                TABLE_PROFILES,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return StoredProfile(profileKey, fingerprint, rawHex, nextSeenCount, profileCount(), previouslyObserved, now)
    }

    private fun existingCountFor(profileKey: String): Long =
        readableDatabase.rawQuery(
            "SELECT seen_count FROM $TABLE_PROFILES WHERE profile_key = ?",
            arrayOf(profileKey)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 1L }

    fun profileCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_PROFILES", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val DATABASE_NAME = "usb_dac_profiles.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_PROFILES = "usb_dac_profiles"
    }
}
