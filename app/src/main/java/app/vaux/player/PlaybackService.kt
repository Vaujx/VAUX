package app.vaux.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

// Lives only while audio plays; stops itself when swiped away and paused.
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).setHandleAudioBecomingNoisy(true).build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        session = MediaSession.Builder(this, player).build()
    }
    override fun onGetSession(info: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() { session?.run { player.release(); release() }; session = null; super.onDestroy() }
}
