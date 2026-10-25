package mebot.nguli.com.ui.feed

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mebot.nguli.com.BuildConfig
import mebot.nguli.com.R

/** Licensed editorial photography, never presented as today's news or a user's location. */
@Composable internal fun EditorialPhoto(dar:Boolean,height:Dp=132.dp) {
    val context=LocalContext.current
    val source=if(dar)"https://commons.wikimedia.org/wiki/File:Dar_es_salaam_Skyline_from_the_sea.jpg" else "https://science.nasa.gov/resource/earth-at-night/"
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Image(painterResource(if(dar)R.drawable.onboarding_dar else R.drawable.onboarding_earth),
            stringResource(if(dar)R.string.photo_dar_alt else R.string.photo_earth_alt),contentScale=ContentScale.Crop,
            modifier=Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(18.dp)))
        TextButton(onClick={openSource(context,source)},contentPadding=PaddingValues(horizontal=0.dp,vertical=0.dp),modifier=Modifier.heightIn(min=40.dp)){
            Text(stringResource(if(dar)R.string.photo_dar_credit else R.string.photo_earth_credit),style=MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable internal fun AboutMeBotContent() {
    val context=LocalContext.current
    EditorialPhoto(false,118.dp)
    Text(stringResource(R.string.app_name),style=MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.version_label,BuildConfig.VERSION_NAME),style=MaterialTheme.typography.labelMedium)
    Text(stringResource(R.string.about_project),style=MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.mb_data_summary),style=MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.license_label),style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={openSource(context,"https://github.com/EmmanuelMmanda/MeBot")}){Text(stringResource(R.string.about_source))}
    TextButton(onClick={openSource(context,"https://github.com/EmmanuelMmanda/MeBot/blob/main/docs/IMAGE-CREDITS.md")}){Text(stringResource(R.string.about_images))}
}
