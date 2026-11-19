package app.vaux.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index("uri", unique = true)])
data class Track(@PrimaryKey(autoGenerate = true) val id: Long = 0,
                 val uri: String, val title: String, val artist: String, val durationMs: Long)

@Entity data class Group(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(primaryKeys = ["groupId", "trackId"])
data class GroupTrack(val groupId: Long, val trackId: Long)

@Dao interface VauxDao {
    @Query("SELECT * FROM Track ORDER BY title COLLATE NOCASE") fun tracks(): Flow<List<Track>>
    @Query("SELECT t.* FROM Track t JOIN GroupTrack g ON g.trackId = t.id WHERE g.groupId = :gid ORDER BY t.title COLLATE NOCASE")
    fun tracksIn(gid: Long): Flow<List<Track>>
    @Query("SELECT * FROM `Group` ORDER BY name COLLATE NOCASE") fun groups(): Flow<List<Group>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(t: Track)
    @Insert suspend fun addGroup(g: Group): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addToGroup(gt: GroupTrack)
    @Query("DELETE FROM Track WHERE id = :id") suspend fun remove(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(t: Track): Long
    @Query("UPDATE Track SET title = :t, artist = :a WHERE id = :id") suspend fun edit(id: Long, t: String, a: String)
    @Query("UPDATE Track SET artist = :a WHERE id IN (:ids)") suspend fun setArtist(ids: List<Long>, a: String)
    @Query("DELETE FROM GroupTrack WHERE groupId = :gid") suspend fun clearGroup(gid: Long)
    @Query("DELETE FROM `Group` WHERE id = :gid") suspend fun deleteGroup(gid: Long)
    @Query("SELECT id FROM Track WHERE uri = :u") suspend fun idOf(u: String): Long
    @Query("SELECT uri FROM Track") suspend fun uris(): List<String>
    @Query("DELETE FROM Track WHERE id IN (:ids)") suspend fun removeAll(ids: List<Long>)
    @Query("DELETE FROM GroupTrack WHERE trackId IN (:ids)") suspend fun unlinkAll(ids: List<Long>)
    @Query("DELETE FROM GroupTrack WHERE groupId = :gid AND trackId IN (:ids)") suspend fun removeFromGroup(gid: Long, ids: List<Long>)
}

@Database(entities = [Track::class, Group::class, GroupTrack::class], version = 1, exportSchema = false)
abstract class VauxDb : RoomDatabase() {
    abstract fun dao(): VauxDao
    companion object {
        @Volatile private var i: VauxDb? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, VauxDb::class.java, "vaux.db").build().also { i = it }
        }
    }
}
