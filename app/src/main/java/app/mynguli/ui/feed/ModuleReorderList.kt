package app.mynguli.ui.feed

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.mynguli.R
import app.mynguli.core.FeedModule
import app.mynguli.core.ModuleOrder
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** Bounded catalog list. The outer boxes retain layout bounds while the dragged row follows a finger. */
@Composable
internal fun ModuleReorderList(modules: List<FeedModule>, scroll: ScrollState, viewport: Rect,
    error: String?, onDismissError: () -> Unit, onReorder: (List<String>) -> Unit,
    onEdit: (String?) -> Unit, onToggle: (String, Boolean) -> Unit,
    visibleIds: Set<String> = modules.filter { it.enabled || it.kind == "watch" }.map { it.id }.toSet()) {
    val latestModules by rememberUpdatedState(modules)
    val latestVisibleIds by rememberUpdatedState(visibleIds)
    val latestSubmit by rememberUpdatedState(onReorder)
    var draftIds by rememberSaveable { mutableStateOf(modules.map { it.id }) }
    var pendingIds by remember { mutableStateOf<List<String>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    val rowBounds = remember { mutableStateMapOf<String, Rect>() }
    val byId = modules.associateBy { it.id }
    val installedIds = draftIds.filter { it in latestVisibleIds && it in byId }
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val step = with(LocalDensity.current) { 12.dp.toPx() }
    val moveUp = stringResource(R.string.modules_move_up)
    val moveDown = stringResource(R.string.modules_move_down)

    fun cancelDrag() { draggingId = null; draftIds = latestModules.map { it.id } }
    fun submit(ids: List<String>) {
        val committed = latestModules.map { it.id }
        if (ids == committed) return
        if (ids.toSet() != committed.toSet()) { cancelDrag(); return }
        onDismissError()
        draftIds = ids
        pendingIds = ids
        latestSubmit(ids)
    }
    fun move(id: String, offset: Int) {
        if (pendingIds != null || draggingId != null) return
        val current = installedIds.toMutableList()
        val index = current.indexOf(id)
        val destination = index + offset
        if (index < 0 || destination !in current.indices) return
        current.add(destination, current.removeAt(index))
        submit(ModuleOrder.reorderSubset(draftIds, current))
    }
    fun reorderAtPointer() {
        val id = draggingId ?: return
        val current = draftIds.filter { key -> key in latestVisibleIds && latestModules.any { it.id == key } }
        val size = rowBounds[id]?.height ?: return
        val center = pointerY - grabOffset + size / 2f
        val target = current.mapNotNull { key -> rowBounds[key]?.takeIf { it.height > 0 }?.let { key to abs(it.center.y - center) } }
            .minByOrNull { it.second }?.first ?: return
        if (target == id) return
        val reordered = current.toMutableList()
        val from = reordered.indexOf(id)
        val to = reordered.indexOf(target)
        if (from < 0 || to < 0) return
        reordered.add(to, reordered.removeAt(from))
        draftIds = ModuleOrder.reorderSubset(draftIds, reordered)
    }

    LaunchedEffect(modules, visibleIds) {
        val committed = modules.map { it.id }
        if (committed.toSet() != draftIds.toSet()) { pendingIds = null; cancelDrag() }
        else if (pendingIds == committed) { pendingIds = null; draftIds = committed }
        else if (pendingIds == null && draggingId == null) draftIds = committed
        if (draggingId != null && (draggingId !in visibleIds || modules.none { it.id == draggingId })) cancelDrag()
    }
    LaunchedEffect(error) {
        if (error != null && pendingIds != null) { pendingIds = null; cancelDrag() }
    }
    LaunchedEffect(draggingId) {
        while (isActive && draggingId != null) {
            val visible = draftIds.filter { key -> key in latestVisibleIds && latestModules.any { it.id == key } }
            val index = visible.indexOf(draggingId)
            val delta = when {
                viewport.height <= 0 -> 0f
                pointerY < viewport.top + edge && index > 0 -> -step
                pointerY > viewport.bottom - edge && index in 0 until visible.lastIndex -> step
                else -> 0f
            }
            if (delta != 0f) { scroll.scrollBy(delta); reorderAtPointer() }
            withFrameNanos { }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (installedIds.size > 1) Text(stringResource(R.string.modules_reorder_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (pendingIds != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("module-order-pending"))
            Text(stringResource(R.string.modules_order_saving), style = MaterialTheme.typography.bodySmall)
        }
        installedIds.forEachIndexed { index, id -> key(id) {
            val module = byId.getValue(id)
            val dragging = draggingId == id
            var handleBounds by remember { mutableStateOf(Rect.Zero) }
            var menu by rememberSaveable { mutableStateOf(false) }
            val label = stringResource(R.string.modules_reorder, moduleName(module))
            Box(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                .onGloballyPositioned { rowBounds[id] = it.boundsInRoot() }.testTag("module-order-$id")) {
                ModuleRow(module, onEdit, onToggle,
                    interactionEnabled = pendingIds == null && draggingId == null,
                    modifier = Modifier.graphicsLayer {
                        translationY = if (dragging) pointerY - grabOffset - (rowBounds[id]?.top ?: 0f) else 0f
                    }, dragging = dragging,
                    dragHandle = if (installedIds.size < 2) null else {
                        {
                            Box {
                                IconButton(onClick = { menu = true }, enabled = pendingIds == null && draggingId == null,
                                    modifier = Modifier.size(48.dp).testTag("module-drag-$id")
                                        .onGloballyPositioned { handleBounds = it.boundsInRoot() }
                                        .semantics {
                                            contentDescription = label
                                            customActions = buildList {
                                                if (pendingIds == null && draggingId == null) {
                                                    if (index > 0) add(CustomAccessibilityAction(moveUp) { move(id, -1); true })
                                                    if (index < installedIds.lastIndex) add(CustomAccessibilityAction(moveDown) { move(id, 1); true })
                                                }
                                            }
                                        }
                                        .pointerInput(id, pendingIds) {
                                            if (pendingIds == null) detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    val bounds = rowBounds[id]
                                                    if (bounds != null) {
                                                        pointerY = handleBounds.top + offset.y
                                                        grabOffset = pointerY - bounds.top
                                                        draggingId = id
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    pointerY += amount.y
                                                    reorderAtPointer()
                                                },
                                                onDragEnd = { draggingId = null; submit(draftIds) },
                                                onDragCancel = { if (pendingIds == null) cancelDrag() else draggingId = null },
                                            )
                                        }) {
                                    Icon(Icons.Outlined.DragHandle, null, Modifier.size(21.dp))
                                }
                                DropdownMenu(expanded = menu && pendingIds == null, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(moveUp) }, enabled = index > 0,
                                        onClick = { menu = false; move(id, -1) }, modifier = Modifier.testTag("module-up-$id"))
                                    DropdownMenuItem(text = { Text(moveDown) }, enabled = index < installedIds.lastIndex,
                                        onClick = { menu = false; move(id, 1) }, modifier = Modifier.testTag("module-down-$id"))
                                }
                            }
                        }
                    })
            }
        } }
    }
}
