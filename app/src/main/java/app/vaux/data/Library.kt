package app.vaux.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Library {
    suspend fun importUri(c: Context, dao: VauxDao, u: Uri) = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c, u)
            dao.add(Track(uri = u.toString(),
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: u.lastPathSegment ?: "Untitled",
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown",
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0))
        } catch (_: Exception) {} finally { r.release() }
    }

    /** Optional scan: returns NEW songs within the length limits. Nothing is added until the user confirms. */
    suspend fun candidates(c: Context, dao: VauxDao, minSec: Int, maxMin: Int): List<Track> = withContext(Dispatchers.IO) {
        val have = dao.uris().toSet(); val out = ArrayList<Track>()
        c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { q ->
            while (q.moveToNext()) {
                val dur = q.getLong(3)
                if (dur < minSec * 1000L || (maxMin > 0 && dur > maxMin * 60000L)) continue
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, q.getLong(0)).toString()
                if (uri !in have) out.add(Track(uri = uri, title = q.getString(1) ?: "Untitled", artist = q.getString(2) ?: "Unknown", durationMs = dur))
            }
        }
        out
    }
}
