package com.autoalbum

import com.autoalbum.player.AutoplayController
import com.autoalbum.player.AutoplayDirection
import org.junit.Assert.*
import org.junit.Test

class AutoplayControllerTest {

    @Test
    fun testInitialStateIsNone() {
        val controller = AutoplayController(videoCount = 5, initialIndex = 0)
        assertEquals(AutoplayDirection.NONE, controller.direction)
        assertEquals(0, controller.currentIndex)
        assertFalse(controller.isEndOfList)
        assertNull(controller.onVideoCompleted())
    }

    @Test
    fun testForwardDirectionSelectionDoesNotChangeIndex() {
        val controller = AutoplayController(videoCount = 5, initialIndex = 2)
        controller.selectForward()
        assertEquals(AutoplayDirection.FORWARD, controller.direction)
        assertEquals(2, controller.currentIndex) // Must not skip or change immediately
    }

    @Test
    fun testBackwardDirectionSelectionDoesNotChangeIndex() {
        val controller = AutoplayController(videoCount = 5, initialIndex = 2)
        controller.selectBackward()
        assertEquals(AutoplayDirection.BACKWARD, controller.direction)
        assertEquals(2, controller.currentIndex) // Must not skip or change immediately
    }

    @Test
    fun testForwardAutoplayAdvancesSequentially() {
        val controller = AutoplayController(videoCount = 3, initialIndex = 0)
        controller.selectForward()

        val next1 = controller.onVideoCompleted()
        assertEquals(1, next1)
        assertEquals(1, controller.currentIndex)

        val next2 = controller.onVideoCompleted()
        assertEquals(2, next2)
        assertEquals(2, controller.currentIndex)

        // At end of list (index 2 of 3 items: 0, 1, 2)
        val next3 = controller.onVideoCompleted()
        assertNull(next3)
        assertEquals(2, controller.currentIndex)
        assertTrue(controller.isEndOfList)
        assertTrue(controller.endOfListMessage?.contains("end of album") == true)
    }

    @Test
    fun testBackwardAutoplayAdvancesSequentially() {
        val controller = AutoplayController(videoCount = 3, initialIndex = 2)
        controller.selectBackward()

        val prev1 = controller.onVideoCompleted()
        assertEquals(1, prev1)
        assertEquals(1, controller.currentIndex)

        val prev2 = controller.onVideoCompleted()
        assertEquals(0, prev2)
        assertEquals(0, controller.currentIndex)

        // At start of list (index 0)
        val prev3 = controller.onVideoCompleted()
        assertNull(prev3)
        assertEquals(0, controller.currentIndex)
        assertTrue(controller.isEndOfList)
        assertTrue(controller.endOfListMessage?.contains("start of album") == true)
    }

    @Test
    fun testOnlyOneDirectionSelectedAtATime() {
        val controller = AutoplayController(videoCount = 5, initialIndex = 1)
        controller.selectForward()
        assertEquals(AutoplayDirection.FORWARD, controller.direction)

        controller.selectBackward()
        assertEquals(AutoplayDirection.BACKWARD, controller.direction)

        controller.selectForward()
        assertEquals(AutoplayDirection.FORWARD, controller.direction)
    }

    @Test
    fun testToggleDirectionOff() {
        val controller = AutoplayController(videoCount = 5, initialIndex = 1)
        controller.selectForward()
        assertEquals(AutoplayDirection.FORWARD, controller.direction)

        controller.selectForward()
        assertEquals(AutoplayDirection.NONE, controller.direction)
        assertNull(controller.onVideoCompleted())
    }

    @Test
    fun testJumpToIndex() {
        val controller = AutoplayController(videoCount = 10, initialIndex = 0)
        controller.selectForward()
        controller.jumpToIndex(7)
        assertEquals(7, controller.currentIndex)
        assertEquals(AutoplayDirection.FORWARD, controller.direction) // Direction preserved

        val next = controller.onVideoCompleted()
        assertEquals(8, next)
    }

    @Test
    fun testManualSwipeJumpPreservesBackwardDirection() {
        val controller = AutoplayController(videoCount = 5, initialIndex = 4)
        controller.selectBackward()
        assertEquals(AutoplayDirection.BACKWARD, controller.direction)

        // Manual swipe to index 2
        controller.jumpToIndex(2)
        assertEquals(2, controller.currentIndex)
        assertEquals(AutoplayDirection.BACKWARD, controller.direction) // Must remain BACKWARD

        // On completion, should advance backwards to 1
        val prev = controller.onVideoCompleted()
        assertEquals(1, prev)
        assertEquals(1, controller.currentIndex)
    }

    @Test
    fun testJumpToIndexOutOfBoundsIsIgnored() {
        val controller = AutoplayController(videoCount = 5, initialIndex = 2)
        controller.jumpToIndex(-1)
        assertEquals(2, controller.currentIndex) // Ignored, kept at 2

        controller.jumpToIndex(5)
        assertEquals(2, controller.currentIndex) // Ignored, kept at 2
    }

    @Test
    fun testManualSwipeAtLastVideoDoesNotLoop() {
        val controller = AutoplayController(videoCount = 3, initialIndex = 2)
        controller.selectForward()

        // At last item (index 2), attempting to advance via completion
        val next = controller.onVideoCompleted()
        assertNull(next)
        assertEquals(2, controller.currentIndex)
        assertTrue(controller.isEndOfList)

        // Manual jump resets end of list flag safely
        controller.jumpToIndex(1)
        assertFalse(controller.isEndOfList)
        assertEquals(1, controller.currentIndex)
    }
}
