package dev.bilby.ui.player

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 核实 [SurfaceSyncContainer] 在竖屏原地全屏缩放、位移、去抖与卸载时的真实视图同步行为。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1080dp-h2400dp-mdpi")
class SurfaceSyncContainerTest {

    @Test
    fun `原地竖屏全屏缩放与位移触发同步且相同边界不循环`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
        val host = FrameLayout(activity)
        val container = SurfaceSyncContainer(activity)
        host.addView(container, frameParams(left = 369, top = 110, width = 342, height = 608))
        activity.setContentView(host)
        idle()

        assertTrue("container 应已挂载到窗口", container.isAttachedToWindow)

        // 1. 内嵌 16:9 盒子里 9:16 竖屏视频首次布局(约 342x608 @ (369, 110))
        val afterEmbedded = container.syncExecutionCount
        assertTrue("首次布局后应执行同步", afterEmbedded >= 1)
        assertEquals(342, container.surfaceView.width)
        assertEquals(608, container.surfaceView.height)

        // 2. 相同边界再次触发布局不重复触发同步,避免死循环
        container.layoutParams = frameParams(left = 369, top = 110, width = 342, height = 608)
        container.requestLayout()
        idle()
        assertEquals(afterEmbedded, container.syncExecutionCount)

        // 3. 首次竖屏全屏:无旋转、原地跨 3 倍以上尺寸跃迁(1080x1920 @ (0, 240))
        container.layoutParams = frameParams(left = 0, top = 240, width = 1080, height = 1920)
        container.requestLayout()
        idle()
        assertEquals(afterEmbedded + 1, container.syncExecutionCount)
        assertEquals(1080, container.surfaceView.width)
        assertEquals(1920, container.surfaceView.height)

        // 4. 仅窗口位置变化(例如顶部状态栏 Spacer 撤掉或系统栏隐藏)也要同步
        container.layoutParams = frameParams(left = 0, top = 180, width = 1080, height = 1920)
        container.requestLayout()
        idle()
        assertEquals(afterEmbedded + 2, container.syncExecutionCount)

        // 5. 同帧多次触发合并为一次同步
        container.scheduleSurfaceSync()
        container.scheduleSurfaceSync()
        container.scheduleSurfaceSync()
        idle()
        assertEquals(afterEmbedded + 3, container.syncExecutionCount)

        // 6. 卸载前取消挂起任务,重新挂载后恢复同步能力
        container.scheduleSurfaceSync()
        host.removeView(container)
        idle()
        assertEquals(afterEmbedded + 3, container.syncExecutionCount)

        host.addView(container, frameParams(left = 0, top = 240, width = 1080, height = 1920))
        idle()
        assertEquals(afterEmbedded + 4, container.syncExecutionCount)
    }

    private fun frameParams(left: Int, top: Int, width: Int, height: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
        }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
