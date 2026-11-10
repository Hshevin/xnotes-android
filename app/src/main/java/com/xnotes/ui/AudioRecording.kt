package com.xnotes.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.R
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.platform.AudioFiles
import com.xnotes.platform.AudioRecorder
import com.xnotes.platform.AudioRemux
import com.xnotes.platform.RecordingService
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** What a recording needs from the editor it was started in. */
interface RecordOwner {
    /** Exact current adoption for work that may finish after the document is replaced. */
    fun recordingRecoveryTarget(): RecordingRecoveryTarget? = null
    /** A recording id nothing in this editor's document uses yet. */
    fun freshRecordingId(): Int

    /** Place the finished recording where the view is now, as recording [rid], selected; null when nothing can take it. */
    fun placeRecording(audio: AudioData, name: String, rid: Int): AudioItem?

    /** A placed recording's .m4a is ready: [item] plays from [audio] from now on, and the note keeps it. */
    fun recordingMoved(item: AudioItem, audio: AudioData)

    /** Show [text], with [action] as a button on it. */
    fun recordingMessage(text: String, action: Pair<String, () -> Unit>? = null)

    /** Where a placed recording's files live, beside the document's other assets. */
    val recordingDir: File

    /** The note's name, which the notification shows. */
    val recordingTitle: String

    /** What a recording started here is found again by after a crash: the document it belongs to. */
    val recordingKey: String
}

/** An editor that plays and records: both canvases are. */
interface AudioHost : AudioOwner, RecordOwner

/**
 * The one recording in the app, for one editor. Recorded time counts only while it runs, so a
 * pause leaves no gap in the moments ink is given. It carries on with xnotes off screen, under
 * [RecordingService]. Stop, or closing the note, places it; only Discard throws it away, and
 * even that can be undone for a few seconds.
 */
object AudioRecording {

    class Session(val owner: RecordOwner, val rid: Int, val name: String, val file: File) {
        internal val target=owner.recordingRecoveryTarget()
        internal var recoveryId:String?=null
        /** Recorded seconds, for the chip; it changes once a second. */
        var seconds by mutableIntStateOf(0)
            internal set
        var paused by mutableStateOf(false)
            internal set

        /** How loud the microphone is now, 0 to 1, for the menu's meter. */
        var level by mutableFloatStateOf(0f)
            internal set

        internal var accumulated = 0L
        internal var runningSince = SystemClock.elapsedRealtime()
        internal var levels = FloatArray(1024)
        internal var levelCount = 0

        /** Recorded time so far, in ms, pauses left out. */
        fun elapsedMs(): Long = accumulated + if (paused) 0L else SystemClock.elapsedRealtime() - runningSince

        internal fun addLevel(l: Float) {
            if (levelCount == levels.size) levels = levels.copyOf(levels.size * 2)
            levels[levelCount++] = l
        }
    }

    var session by mutableStateOf<Session?>(null)
        private set

    private const val TICK_MS = 50L

    /** Below this much free space a recording stops and is placed. */
    private const val MIN_FREE_BYTES = 100L shl 20

    /** How long a discarded recording can still be had back. */
    private const val UNDO_MS = 15_000L

    private var app: Context? = null
    private var recorder: AudioRecorder? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-remux") }
    internal var recoveryExecutor:java.util.concurrent.Executor=worker
    internal var readDuration:(File)->Int?=AudioFiles::durationOf
    internal var remux:(File,File)->Boolean=AudioRemux::toM4a
    internal var readPeaks:(File,Int,((ByteArray?)->Unit))->Unit=com.xnotes.platform.AudioPeaks::readLater
    private val recovering=hashSetOf<String>()
    private var ticks = 0
    private var discarded: Session? = null
    private val forget = Runnable { dropDiscarded() }

    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (s.paused) return
            val l = recorder?.level() ?: 0f
            s.level = l
            s.addLevel(l)
            val sec = (s.elapsedMs() / 1000).toInt()
            if (sec != s.seconds) s.seconds = sec
            if (++ticks % 100 == 0 && lowOnSpace()) {
                stop()
                s.owner.recordingMessage(app?.getString(R.string.err_storage_low) ?: return)
                return
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        AudioRecovery.init(context)
    }

    /** Where recordings in progress are written; nothing else lives there. */
    fun dir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }
    internal fun newRecordingFile(context:Context,now:Long):File=File.createTempFile("rec-$now-",".aac",dir(context))
    internal fun recoveryFileProtected(file:File):Boolean {
        val key=file.canonicalPath
        return session?.file?.canonicalPath==key || discarded?.file?.canonicalPath==key
    }

    /** Start recording for [owner]. False, with a message, when the microphone cannot be had. */
    fun start(owner: RecordOwner): Boolean {
        if (session != null) return false
        val ctx = app ?: return false
        AudioPlayback.close()
        dropDiscarded()
        val now = System.currentTimeMillis()
        val file = runCatching {newRecordingFile(ctx,now)}.getOrNull() ?: run {
            owner.recordingMessage(ctx.getString(R.string.err_record_failed));return false
        }
        val r = AudioRecorder(ctx, file)
        if (!r.start()) {
            file.delete()
            owner.recordingMessage(ctx.getString(R.string.err_record_failed))
            return false
        }
        recorder = r
        val s = Session(owner, owner.freshRecordingId(), defaultName(ctx, now), file)
        if(s.target?.isCurrent()!=true || !writeSidecar(s, owner.recordingKey)) {
            r.stop();recorder=null
            // A failed initial journal may leave useful AAC bytes: the recovery menu lists orphans.
            if(file.length()==0L) file.delete()
            owner.recordingMessage(ctx.getString(R.string.err_record_failed))
            return false
        }
        session = s
        ticks = 0
        RecordingService.start(ctx)
        main.removeCallbacks(tick)
        main.post(tick)
        return true
    }

    fun pause() {
        val s = session ?: return
        if (s.paused) return
        recorder?.pause()
        s.accumulated = s.elapsedMs()
        s.paused = true
        s.level = 0f
        main.removeCallbacks(tick)
        notifyChange()
    }

    fun resume() {
        val s = session ?: return
        if (!s.paused) return
        val r = recorder ?: AudioRecorder(app ?: return, s.file).also { recorder = it }
        if (!r.resume()) {
            s.owner.recordingMessage(app?.getString(R.string.err_record_failed) ?: return)
            return
        }
        s.runningSince = SystemClock.elapsedRealtime()
        s.paused = false
        main.removeCallbacks(tick)
        main.post(tick)
        notifyChange()
    }

    fun toggle() {
        if (session?.paused == true) resume() else pause()
    }

    /** Stop and place the recording where its editor's view is now. */
    fun stop() {
        val s = end() ?: return
        place(s)
    }

    /** Stop and place it if it is [owner]'s, as when that editor's note closes. */
    fun stopFor(owner: RecordOwner) {
        if (session?.owner === owner) stop()
    }

    /** Throw the recording away, though Undo on the message brings it back, paused, for a few seconds. */
    fun discard() {
        val s = end() ?: return
        dropDiscarded()
        // The raw file is reserved for Undo; recovery cannot claim it during this window.
        recoveryCandidate(s)?.let {AudioRecovery.store?.discardMetadata(it)}
        discarded = s
        main.postDelayed(forget, UNDO_MS)
        val ctx = app ?: return
        s.owner.recordingMessage(ctx.getString(R.string.recording_discarded), ctx.getString(R.string.undo) to { restore() })
    }

    /** The app is being closed under the recording: close the file as it stands, for the next open of its note. */
    fun abandon() {
        end()
    }

    /** The moment an item [owner] adds now is given: while it records there and is not paused. */
    fun markFor(owner: RecordOwner): com.xnotes.core.model.SyncMark? {
        val s = session ?: return null
        if (s.owner !== owner || s.paused || s.target?.isCurrent()!=true) return null
        return com.xnotes.core.model.SyncMark(s.rid, s.elapsedMs().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** Give [items], just added by [owner], the moment of the recording running there; a pill never syncs. */
    fun stamp(owner: RecordOwner, items: List<com.xnotes.core.model.CanvasItem>) {
        if (items.isEmpty()) return
        val mark = markFor(owner) ?: return
        for (item in items) if (item !is AudioItem && item.sync == null) item.sync = mark
    }

    /** What the notification shows, or null with nothing recording. */
    fun notice(): RecordingService.Notice? {
        val s = session ?: return null
        return RecordingService.Notice(s.owner.recordingTitle, s.elapsedMs(), s.paused)
    }

    private fun restore() {
        val s = discarded ?: return
        main.removeCallbacks(forget)
        discarded = null
        if (session != null) return dropFile(s)
        val ctx = app ?: return
        if(s.target?.isCurrent()!=true) {
            // The old Undo action has no authority over a replacement model. Keep the raw orphan.
            s.owner.recordingMessage(ctx.getString(R.string.recording_recovery_kept),
                ctx.getString(R.string.recording_recovery_title) to {AudioRecovery.open(s.owner)})
            return
        }
        recorder = null
        s.paused = true
        if(!writeSidecar(s, s.owner.recordingKey)) {
            s.owner.recordingMessage(ctx.getString(R.string.err_record_failed));return
        }
        session = s
        RecordingService.start(ctx)
    }

    /** Close the recorder and the session, leaving the file whole. */
    private fun end(): Session? {
        val s = session ?: return null
        main.removeCallbacks(tick)
        if (!s.paused) s.accumulated = s.elapsedMs()
        s.paused = true
        recorder?.stop()
        recorder = null
        session = null
        app?.let { RecordingService.stop(it) }
        return s
    }

    private fun place(s: Session) {
        val duration = s.accumulated.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val target=s.target
        val candidate=recoveryCandidate(s)
        if(!s.file.isFile || s.file.length()==0L || duration<=0 || target==null || candidate==null || !target.isCurrent()) return
        if(target.recordings(s.rid).isNotEmpty()) return problem(target)
        val adts=copyOut(s.file,target.directory) ?: return problem(target)
        val record=runCatching {AudioRecovery.store?.applying(candidate,target.kind,target.documentId,s.rid,
            com.xnotes.format.BundleMigration.hash(adts),target.ownerToken)}.getOrNull()
        if(record==null || !target.isCurrent()) {adts.delete();return}
        val item=runCatching {target.owner.placeRecording(AudioData(adts,"aac",duration,peaksOf(s)),s.name,s.rid)}.getOrNull()
            ?: return problem(target)
        toM4a(target,item,adts,record.id)
    }

    /** Keep the original name until durable body acknowledgement; a hard link avoids copying on Stop. */
    private fun copyOut(file: File, dir: File): File? {
        val adts = runCatching { File.createTempFile("aud", ".aac", dir) }.getOrNull() ?: return null
        val copied=runCatching {
            adts.delete()
            try {java.nio.file.Files.createLink(adts.toPath(),file.toPath())}
            catch(_:Exception) {java.io.FileOutputStream(adts).use {out->file.inputStream().use {it.copyTo(out)};out.fd.sync()}}
        }.isSuccess
        if(!copied) {
            adts.delete();return null
        }
        return adts
    }

    /** Copy [adts] into an .m4a off the main thread, and hand it to [item] if it still plays from [adts]. */
    private fun toM4a(target:RecordingRecoveryTarget,item:AudioItem,adts:File,recordId:String,lineage:MutableSet<File> = mutableSetOf(adts)) {
        recoveryExecutor.execute {
            val m4a = runCatching {File.createTempFile("aud", null, adts.parentFile)}.getOrNull() ?: return@execute
            val ok = runCatching {remux(adts, m4a)}.getOrDefault(false)
            val exact = if (ok) readDuration(m4a) else null
            val registered=ok && exact!=null && runCatching {AudioRecovery.store?.register(recordId,com.xnotes.format.BundleMigration.hash(m4a))==true}.getOrDefault(false)
            main.post {
                if (!registered || exact == null || !target.isCurrent() || !target.holds(item) || item.audio.file !== adts) {
                    m4a.delete()
                    return@post
                }
                lineage.add(m4a)
                target.owner.recordingMoved(item, AudioData(m4a, "m4a", exact, item.audio.peaks))
            }
        }
    }

    /**
     * Place what a crash or a swipe left unplaced for the document [owner] now shows, as if Stop had
     * been pressed when it ended: where the view is, with the ink written during it still tied to it.
     * [keys] are the names that document goes by; a recording still going is never touched.
     */
    fun recover(owner:RecordOwner,keys:List<String>) {owner.recordingRecoveryTarget()?.let {recover(it.withKeys(keys.toSet()))}}

    fun recover(target:RecordingRecoveryTarget) {
        if(!target.isCurrent()) return
        for(candidate in AudioRecovery.candidates()) {
            val r=candidate.record ?: continue
            if(candidate.problem==null && r.phase==RecordingRecoveryStore.Phase.Pending && r.key in target.keys &&
                r.kind==target.kind && r.documentId==target.documentId && target.recordings(r.rid).isEmpty())
                prepareRecovery(target,candidate,r.rid)
        }
    }

    internal fun recoverExplicit(target:RecordingRecoveryTarget,candidate:RecordingRecoveryStore.Candidate,separate:Boolean=false) {
        if(!target.isCurrent()) return problem(target)
        if(candidate.raw?.let(::recoveryFileProtected)==true) return
        val r=candidate.record
        val same=r!=null && r.targetKind==target.kind && r.targetId==target.documentId
        val rid=if(!separate && same) r!!.targetRid else target.owner.freshRecordingId()
        prepareRecovery(target,candidate,rid)
    }

    private fun prepareRecovery(target:RecordingRecoveryTarget,candidate:RecordingRecoveryStore.Candidate,rid:Int) {
        if(!target.isCurrent() || candidate.raw?.let(::recoveryFileProtected)==true || target.recordings(rid).isNotEmpty() || !recovering.add(candidate.id)) return
        AudioRecovery.busy=true
        recoveryExecutor.execute {
            val source=candidate.raw
            val adts=if(source?.isFile==true && source.length()>0L) copyOut(source,target.directory) else null
            val duration=adts?.let {runCatching {readDuration(it)}.getOrNull()}
            val sha=adts?.let {runCatching {com.xnotes.format.BundleMigration.hash(it)}.getOrNull()}
            main.post {
                if(adts==null || duration==null || duration<=0 || sha==null || !target.isCurrent() || target.recordings(rid).isNotEmpty()) {
                    adts?.delete();finishRecovery(candidate.id);problem(target);return@post
                }
                // Durable Applying precedes placement. A restart never guesses whether an absent pill was undone.
                recoveryExecutor.execute {
                    val record=runCatching {AudioRecovery.store?.applying(candidate,target.kind,target.documentId,rid,sha,target.ownerToken)}.getOrNull()
                    main.post placed@{
                        if(record==null || !target.isCurrent() || target.recordings(rid).isNotEmpty()) {
                            adts.delete();finishRecovery(candidate.id);problem(target);return@placed
                        }
                        val name=candidate.record?.name ?: source?.nameWithoutExtension.orEmpty()
                        val item=runCatching {target.owner.placeRecording(AudioData(adts,"aac",duration,ByteArray(0)),name,rid)}.getOrNull()
                        finishRecovery(candidate.id)
                        if(item==null) {adts.delete();problem(target);return@placed}
                        val lineage=mutableSetOf(adts)
                        readPeaks(adts,duration) {peaks->main.post {
                            if(target.isCurrent() && target.holds(item) && item.audio.file in lineage && item.audio.peaks.isEmpty())
                                target.owner.recordingMoved(item,item.audio.withPeaks(peaks ?: ByteArray(AudioData.PEAK_BINS) {40}))
                        }}
                        toM4a(target,item,adts,record.id,lineage)
                    }
                }
            }
        }
    }
    private fun finishRecovery(id:String) {recovering.remove(id);AudioRecovery.busy=recovering.isNotEmpty();AudioRecovery.refresh()}
    private fun problem(target:RecordingRecoveryTarget) {
        val ctx=app ?: return
        if(target.isCurrent()) target.owner.recordingMessage(ctx.getString(R.string.recording_recovery_kept),
            ctx.getString(R.string.recording_recovery_title) to {AudioRecovery.open(target.owner)})
    }

    /** What finds the recording again if the app dies under it: its note, its id, its name. */
    private fun writeSidecar(s: Session,key:String):Boolean {
        val target=s.target
        val record=AudioRecovery.store?.create(s.file,key,s.name,s.rid,target?.kind,target?.documentId) ?: return false
        s.recoveryId=record.id;return true
    }
    private fun recoveryCandidate(s:Session)=AudioRecovery.store?.list()?.firstOrNull {it.record?.id==s.recoveryId}

    private fun peaksOf(s: Session): ByteArray {
        val n = s.levelCount
        if (n == 0) return ByteArray(AudioData.PEAK_BINS)
        val top = (0 until n).maxOf { s.levels[it] }
        val shaped = FloatArray(n) { if (top > 0f) sqrt(s.levels[it] / top) else 0f }
        return AudioData.peaksOf(shaped, n)
    }

    private fun dropDiscarded() {
        main.removeCallbacks(forget)
        discarded?.let { dropFile(it) }
        discarded = null
    }

    private fun dropFile(s: Session) {
        val candidate=recoveryCandidate(s)
        if(candidate!=null) AudioRecovery.store?.discard(candidate)
        else AudioRecovery.store?.deleteIfUnreferenced(s.file) ?: s.file.delete()
    }

    private fun notifyChange() {
        val ctx = app ?: return
        notice()?.let { RecordingService.update(ctx, it) }
    }

    private fun lowOnSpace(): Boolean = runCatching {
        StatFs((app ?: return false).filesDir.path).availableBytes < MIN_FREE_BYTES
    }.getOrDefault(false)

    private fun defaultName(context: Context, at: Long): String {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "dMMMjjmm")
        return android.text.format.DateFormat.format(pattern, at).toString()
            .ifBlank { context.getString(R.string.recording) }
    }
}
