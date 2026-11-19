package app.vaux

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.graphics.luminance
import kotlin.math.cos
import androidx.compose.animation.core.LinearEasing
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.vaux.data.*
import app.vaux.player.PlaybackService
import app.vaux.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PState(c: Player) {
    var playing by mutableStateOf(c.isPlaying)
    var title by mutableStateOf(c.mediaMetadata.title?.toString())
    var artist by mutableStateOf(c.mediaMetadata.artist?.toString() ?: "")
    var shuffle by mutableStateOf(c.shuffleModeEnabled)
    var repeat by mutableIntStateOf(c.repeatMode)
}

@Composable fun rememberPState(c: Player): PState {
    val s = remember(c) { PState(c) }
    DisposableEffect(c) {
        val l = object : Player.Listener {
            override fun onEvents(p: Player, e: Player.Events) {
                s.playing = p.isPlaying; s.title = p.mediaMetadata.title?.toString()
                s.artist = p.mediaMetadata.artist?.toString() ?: ""; s.shuffle = p.shuffleModeEnabled; s.repeat = p.repeatMode
            }
        }
        c.addListener(l); onDispose { c.removeListener(l) }
    }
    return s
}

@OptIn(ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("vaux", 0)
        Ink.load(prefs)
        if (prefs.getBoolean("dl", false)) Downloader.schedule(this)
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        f.addListener({ controller = f.get() }, ContextCompat.getMainExecutor(this))
        val dao = VauxDb.get(this).dao()
        setContent { VauxTheme { App(dao, prefs) } }
    }

    private fun play(list: List<Track>, start: Int) {
        val c = controller ?: return
        c.setMediaItems(list.map { MediaItem.Builder().setUri(it.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).build()).build() }, start, 0)
        c.prepare(); c.play()
    }

    @Composable private fun App(dao: VauxDao, prefs: SharedPreferences) {
        var screen by remember { mutableStateOf("intro") }
        BackHandler(screen == "now" || screen == "settings" || screen == "review") { screen = "lib" }
        val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        var found by remember { mutableStateOf<List<Track>>(emptyList()) }
        val scanPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) lifecycleScope.launch {
                found = Library.candidates(this@MainActivity, dao, prefs.getInt("minSec", 30), prefs.getInt("maxMin", 0))
                if (found.isEmpty()) Toast.makeText(this@MainActivity, "Nothing new found", Toast.LENGTH_SHORT).show() else screen = "review"
            }
        }
        when (screen) {
            "intro" -> KatanaIntro { screen = if (prefs.getBoolean("seen", false)) "lib" else "welcome" }
            "welcome" -> Box(Modifier.fillMaxSize().background(Ink.Black)) {
                Halftone(Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("V A U X", color = Ink.Paper, fontSize = 44.sp, fontWeight = FontWeight.Black)
                    Text("Your music. Your device.\nNothing else.", color = Ink.Tone20, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
                    Button({ prefs.edit().putBoolean("seen", true).apply(); screen = "lib"
                        if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(Ink.Paper, Ink.Black)) { Text("Enter") }
                }
            }
            "now" -> NowPlaying { screen = "lib" }
            "review" -> ReviewScreen(dao, found) { screen = "lib" }
            "settings" -> Settings(dao, prefs, { scanPerm.launch(permission) }) { screen = "lib" }
            else -> LibraryScreen(dao, { screen = "settings" }) { screen = "now" }
        }
    }

    @Composable private fun LibraryScreen(dao: VauxDao, onSettings: () -> Unit, onNow: () -> Unit) {
        var groupId by remember { mutableStateOf<Long?>(null) }
        var q by remember { mutableStateOf("") }
        val all by remember(groupId) { groupId?.let { dao.tracksIn(it) } ?: dao.tracks() }.collectAsState(emptyList())
        val tracks = all.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
        val groups by dao.groups().collectAsState(emptyList())
        var sel by remember(groupId) { mutableStateOf(setOf<Long>()) }
        var pickGroup by remember { mutableStateOf(false) }
        var confirm by remember { mutableStateOf(false) }
        var edit by remember { mutableStateOf(false) }
        var wipe by remember { mutableStateOf(false) }
        var delGroup by remember { mutableStateOf(false) }
        var newGroup by remember { mutableStateOf(0) }   // 0 none, 1 just create, 2 create + add selected
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            lifecycleScope.launch(Dispatchers.IO) { uris.forEach { u ->
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                Library.importUri(this@MainActivity, dao, u) } }
        }
        BackHandler(sel.isNotEmpty()) { sel = emptySet() }
        fun tog(id: Long) { sel = if (id in sel) sel - id else sel + id }
        Column(Modifier.fillMaxSize().background(Ink.Black)) {
            Box { Halftone(Modifier.fillMaxWidth().height(150.dp))
                if (sel.isEmpty()) Row(Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("V A U X", color = Ink.Paper, fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    TextButton(onSettings) { Text("⚙", color = Ink.Paper, fontSize = 22.sp) }
                    Button({ picker.launch(arrayOf("audio/*")) }, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(Ink.Paper, Ink.Black)) { Text("+ Add") }
                } else Column(Modifier.statusBarsPadding().padding(16.dp, 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${sel.size} selected", color = Ink.Paper, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton({ sel = emptySet() }) { Text("✕", color = Ink.Paper) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ sel = tracks.map { it.id }.toSet() }) { Text("All", color = Ink.Paper) }
                        TextButton({ edit = true }) { Text("Edit", color = Ink.Paper) }
                        TextButton({ pickGroup = true }) { Text("Group", color = Ink.Paper) }
                        TextButton({ confirm = true }) { Text("Remove", color = Ink.Paper) }
                    }
                } }
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp), singleLine = true, shape = RoundedCornerShape(50), placeholder = { Text("Search") })
            LazyRow(contentPadding = PaddingValues(24.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(groupId == null, { groupId = null }, { Text("All") }, shape = RoundedCornerShape(50)) }
                items(groups.size) { i -> val g = groups[i]; FilterChip(groupId == g.id, { groupId = g.id }, { Text(g.name) }, shape = RoundedCornerShape(50)) }
                item { AssistChip({ newGroup = 1 }, { Text("+ Group") }, shape = RoundedCornerShape(50)) }
                if (groupId != null) item { AssistChip({ delGroup = true }, { Text("Delete group") }, shape = RoundedCornerShape(50)) }
            }
            if (all.isEmpty()) Text("Nothing here yet.\nTap + Add to choose your songs.", color = Ink.Tone50, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).fillMaxWidth().padding(48.dp))
            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                    val on = t.id in sel
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (on) Ink.Paper else Ink.Tone80)
                        .combinedClickable(onClick = { if (sel.isNotEmpty()) tog(t.id) else { play(tracks, i); onNow() } }, onLongClick = { tog(t.id) }).padding(18.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = if (on) Ink.Black else Ink.Paper, maxLines = 1, fontWeight = FontWeight.SemiBold)
                            Text(t.artist, color = Ink.Tone50, maxLines = 1, fontSize = 13.sp)
                        }
                        Text(if (on) "✓" else "%d:%02d".format(t.durationMs / 60000, t.durationMs / 1000 % 60), color = if (on) Ink.Black else Ink.Tone50, fontSize = 13.sp)
                    }
                }
            }
            controller?.let { c -> val s = rememberPState(c)
                if (s.title != null) Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).clip(RoundedCornerShape(36.dp)).background(Ink.Paper)
                    .clickable { onNow() }.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title ?: "", color = Ink.Black, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                    TextButton({ if (s.playing) c.pause() else c.play() }) { Text(if (s.playing) "❚❚" else "▶", color = Ink.Black) }
                    TextButton({ c.seekToNextMediaItem() }) { Text("▶▶", color = Ink.Black) }
                } else Spacer(Modifier.navigationBarsPadding())
            }
        }
        if (pickGroup) AlertDialog(onDismissRequest = { pickGroup = false }, confirmButton = {}, title = { Text("Add ${sel.size} to group") },
            text = { Column { groups.forEach { g -> TextButton({ val ids = sel; lifecycleScope.launch { ids.forEach { dao.addToGroup(GroupTrack(g.id, it)) } }; sel = emptySet(); pickGroup = false }) { Text(g.name) } }
                TextButton({ pickGroup = false; newGroup = 2 }) { Text("+ New group") } } })
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, confirmButton = {}, title = { Text("Remove ${sel.size} songs?") },
            text = { Column {
                if (groupId != null) TextButton({ val ids = sel.toList(); val g = groupId!!; lifecycleScope.launch { ids.chunked(500).forEach { dao.removeFromGroup(g, it) } }; sel = emptySet(); confirm = false }) { Text("Remove from this group only") }
                TextButton({ confirm = false; wipe = true }) { Text("Remove from VAUX") }
                TextButton({ confirm = false }) { Text("Cancel") } } })
        if (edit) { val one = if (sel.size == 1) all.firstOrNull { it.id == sel.first() } else null
            EditDialog(one, sel.size, { edit = false }) { t, a ->
                val ids = sel.toList()
                lifecycleScope.launch { if (one != null) dao.edit(one.id, t, a) else ids.chunked(500).forEach { dao.setArtist(it, a) } }
                sel = emptySet(); edit = false } }
        if (wipe) ConfirmVaux("Remove ${sel.size} songs from VAUX?", "They are only removed from VAUX. The files on your phone are not deleted.", "Remove", { wipe = false }) {
            val ids = sel.toList(); lifecycleScope.launch { ids.chunked(500).forEach { dao.unlinkAll(it); dao.removeAll(it) } }; sel = emptySet(); wipe = false }
        if (delGroup) { val g = groups.firstOrNull { it.id == groupId }
            if (g != null) ConfirmVaux("Delete group \"${g.name}\"?", "The group is removed. Your songs stay in VAUX.", "Delete group", { delGroup = false }) {
                lifecycleScope.launch { dao.clearGroup(g.id); dao.deleteGroup(g.id) }; groupId = null; delGroup = false } else delGroup = false }
        if (newGroup > 0) NewGroupDialog({ newGroup = 0 }) { n ->
            val ids = sel; val add = newGroup == 2
            lifecycleScope.launch { val gid = dao.addGroup(Group(name = n)); if (add) ids.forEach { dao.addToGroup(GroupTrack(gid, it)) } }
            if (add) sel = emptySet(); newGroup = 0 }
    }

    @Composable private fun ReviewScreen(dao: VauxDao, found: List<Track>, onDone: () -> Unit) {
        val groups by dao.groups().collectAsState(emptyList())
        var sel by remember { mutableStateOf(found.map { it.uri }.toSet()) }
        var gid by remember { mutableStateOf<Long?>(null) }
        var newGroup by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(Ink.Black).statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onDone) { Text("✕", color = Ink.Paper) }
                Text("Found ${found.size} new songs", color = Ink.Paper, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton({ sel = if (sel.size == found.size) emptySet() else found.map { it.uri }.toSet() }) { Text(if (sel.size == found.size) "None" else "All", color = Ink.Paper) }
            }
            Text("Tick what you want in VAUX. Optionally put them straight into a group.", color = Ink.Tone50, fontSize = 13.sp)
            LazyRow(contentPadding = PaddingValues(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(gid == null, { gid = null }, { Text("No group") }, shape = RoundedCornerShape(50)) }
                items(groups.size) { i -> val g = groups[i]; FilterChip(gid == g.id, { gid = g.id }, { Text(g.name) }, shape = RoundedCornerShape(50)) }
                item { AssistChip({ newGroup = true }, { Text("+ Group") }, shape = RoundedCornerShape(50)) }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(found.size, key = { found[it].uri }) { i -> val t = found[i]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ink.Tone80)
                        .clickable { sel = if (t.uri in sel) sel - t.uri else sel + t.uri }.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(t.uri in sel, null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) { Text(t.title, color = Ink.Paper, maxLines = 1); Text(t.artist, color = Ink.Tone50, fontSize = 12.sp, maxLines = 1) }
                        Text("%d:%02d".format(t.durationMs / 60000, t.durationMs / 1000 % 60), color = Ink.Tone50, fontSize = 12.sp)
                    } }
            }
            Button({ val chosen = found.filter { it.uri in sel }; val g = gid
                lifecycleScope.launch { chosen.forEach { t -> val id = dao.insert(t); if (g != null) dao.addToGroup(GroupTrack(g, if (id > 0) id else dao.idOf(t.uri))) }
                    Toast.makeText(this@MainActivity, "Added ${chosen.size}", Toast.LENGTH_SHORT).show(); onDone() } },
                Modifier.fillMaxWidth().padding(top = 12.dp), enabled = sel.isNotEmpty(), shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(Ink.Paper, Ink.Black)) { Text("Add ${sel.size} to VAUX") }
        }
        if (newGroup) NewGroupDialog({ newGroup = false }) { n -> lifecycleScope.launch { gid = dao.addGroup(Group(name = n)) }; newGroup = false }
    }

    @Composable private fun NowPlaying(onBack: () -> Unit) {
        val c = controller ?: return
        val s = rememberPState(c)
        var pos by remember { mutableLongStateOf(0L) }; var dur by remember { mutableLongStateOf(1L) }
        var drag by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(c) { while (true) { pos = c.currentPosition; dur = c.duration.coerceAtLeast(1); delay(500) } }
        fun t(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
        Box(Modifier.fillMaxSize().background(Ink.Black)) {
            Halftone(Modifier.fillMaxWidth().height(320.dp).align(Alignment.BottomCenter))
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onBack, Modifier.align(Alignment.Start)) { Text("⌄ Library", color = Ink.Paper) }
                Box(Modifier.padding(vertical = 24.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(36.dp)).background(Ink.Paper), contentAlignment = Alignment.Center) {
                    Halftone(Modifier.fillMaxSize(), Ink.Tone20)
                    Text("V", color = Ink.Black, fontSize = 120.sp, fontWeight = FontWeight.Black)
                }
                Text(s.title ?: "", color = Ink.Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(s.artist, color = Ink.Tone50)
                Slider(drag ?: (pos.toFloat() / dur), { drag = it }, Modifier.padding(top = 16.dp),
                    onValueChangeFinished = { drag?.let { c.seekTo((it * dur).toLong()) }; drag = null },
                    colors = SliderDefaults.colors(Ink.Paper, Ink.Paper, inactiveTrackColor = Ink.Tone80))
                Row(Modifier.fillMaxWidth()) { Text(t(pos), color = Ink.Tone50, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(t(dur), color = Ink.Tone50, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ c.shuffleModeEnabled = !s.shuffle }) { Text("⇄", fontSize = 22.sp, color = if (s.shuffle) Ink.Paper else Ink.Tone50) }
                    TextButton({ c.seekToPreviousMediaItem() }) { Text("◀◀", color = Ink.Paper, fontSize = 20.sp) }
                    Button({ if (s.playing) c.pause() else c.play() }, Modifier.size(72.dp), shape = RoundedCornerShape(50), contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(Ink.Paper, Ink.Black)) { Text(if (s.playing) "❚❚" else "▶", fontSize = 22.sp) }
                    TextButton({ c.seekToNextMediaItem() }) { Text("▶▶", color = Ink.Paper, fontSize = 20.sp) }
                    TextButton({ c.repeatMode = when (s.repeat) { Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF; else -> Player.REPEAT_MODE_ALL } }) {
                        Text(if (s.repeat == Player.REPEAT_MODE_ONE) "⟲1" else "⟲", fontSize = 22.sp, color = if (s.repeat == Player.REPEAT_MODE_OFF) Ink.Tone50 else Ink.Paper) }
                }
            }
        }
    }

    @Composable private fun Settings(dao: VauxDao, prefs: SharedPreferences, onScan: () -> Unit, onBack: () -> Unit) {
        var dl by remember { mutableStateOf(prefs.getBoolean("dl", false)) }
        var consent by remember { mutableStateOf(false) }
        var url by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        var minSec by remember { mutableIntStateOf(prefs.getInt("minSec", 30)) }
        var maxMin by remember { mutableIntStateOf(prefs.getInt("maxMin", 0)) }
        val cv = remember { mutableStateListOf(Ink.hex(Ink.Black), Ink.hex(Ink.Paper), Ink.hex(Ink.Tone80), Ink.hex(Ink.Tone50)) }
        var applyDlg by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(Ink.Black).statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onBack) { Text("← Back", color = Ink.Paper) }
            Text("Settings", color = Ink.Paper, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Ink.Tone80).padding(20.dp)) {
                Text("Scan all music", color = Ink.Paper, fontWeight = FontWeight.SemiBold)
                Text("Optional. Finds music on your phone, then lets you choose what to add before anything goes into VAUX.", color = Ink.Tone50, fontSize = 13.sp)
                Text("Skip songs shorter than $minSec s", color = Ink.Paper, modifier = Modifier.padding(top = 12.dp))
                Slider(minSec.toFloat(), { minSec = it.roundToInt() }, valueRange = 0f..300f, onValueChangeFinished = { prefs.edit().putInt("minSec", minSec).apply() },
                    colors = SliderDefaults.colors(Ink.Paper, Ink.Paper, inactiveTrackColor = Ink.Tone50))
                Text(if (maxMin == 0) "No maximum length" else "Skip songs longer than $maxMin min", color = Ink.Paper)
                Slider(maxMin.toFloat(), { maxMin = it.roundToInt() }, valueRange = 0f..60f, onValueChangeFinished = { prefs.edit().putInt("maxMin", maxMin).apply() },
                    colors = SliderDefaults.colors(Ink.Paper, Ink.Paper, inactiveTrackColor = Ink.Tone50))
                Button(onScan, Modifier.padding(top = 12.dp), colors = ButtonDefaults.buttonColors(Ink.Paper, Ink.Black)) { Text("Scan & review") }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Ink.Tone80).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Downloader (yt-dlp)", color = Ink.Paper, fontWeight = FontWeight.SemiBold)
                        Text("Off by default. Auto-updates weekly on Wi-Fi.", color = Ink.Tone50, fontSize = 13.sp) }
                    Switch(dl, { if (it) consent = true else { dl = false; prefs.edit().putBoolean("dl", false).apply(); Downloader.cancel(this@MainActivity) } })
                }
                if (dl) {
                    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true, shape = RoundedCornerShape(50), placeholder = { Text("Paste a link") })
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ lifecycleScope.launch { status = "Working…"
                            status = try { "Added: " + Downloader.download(this@MainActivity, dao, url.trim()) { p -> status = "Downloading ${p.toInt()}%" } }
                                     catch (e: Exception) { "Failed: ${e.message?.take(120)}" } } },
                            colors = ButtonDefaults.buttonColors(Ink.Paper, Ink.Black)) { Text("Download") }
                        OutlinedButton({ lifecycleScope.launch { status = "Updating…"
                            status = try { Downloader.update(this@MainActivity) } catch (e: Exception) { "Failed: ${e.message?.take(120)}" } } }) { Text("Update now") }
                    }
                    Text(status, color = Ink.Tone20, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            val pc = cv.map { Ink.parse(it) }; val valid = pc.all { it != null }
            fun ratio(a: Color, b: Color): Float { val x = a.luminance() + 0.05f; val y = b.luminance() + 0.05f; return maxOf(x, y) / minOf(x, y) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Ink.Tone80).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Colors", color = Ink.Paper, fontWeight = FontWeight.SemiBold)
                Text("Type hex codes like 0B0B0C. The preview updates as you type.", color = Ink.Tone50, fontSize = 13.sp)
                listOf("Background", "Text", "Cards", "Muted text").forEachIndexed { i, label ->
                    OutlinedTextField(cv[i], { cv[i] = it.take(7) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
                        prefix = { Text("#") }, isError = pc[i] == null, shape = RoundedCornerShape(50))
                }
                if (valid) {
                    ColorPreview(pc[0]!!, pc[1]!!, pc[2]!!, pc[3]!!)
                    if (ratio(pc[1]!!, pc[0]!!) < 4.5f || ratio(pc[1]!!, pc[2]!!) < 4.5f) Text("Low contrast: text may be hard to read.", color = Ink.Tone20, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ applyDlg = true }, enabled = valid, colors = ButtonDefaults.buttonColors(Ink.Paper, Ink.Black)) { Text("Apply") }
                    OutlinedButton({ Ink.defaults.forEachIndexed { i, d -> cv[i] = d } }) { Text("Reset") }
                }
            }
            Text("VAUX 1.0 · No accounts, ads, analytics or servers. Not affiliated with YouTube or yt-dlp. You are responsible for what you download and must follow copyright law and site terms.", color = Ink.Tone50, fontSize = 12.sp)
        }
        if (applyDlg && cv.all { Ink.parse(it) != null }) { val c = cv.map { Ink.parse(it)!! }
            ConfirmVaux("Apply these colors?", "This changes the look of the whole app. You can reset later.", "Apply", { applyDlg = false },
                extra = { ColorPreview(c[0], c[1], c[2], c[3]) }) { Ink.set(c[0], c[1], c[2], c[3]); Ink.save(prefs); applyDlg = false } }
        if (consent) AlertDialog(onDismissRequest = { consent = false },
            title = { Text("Before you enable this") },
            text = { Text("The downloader fetches the open-source yt-dlp tool onto your phone. Only download content you have the right to keep. Downloading copyrighted material, or breaking a site's terms, is your responsibility alone, not the developer's.") },
            confirmButton = { TextButton({ dl = true; consent = false; prefs.edit().putBoolean("dl", true).apply(); Downloader.schedule(this@MainActivity) }) { Text("I understand") } },
            dismissButton = { TextButton({ consent = false }) { Text("Cancel") } })
    }
}

@Composable
fun NewGroupDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var n by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New group") },
        text = { OutlinedTextField(n, { n = it }, singleLine = true) },
        confirmButton = { TextButton({ if (n.isNotBlank()) onCreate(n.trim()) else onDismiss() }) { Text("Create") } })
}

/** ~2s intro: three fast katana slashes cut across the logo, it recoils on every hit, then the wordmark appears. Tap to skip. */
@Composable
fun KatanaIntro(onDone: () -> Unit) {
    val slash = remember { List(3) { Animatable(0f) } }
    val shake = remember { Animatable(0f) }; val scar = remember { Animatable(0f) }; val word = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    fun finish() { if (!done) { done = true; onDone() } }
    LaunchedEffect(Unit) {
        delay(300)
        for (i in 0..2) {
            launch { slash[i].animateTo(1.45f, tween(210, easing = LinearEasing)) }
            delay(100); shake.snapTo(1f); launch { shake.animateTo(0f, tween(220)) }; delay(70)
        }
        delay(150); launch { scar.animateTo(1f, tween(600)) }
        word.animateTo(1f, tween(500))
        delay(600); finish()
    }
    Column(Modifier.fillMaxSize().background(Color.Black)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(220.dp)) {
            val dx = sin(shake.value * 30f) * shake.value * 12f; val dy = cos(shake.value * 30f) * shake.value * 6f
            Image(painterResource(R.drawable.ic_fg), null, Modifier.fillMaxSize().offset { IntOffset(dx.roundToInt(), dy.roundToInt()) })
            Canvas(Modifier.fillMaxSize()) {
                val m = size.minDimension; val c = center
                val angles = floatArrayOf(-38f, 24f, -8f); val offs = floatArrayOf(-0.12f, 0.10f, 0f)
                for (i in 0..2) {
                    val p = slash[i].value; if (p <= 0f) continue
                    val a = Math.toRadians(angles[i].toDouble()); val d = Offset(cos(a).toFloat(), sin(a).toFloat()); val n = Offset(-d.y, d.x)
                    val s = c + n * (offs[i] * m) - d * (m * 0.85f); val e = s + d * (m * 1.7f)
                    val head = s + (e - s) * p.coerceAtMost(1f); val tail = s + (e - s) * (p - 0.45f).coerceIn(0f, 1f)
                    val mid = (head + tail) * 0.5f; val w = 6.dp.toPx()
                    val blade = Path().apply { moveTo(tail.x, tail.y); lineTo(mid.x + n.x * w, mid.y + n.y * w); lineTo(head.x, head.y); lineTo(mid.x - n.x * w, mid.y - n.y * w); close() }
                    drawPath(blade, Color.White)
                    drawLine(Color.White.copy(alpha = 0.55f * (1f - scar.value)), s, head, 1.5.dp.toPx())   // lingering cut line
                }
            }
        }
        Text("V A U X", color = Ink.Paper, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 28.dp).alpha(word.value))
    }
}

@Composable
fun ConfirmVaux(title: String, body: String, confirmLabel: String, onDismiss: () -> Unit, extra: @Composable () -> Unit = {}, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(body); extra()
            OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text("Type VAUX to confirm") }) } },
        confirmButton = { TextButton(onConfirm, enabled = typed.trim().equals("vaux", ignoreCase = true)) { Text(confirmLabel) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun EditDialog(one: Track?, count: Int, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var t by remember { mutableStateOf(one?.title ?: "") }
    var a by remember { mutableStateOf(one?.artist ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (one != null) "Edit song" else "Set artist for $count songs") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (one != null) OutlinedTextField(t, { t = it }, label = { Text("Title") }, singleLine = true)
            OutlinedTextField(a, { a = it }, label = { Text("Artist") }, singleLine = true) } },
        confirmButton = { TextButton({ if (a.isNotBlank() && (one == null || t.isNotBlank())) onSave(t.trim(), a.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun ColorPreview(bg: Color, text: Color, card: Color, muted: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("V A U X", color = text, fontWeight = FontWeight.Black)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(12.dp)) {
            Text("Song title", color = text); Text("Artist", color = muted, fontSize = 12.sp) }
        Box(Modifier.clip(RoundedCornerShape(50)).background(text).padding(horizontal = 20.dp, vertical = 8.dp)) { Text("▶ Play", color = bg) }
    }
}
