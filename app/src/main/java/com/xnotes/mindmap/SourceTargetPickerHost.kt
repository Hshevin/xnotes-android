package com.xnotes.mindmap

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Registered outside the keyed document body, so a late result keeps its original OS slot. */
internal class SourceTargetPickerHost(val pendingId:String?,val launch:(SourceTargetPickerSlot)->Unit)
internal val LocalSourceTargetPickerHost=staticCompositionLocalOf<SourceTargetPickerHost?> {null}

@Composable
internal fun rememberSourceTargetPickerHost():SourceTargetPickerHost {
    var pending by rememberSaveable {mutableStateOf<String?>(null)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        val original=pending;pending=null
        if(original!=null) SourceTargetEditController.deliverPicker(original,uri)
    }
    return SourceTargetPickerHost(pending) {slot->
        if(pending!=null) SourceTargetEditController.deliverPicker(slot.id,null)
        else {
            pending=slot.id
            try {launcher.launch(arrayOf("*/*"))}
            catch(_:Exception) {pending=null;SourceTargetEditController.deliverPicker(slot.id,null)}
        }
    }
}
