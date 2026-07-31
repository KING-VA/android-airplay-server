package io.github.jqssun.airplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

// Compatibility helper: getSystemService(Class) is API 28+.
// On older APIs use the string-based overload instead.
@Suppress("DEPRECATION")
private fun <T> Context.compatGetSystemService(serviceClass: Class<T>): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        @Suppress("UNCHECKED_CAST")
        getSystemService(serviceClass) as T?
    } else {
        val serviceName = when (serviceClass) {
            DisplayManager::class.java -> Context.DISPLAY_SERVICE
            else -> throw IllegalArgumentException("Unsupported service class: $serviceClass")
        }
        @Suppress("UNCHECKED_CAST")
        getSystemService(serviceName) as T?
    }
}

// physical panel resolution
fun Context.realDisplaySize(): Pair<Int, Int> {
    val mode = compatGetSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
    return if (mode != null) mode.physicalWidth to mode.physicalHeight
    else resources.displayMetrics.let { it.widthPixels to it.heightPixels }
}
