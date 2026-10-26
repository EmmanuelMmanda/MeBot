package mebot.nguli.com.security

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.activity.ComponentActivity
import java.util.WeakHashMap

/** Synchronous native protection; it does not wait for a lifecycle-aware Flow collector or Compose frame. */
class PrivacyCurtain(private val activity: ComponentActivity, private val controller: AppLockController) {
    var epoch by mutableLongStateOf(0)
        private set
    private var covered = false
    private val content get() = activity.findViewById<View>(android.R.id.content)
    private val cover = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true; isFocusable = true; isFocusableInTouchMode = true
        contentDescription = activity.getString(mebot.nguli.com.R.string.app_name)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnTouchListener { _, _ -> true }
        setOnKeyListener { _, _, _ -> true }
        visibility = View.GONE
    }
    private data class ProtectedRoot(val ticket: Long, val visibility: Int, val accessibility: Int)
    private val roots = mutableMapOf<View, ProtectedRoot>()
    init {
        owners[activity] = this
        (activity.window.decorView as ViewGroup).addView(cover,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    fun cover() {
        if (!controller.state.value.enabled) return
        epoch++
        covered = true
        content?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        cover.visibility = View.VISIBLE
        cover.bringToFront(); cover.requestFocus()
        roots.keys.toList().forEach(::hide)
    }
    /** Called only after the corresponding protected/locked composition has committed. */
    fun committed(expectedEpoch: Long, expectedTicket: Long, expectedLocked: Boolean): Boolean {
        if (expectedEpoch != epoch || expectedTicket != controller.ticket() ||
            expectedLocked != controller.state.value.locked) return false
        covered = false
        cover.visibility = View.GONE
        content?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (!expectedLocked) roots.forEach { (root, saved) ->
            if (!controller.state.value.enabled || saved.ticket == expectedTicket) { root.visibility = saved.visibility; root.importantForAccessibility = saved.accessibility }
        }
        return true
    }
    private fun hide(root: View) {
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        root.visibility = View.INVISIBLE
    }
    fun register(root: View): () -> Unit {
        if (root === activity.window.decorView) return {}
        val saved = ProtectedRoot(controller.ticket(), root.visibility, root.importantForAccessibility)
        roots[root] = saved
        fun restricted() = covered || (controller.state.value.enabled &&
            (controller.state.value.locked || saved.ticket != controller.ticket()))
        if (restricted()) hide(root)
        val beforeDraw = ViewTreeObserver.OnPreDrawListener {
            if (restricted()) { hide(root); false } else true
        }
        root.viewTreeObserver.addOnPreDrawListener(beforeDraw)
        return {
            roots.remove(root)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(beforeDraw)
            // Do not reveal a disposed but not-yet-dismissed sensitive window.
        }
    }
    internal fun isCovered() = covered
    internal fun registeredRootCount() = roots.size
    internal fun registeredRootsHidden() = roots.keys.all { it.visibility != View.VISIBLE }
    fun close() {
        owners.remove(activity)
        roots.clear()
        (cover.parent as? ViewGroup)?.removeView(cover)
    }
    companion object {
        private val owners = WeakHashMap<ComponentActivity, PrivacyCurtain>()
        internal fun forActivity(activity: ComponentActivity?) = activity?.let(owners::get)
    }
}

/** Put inside every personal Dialog, AlertDialog, DropdownMenu and ModalBottomSheet window. */
@Composable
fun ProtectedWindowRegistration() {
    val view = LocalView.current
    val activity = LocalContext.current.findComponentActivity()
    val curtain = PrivacyCurtain.forActivity(activity)
    DisposableEffect(view, curtain) {
        var root: View? = null
        var remove: (() -> Unit)? = null
        fun attach() {
            val current = view.rootView
            if (current !== root) { remove?.invoke(); root = current; remove = curtain?.register(current) }
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = attach()
            override fun onViewDetachedFromWindow(v: View) { remove?.invoke(); remove = null; root = null }
        }
        view.addOnAttachStateChangeListener(listener)
        attach()
        onDispose { remove?.invoke(); view.removeOnAttachStateChangeListener(listener) }
    }
}

private fun android.content.Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
