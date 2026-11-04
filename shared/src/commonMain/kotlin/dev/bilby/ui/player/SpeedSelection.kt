package dev.bilby.ui.player

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

internal const val SpeedHorizontalBlockStepDp = 84f
internal const val SpeedVerticalBlockStepDp = 46f
internal const val SpeedDragStepDp = 36f
internal const val SpeedModeStepDp = 32f
internal const val SpeedSelectionIntentDp = 10f

internal val SpeedPresets = listOf(50, 100, 150, 200, 250, 300)
internal const val DefaultHoldSpeed = 2.5f

internal fun speedHundredths(speed: Float): Int = (speed * 100).roundToInt().coerceIn(25, 300)

internal fun speedNumber(value: Int): String = when {
    value % 100 == 0 -> (value / 100).toString()
    value % 10 == 0 -> "${value / 100}.${value % 100 / 10}"
    else -> "${value / 100}.${(value % 100).toString().padStart(2, '0')}"
}

internal data class SpeedSelection(
    val value: Int,
    val choices: List<Int>,
    val position: Float,
    val temporary: Boolean,
    val locked: Boolean = false,
    val dockEnd: Boolean = false,
    val dockTop: Boolean = false,
) {
    val speed: Float get() = value / 100f

    fun coarseAt(position: Float): SpeedSelection {
        val bounded = position.coerceIn(0f, choices.lastIndex.toFloat())
        return copy(position = bounded, value = choices[bounded.roundToInt()])
    }

    fun snapped(): SpeedSelection {
        val index = choices.indexOf(value).takeIf { it >= 0 } ?: position.roundToInt().coerceIn(0, choices.lastIndex)
        return copy(position = index.toFloat(), value = choices[index])
    }

    fun nearestTo(target: Int): SpeedSelection {
        val nearest = choices.minByOrNull { abs(it - target) } ?: value
        return copy(position = choices.indexOf(nearest).toFloat(), value = nearest)
    }

    companion object {
        fun start(
            initial: Float,
            temporary: Boolean,
            dockEnd: Boolean = false,
            dockTop: Boolean = false,
        ): SpeedSelection {
            val value = speedHundredths(initial)
            val choices = (SpeedPresets + value).distinct().sorted()
            return SpeedSelection(
                value = value,
                choices = choices,
                position = choices.indexOf(value).toFloat(),
                temporary = temporary,
                dockEnd = dockEnd,
                dockTop = dockTop,
            )
        }
    }
}

/** 按手指沿主轴的增量位移纯跟手换档，往返转向即时响应，正交方向滑动不锁定。 */
internal class SpeedSelectionDrag {
    private var lastAlong = 0f
    private var startAlong = 0f
    private var engaged = false

    fun move(
        selection: SpeedSelection,
        along: Float,
        @Suppress("UNUSED_PARAMETER")
        across: Float = 0f,
    ): SpeedSelection {
        if (selection.locked) return selection
        val deltaAlong = along - lastAlong
        lastAlong = along
        if (!engaged) {
            val localAlong = along - startAlong
            if (abs(localAlong) < SpeedSelectionIntentDp) return selection
            engaged = true
            val effective = localAlong - SpeedSelectionIntentDp * sign(localAlong)
            return selection.coarseAt(selection.position + effective / SpeedDragStepDp)
        }
        return selection.coarseAt(selection.position + deltaAlong / SpeedDragStepDp)
    }
}
