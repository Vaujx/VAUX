package app.vaux.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.work.*
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in only. Fetches yt-dlp at runtime on the user's device. See LEGAL.md. */
object Downloader {
    private var ready = false
    private fun init(c: Context) { if (!ready) { YoutubeDL.getInstance().init(c); FFmpeg.getInstance().init(c); ready = true } }

    suspend fun update(c: Context): String = withContext(Dispatchers.IO) {
        init(c)
        val r = YoutubeDL.getInstance().updateYoutubeDL(c, YoutubeDL.UpdateChannel.STABLE)
        if (r == YoutubeDL.UpdateStatus.DONE) "yt-dlp updated" else "yt-dlp is up to date"
    }

    suspend fun download(c: Context, dao: VauxDao, url: String, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        init(c)
        val dir = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!.also { it.mkdirs() }
        val before = dir.listFiles()?.toSet() ?: emptySet()
        val req = YoutubeDLRequest(url).apply {
            addOption("-x"); addOption("--audio-format", "m4a"); addOption("--no-playlist")
            addOption("-o", dir.absolutePath + "/%(title)s.%(ext)s")
        }
        YoutubeDL.getInstance().execute(req) { p, _, _ -> onProgress(p) }
        val f: File = dir.listFiles()!!.first { it !in before }
        Library.importUri(c, dao, Uri.fromFile(f)); f.name
    }

    /** One weekly job: Wi-Fi only, battery not low. Never runs at app launch. */
    fun schedule(c: Context) = WorkManager.getInstance(c).enqueueUniquePeriodicWork("ytdlp-update", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<UpdateWorker>(7, TimeUnit.DAYS).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()).build())

    fun cancel(c: Context) { WorkManager.getInstance(c).cancelUniqueWork("ytdlp-update") }
}

class UpdateWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork() = try { Downloader.update(applicationContext); Result.success() } catch (_: Exception) { Result.retry() }
}
