package dev.bilby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageRestTrackerTest {

    private companion object {
        const val MINUTE = 60_000L
    }

    private fun trackerWithClock(): Pair<UsageRestTracker, (Long) -> Unit> {
        var now = 1_000_000L
        val tracker = UsageRestTracker(clock = { now })
        val advanceBy: (Long) -> Unit = { deltaMillis -> now += deltaMillis }
        return tracker to advanceBy
    }

    @Test
    fun `连续使用满 60 分钟后触发休息提醒`() {
        val (tracker, advance) = trackerWithClock()
        tracker.setEnabled(true)
        tracker.onForegroundEntered()

        repeat(59) {
            advance(MINUTE)
            tracker.tick()
            assertFalse(tracker.state.value.reminderVisible)
        }

        advance(MINUTE)
        tracker.tick()

        assertTrue(tracker.state.value.reminderVisible)
        assertEquals(60, tracker.state.value.continuousMinutes)
    }

    @Test
    fun `短时切到后台不满 5 分钟时继续累计,满 5 分钟后清零重计`() {
        val (tracker, advance) = trackerWithClock()
        tracker.setEnabled(true)
        tracker.onForegroundEntered()

        // 前台使用 40 分钟
        repeat(40) {
            advance(MINUTE)
            tracker.tick()
        }
        // 切出应用 2 分钟(< 5 分钟)
        tracker.onBackgroundEntered()
        advance(2 * MINUTE)
        tracker.onForegroundEntered()

        // 再使用 20 分钟即达到累计 60 分钟
        repeat(20) {
            advance(MINUTE)
            tracker.tick()
        }
        assertTrue(tracker.state.value.reminderVisible)
        assertEquals(60, tracker.state.value.continuousMinutes)

        // 若延后后再切到后台超过 5 分钟,视为真正休息,清空累计和待提醒状态
        tracker.snooze()
        tracker.onBackgroundEntered()
        advance(5 * MINUTE)
        tracker.onForegroundEntered()

        repeat(59) {
            advance(MINUTE)
            tracker.tick()
            assertFalse(tracker.state.value.reminderVisible)
        }
        advance(MINUTE)
        tracker.tick()
        assertTrue(tracker.state.value.reminderVisible)
        assertEquals(60, tracker.state.value.continuousMinutes)
    }

    @Test
    fun `延后 15 分钟后再累计 15 分钟再次提醒并累加分钟数`() {
        val (tracker, advance) = trackerWithClock()
        tracker.setEnabled(true)
        tracker.onForegroundEntered()

        repeat(60) {
            advance(MINUTE)
            tracker.tick()
        }
        assertTrue(tracker.state.value.reminderVisible)
        assertEquals(60, tracker.state.value.continuousMinutes)

        tracker.snooze()
        assertFalse(tracker.state.value.reminderVisible)

        repeat(14) {
            advance(MINUTE)
            tracker.tick()
            assertFalse(tracker.state.value.reminderVisible)
        }
        advance(MINUTE)
        tracker.tick()
        assertTrue(tracker.state.value.reminderVisible)
        assertEquals(75, tracker.state.value.continuousMinutes)
    }

    @Test
    fun `选择休息后清零并在下一个 60 分钟重新提醒`() {
        val (tracker, advance) = trackerWithClock()
        tracker.setEnabled(true)
        tracker.onForegroundEntered()

        repeat(60) {
            advance(MINUTE)
            tracker.tick()
        }
        assertTrue(tracker.state.value.reminderVisible)

        tracker.takeBreak()
        assertFalse(tracker.state.value.reminderVisible)

        repeat(59) {
            advance(MINUTE)
            tracker.tick()
            assertFalse(tracker.state.value.reminderVisible)
        }
        advance(MINUTE)
        tracker.tick()
        assertTrue(tracker.state.value.reminderVisible)
        assertEquals(60, tracker.state.value.continuousMinutes)
    }

    @Test
    fun `单次前台 tick 跨越休眠空窗视作已休息而清零`() {
        val (tracker, advance) = trackerWithClock()
        tracker.setEnabled(true)
        tracker.onForegroundEntered()

        repeat(50) {
            advance(MINUTE)
            tracker.tick()
        }
        // 比如电脑休眠 10 分钟未经过 ON_STOP
        advance(10 * MINUTE)
        tracker.tick()
        assertFalse(tracker.state.value.reminderVisible)

        // 从 0 重新累计满 60 分钟才提醒
        repeat(59) {
            advance(MINUTE)
            tracker.tick()
            assertFalse(tracker.state.value.reminderVisible)
        }
        advance(MINUTE)
        tracker.tick()
        assertTrue(tracker.state.value.reminderVisible)
        assertEquals(60, tracker.state.value.continuousMinutes)
    }

    @Test
    fun `关闭提醒后立即清除状态且不再触发`() {
        val (tracker, advance) = trackerWithClock()
        tracker.setEnabled(true)
        tracker.onForegroundEntered()

        repeat(60) {
            advance(MINUTE)
            tracker.tick()
        }
        assertTrue(tracker.state.value.reminderVisible)

        tracker.setEnabled(false)
        assertFalse(tracker.state.value.reminderVisible)

        repeat(70) {
            advance(MINUTE)
            tracker.tick()
            assertFalse(tracker.state.value.reminderVisible)
        }
    }
}
