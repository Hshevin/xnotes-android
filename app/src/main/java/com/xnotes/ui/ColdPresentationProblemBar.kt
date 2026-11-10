package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize

/** Recovery stays an explicit action on the usable Home screen. */
@Composable
internal fun ColdPresentationProblemBar(problem:ColdPresentationProblem,busy:Boolean,
    onRetry:()->Unit,onRecovery:()->Unit,onReset:()->Unit) {
    Surface {
        Column(Modifier.fillMaxWidth().padding(Space.s8),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
            Text(stringResource(if(problem==ColdPresentationProblem.INVALID) R.string.last_presentation_invalid
                else R.string.last_presentation_unavailable),fontSize=TextSize.body)
            Row(horizontalArrangement=Arrangement.spacedBy(Space.s8)) {
                TextButton(onClick=onRetry,enabled=!busy,modifier=Modifier.heightIn(min=MinTouchTarget)) {
                    Text(stringResource(R.string.last_presentation_retry),fontSize=TextSize.body)
                }
                TextButton(onClick=onRecovery,enabled=!busy,modifier=Modifier.heightIn(min=MinTouchTarget)) {
                    Text(stringResource(R.string.last_presentation_recovery),fontSize=TextSize.body)
                }
                TextButton(onClick=onReset,enabled=!busy,modifier=Modifier.heightIn(min=MinTouchTarget)) {
                    Text(stringResource(R.string.last_presentation_reset),fontSize=TextSize.body)
                }
            }
        }
    }
}
