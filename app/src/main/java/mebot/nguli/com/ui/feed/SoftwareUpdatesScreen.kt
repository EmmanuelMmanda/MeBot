package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mebot.nguli.com.BuildConfig
import mebot.nguli.com.R
import mebot.nguli.com.updates.*
import mebot.nguli.com.ui.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SoftwareUpdatesScreen(manager:SoftwareUpdateManager){
    val state by manager.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    LaunchedEffect(manager){manager.enter()}
    SoftwareUpdatesContent(state,manager::checkNow,manager::downloadNow,{scope.launch{manager.install()}},manager::cancel,manager::configure)
}

@Composable
internal fun SoftwareUpdatesContent(state:SoftwareUpdateState,onCheck:()->Unit,onDownload:()->Unit,onInstall:()->Unit,onCancel:()->Unit,onConfigure:(Boolean,Boolean)->Unit){
    val context=LocalContext.current
    val release=state.release
    val busy=state.checking||state.downloading
    ScreenContainer(Modifier.testTag("software-updates-screen")){
        ScreenTitle(stringResource(R.string.software_updates),stringResource(R.string.update_intro))
        AppCard(color=MaterialTheme.colorScheme.primaryContainer){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                Icon(if(state.ready)Icons.Outlined.CheckCircleOutline else Icons.Outlined.SystemUpdate,null,Modifier.size(30.dp))
                Column(Modifier.weight(1f)){
                    Text(stringResource(when{state.checking->R.string.update_checking;state.downloading->R.string.update_downloading;state.ready->R.string.update_ready;release!=null->R.string.update_available;state.lastChecked>0&&state.error==null->R.string.update_current;else->R.string.update_check_title}),style=MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.update_installed_version,BuildConfig.VERSION_NAME),style=MaterialTheme.typography.bodySmall)
                }
            }
            if(release!=null)Text(stringResource(R.string.update_release_size,release.version,release.sizeBytes/1_000_000.0),style=MaterialTheme.typography.bodyMedium)
            if(state.downloading && release!=null){
                LinearProgressIndicator(progress={ (state.bytes.toFloat()/release.sizeBytes).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_download_progress,state.bytes/1_000_000.0,release.sizeBytes/1_000_000.0),style=MaterialTheme.typography.bodySmall)
            }
            if(state.awaitingInstall)Text(stringResource(R.string.update_install_approval),style=MaterialTheme.typography.bodySmall)
            state.error?.let{Text(stringResource(when(it){"update_integrity","update_metadata"->R.string.update_error_integrity;"update_install_failed"->R.string.update_error_install;"update_newer_android"->R.string.update_error_android;else->R.string.update_error_network}),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("update-error"))}
            Button(onClick=when{state.ready->onInstall;release!=null->onDownload;else->onCheck},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp).testTag("update-primary"),shape=RoundedCornerShape(16.dp)){
                Text(stringResource(when{state.ready->R.string.update_install;release!=null->R.string.update_download;else->R.string.update_check_now}))
            }
            if(busy)TextButton(onClick=onCancel){Text(stringResource(R.string.cancel))}
            else if(release!=null)TextButton(onClick=onCheck){Text(stringResource(R.string.update_check_again))}
            if(state.lastChecked>0)Text(stringResource(R.string.update_checked_at,DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(state.lastChecked))),style=MaterialTheme.typography.bodySmall)
        }
        if(release!=null)AppCard{
            Text(stringResource(R.string.update_changes),style=MaterialTheme.typography.titleMedium)
            RichResponse(release.notes.ifBlank{stringResource(R.string.update_notes_fallback)})
            TextButton(onClick={openSource(context,release.releaseUrl)}){Text(stringResource(R.string.update_full_release))}
        }
        AppCard{
            Text(stringResource(R.string.update_preferences),style=MaterialTheme.typography.titleMedium)
            UpdateToggle(stringResource(R.string.update_automatic),state.automaticChecks){onConfigure(it,state.downloadOnWifi)}
            UpdateToggle(stringResource(R.string.update_wifi),state.downloadOnWifi){onConfigure(state.automaticChecks,it)}
            Text(stringResource(R.string.update_safety_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun UpdateToggle(label:String,value:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(value,onChange,modifier=Modifier.semantics { contentDescription = label })
    }
}
