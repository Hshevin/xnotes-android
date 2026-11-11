package com.xnotes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Which Home — the main menu, or one pane's Home — shows each sidebar tool ([BackstageView.tool]) as its pane.
 * Only the Home key is kept, so an Activity rebuild finds the same Home; the tool's own model decides visibility.
 */
@Stable
internal class HomeToolOwners(initial:Map<BackstageView,String> = emptyMap()) {
    var owners by mutableStateOf(initial);private set
    fun claim(tool:BackstageView,target:Editor?) {
        require(tool.tool)
        owners=owners+(tool to key(target))
    }
    /** The tool has no Home: its host presents it on its own. */
    fun forget(tool:BackstageView) {if(tool in owners) owners=owners-tool}
    fun owner(tool:BackstageView)=owners[tool]
    companion object {
        const val MAIN="main"
        fun key(target:Editor?)=target?.pane?.name ?: MAIN
        val Saver=listSaver<HomeToolOwners,String>(save={it.owners.flatMap {(tool,home)->listOf(tool.name,home)}},
            restore={saved->HomeToolOwners(saved.chunked(2).mapNotNull {pair->
                BackstageView.entries.firstOrNull {it.name==pair[0] && it.tool}?.let {it to pair[1]}
            }.toMap())})
    }
}

/**
 * Releases a tool when the Home pane showing it leaves composition — the pane's Home closed or covered — except
 * across an Activity rebuild. [release] checks whether its presentation still needs releasing: one handed to a
 * reader or a map has already left the pane and stays for the return.
 */
@Composable
internal fun HomeToolLease(release:()->Unit) {
    val activity=LocalContext.current as? android.app.Activity
    val latest by rememberUpdatedState(release)
    DisposableEffect(Unit) {onDispose {if(activity?.isChangingConfigurations!=true) latest()}}
}
