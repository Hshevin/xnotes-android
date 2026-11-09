package com.xnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import com.xnotes.ui.theme.MinTouchTarget

/** Serializable registry identity, never authority to publish a document or reuse an old owner. */
internal data class DestinationPickerRequest(val key:String,val kind:String,val name:String,val owner:String="")
internal data class InterruptedDestination(val request:DestinationPickerRequest,val uri:String)

internal class DestinationPickerState {
    var pending by mutableStateOf<DestinationPickerRequest?>(null)
    var interrupted by mutableStateOf<InterruptedDestination?>(null)
    fun retain(request:DestinationPickerRequest,uri:Uri?) {
        // A CreateDocument result does not prove that its URI was absent beforehand. Even an
        // empty result is never deletion authority. OpenDocument creates no target to recover.
        if(uri!=null && request.kind=="CREATE") interrupted=InterruptedDestination(request,uri.toString())
    }
    companion object {
        val Saver=listSaver<DestinationPickerState,String>(save={state->
            val interrupted=state.interrupted
            val request=state.pending ?: interrupted?.request
            if(request==null) emptyList() else listOf(if(state.pending!=null) "PENDING" else "RESULT",
                request.key,request.kind,request.name,request.owner,interrupted?.uri.orEmpty())
        },restore={values->DestinationPickerState().apply {
            if(values.size==6) {
                val request=DestinationPickerRequest(values[1],values[2],values[3],values[4])
                if(values[0]=="PENDING") pending=request
                else if(values[0]=="RESULT") interrupted=InterruptedDestination(request,values[5])
            }
        }})
    }
}

@Composable
internal fun rememberDestinationPickerState()=rememberSaveable(saver=DestinationPickerState.Saver) {DestinationPickerState()}

/** A missing runtime owner cannot be reconstructed from a filename. Preserve and expose its result. */
@Composable
internal fun InterruptedDestinationNotice(state:DestinationPickerState) {
    val result=state.interrupted ?: return
    val context=LocalContext.current
    LaunchedEffect(result) {
        runCatching {context.contentResolver.takePersistableUriPermission(Uri.parse(result.uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
    }
    AlertDialog(onDismissRequest={},title={Text(stringResource(R.string.destination_interrupted_title),fontSize=TextSize.heading)},text={
        Column(verticalArrangement=Arrangement.spacedBy(Space.s8)) {
            Text(stringResource(R.string.destination_interrupted_notice),fontSize=TextSize.body)
            if(result.request.name.isNotBlank()) Text(result.request.name,fontSize=TextSize.body)
            SelectionContainer {Text(result.uri,fontSize=TextSize.caption)}
        }
    },confirmButton={TextButton(onClick={if(state.interrupted==result) state.interrupted=null},modifier=Modifier.heightIn(min=MinTouchTarget)) {Text(stringResource(R.string.done),fontSize=TextSize.body)}},
        dismissButton={TextButton(onClick={
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(result.request.name,result.uri))
        },modifier=Modifier.heightIn(min=MinTouchTarget)) {Text(stringResource(R.string.destination_copy_location),fontSize=TextSize.body)}})
}
