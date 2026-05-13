package tw.nekomimi.nekogram.translate.source

import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.TranslateAlert2
import tw.nekomimi.nekogram.translate.HTMLKeeper
import tw.nekomimi.nekogram.translate.Translator
import tw.nekomimi.nekogram.translate.source.fallback.DeepLTranslatorNeko
import tw.nekomimi.nekogram.utils.HttpClient
import xyz.nextalone.nagram.NaConfig
import java.io.IOException

object DeepLTranslator : Translator {

    private val httpClient = HttpClient.instance

    override suspend fun doTranslate(
        from: String, to: String, query: String, entities: ArrayList<TLRPC.MessageEntity>
    ): TLRPC.TL_textWithEntities {
        if (to.isEmpty()) {
            throw UnsupportedOperationException(getString(R.string.TranslateApiUnsupported) + " " + to)
        }

        val originalText = TLRPC.TL_textWithEntities().apply {
            this.text = query
            this.entities = entities
        }

        val textToTranslate = if (entities.isNotEmpty()) HTMLKeeper.entitiesToHtml(
            query, entities, false
        ) else query

        val apiKey = NaConfig.deepLTranslateKey.String().trim()
        val translatedText = if (apiKey.isEmpty()) {
            try {
                DeepLTranslatorNeko.translate(textToTranslate, from, to)
            } catch (e: Exception) {
                error("DeepL API request failed: ${e.message}")
            }
        } else {
            translateWithApiKey(apiKey, textToTranslate, from, to, entities.isNotEmpty())
        }

        val finalString = StringBuilder().append(translatedText)
        var finalText = TLRPC.TL_textWithEntities()
        if (entities.isNotEmpty()) {
            val resultPair = HTMLKeeper.htmlToEntities(finalString.toString(), entities, false)
            finalText.text = resultPair.first
            finalText.entities = resultPair.second
            finalText = TranslateAlert2.preprocess(originalText, finalText)
        } else {
            finalText.text = finalString.toString()
        }

        return finalText
    }

    private suspend fun translateWithApiKey(
        apiKey: String,
        text: String,
        from: String,
        to: String,
        html: Boolean
    ): String {
        val formBodyBuilder = FormBody.Builder()
            .add("text", text)
            .add("target_lang", to)

        if (from.isNotEmpty() && from != "auto") {
            formBodyBuilder.add("source_lang", from)
        }
        if (html) {
            formBodyBuilder.add("tag_handling", "html")
        }

        val request = Request.Builder()
            .url(getEndpoint(apiKey))
            .header("Authorization", "DeepL-Auth-Key $apiKey")
            .post(formBodyBuilder.build())
            .build()

        val response = try {
            httpClient.newCall(request).await()
        } catch (e: IOException) {
            error("DeepL API request failed due to network issue: ${e.message}")
        } catch (e: Exception) {
            error("An unexpected error occurred during DeepL translation: ${e.message}")
        }

        val responseBodyString = response.use {
            val bodyString = try {
                it.body.string()
            } catch (e: IOException) {
                error("DeepL API response reading failed: ${e.message}")
            }
            if (!it.isSuccessful) {
                error("DeepL API request failed: HTTP ${it.code} : $bodyString")
            }
            bodyString
        }

        try {
            val respArr = JSONObject(responseBodyString).getJSONArray("translations")
            if (respArr.length() == 0) {
                error("Empty translation result")
            }
            return respArr.getJSONObject(0).getString("text")
        } catch (e: JSONException) {
            error("DeepL API response parsing failed: ${e.message}")
        }
    }

    private fun getEndpoint(apiKey: String): String {
        return if (apiKey.endsWith(":fx")) {
            "https://api-free.deepl.com/v2/translate"
        } else {
            "https://api.deepl.com/v2/translate"
        }
    }

}
