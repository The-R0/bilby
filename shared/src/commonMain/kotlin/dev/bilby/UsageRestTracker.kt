package dev.bilby

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 连续使用提醒的界面状态。
 *
 * @property reminderVisible 是否正在展示休息提醒弹窗。
 * @property continuousMinutes 触发当前提醒时累计的连续前台使用分钟数(含延后后再次提醒)。
 */
data class UsageRestState(
    val reminderVisible: Boolean = false,
    val continuousMinutes: Int = 0,
)

/**
 * 连续使用提醒计时器(主线程调用)。
 *
 * 累计应用处于前台(`STARTED`)的连续使用时长:短时切走再回来会接着算,离开或设备休眠达到
 * [resetGapMillis] 视作已经休息并清零重计。达到 [intervalMillis](默认 60 分钟)后触发提醒;
 * 选择休息后清零重计,选择延后则再过 [snoozeMillis](默认 15 分钟)再次提醒。
 *
 * 使用单调时钟 [elapsedRealtimeMillis] 而不是挂钟时间,避免系统校时或跨时区触发误报。
 */
class UsageRestTracker(
    private val clock: () -> Long = ::elapsedRealtimeMillis,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val snoozeMillis: Long = DEFAULT_SNOOZE_MILLIS,
    private val resetGapMillis: Long = DEFAULT_RESET_GAP_MILLIS,
) {
    private val _state = MutableStateFlow(UsageRestState())
    val state: StateFlow<UsageRestState> = _state.asStateFlow()

    private var enabled = false
    private var inForeground = false
    private var accumulatedMillis = 0L
    private var nextTriggerMillis = intervalMillis
    private var lastTickMillis: Long? = null
    private var backgroundSinceMillis: Long? = null

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        resetAccumulation()
        if (enabled && inForeground) {
            lastTickMillis = clock()
        }
    }

    fun onForegroundEntered() {
        if (inForeground) return
        inForeground = true
        if (!enabled) return
        val now = clock()
        val awaySince = backgroundSinceMillis
        backgroundSinceMillis = null
        if (awaySince != null) {
            val awayDuration = (now - awaySince).coerceAtLeast(0L)
            if (awayDuration >= resetGapMillis) {
                resetAccumulation()
            }
        }
        lastTickMillis = now
    }

    fun onBackgroundEntered() {
        if (!inForeground) return
        val now = clock()
        if (enabled && !_state.value.reminderVisible) {
            advanceTo(now)
        }
        inForeground = false
        lastTickMillis = null
        backgroundSinceMillis = now
    }

    fun tick() {
        if (!enabled || !inForeground) return
        advanceTo(clock())
    }

    /** 距下一次触发还剩多少毫秒,供协程按需挂起。 */
    fun millisUntilNextTrigger(): Long {
        if (!enabled || _state.value.reminderVisible) return DEFAULT_POLL_INTERVAL_MILLIS
        return (nextTriggerMillis - accumulatedMillis).coerceAtLeast(1L)
    }

    /** 用户选择「休息一下」:清零本轮累计时长并关闭弹窗。 */
    fun takeBreak() {
        resetAccumulation()
        if (enabled && inForeground) {
            lastTickMillis = clock()
        }
    }

    /** 用户选择延后或点按外部关闭:延后 [snoozeMillis] 后再次提醒,保留已累计时长。 */
    fun snooze() {
        val now = clock()
        nextTriggerMillis = accumulatedMillis + snoozeMillis
        lastTickMillis = if (enabled && inForeground) now else null
        _state.update { it.copy(reminderVisible = false) }
    }

    private fun advanceTo(now: Long) {
        val prev = lastTickMillis ?: now
        lastTickMillis = now
        val delta = (now - prev).coerceAtLeast(0L)
        if (_state.value.reminderVisible) return
        // 前台协程两次 tick 之间出现大段空窗(如电脑合盖休眠未收到 ON_STOP),视作已经休息。
        if (delta >= resetGapMillis) {
            resetAccumulation()
            lastTickMillis = now
            return
        }
        accumulatedMillis += delta
        if (accumulatedMillis >= nextTriggerMillis) {
            val minutes = (accumulatedMillis / MILLIS_PER_MINUTE).toInt().coerceAtLeast(1)
            _state.value = UsageRestState(
                reminderVisible = true,
                continuousMinutes = minutes,
            )
        }
    }

    private fun resetAccumulation() {
        accumulatedMillis = 0L
        nextTriggerMillis = intervalMillis
        lastTickMillis = null
        backgroundSinceMillis = null
        _state.value = UsageRestState()
    }

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
        const val DEFAULT_INTERVAL_MILLIS = 60 * MILLIS_PER_MINUTE
        const val DEFAULT_SNOOZE_MILLIS = 15 * MILLIS_PER_MINUTE
        const val DEFAULT_RESET_GAP_MILLIS = 5 * MILLIS_PER_MINUTE
        const val DEFAULT_POLL_INTERVAL_MILLIS = 10_000L
    }
}
