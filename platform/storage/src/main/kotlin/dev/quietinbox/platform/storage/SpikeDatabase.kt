package dev.quietinbox.platform.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "spike")
data class SpikeEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val text: String)

@Dao
interface SpikeDao {
    @Insert
    suspend fun insert(entity: SpikeEntity): Long

    @Query("SELECT COUNT(*) FROM spike")
    suspend fun count(): Int
}

@Database(entities = [SpikeEntity::class], version = 1, exportSchema = true)
abstract class SpikeDatabase : RoomDatabase() {
    abstract fun spikeDao(): SpikeDao

    companion object {
        fun open(context: Context, key: ByteArray): SpikeDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, SpikeDatabase::class.java, "spike.db")
                .openHelperFactory(SupportOpenHelperFactory(key))
                .build()
        }
    }
}
