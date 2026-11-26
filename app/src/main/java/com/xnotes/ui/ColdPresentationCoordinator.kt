package com.xnotes.ui

import androidx.annotation.MainThread
import com.xnotes.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One UI host's finite publication and bootstrap work. The retained owner stores only routing facts. */
@MainThread
internal class ColdPresentationCoordinator(
    private val editor:Editor,
    private val bootstrap:ColdPresentationBootstrap,
    private val store:LastPresentationStore=LastPresentationStore(editor.view.context.filesDir),
) {
    internal data class Frame(val homePanes:Set<Pane> = emptySet(),val parked:Boolean=false)
    private var host=bootstrap.bind()
    private var active=true
    private var publication=Any()
    private var frameProvider:()->Frame={Frame()}
    internal var onRestored:((LastPresentation)->Unit)?=null

    fun bindFrame(provider:()->Frame) {frameProvider=provider}
    private fun hostCurrent()=active && bootstrap.current(host)

    /** Every accepted front-door intent retires a cold preparation before the new IO starts. */
    fun retireIntent() {
        if(!active) return
        bootstrap.retire();host=bootstrap.bind()
        publication=Any()
    }

    suspend fun bootstrap(retry:Boolean=false):Boolean {
        if(retry) {host=bootstrap.bind(retry=true);publication=Any()}
        val request=host
        fun current()=active && bootstrap.current(request)
        try {
            if(request.retained) {
                val retained=bootstrap.presentation
                val priorProblem=bootstrap.problem
                val success=when {
                    retained is LastPresentation.Native->restoreNative(retained,::current) {true}
                    retained==null && bootstrap.permitsLegacySession && priorProblem==null &&
                        bootstrap.observation?.read===LastPresentationRead.Absent->{
                        editor.restoreSession();if(current()) editor.restoreSplitWorkspace();current()
                    }
                    retained==null && priorProblem!=null->false
                    else->true
                }
                if(current() && !success) { bootstrap.finish(request, LastPresentation.Home); onRestored?.invoke(LastPresentation.Home) }
                return success && current()
            }
            val observed=withContext(Dispatchers.IO) {store.observe()}
            if(!current() || !bootstrap.observed(request,observed)) return false
            val read=observed.read
            if(read===LastPresentationRead.Absent) {
                editor.restoreSession()
                if(!current()) return false
                editor.restoreSplitWorkspace()
                return bootstrap.finish(request,null)
            }
            if(read===LastPresentationRead.Invalid) {
                bootstrap.finish(request, LastPresentation.Home);onRestored?.invoke(LastPresentation.Home);return true
            }
            val value=(read as LastPresentationRead.Ready).value
            fun finalProof()=current()
            val adopted=when(value) {
                LastPresentation.Home->finalProof()
                is LastPresentation.Native->restoreNative(value,::current,::finalProof)
            }
            if(current()) {
                val shown = if (adopted) value else LastPresentation.Home
                bootstrap.finish(request, shown)
                try {onRestored?.invoke(shown)} catch(error:Exception) {
                    android.util.Log.w("xnotes.presentation","Restored workspace UI notification failed",error)
                }
            }
            return adopted && current()
        } catch(cancelled:CancellationException) {
            currentCoroutineContext().ensureActive()
            return false
        } catch(_:Exception) {
            if(current()) { bootstrap.finish(request, LastPresentation.Home); onRestored?.invoke(LastPresentation.Home) }
            return false
        }
    }

    private suspend fun restoreNative(value:LastPresentation.Native,current:()->Boolean,finalProof:()->Boolean):Boolean {
        val cap=editor.prepareColdNativePresentation(value,current) ?: return false
        return try {
            if(!cap.verify() || !current() || !finalProof() || !cap.claimForAdoption()) false
            else {cap.applyAdoption();true}
        } finally {cap.close()}
    }

    private suspend fun native(home:Set<Pane>,current:()->Boolean):Editor.PreparedNativePresentationCheckpoint? =
        when(val result=editor.prepareNativePresentationCheckpointResult(home,current)) {
            is NativeCheckpointResult.Ready->result.checkpoint
            NativeCheckpointResult.Superseded->null
            NativeCheckpointResult.Deferred->null
            is NativeCheckpointResult.Failed->{failedCheckpoint(current);null}
        }
    suspend fun checkpoint():Boolean {
        if(!hostCurrent() || !bootstrap.completed || bootstrap.problem!=null) return false
        val epoch=Any().also {publication=it}
        val frame=frameProvider()
        val nativeIdentity=editor.captureNativePresentationIdentity()
        val nativeVersion=editor.presentationVersion
        val secondVersion=editor.secondary?.presentationVersion
        val nativeLayout=listOf(editor.splitRatio,editor.splitSideBySide,editor.splitReversed,editor.focusedPane)
        fun current()=hostCurrent() && publication===epoch && frameProvider()==frame &&
            editor.presentationVersion==nativeVersion && editor.secondary?.presentationVersion==secondVersion &&
            listOf(editor.splitRatio,editor.splitSideBySide,editor.splitReversed,editor.focusedPane)==nativeLayout &&
            editor.matchesNativePresentationIdentity(nativeIdentity)
        for (pane in listOfNotNull(editor, editor.secondary)) {
            if ((frame.parked || pane.pane in frame.homePanes || !pane.noteOpen) && !pane.pauseParkedSession(::current)) return false
        }
        val native = if (!frame.parked && editor.openPanes.any { it.noteOpen && it.pane !in frame.homePanes }) {
            native(frame.homePanes, ::current) ?: return false
        } else null
        fun proof() = current() && native?.current() != false
        val value = native?.value ?: LastPresentation.Home
        if(!proof()) return false
        val written = withContext(Dispatchers.IO) { store.write(value, ::proof) }
        if (written && current()) bootstrap.presented(value)
        return written
    }

    private fun failedCheckpoint(current:()->Boolean):Boolean {
        if(current() && editor.message==null) editor.message=editor.view.context.getString(R.string.err_save_recovery_session)
        return false
    }

    fun close() {active=false;publication=Any()}
}
