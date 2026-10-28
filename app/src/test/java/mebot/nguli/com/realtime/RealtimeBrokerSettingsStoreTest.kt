package mebot.nguli.com.realtime

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealtimeBrokerSettingsStoreTest {
    @Test fun clearWithoutSavedCredentialDoesNotOpenAndroidKeyStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("mebot.realtime.broker", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())

        // Robolectric does not provide AndroidKeyStore. First-run and full-reset paths must
        // still clear this dedicated namespace without trying to initialize that provider.
        RealtimeBrokerSettingsStore(context).clear()
        assertTrue(prefs.all.isEmpty())
    }
}
