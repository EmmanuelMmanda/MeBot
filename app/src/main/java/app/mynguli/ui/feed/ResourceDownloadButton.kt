package app.mynguli.ui.feed

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mynguli.R
import app.mynguli.core.DownloadResource
import app.mynguli.feed.ResourceDownloader
import app.mynguli.feed.ResourceOperations
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ResourceDownloadButton(url: String) {
    val resource = remember(url) { DownloadResource.from(url) } ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPath by rememberSaveable(url) { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = pendingPath; pendingPath = null
        val temp = path?.let(::File)
        if (temp != null && temp.parentFile?.canonicalPath == File(context.cacheDir, "resource-downloads").canonicalPath) {
            val uri = result.data?.data
            if (result.resultCode == Activity.RESULT_OK && uri != null) {
                scope.launch {
                    working = true; failed = false
                    try {
                        ResourceOperations.run("saving") {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openOutputStream(uri, "w").use { output ->
                                    requireNotNull(output)
                                    temp.inputStream().use { it.copyTo(output) }
                                }
                            }
                        }
                        Toast.makeText(context, R.string.resource_saved, Toast.LENGTH_SHORT).show()
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { failed = true }
                    finally { temp.delete(); working = false }
                }
            } else temp.delete()
        } else if (path != null) failed = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = {
            if (!working && pendingPath == null) scope.launch {
                working = true; failed = false
                try {
                    val file = ResourceOperations.run("downloading") { ResourceDownloader(context.applicationContext).fetch(resource) }
                    pendingPath = file.path
                    picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(resource.mime).putExtra(Intent.EXTRA_TITLE, resource.name))
                } catch (_: TimeoutCancellationException) { failed = true }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { failed = true; pendingPath?.let { File(it).delete() }; pendingPath = null }
                finally { working = false }
            }
        }, enabled = !working && pendingPath == null, modifier = Modifier.heightIn(min = 48.dp).testTag("download-resource"),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(stringResource(if (working) R.string.resource_wait else R.string.resource_download))
        }
        if (failed) Text(stringResource(R.string.resource_failed), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("resource-download-error"))
    }
}
