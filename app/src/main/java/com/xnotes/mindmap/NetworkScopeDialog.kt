package com.xnotes.mindmap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.xnotes.R
import com.xnotes.ui.AlertDialog
import com.xnotes.ui.theme.*
import java.util.UUID

private data class NetworkScopeDraft(val id:String,val revision:Long,val canChoose:Boolean,val ids:Set<String>) {
    companion object {
        val Saver=listSaver<NetworkScopeDraft?,Any>(save={draft->if(draft==null) emptyList() else
            listOf(draft.id,draft.revision,draft.canChoose,ArrayList(draft.ids))},restore={saved->
            if(saved.isEmpty()) null else runCatching {
                val ids=(saved[3] as List<*>).map {it as String}.toSet()
                require(ids.size<=250 && ids.all {UUID.fromString(it).toString()==it})
                NetworkScopeDraft(saved[0] as String,saved[1] as Long,saved[2] as Boolean,ids)
            }.getOrNull()
        })
    }
}

/** No source/native owner or picker: this dialog only confirms a pure query scope. */
@Composable
internal fun NetworkScopeControls(entry:FileNetworkWorkspace.Entry,enabled:Boolean) {
    val controller=entry.controller;val revision=controller.filterRevision
    var attached by remember(entry) {mutableStateOf(true)}
    var draft by rememberSaveable(entry.query.focusId,stateSaver=NetworkScopeDraft.Saver) {mutableStateOf<NetworkScopeDraft?>(null)}
    var problem by remember(entry) {mutableStateOf(false)}
    val latestEnabled by rememberUpdatedState(enabled)
    DisposableEffect(entry) {onDispose {attached=false}}
    fun scopeCurrent()=attached && entry.alive && latestEnabled && controller.filterRevision==revision
    MapButton(R.string.network_scope_library,enabled,controller.browseMode==NetworkBrowseMode.LIBRARY) {
        if(scopeCurrent() && controller.showLibrary(revision)) entry.presentation.invalidateInput()
    }
    MapButton(R.string.network_scope_files,enabled,controller.browseMode==NetworkBrowseMode.FILES) {
        if(scopeCurrent()) {
            problem=false
            draft=NetworkScopeDraft(UUID.randomUUID().toString(),revision,controller.browseMode!=NetworkBrowseMode.LOCAL,
                if(controller.browseMode==NetworkBrowseMode.FILES) controller.centreFiles.toSet() else emptySet())
        }
    }
    if(entry.query.hasLocalFocus) MapButton(R.string.network_scope_local,enabled,controller.browseMode==NetworkBrowseMode.LOCAL) {
        if(scopeCurrent() && controller.showLocal(revision)) entry.presentation.invalidateInput()
    }
    val shown=draft ?: return
    fun sameDraft()=attached && entry.alive && draft?.id==shown.id
    fun current()=sameDraft() && latestEnabled && controller.filterRevision==shown.revision
    fun close() {if(sameDraft()) {draft=null;problem=false}}
    AlertDialog(onDismissRequest=::close,title={Text(stringResource(R.string.network_scope_title),fontSize=TextSize.heading)},text={
        LazyColumn(Modifier.heightIn(max=MindMapNodeListHeight),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
            item("notice") {
                Text(stringResource(R.string.network_scope_notice),fontSize=TextSize.caption)
                if(problem || controller.filterRevision!=shown.revision) Text(stringResource(R.string.network_scope_changed),fontSize=TextSize.body)
            }
            if(!shown.canChoose) item("library-first") {
                Text(stringResource(R.string.network_scope_library_first),fontSize=TextSize.body)
                MapButton(R.string.network_scope_view_library,enabled=current()) {
                    if(draft===shown && current() && controller.showLibrary(shown.revision)) {
                        entry.presentation.invalidateInput();close()
                    }
                }
            } else {
                item("count") {
                    Text(stringResource(R.string.network_scope_selection_count,shown.ids.size),fontSize=TextSize.body)
                    MapButton(R.string.clear_selection,enabled=current()) {if(current()) draft=draft?.copy(ids=emptySet())}
                    if(controller.fileChoices.isEmpty()) Text(stringResource(R.string.network_scope_no_files),fontSize=TextSize.body)
                }
                for(file in controller.fileChoices) item(file.documentId) {
                    val checked=file.documentId in shown.ids
                    Row(Modifier.fillMaxWidth().heightIn(min=MinTouchTarget).toggleable(value=checked,
                        enabled=current() && (checked || shown.ids.size<250),role=Role.Checkbox,onValueChange={
                            if(current()) draft=draft?.let {latest->
                                if(file.documentId in latest.ids) latest.copy(ids=latest.ids-file.documentId)
                                else if(latest.ids.size<250) latest.copy(ids=latest.ids+file.documentId) else latest
                            }
                        }),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Space.s8)) {
                        Checkbox(checked,onCheckedChange=null,enabled=current() && (checked || shown.ids.size<250))
                        Text(file.observedNames.firstOrNull().orEmpty().ifBlank {stringResource(R.string.network_file)},
                            fontSize=TextSize.body,modifier=Modifier.weight(1f))
                    }
                }
            }
        }
    },confirmButton={if(shown.canChoose) TextButton(onClick={
        if(draft===shown && current()) {
            if(controller.showCollection(shown.ids,shown.revision)) {entry.presentation.invalidateInput();close()} else problem=true
        }
    },enabled=current() && shown.ids.isNotEmpty()) {Text(stringResource(R.string.apply),fontSize=TextSize.body)}},
        dismissButton={TextButton(onClick=::close) {Text(stringResource(R.string.cancel),fontSize=TextSize.body)}})
}
