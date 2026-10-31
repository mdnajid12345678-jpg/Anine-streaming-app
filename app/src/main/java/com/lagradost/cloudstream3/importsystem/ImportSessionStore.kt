package com.lagradost.cloudstream3.importsystem

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable, app-private storage for one resumable import scan.
 *
 * The cursor is committed only after the engine has classified a page. Pending canonical records
 * are stored independently so process death cannot discard already loaded metadata or playable
 * source links. The caller persists only public provider data and never the Supabase caller key.
 */
class ImportSessionStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val lock = Any()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $SESSION_TABLE (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                payload TEXT NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE $PENDING_TABLE (
                canonical_key TEXT PRIMARY KEY NOT NULL,
                payload TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first durable import-session schema. Future versions must migrate these
        // two tables rather than clearing user data.
        if (oldVersion < 1) onCreate(db)
    }

    fun loadSession(): ImportScanSession? = synchronized(lock) {
        val payload = readableDatabase.rawQuery(
            "SELECT payload FROM $SESSION_TABLE WHERE id = 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: return@synchronized null

        val stored = runCatching { json.decodeFromString<ImportScanSession>(payload) }
            .getOrNull() ?: return@synchronized null
        if (stored.status != ImportSessionStatus.RUNNING) return@synchronized stored

        // A process death during a run is a safe pause: the last committed page cursor is retained.
        stored.copy(
            status = ImportSessionStatus.PAUSED,
            progress = stored.progress.copy(
                phase = ImportPhase.PAUSED,
                message = "Previous scan restored; resume from the last saved page",
            ),
        ).also(::saveSession)
    }

    fun saveSession(session: ImportScanSession) = synchronized(lock) {
        val values = ContentValues().apply {
            put("id", 1)
            put("payload", json.encodeToString(session))
        }
        writableDatabase.insertWithOnConflict(
            SESSION_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Idempotent replace by canonical key; source arrays already contain the merged providers. */
    fun savePendingContent(content: CanonicalContent) = synchronized(lock) {
        val previousPayload = readableDatabase.rawQuery(
            "SELECT payload FROM $PENDING_TABLE WHERE canonical_key = ?",
            arrayOf(content.canonicalKey),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val previous = previousPayload?.let {
            runCatching { json.decodeFromString<CanonicalContent>(it) }.getOrNull()
        }
        val value = previous?.let { existing ->
            runCatching { ContentMerger.merge(existing, content).content }.getOrDefault(content)
        } ?: content
        val values = ContentValues().apply {
            put("canonical_key", value.canonicalKey)
            put("payload", json.encodeToString(value))
            put("updated_at", value.updatedAt)
        }
        writableDatabase.insertWithOnConflict(
            PENDING_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun loadPendingContents(): List<CanonicalContent> = synchronized(lock) {
        readableDatabase.rawQuery(
            "SELECT payload FROM $PENDING_TABLE ORDER BY canonical_key",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    runCatching { json.decodeFromString<CanonicalContent>(cursor.getString(0)) }
                        .getOrNull()?.let(::add)
                }
            }
        }
    }

    fun clearPendingContents() = synchronized(lock) {
        writableDatabase.delete(PENDING_TABLE, null, null)
    }

    /** Explicitly discards a scan session and its unimported local batch. */
    fun clearAll() = synchronized(lock) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete(PENDING_TABLE, null, null)
            writableDatabase.delete(SESSION_TABLE, null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private companion object {
        const val DATABASE_NAME = "cloudstream_import_sessions.db"
        const val DATABASE_VERSION = 1
        const val SESSION_TABLE = "import_scan_session"
        const val PENDING_TABLE = "import_scan_pending_contents"
    }
}
