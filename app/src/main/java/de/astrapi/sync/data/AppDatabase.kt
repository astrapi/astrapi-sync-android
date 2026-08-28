package de.astrapi.sync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [KnownFileEntity::class, KnownDirEntity::class, FolderBindingEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "astrapi-sync.db",
                )
                    // App ist noch in aktiver Entwicklung, keine echten
                    // Installationen mit schützenswertem Bestand -- ein
                    // formales Migration()-Objekt für v1 -> v2 wäre
                    // Aufwand ohne Nutzen. Vor dem ersten echten Rollout
                    // sollte hier eine richtige Migration stehen.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
