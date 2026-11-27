package dev.bilby.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.Build
import android.util.Rational
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.window.SurfaceSyncGroup
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import dev.bilby.BiliLog
import dev.bilby.player.MediaControllerHandle
import dev.bilby.player.PlayerHandle
import kotlinx.coroutines.delay

/**
 * 画中画。做法照 android-docs-mirror 的 `develop/ui/compose/system/pip-*.md`:manifest 声明
 * `supportsPictureInPicture`,按钮调 `enterPictureInPictureMode`,界面按 [rememberIsInPipMode]
 * 只留画面。
 *
 * **小窗上的播放、暂停不自己写。** 文档原话:"If you are using a MediaSession, there will be
 * default actions added to the window",播放器本来就挂在 session 上。
 *
 * **只从按钮进,不在按 Home 时自动进。** 自动进(`setAutoEnterEnabled`)意味着每次离开应用都
 * 留下一个浮窗,而看视频退到后台原本是暂停的(见 MainActivity.onStop);那是两种不同的约定,
 * 要不要换约定是另一件事。
 */

/** 这台设备能不能画中画。有的系统(部分低内存机、一些 TV)不支持,按钮就不给。 */
private fun Context.supportsPip(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/**
 * 进画中画。[aspect] 是画面的宽高比,未知时交给系统(默认 16:9 上下)。
 *
 * **比例要夹进 1:2.39 到 2.39:1。** 超出这个范围 `setAspectRatio` 直接抛异常(文档的 Warning),
 * 超宽的电影流和极窄的竖屏流都可能越界。
 */
private fun Context.enterPip(aspect: Float?) {
    val activity = findActivity() ?: return
    val builder = PictureInPictureParams.Builder()
    aspect?.takeIf { it > 0f }?.let { ratio ->
        val clamped = ratio.coerceIn(MinAspect, MaxAspect)
        builder.setAspectRatio(Rational((clamped * AspectScale).toInt(), AspectScale))
    }
    runCatching { activity.enterPictureInPictureMode(builder.build()) }
        .onFailure { BiliLog.w("进入画中画失败", it) }
}

@Composable
actual fun rememberPictureInPicture(): PictureInPicture {
    val context = LocalContext.current
    return remember(context) {
        val supported = context.supportsPip()
        object : PictureInPicture {
            override val supported: Boolean = supported
            override fun enter(aspect: Float?) = context.enterPip(aspect)
        }
    }
}

@Composable
actual fun VideoSurface(player: PlayerHandle, modifier: Modifier, isFullscreen: Boolean) {
    val controller = (player as MediaControllerHandle).controller
    var container by remember { mutableStateOf<SurfaceSyncContainer?>(null) }
    var hasToggledFullscreen by remember { mutableStateOf(isFullscreen) }
    // Compose 的 AndroidViewsHandler 默认关闭 clipChildren;API 30 及以下 SurfaceView 靠
    // PorterDuff.Mode.CLEAR 打洞,不加 clipToBounds 会把祖先裁剪层整块清空(media3#3466)。
    val surfaceModifier = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
        modifier.clipToBounds()
    } else {
        modifier
    }

    AndroidView(
        modifier = surfaceModifier,
        factory = { SurfaceSyncContainer(it) },
        onReset = {},
        update = { container = it },
    )

    container?.let { host ->
        DisposableEffect(host, controller) {
            val previous = host.attachedPlayer
            if (previous != null && previous !== controller &&
                previous.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)
            ) {
                previous.clearVideoSurfaceView(host.surfaceView)
            }
            if (controller.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
                controller.setVideoSurfaceView(host.surfaceView)
                host.attachedPlayer = controller
            }
            val listener = object : Player.Listener {
                override fun onSurfaceSizeChanged(width: Int, height: Int) {
                    if (width > 0 && height > 0) host.scheduleSurfaceSync()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) host.scheduleSurfaceSync()
                }
            }
            controller.addListener(listener)
            onDispose {
                controller.removeListener(listener)
                host.cancelPendingSync()
                if (host.attachedPlayer === controller) {
                    if (controller.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
                        controller.clearVideoSurfaceView(host.surfaceView)
                    }
                    host.attachedPlayer = null
                }
            }
        }

        LaunchedEffect(host, isFullscreen) {
            if (!isFullscreen && !hasToggledFullscreen) return@LaunchedEffect
            hasToggledFullscreen = true
            // 竖屏视频进出全屏不触发 Activity 旋转与重建,SurfaceView 在同一布局分支内原地
            // 跨 3 倍以上尺寸缩放并同时撤掉外层 clipToBounds 与系统栏。在下一帧及系统栏
            // 动画落定后各补一次几何同步,避免首次竖屏全屏 SurfaceControl 停留在旧内嵌尺寸。
            withFrameNanos { }
            host.scheduleSurfaceSync()
            delay(FullscreenInsetsSettleSyncDelayMillis)
            host.scheduleSurfaceSync()
        }
    }
}

/**
 * 为 Compose `AndroidView` 托管的 [SurfaceView] 补齐几何与合成器同步。
 *
 * Media3 `1.11.1` 的 `PlayerSurface` 仅在 `SDK_INT == 34` 的 `onSurfaceSizeChanged` 里
 * 挂接 `SurfaceSyncGroup`,且把完成回调放在无子视图且 `WILL_NOT_DRAW` 的裸 `SurfaceView` 上,
 * 既覆盖不到 Android 15/16 及部分厂商 ROM 上的原地缩放丢事务问题,也捕获不到仅位置变化或
 * `clipToBounds` 卸载时的几何不同步。外包一层 [FrameLayout] 让 `dispatchDraw` 在每次
 * `invalidate()` 后稳定执行,并把布局边界变化、SurfaceHolder 回调与 API 34+ `SurfaceSyncGroup`
 * 合并到同一条去抖主线程同步通道。
 */
internal class SurfaceSyncContainer(context: Context) : FrameLayout(context) {
    val surfaceView: SurfaceView = SurfaceView(context)
    var attachedPlayer: Player? = null
    internal var syncExecutionCount: Int = 0
        private set

    private val locationInWindow = IntArray(2)
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastWindowX = Int.MIN_VALUE
    private var lastWindowY = Int.MIN_VALUE
    private var syncScheduled = false
    private var pendingSyncGroup: Any? = null

    private val syncRunnable = Runnable {
        syncScheduled = false
        performSurfaceSync()
    }

    private val syncTimeoutRunnable = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            completePendingSyncGroupV34()
        }
    }

    init {
        addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        surfaceView.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    scheduleSurfaceSync()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (width > 0 && height > 0) scheduleSurfaceSync()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    cancelPendingSync()
                }
            },
        )
        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            getLocationInWindow(locationInWindow)
            val wx = locationInWindow[0]
            val wy = locationInWindow[1]
            if (w != lastWidth || h != lastHeight || wx != lastWindowX || wy != lastWindowY) {
                lastWidth = w
                lastHeight = h
                lastWindowX = wx
                lastWindowY = wy
                scheduleSurfaceSync()
            }
        }
    }

    fun scheduleSurfaceSync() {
        if (!isAttachedToWindow || syncScheduled) return
        syncScheduled = true
        post(syncRunnable)
    }

    fun cancelPendingSync() {
        removeCallbacks(syncRunnable)
        removeCallbacks(syncTimeoutRunnable)
        syncScheduled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            completePendingSyncGroupV34()
        }
    }

    private fun performSurfaceSync() {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return
        syncExecutionCount++
        surfaceView.requestLayout()
        surfaceView.invalidate()
        invalidate()
        (parent as? View)?.invalidate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        ) {
            registerSurfaceSyncGroupV34()
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerSurfaceSyncGroupV34() {
        val rootSurfaceControl = surfaceView.rootSurfaceControl ?: return
        completePendingSyncGroupV34()
        val syncGroup = SurfaceSyncGroup("bilby-surface-sync")
        if (syncGroup.add(rootSurfaceControl) {}) {
            pendingSyncGroup = syncGroup
            postDelayed(syncTimeoutRunnable, SurfaceSyncTimeoutMillis)
            invalidate()
            surfaceView.invalidate()
            rootSurfaceControl.applyTransactionOnDraw(SurfaceControl.Transaction())
        } else {
            syncGroup.markSyncReady()
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun completePendingSyncGroupV34() {
        removeCallbacks(syncTimeoutRunnable)
        (pendingSyncGroup as? SurfaceSyncGroup)?.markSyncReady()
        pendingSyncGroup = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (width > 0 && height > 0) scheduleSurfaceSync()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            completePendingSyncGroupV34()
        }
    }

    override fun onDetachedFromWindow() {
        cancelPendingSync()
        lastWidth = 0
        lastHeight = 0
        lastWindowX = Int.MIN_VALUE
        lastWindowY = Int.MIN_VALUE
        super.onDetachedFromWindow()
    }
}

/** 系统的小窗自带播放与回到应用的按钮,触摸也不交给应用。 */
@Composable
actual fun rememberPipWindow(): PipWindow? = null

@Composable
internal actual fun BoxScope.PipResizeHandles(window: PipWindow) = Unit

@Composable
actual fun rememberIsInPipMode(): Boolean {
    val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return false
    var inPip by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val observer = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(observer)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(observer) }
    }
    return inPip
}

private const val MaxAspect = 2.39f
private const val MinAspect = 1f / 2.39f

/** 把浮点比例化成整数分子分母的倍数。千分之一的误差看不出来。 */
private const val AspectScale = 1000

/** 系统栏隐藏动画落定后补一次 SurfaceControl 几何同步的等待时长。 */
private const val FullscreenInsetsSettleSyncDelayMillis = 250L

/** SurfaceSyncGroup 兜底超时,防止窗口被遮挡跳过 dispatchDraw 时卡住后续帧同步。 */
private const val SurfaceSyncTimeoutMillis = 120L
