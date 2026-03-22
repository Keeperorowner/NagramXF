package xyz.nextalone.nagram.helper

import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.EditText
import xyz.nextalone.nagram.NaConfig

object SystemAiServiceHelper {
    fun isSystemAiAvailable(context: Context): Boolean {
        if (!NaConfig.useSystemAiService.Bool()) {
            return false
        }
        return HyperOsHelper.isHyperAiAvailable(context) || ColorOsHelper.isColorOSAiAvailable()
    }

    @JvmOverloads
    fun startSystemAiService(view: View, text: String = "") {
        val selectedText = if (view is EditText && view.hasSelection()) {
            val selectionStart = view.selectionStart
            val selectionEnd = view.selectionEnd
            if (selectionStart != selectionEnd) {
                view.text.subSequence(selectionStart, selectionEnd).toString()
            } else {
                text
            }
        } else {
            text
        }

        when {
            HyperOsHelper.IS_HYPEROS -> HyperOsHelper.startHyperOsAiService(view, selectedText)
            ColorOsHelper.isColorOS -> ColorOsHelper.startColorOsAiService(view, selectedText)
        }
    }

    fun startSystemAiService(context: Context, uri: Uri): Boolean {
        if (ColorOsHelper.isColorOS) {
            return ColorOsHelper.startColorOsAiService(context, uri)
        }
        return false
    }
}
