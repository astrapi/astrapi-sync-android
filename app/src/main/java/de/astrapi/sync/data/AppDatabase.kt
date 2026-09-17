package de.astrapi.sync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        KnownFileEntity::class,
        KnownDirEntity::class,
        FolderBindingEntity::class,
        PendingConflictEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** lastSyncedAt fuer "zuletzt synchronisiert"-Anzeige. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folder_bindings ADD COLUMN lastSyncedAt INTEGER")
            }
        }

        /** Ordnergruppen (T-313-SYNC, in v3 spaeter wieder verworfen). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folder_bindings ADD COLUMN groupId TEXT")
                db.execSQL("ALTER TABLE folder_bindings ADD COLUMN groupDescription TEXT")
                db.execSQL("ALTER TABLE folder_bindings ADD COLUMN groupColor TEXT")
            }
        }

        /** Ordnergruppen durch einzelnes color-Feld ersetzt (T-317-SYNC).
         * minSdk 26 kennt noch kein ALTER TABLE ... DROP COLUMN, daher
         * Tabelle per CREATE/INSERT-SELECT/DROP/RENAME neu aufbauen statt
         * einzelner ALTERs. groupColor wird best-effort nach color
         * uebernommen. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE folder_bindings_new (" +
                        "`folderId` TEXT NOT NULL, `treeUri` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `lastSyncedAt` INTEGER, " +
                        "`color` TEXT, PRIMARY KEY(`folderId`))",
                )
                db.execSQL(
                    "INSERT INTO folder_bindings_new " +
                        "(folderId, treeUri, description, lastSyncedAt, color) " +
                        "SELECT folderId, treeUri, description, lastSyncedAt, groupColor " +
                        "FROM folder_bindings",
                )
                db.execSQL("DROP TABLE folder_bindings")
                db.execSQL("ALTER TABLE folder_bindings_new RENAME TO folder_bindings")
            }
        }

        /** Neue Tabelle für nutzergesteuerte Konfliktauflösung -- die
         * Engine legt bei einem erkannten Konflikt hier eine Zeile an,
         * statt ihn wie bisher sofort automatisch aufzulösen (siehe
         * Entities.kt-Doc-Kommentar). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE pending_conflicts (" +
                        "`folderId` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                        "`localSha256` TEXT NOT NULL, `localSize` INTEGER NOT NULL, " +
                        "`remoteSha256` TEXT NOT NULL, `remoteSize` INTEGER NOT NULL, " +
                        "`detectedAt` INTEGER NOT NULL, PRIMARY KEY(`folderId`, `path`))",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "astrapi-sync.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    // Nur fuer den Fall eines manuellen Downgrades (aeltere
                    // APK ueber neuere installiert) -- dafuer gibt es keine
                    // sinnvolle Migration, aber Vorwaertsupdates duerfen nie
                    // mehr destruktiv sein (siehe T-336-SYNC).
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
