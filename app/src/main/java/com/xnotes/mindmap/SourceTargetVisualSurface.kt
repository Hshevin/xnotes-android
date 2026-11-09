package com.xnotes.mindmap

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.ui.theme.*

@Composable
internal fun SourceTargetVisualSurface(controller:SourceTargetEditController,shown:SourceTargetEditState,modifier:Modifier=Modifier) {
    val frame=shown.frame ?: return
    key(shown.id,frame) {
        val host=remember {Any()}
        var view by remember {mutableStateOf<SourceRegionVisualView?>(null)}
        var pan by remember {mutableStateOf(shown.selectionScope!=SourceRangeScope.REGION)}
        val background=LocalPalette.current.bg.toComposeColor().toArgb()
        val description=stringResource(R.string.source_target_region_view)
        DisposableEffect(controller,host) {onDispose {view?.retire();controller.detachVisualHost(host)}}
        Column(modifier,verticalArrangement=Arrangement.spacedBy(Space.s4)) {
            Row(horizontalArrangement=Arrangement.spacedBy(Space.s4)) {
                if(shown.selectionScope==SourceRangeScope.REGION) {
                    MapButton(R.string.source_target_region,enabled=!controller.visualBusy,selected=!pan) {pan=false}
                    MapButton(R.string.source_target_pan,enabled=!controller.visualBusy,selected=pan) {pan=true}
                }
                MapButton(R.string.source_target_zoom_out,enabled=!controller.visualBusy) {view?.zoomBy(.8)}
                MapButton(R.string.source_target_fit,enabled=!controller.visualBusy) {view?.fit()}
                MapButton(R.string.source_target_zoom_in,enabled=!controller.visualBusy) {view?.zoomBy(1.25)}
            }
            if(shown.selectionScope==SourceRangeScope.REGION) {
                Text(stringResource(R.string.source_target_region_help),fontSize=TextSize.caption)
                Text(stringResource(if(shown.region==null) R.string.source_target_no_region else R.string.source_target_region_selected),fontSize=TextSize.caption)
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory={context->SourceRegionVisualView(context).also {view=it}},modifier=Modifier.fillMaxSize(),
                update={surface->
                    if(controller.attachVisualHost(host,frame)) {
                        surface.contentDescription=description
                        surface.bind(frame,frame.bounds,frame.initialRegion,shown.region,
                            shown.selectionScope==SourceRangeScope.REGION && !pan,shown.camera,background,
                            current={controller.visualCurrent(host,frame)},onRegion={controller.setRegion(frame,it)},
                            onBusy={controller.setVisualBusy(host,frame,it)},onSettled={viewport,camera->controller.render(viewport,host,camera)})
                        val image=controller.image
                        surface.image(image?.bitmap,image?.frame)
                    } else surface.retire()
                })
            if(controller.image==null) Text(stringResource(R.string.source_target_loading_preview),fontSize=TextSize.body,modifier=Modifier.align(Alignment.Center))
            }
        }
    }
}
