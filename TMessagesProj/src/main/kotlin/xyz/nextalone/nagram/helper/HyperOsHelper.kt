package xyz.nextalone.nagram.helper

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.view.View
import org.telegram.messenger.AndroidUtilities

object HyperOsHelper {
    val IS_HYPEROS: Boolean =
        !TextUtils.isEmpty(AndroidUtilities.getSystemProperty("ro.mi.os.version.name"))

    private const val HYPEROS_NOTES_PKG = "com.miui.notes"
    private const val HYPEROS_AI_SERVICE = "com.miui.notes.ai.AiTextWidgetService"
    private const val MIN_SUPPORTED_NOTES_VERSION_CODE = 1100L

    fun isHyperAiAvailable(context: Context): Boolean {
        if (!IS_HYPEROS) {
            return false
        }

        return try {
            val packageInfo = context.packageManager.getPackageInfo(HYPEROS_NOTES_PKG, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            versionCode >= MIN_SUPPORTED_NOTES_VERSION_CODE
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun startHyperOsAiService(view: View, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        try {
            val serviceIntent = Intent().apply {
                putExtra("packageName", view.context.packageName)
                putExtra("selectedText", text)
                putExtra("originalViewLeft", view.left)
                putExtra("originalViewTop", view.top)
                putExtra("originalViewRight", view.right)
                putExtra("originalViewBottom", view.bottom)
                putExtra("originalViewName", view.javaClass.name)
                putExtra("isEditor", true)
            }

            val screenCoordinates = IntArray(2)
            val focusedRect = Rect()
            view.getLocationOnScreen(screenCoordinates)
            view.getFocusedRect(focusedRect)
            focusedRect.offset(screenCoordinates[0], screenCoordinates[1])

            val currentActivity = view.context as? Activity
            if (currentActivity != null) {
                val windowFrame = Rect()
                currentActivity.window.decorView.getWindowVisibleDisplayFrame(windowFrame)
                serviceIntent.putExtra("left", windowFrame.left)
                serviceIntent.putExtra("top", windowFrame.top)
                serviceIntent.putExtra("right", windowFrame.right)
                serviceIntent.putExtra("bottom", windowFrame.bottom)
                serviceIntent.putExtra("taskId", currentActivity.taskId)
            }

            serviceIntent.component = ComponentName(HYPEROS_NOTES_PKG, HYPEROS_AI_SERVICE)
            view.context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e("HyperOsHelper", "Failed to start HyperOS AI service", e)
        }
    }
}
