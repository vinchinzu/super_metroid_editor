package com.supermetroid.editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import java.awt.Window
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * CompositionLocal for the AWT/Swing window so we can attach macOS magnification (pinch) listener.
 */
val LocalSwingWindow = staticCompositionLocalOf<Window?> { null }

private fun isMacOs(): Boolean =
    System.getProperty("os.name", "").lowercase().contains("mac")

/**
 * Attaches macOS trackpad pinch-to-zoom (magnification): pinch out = zoom in, pinch in = zoom out (like iPhone camera).
 * No-op on non-Mac or if Apple API isn't available. Requires JVM args:
 * --add-exports java.desktop/com.apple.eawt.event=ALL-UNNAMED
 * --add-opens java.desktop/com.apple.eawt.event=ALL-UNNAMED
 */
@Composable
fun AttachMacPinchZoom(
    awtWindow: Window?,
    zoomState: MutableState<Float>,
    minZoom: Float = 0.25f,
    maxZoom: Float = 4f
) {
    if (!isMacOs() || awtWindow == null) return
    val rootPane = (awtWindow as? RootPaneContainer)?.rootPane ?: return

    DisposableEffect(awtWindow, zoomState) {
        var listener: Any?
        try {
            @Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")
            val listenerClass = Class.forName("com.apple.eawt.event.MagnificationListener")
            listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name == "magnification" && args != null && args.isNotEmpty()) {
                    val event = args[0]
                    val magnification = event.javaClass.getMethod("getMagnification").invoke(event) as Double
                    val scale = (1 + magnification).toFloat()
                    SwingUtilities.invokeLater {
                        zoomState.value = (zoomState.value * scale).coerceIn(minZoom, maxZoom)
                    }
                }
                null
            }
            val gestureUtils = Class.forName("com.apple.eawt.event.GestureUtilities")
            val gestureListenerClass = Class.forName("com.apple.eawt.event.GestureListener")
            val addMethod = gestureUtils.getMethod("addGestureListenerTo", java.awt.Component::class.java, gestureListenerClass)
            addMethod.invoke(null, rootPane, listener)
        } catch (_: ClassNotFoundException) {
            listener = null
        } catch (_: Exception) {
            listener = null
        }
        onDispose {
            if (listener != null) {
                try {
                    val gestureUtils = Class.forName("com.apple.eawt.event.GestureUtilities")
                    val gestureListenerClass = Class.forName("com.apple.eawt.event.GestureListener")
                    val removeMethod = gestureUtils.getMethod("removeGestureListenerFrom", java.awt.Component::class.java, gestureListenerClass)
                    removeMethod.invoke(null, rootPane, listener)
                } catch (_: Exception) { }
            }
        }
    }
}
