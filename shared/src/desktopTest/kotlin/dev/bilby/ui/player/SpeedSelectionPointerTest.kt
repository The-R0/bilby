package dev.bilby.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.bilby.player.*
import dev.bilby.ui.LocalPlaybackHost
import dev.bilby.ui.theme.BilbyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class SpeedSelectionPointerTest {
    @Test(timeout = 30000)
    fun realPlayerShellLongPressAndDrag() = runBlocking(Dispatchers.Swing) {
        val player = PointerTestPlayer().apply { setPlaybackSpeed(1.25f) }
        val host = PointerTestPlaybackHost(player)
        val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        lateinit var selector: PlaybackSpeedController
        var controls = true
        val scene = ImageComposeScene(360, 640, coroutineContext = coroutineContext) {
            BilbyTheme {
                CompositionLocalProvider(LocalPlaybackHost provides host, LocalNavigationEventDispatcherOwner provides navigation) {
                    selector = rememberPlaybackSpeedController(player, "qa", defaultHoldSpeed = 2.5f)
                    Box(Modifier.fillMaxSize()) {
                        PlayerShell(
                            player = player, attached = false, placeholderCoverUrl = "",
                            isFullscreen = false, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                            speedController = selector,
                            modifier = Modifier.size(360.dp, 202.dp),
                            overlay = { controls = controlsVisible },
                        )
                    }
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x, y), type = PointerType.Touch)
        try {
            frame()
            // 1. 偏下长按(y = 140 >= 101):选档条放画面上沿(dockTop = true),起手跳默认 2.5x,单滑直达 0.5x 并即时反向回拨
            event(PointerEventType.Press, 200f, 140f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertTrue("lower-half press must dock dial to top", selector.selection!!.dockTop)
            assertEquals("first hold must jump to default hold speed", 2.5f, player.playbackSpeed, 0f)
            assertFalse("normal controls should hide", controls)
            event(PointerEventType.Move, 162f, 140f)
            frame()
            assertEquals(2f, player.playbackSpeed, 0f)
            delay(600)
            frame()
            assertEquals("holding still must keep the chosen speed", 2f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 40f, 140f)
            frame()
            assertEquals("single swipe must directly reach 0.5x", .5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 112f, 140f)
            frame()
            assertEquals("reversing right responds immediately to 1.5x", 1.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Release, 112f, 140f)
            frame()
            assertEquals("plain release restores base speed", 1.25f, player.playbackSpeed, 0f)

            // 2. 偏上长按(y = 60 < 101):上滑/下滑不锁定,松手回 1.25x 且不触发普通点按
            event(PointerEventType.Press, 180f, 60f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertFalse("upper-half press must dock dial at bottom", selector.selection!!.dockTop)
            assertEquals(2.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 142f, 60f)
            frame()
            assertEquals(2f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 142f, 100f)
            frame()
            assertFalse("vertical slide must not lock", selector.selection!!.locked)
            event(PointerEventType.Release, 142f, 100f)
            frame()
            assertFalse(selector.active)
            assertEquals(1.25f, player.playbackSpeed, 0f)
            assertFalse(selector.lockedByHold)
            assertFalse("releasing a long press must not fire a normal tap", controls)

            // 3. 偏下长按并上滑同样不锁定,松手回面板设定的基速
            selector.applySpeed(1.5f)
            event(PointerEventType.Press, 180f, 150f)
            delay(650)
            frame()
            assertTrue(selector.selection!!.dockTop)
            assertEquals(2.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 220f, 150f)
            frame()
            assertEquals(3f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 220f, 110f)
            frame()
            assertFalse("upward slide must not lock", selector.selection!!.locked)
            event(PointerEventType.Release, 220f, 110f)
            frame()
            assertEquals(1.5f, player.playbackSpeed, 0f)
            assertFalse(selector.lockedByHold)

            // 4. 按住期间组件卸载时恢复起手前倍速
            selector.applySpeed(2f)
            event(PointerEventType.Press, 180f, 150f)
            delay(650)
            frame()
            assertEquals(2.5f, player.playbackSpeed, 0f)
            scene.setContent {}
            frame()
            assertFalse(selector.active)
            assertEquals("unmount must restore pre-hold speed", 2f, player.playbackSpeed, 0f)
            assertEquals(0, player.playPauseCalls)
            assertEquals(0, player.seeks)
        } finally { scene.close() }
    }

    @Test(timeout = 30000)
    fun landscapeUsesVerticalAxisAndSideDependentLock() = runBlocking(Dispatchers.Swing) {
        val player = PointerTestPlayer().apply { setPlaybackSpeed(1f) }
        val host = PointerTestPlaybackHost(player)
        val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        lateinit var selector: PlaybackSpeedController
        val scene = ImageComposeScene(660, 360, coroutineContext = coroutineContext) {
            BilbyTheme {
                CompositionLocalProvider(LocalPlaybackHost provides host, LocalNavigationEventDispatcherOwner provides navigation) {
                    selector = rememberPlaybackSpeedController(player, "landscape-qa", defaultHoldSpeed = 2.5f)
                    Box(Modifier.fillMaxSize()) {
                        PlayerShell(
                            player = player, attached = false, placeholderCoverUrl = "",
                            isFullscreen = true, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                            speedController = selector, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x, y), type = PointerType.Touch)
        try {
            frame()
            event(PointerEventType.Press, 200f, 200f)
            delay(650)
            frame()
            assertTrue(selector.selection!!.dockEnd)
            assertEquals(2.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 162f)
            frame()
            assertEquals(2f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 40f)
            frame()
            assertEquals("upward swipe reaches 0.5x directly", .5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 112f)
            frame()
            assertEquals(1.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 250f)
            frame()
            assertEquals("downward swipe reaches 3.0x directly", 3f, player.playbackSpeed, 0f)
            event(PointerEventType.Release, 200f, 250f)
            frame()
            assertEquals(1f, player.playbackSpeed, 0f)

            // 左半屏长按 + 横向偏移不锁定,松手恢复 1.0x
            event(PointerEventType.Press, 200f, 100f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertTrue(selector.selection!!.dockEnd)
            assertEquals(2.5f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 200f, 62f)
            frame()
            assertEquals(2f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 240f, 68f)
            frame()
            assertFalse("horizontal drift must not lock", selector.selection!!.locked)
            event(PointerEventType.Release, 240f, 68f)
            frame()
            assertFalse(selector.active)
            assertEquals(1f, player.playbackSpeed, 0f)
            assertNull(selector.lockedHint)

            // 右半屏长按:下滑升到 3.0x,横向偏移同样不锁定,松手恢复 1.0x
            event(PointerEventType.Press, 450f, 100f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertFalse(selector.selection!!.dockEnd)
            assertEquals(2.5f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 450f, 138f)
            frame()
            assertEquals(3f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 410f, 144f)
            frame()
            assertFalse("horizontal drift must not lock", selector.selection!!.locked)
            event(PointerEventType.Release, 410f, 144f)
            frame()
            assertFalse(selector.active)
            assertEquals(1f, player.playbackSpeed, 0f)
            assertNull(selector.lockedHint)
            assertEquals(0, player.seeks)
            assertEquals(0, player.playPauseCalls)
        } finally { scene.close() }
    }
}

private class PointerTestPlayer : PlayerHandle {
    private var actualSpeed = 1f
    private val listeners = mutableSetOf<PlayerListener>()
    var playPauseCalls = 0
    var seeks = 0
    override val isPlaying = true
    override val playWhenReady = true
    override val playbackState = PlayerPhase.READY
    override val currentPosition = 10000L
    override val bufferedPosition = 80000L
    override val duration = 120000L
    override val playbackSpeed get() = actualSpeed
    override val videoSize = VideoDimensions(1920, 1080)
    override var shuffleModeEnabled = false
    override fun play() { playPauseCalls++ }
    override fun pause() { playPauseCalls++ }
    override fun seekTo(positionMillis: Long) { seeks++ }
    override fun seekTo(index: Int, positionMillis: Long) { seeks++ }
    override fun seekToNext() = Unit
    override fun seekToPrevious() = Unit
    override fun setPlaybackSpeed(speed: Float) { actualSpeed = speed; listeners.toList().forEach { it.onPlaybackSpeedChanged(speed) } }
    override fun setVideoDisabled(disabled: Boolean) = Unit
    override fun addListener(listener: PlayerListener) { listeners += listener }
    override fun removeListener(listener: PlayerListener) { listeners -= listener }
    override fun send(command: PlaybackCommand) = Unit
}

private class PointerTestPlaybackHost(private val player: PointerTestPlayer) : PlaybackHost {
    override val state = MutableStateFlow(AudioPlaybackUiState())
    override val positionTicks = MutableStateFlow(PositionTick())
    override val sleepTimerState = MutableStateFlow(SleepTimerState())
    override fun currentPositionMillis() = player.currentPosition
    override fun setBackgroundPlaybackAllowed(allowed: Boolean) = Unit
    override fun pauseForAppBackground() = Unit
    override fun retainFrames(ids: Set<String>) = Unit
    override fun stop() = Unit
    override fun connect(onConnected: (PlayerHandle) -> Unit): PlayerConnection {
        onConnected(player)
        return object : PlayerConnection { override fun release() = Unit }
    }
}
