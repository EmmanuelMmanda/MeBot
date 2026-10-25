package mebot.nguli.com.ui

import androidx.compose.ui.geometry.Offset
import mebot.nguli.com.ui.feed.mediaPanBounds
import org.junit.Assert.*
import org.junit.Test

class MediaPanBounds085Test {
    @Test fun portraitLetterboxCannotPanOutOfViewHorizontally() {
        assertEquals(Offset(0f, 2000f), mediaPanBounds(100, 1000, 1000, 1000, 5f))
    }
    @Test fun landscapeLetterboxCannotPanOutOfViewVertically() {
        assertEquals(Offset(2000f, 0f), mediaPanBounds(1000, 100, 1000, 1000, 5f))
    }
    @Test fun fitAndViewportResizeUseActualImageDimensions() {
        assertEquals(Offset.Zero, mediaPanBounds(100, 200, 300, 400, 1f))
        assertEquals(Offset(50f, 200f), mediaPanBounds(100, 200, 300, 400, 2f))
        assertEquals(Offset.Zero, mediaPanBounds(100, 200, 0, 400, 2f))
    }
}
