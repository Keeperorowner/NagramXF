package tw.nekomimi.nekogram.parts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.telegram.messenger.AndroidUtilities
import org.telegram.tgnet.tl.TL_iv
import org.telegram.ui.ArticleViewer
import tw.nekomimi.nekogram.translate.Translator
import tw.nekomimi.nekogram.utils.AlertUtil
import tw.nekomimi.nekogram.utils.AppScope
import tw.nekomimi.nekogram.utils.uUpdate
import java.util.concurrent.atomic.AtomicInteger

private fun extractTextPlainLeaves(
    richText: TL_iv.RichText, leaves: MutableList<TL_iv.textPlain>
) {
    when (richText) {
        is TL_iv.textPlain -> {
            if (richText.text.isNotBlank()) {
                leaves.add(richText)
            }
        }

        is TL_iv.textConcat -> richText.texts.forEach { extractTextPlainLeaves(it, leaves) }
        is TL_iv.textBold -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textItalic -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textUnderline -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textStrike -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textFixed -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textUrl -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textEmail -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textPhone -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textMarked -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textSubscript -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textSuperscript -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textAnchor -> extractTextPlainLeaves(richText.text, leaves)
        is TL_iv.textSpoiler -> extractTextPlainLeaves(richText.text, leaves)
    }
}

fun ArticleViewer.doTransLATE() {
    val status = AlertUtil.showProgress(parentActivity)
    status.show()

    val adapter = pages[0].adapter
    val translatedTextCache = adapter.translatedTextCache
    val job: Job = AppScope.io.launch {
        val dispatcher = Dispatchers.IO.limitedParallelism(5)
        val textsToTranslate = HashSet<String>()
        pages[0].adapter.textBlocks.forEach { item ->
            when (item) {
                is TL_iv.RichText -> {
                    val leaves = mutableListOf<TL_iv.textPlain>()
                    extractTextPlainLeaves(item, leaves)
                    leaves.forEach { plainText ->
                        textsToTranslate.add(plainText.text)
                    }
                }

                is String -> {
                    if (item.isNotBlank()) {
                        textsToTranslate.add(item)
                    }
                }
            }
        }

        val errorCount = AtomicInteger()
        val all = textsToTranslate.size
        val taskCount = AtomicInteger(all)

        status.uUpdate("0 / $all")

        supervisorScope {
            val jobs = textsToTranslate.map { str ->
                launch(dispatcher) {
                    if (!isActive) return@launch

                    if (translatedTextCache.containsKey(str)) {
                        taskCount.decrementAndGet()
                        status.uUpdate("${all - taskCount.get()} / $all")
                        return@launch
                    }

                    runCatching {
                        val translatedResult = Translator.translateArticle(str)
                        translatedTextCache[str] = translatedResult

                        status.uUpdate("${all - taskCount.get()} / $all")

                        if (taskCount.decrementAndGet() % 10 == 0) {
                            AndroidUtilities.runOnUIThread { updatePaintSize() }
                        }
                    }.onFailure {
                        if (!isActive) return@launch

                        if (errorCount.incrementAndGet() > 3) {
                            this@supervisorScope.cancel()
                            AndroidUtilities.runOnUIThread {
                                status.dismiss()
                                updatePaintSize()
                                updateTranslateButton(false)
                                translatedTextCache.clear()
                                AlertUtil.showTransFailedDialog(
                                    parentActivity,
                                    it is UnsupportedOperationException,
                                    it.message ?: it.javaClass.simpleName
                                ) {
                                    doTransLATE()
                                }
                            }
                        }
                    }
                }
            }

            jobs.joinAll()
        }

        if (isActive) {
            AndroidUtilities.runOnUIThread {
                updatePaintSize()
                status.dismiss()
            }
        }
    }

    status.setOnCancelListener {
        updateTranslateButton(false)
        job.cancel()
        translatedTextCache.clear()
        AndroidUtilities.runOnUIThread { updatePaintSize() }
    }
}
