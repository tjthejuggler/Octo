package com.example.octo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        VideoEntity::class,
        VideoBodyPartCrossRef::class,
        SessionEntity::class,
        BodyPartStretchEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class OctoDatabase : RoomDatabase() {

    abstract fun videoDao(): VideoDao
    abstract fun sessionDao(): SessionDao
    abstract fun bodyPartDao(): BodyPartDao
    abstract fun videoBodyPartDao(): VideoBodyPartDao

    companion object {
        const val NAME = "octo.db"
    }
}

/** Process-wide singletons; owned by [com.example.octo.OctoApplication.AppContainer]. */
object DatabaseProvider {

    @Volatile
    private var instance: OctoDatabase? = null

    fun get(context: Context): OctoDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OctoDatabase::class.java,
                OctoDatabase.NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
}
