package mebot.nguli.com.alarm

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

internal fun Context.alarmLocale(language: String): Context {
    val configuration = Configuration(resources.configuration)
    val code = if (language == "system") Locale.getDefault().language else language
    configuration.setLocale(Locale.forLanguageTag(if (code == "sw") "sw" else "en"))
    return createConfigurationContext(configuration)
}

internal fun Context.alarmString(@StringRes resource: Int, vararg args: Any): String =
    alarmLocale(AlarmStore(this).language).getString(resource, *args)
