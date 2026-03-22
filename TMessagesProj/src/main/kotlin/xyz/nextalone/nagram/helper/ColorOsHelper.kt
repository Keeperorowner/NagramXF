package xyz.nextalone.nagram.helper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import android.view.View
import org.telegram.messenger.AndroidUtilities

object ColorOsHelper {
    val isColorOS: Boolean =
        !TextUtils.isEmpty(AndroidUtilities.getSystemProperty("ro.build.version.oplusrom"))

    private val colorOSVersion: Int = try {
        AndroidUtilities.getSystemProperty("ro.build.version.release").toInt()
    } catch (_: Exception) {
        0
    }

    fun isColorOSAiAvailable(): Boolean {
        if (!isColorOS) {
            return false
        }
        return colorOSVersion == 15 || colorOSVersion == 16
    }

    fun startColorOsAiService(view: View, text: String) {
        try {
            val intent = Intent().apply {
                `package` = "com.heytap.speechassist"
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra("open_with_zoomwindow", true)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            view.context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("ColorOsHelper", "Failed to start ColorOS AI service", e)
        }
    }

    fun startColorOsAiService(context: Context, uri: Uri): Boolean {
        return try {
            context.grantUriPermission(
                "com.heytap.speechassist",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val intent = Intent().apply {
                `package` = "com.heytap.speechassist"
                action = Intent.ACTION_SEND
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("open_with_zoomwindow", true)
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("ColorOsHelper", "Failed to start ColorOS AI service", e)
            false
        }
    }
}
