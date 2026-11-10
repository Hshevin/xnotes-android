package com.xnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import kotlinx.coroutines.launch

/** Explicit choices for ambiguous attempts and recordings whose first metadata write failed. */
@Composable
internal fun RecordingRecoveryDialog(owner:RecordOwner) {
    val state=AudioRecovery.dialog?.takeIf {it.owner===owner} ?: return
    RecordingRecoveryLifetime(state)
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var exporting by remember {mutableStateOf<AudioRecovery.Export?>(null)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/aac")) {uri->
        val pending=exporting ?: return@rememberLauncherForActivityResult
        val source=pending.candidate.raw;exporting=null
        if(uri==null || source==null || !AudioRecovery.startExport(pending)) {
            AudioRecovery.finishExport(pending);return@rememberLauncherForActivityResult
        }
        val job=scope.launch {
            val ok=AudioRecovery.exportBytes(pending) {
                source.inputStream().use {input->requireNotNull(context.contentResolver.openOutputStream(uri)).use {input.copyTo(it)}}
            }
            owner.recordingMessage(context.getString(if(ok) R.string.audio_saved else R.string.err_save_audio))
        }
        // A disposed composition can cancel the scope before this coroutine's body starts.
        job.invokeOnCompletion {AudioRecovery.finishExport(pending)}
    }
    val busy=AudioRecovery.busy
    AlertDialog(onDismissRequest=AudioRecovery::close,
        title={Text(stringResource(R.string.recording_recovery_title),fontSize=TextSize.heading)},
        text={Column(Modifier.fillMaxWidth().heightIn(max=Space.s48*8).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Space.s12)) {
            Text(stringResource(R.string.recording_recovery_explanation),fontSize=TextSize.body)
            if(state.candidates.isEmpty()) Text(stringResource(R.string.recording_recovery_empty),fontSize=TextSize.body)
            for(candidate in state.candidates) {
                val record=candidate.record;val target=state.target
                val current=target?.isCurrent()==true
                val same=current && record?.targetKind==target?.kind && record?.targetId==target?.documentId
                val occupied=same && target!!.recordings(record!!.targetRid).isNotEmpty()
                val readable=candidate.raw?.let {it.isFile && it.length()>0L}==true
                val reason=when {
                    candidate.problem==RecordingRecoveryStore.Problem.Invalid->R.string.recording_recovery_invalid
                    !readable->R.string.recording_recovery_missing
                    !current->R.string.recording_recovery_target_changed
                    occupied->R.string.recording_recovery_present
                    candidate.problem==RecordingRecoveryStore.Problem.Applying && same->R.string.recording_recovery_applying
                    candidate.problem==RecordingRecoveryStore.Problem.Legacy || candidate.problem==RecordingRecoveryStore.Problem.Unbound->R.string.recording_recovery_unbound
                    !same->R.string.recording_recovery_mismatch
                    else->R.string.recording_recovery_pending
                }
                Column(verticalArrangement=Arrangement.spacedBy(Space.s4)) {
                    Text(record?.name ?: candidate.raw?.name ?: candidate.metadata?.name.orEmpty(),fontSize=TextSize.title)
                    Text(stringResource(reason),fontSize=TextSize.body)
                    if(readable) {
                        if(same && !occupied) RecoveryAction(R.string.recording_recovery_restore,!busy) {
                            AudioRecording.recoverExplicit(requireNotNull(target),candidate)
                        }
                        RecoveryAction(R.string.recording_recovery_separate,!busy && current) {
                            AudioRecording.recoverExplicit(requireNotNull(target),candidate,separate=true)
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(Space.s8)) {
                        RecoveryAction(R.string.audio_save,!busy && readable) {
                            AudioRecovery.beginExport(state,candidate)?.let {pending->
                                exporting=pending
                                try {export.launch(candidate.raw!!.name)}
                                catch(_:Exception) {exporting=null;AudioRecovery.finishExport(pending);owner.recordingMessage(context.getString(R.string.err_save_audio))}
                            }
                        }
                        RecoveryAction(R.string.recording_discard,!busy) {AudioRecovery.discard(candidate)}
                    }
                }
            }
        }},
        confirmButton={RecoveryAction(R.string.recording_recovery_keep,!busy,AudioRecovery::close)})
}

@Composable
internal fun RecordingRecoveryLifetime(dialog:AudioRecovery.Dialog) {
    DisposableEffect(dialog) {onDispose {AudioRecovery.detach(dialog)}}
}

@Composable
private fun RecoveryAction(label:Int,enabled:Boolean,onClick:()->Unit) {
    TextButton(onClick=onClick,enabled=enabled,modifier=Modifier.heightIn(min=MinTouchTarget)) {
        Text(stringResource(label),fontSize=TextSize.body)
    }
}
