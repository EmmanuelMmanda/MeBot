package mebot.nguli.com.security

import android.app.Dialog
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No Flow collector/Compose frame is run between cover() and assertions: native hiding is synchronous. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivacyCurtainTest {
    private fun controller(): AppLockController {
        val record = LockRecord(salt = ByteArray(32), verifier = ByteArray(32), biometric = true)
        return AppLockController(object : LockStorage {
            override fun enabled() = true
            override fun read() = record
            override fun write(record: LockRecord) {}
            override fun disable() {}
        }, { 100_000 }, { 1 }, { false }).apply { foreground(true); biometricSuccess(ticket()) }
    }
    @Test fun coverIsImmediateWithoutCollectorAndStaleFrameCannotRemoveIt() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val c = controller(); val curtain = PrivacyCurtain(activity, c)
        val oldTicket = c.ticket(); val oldEpoch = curtain.epoch
        curtain.cover(); c.foreground(false)
        assertTrue(curtain.isCovered())
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, activity.findViewById<View>(android.R.id.content).importantForAccessibility)
        assertFalse(curtain.committed(oldEpoch, oldTicket, false))
        assertFalse(curtain.committed(curtain.epoch, c.ticket(), false))
        assertTrue(curtain.isCovered())
        assertTrue(curtain.committed(curtain.epoch, c.ticket(), true))
        assertFalse(curtain.isCovered())
        curtain.close()
    }
    @Test fun repeatedPauseWhileAlreadyLockedRequiresCurrentEpochAcknowledgment() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val c = controller(); val curtain = PrivacyCurtain(activity, c)
        curtain.cover(); c.lock(); val first = curtain.epoch
        curtain.cover() // Same locked state; no new StateFlow emission is required.
        assertFalse(curtain.committed(first, c.ticket(), true))
        assertTrue(curtain.isCovered())
        assertTrue(curtain.committed(curtain.epoch, c.ticket(), true))
        curtain.close()
    }
    @Test fun independentSensitiveDialogAndMediaWindowsStayHiddenAcrossOldEpoch() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val c = controller(); val curtain = PrivacyCurtain(activity, c)
        val contextDialog = Dialog(activity).apply { setContentView(TextView(activity).apply { text = "Synthetic private note" }); show() }
        val mediaDialog = Dialog(activity).apply { setContentView(TextView(activity).apply { text = "Synthetic PDF page" }); show() }
        val contextRoot = contextDialog.window!!.decorView; val mediaRoot = mediaDialog.window!!.decorView
        val removeContext = curtain.register(contextRoot); val removeMedia = curtain.register(mediaRoot)
        val oldTicket = c.ticket()
        curtain.cover(); c.lock()
        assertEquals(View.INVISIBLE, contextRoot.visibility); assertEquals(View.INVISIBLE, mediaRoot.visibility)
        assertFalse(curtain.committed(curtain.epoch, oldTicket, false))
        assertTrue(curtain.committed(curtain.epoch, c.ticket(), true))
        assertEquals(View.INVISIBLE, contextRoot.visibility); assertEquals(View.INVISIBLE, mediaRoot.visibility)
        c.foreground(true); assertTrue(c.biometricSuccess(c.ticket()))
        assertTrue(curtain.committed(curtain.epoch, c.ticket(), false))
        assertEquals(View.INVISIBLE, contextRoot.visibility) // Old generation cannot be resurrected.
        removeContext(); removeMedia(); contextDialog.dismiss(); mediaDialog.dismiss(); curtain.close()
    }
    @Test fun disabledProtectionNeverHidesRetainedDialogOrPopupAfterPauseResume() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val c = AppLockController(object : LockStorage {
            override fun enabled() = false
            override fun read(): LockRecord? = null
            override fun write(record: LockRecord) {}
            override fun disable() {}
        }, { 100_000 }, { 1 }, { false })
        c.foreground(true)
        val curtain = PrivacyCurtain(activity, c)
        val dialog = Dialog(activity).apply { setContentView(TextView(activity).apply { text = "Normal context" }); show() }
        val root = dialog.window!!.decorView
        val popup = android.widget.FrameLayout(activity)
        val removeDialog = curtain.register(root); val removePopup = curtain.register(popup)
        curtain.cover(); c.foreground(false); c.foreground(true)
        root.viewTreeObserver.dispatchOnPreDraw(); popup.viewTreeObserver.dispatchOnPreDraw()
        assertFalse(curtain.isCovered())
        assertEquals(View.VISIBLE, root.visibility); assertEquals(View.VISIBLE, popup.visibility)
        assertTrue(curtain.committed(curtain.epoch, c.ticket(), false))
        removeDialog(); removePopup(); dialog.dismiss(); curtain.close()
    }
    @Test fun newlyRegisteredPopupRootWhileLockedIsImmediatelyHidden() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val c = controller(); val curtain = PrivacyCurtain(activity, c)
        curtain.cover(); c.lock()
        val popupRoot = android.widget.FrameLayout(activity)
        val remove = curtain.register(popupRoot)
        assertEquals(View.INVISIBLE, popupRoot.visibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, popupRoot.importantForAccessibility)
        remove(); curtain.close()
    }
}
