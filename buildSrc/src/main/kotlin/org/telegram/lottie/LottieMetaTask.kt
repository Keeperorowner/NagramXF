package org.telegram.lottie

import com.google.gson.stream.JsonReader
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

abstract class LottieMetaTask : DefaultTask() {

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rawResources: ConfigurableFileCollection

    @get:Input
    abstract val packageName: Property<String>

    // Package where the R class lives (variant namespace), for the generated import.
    @get:Input
    abstract val rPackage: Property<String>

    @get:Input
    abstract val className: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private data class Entry(val name: String, val fps: Int, val frameCount: Int, val monocolor: Boolean)

    @TaskAction
    fun run() {
        // Parse metadata + detect mono-color, then collapse resource-qualifier duplicates by name.
        val parsed = rawResources.files
            .filter { it.isFile }
            .mapNotNull { f ->
                val info = parseLottie(f) ?: return@mapNotNull null
                val mono = isMonoColorName(f.nameWithoutExtension)
                f.nameWithoutExtension to Triple(info.fps, info.frameCount, mono)
            }
            .groupBy({ it.first }, { it.second })

        val entries = parsed.toSortedMap().map { (name, list) ->
            if (list.distinct().size > 1) {
                logger.warn("R.raw.$name: conflicting metadata across qualifiers, using the first")
            }
            val (fpsRaw, frameCount, mono) = list.first()

            // fps -> 8 bits, frameCount -> 23 bits. Anything larger cannot be encoded: fail the build.
            val fps = fpsRaw.roundToInt()
            if (fps !in 0..FPS_MAX) {
                throw GradleException("R.raw.$name: fps=$fps does not fit into 8 bits (0..$FPS_MAX)")
            }
            if (frameCount !in 0..FRAMES_MAX) {
                throw GradleException("R.raw.$name: frameCount=$frameCount does not fit into 23 bits (0..$FRAMES_MAX)")
            }

            logger.lifecycle("R.raw.%s — fps=%d, frames=%d, mono=%b".format(name, fps, frameCount, mono))
            Entry(name, fps, frameCount, mono)
        }

        val pkg = packageName.get()
        val cls = className.get()
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val pkgDir = File(root, pkg.replace('.', '/')).apply { mkdirs() }
        File(pkgDir, "$cls.java").writeText(renderJava(pkg, rPackage.get(), cls, entries))
    }

    // --- Lottie parsing -----------------------------------------------------

    private data class LottieInfo(val fps: Double, val frameCount: Int)

    private fun parseLottie(file: File): LottieInfo? = try {
        file.inputStream().buffered().use { raw ->
            // Transparently handle gzipped bodies (.tgs stored as .json), detect by magic.
            raw.mark(2)
            val gzip = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val stream = if (gzip) GZIPInputStream(raw) else raw

            var fr: Double? = null
            var ip: Double? = null
            var op: Double? = null

            JsonReader(stream.reader(Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "fr" -> fr = reader.nextDouble()
                        "ip" -> ip = reader.nextDouble()
                        "op" -> op = reader.nextDouble()
                        else -> reader.skipValue() // skip layers/assets/etc.
                    }
                }
                reader.endObject()
            }

            val frameRate = fr
            val inPoint = ip
            val outPoint = op
            if (frameRate == null || inPoint == null || outPoint == null || frameRate <= 0.0) return null
            LottieInfo(frameRate, (outPoint - inPoint).roundToInt())
        }
    } catch (_: Exception) {
        null // not a Lottie/JSON payload
    }

    private fun isMonoColorName(name: String): Boolean = name in MONO_COLOR_NAMES

    private val MONO_COLOR_NAMES = setOf(
        "addone_icon",
        "auto_night_off",
        "boosts",
        "bot_webview_sheet_to_cross",
        "bt_to_speaker",
        "call_accept",
        "call_mute",
        "calls_log",
        "camera",
        "camera_flip",
        "camera_flip2",
        "camera_outline",
        "camera_wait",
        "caption_down",
        "caption_hide",
        "caption_limit",
        "caption_show",
        "caption_up",
        "chats_hide",
        "chats_swipearchive",
        "chats_unhide",
        "contacts_sync_off",
        "contacts_sync_on",
        "convert_video",
        "copy",
        "done",
        "dots_loading",
        "double_icon",
        "download_arrow",
        "download_finish",
        "download_progress",
        "e_hand_2",
        "edge_30",
        "error",
        "filter_reorder",
        "fire_off",
        "fire_on",
        "fire_once",
        "firefox_30",
        "folder_in",
        "folder_out",
        "forward",
        "fragment",
        "fragment_username",
        "gif_to_keyboard",
        "gif_to_smile",
        "gift",
        "gift_broken",
        "gift_crafting",
        "gift_upgrade",
        "gigagroup_convert",
        "group_pip_delete_icon",
        "hand_1",
        "hand_2",
        "hint_swipe_reply",
        "ic_admin",
        "ic_ban",
        "ic_boosts_replace",
        "ic_delete",
        "ic_download",
        "ic_mute",
        "ic_pin",
        "ic_save_to_gallery",
        "ic_save_to_music",
        "ic_unban",
        "ic_unmute",
        "ic_unpin",
        "import_check",
        "import_progress",
        "imported",
        "incoming_calls",
        "keyboard_to_gif",
        "keyboard_to_smile",
        "keyboard_to_sticker",
        "large_lastseen",
        "large_message_lock",
        "large_readtime",
        "linkbroken",
        "linux_30",
        "mac_30",
        "mapstyle_night",
        "media_enlarge",
        "media_shrink",
        "monetize",
        "msg_antispam",
        "msg_emoji_activities",
        "msg_emoji_cat",
        "msg_emoji_flags",
        "msg_emoji_food",
        "msg_emoji_objects",
        "msg_emoji_other",
        "msg_emoji_smiles",
        "msg_emoji_travel",
        "msg_stories_archive",
        "msg_stories_saved",
        "msg_story_keep",
        "msg_translate",
        "mute_for",
        "name_hide",
        "name_show",
        "not_available",
        "notify_toggle",
        "options_to_search",
        "passcode_lock",
        "passcode_lock_close",
        "payment_success",
        "permission_request_apk",
        "permission_request_camera",
        "permission_request_contacts",
        "permission_request_folder",
        "permission_request_location",
        "permission_request_microphone",
        "photo_arrow",
        "photo_blur",
        "photo_eraser",
        "photo_marker",
        "photo_neon",
        "photo_pen",
        "photo_spoiler",
        "photo_suggest_icon",
        "photo_text_allign",
        "plane_logo_plain",
        "player_prev",
        "position_above",
        "position_below",
        "profile_leave",
        "profile_muting",
        "profile_unmuting",
        "profile_voicechat",
        "qr_matrix",
        "rate",
        "roundcamera_flash_off",
        "roundcamera_flash_on",
        "roundcamera_flip",
        "saved_messages",
        "seek_speed_hint",
        "shared_link_enter",
        "silent_mute",
        "silent_unmute",
        "smile_to_gif",
        "smile_to_keyboard",
        "smile_to_sticker",
        "sound_download",
        "sound_off",
        "sound_on",
        "speaker",
        "speaker_to_bt",
        "speed_15to2",
        "speed_1to15",
        "speed_2to1",
        "speed_fast",
        "speed_limit",
        "speed_slow",
        "star_fill",
        "star_premium_2",
        "star_stroke",
        "sticker_to_keyboard",
        "sticker_to_smile",
        "stories_intro_go_back",
        "stories_intro_go_forward",
        "stories_intro_go_to_next",
        "stories_intro_pause",
        "story_bomb1",
        "story_bomb2",
        "story_repost",
        "sun",
        "sun_outline",
        "swipe_community_ungroup",
        "swipe_delete",
        "swipe_disabled",
        "swipe_mute",
        "swipe_pin",
        "swipe_read",
        "swipe_unmute",
        "swipe_unpin",
        "swipe_unread",
        "tab_article",
        "tab_article_reverse",
        "tab_calls",
        "tab_chats",
        "tab_checklist",
        "tab_checklist_reverse",
        "tab_colors",
        "tab_colors_reverse",
        "tab_contacts",
        "tab_emoji",
        "tab_emoji_reverse",
        "tab_files",
        "tab_files_reverse",
        "tab_gallery",
        "tab_gallery_reverse",
        "tab_gift",
        "tab_gift_reverse",
        "tab_location",
        "tab_location_reverse",
        "tab_models",
        "tab_models_reverse",
        "tab_music",
        "tab_music_reverse",
        "tab_poll",
        "tab_poll_reverse",
        "tab_reply",
        "tab_reply_reverse",
        "tab_settings",
        "tab_sticker",
        "tab_sticker_reverse",
        "tab_symbols",
        "tab_symbols_reverse",
        "tab_wallet",
        "tab_wallet_reverse",
        "tag_icon_3",
        "ticks_double",
        "ticks_single",
        "timer_3",
        "timer_toast",
        "topics",
        "topics_list",
        "topics_tabs",
        "transcribe",
        "unlock_icon",
        "video_stop",
        "voice_and_video",
        "voice_mini",
        "voice_muted",
        "voice_outlined",
        "voice_outlined2",
        "voice_to_text",
        "voip_allow_talk",
        "voip_filled",
        "voip_invite",
        "voip_muted",
        "voip_record_saved",
        "voip_record_start",
        "voip_unmuted",
        "write_contacts_fab_icon"
    )

    // --- Java code generation ----------------------------------------------

    private fun renderJava(pkg: String, rPkg: String, cls: String, entries: List<Entry>): String {
        val rImport = if (pkg == rPkg) "" else "import $rPkg.R;\n"
        val sb = StringBuilder()
        sb.append("package ").append(pkg).append(";\n\n")
        sb.append("import androidx.annotation.RawRes;\n")
        sb.append("import java.util.Arrays;\n")
        sb.append(rImport)
        sb.append("\n")
        sb.append("/** Generated by the Lottie metadata plugin. Do not edit. */\n")
        sb.append("public final class ").append(cls).append(" {\n\n")

        sb.append("    // Packed long layout:\n")
        sb.append("    //   bits 63..32 (32) : R.raw resource id\n")
        sb.append("    //   bits 31..24 ( 8) : fps\n")
        sb.append("    //   bit  23     ( 1) : monocolor\n")
        sb.append("    //   bits 22.. 0 (23) : frame count\n")
        sb.append("    public static final long NOT_FOUND = -1L;\n\n")

        sb.append("    private ").append(cls).append("() {\n    }\n\n")

        sb.append("    private static final class Holder {\n")
        sb.append("        private static final long[] DATA = build();\n")
        sb.append("    }\n\n")

        sb.append("    private static long[] build() {\n")
        sb.append("        final long[] data = new long[]{\n")
        entries.forEach { e ->
            sb.append("            pack(R.raw.").append(e.name).append(", ")
                .append(e.fps).append(", ").append(e.frameCount).append(", ").append(e.monocolor).append("),\n")
        }
        sb.append("        };\n")
        sb.append("        Arrays.sort(data); // sorts by resId (high 32 bits)\n")
        sb.append("        return data;\n")
        sb.append("    }\n\n")

        sb.append("    private static long pack(int resId, int fps, int frameCount, boolean mono) {\n")
        sb.append("        return ((long) resId << 32)\n")
        sb.append("                | ((long) (fps & 0xFF) << 24)\n")
        sb.append("                | (mono ? (1L << 23) : 0L)\n")
        sb.append("                | (frameCount & 0x7FFFFF);\n")
        sb.append("    }\n\n")

        sb.append("    /** @return packed metadata for resId, or {@link #NOT_FOUND}. */\n")
        sb.append("    public static long find(@RawRes int resId) {\n")
        sb.append("        final long[] data = Holder.DATA;\n")
        sb.append("        int lo = 0, hi = data.length - 1;\n")
        sb.append("        while (lo <= hi) {\n")
        sb.append("            final int mid = (lo + hi) >>> 1;\n")
        sb.append("            final int midId = (int) (data[mid] >>> 32);\n")
        sb.append("            if (midId < resId) {\n")
        sb.append("                lo = mid + 1;\n")
        sb.append("            } else if (midId > resId) {\n")
        sb.append("                hi = mid - 1;\n")
        sb.append("            } else {\n")
        sb.append("                return data[mid];\n")
        sb.append("            }\n")
        sb.append("        }\n")
        sb.append("        return NOT_FOUND;\n")
        sb.append("    }\n\n")

        sb.append("    public static boolean isLottie(@RawRes int resId) {\n")
        sb.append("        return find(resId) != NOT_FOUND;\n")
        sb.append("    }\n\n")

        sb.append("    /** Drop-in replacement for the old MonoColorLottieList.isMonoColorLottie(resId). */\n")
        sb.append("    public static boolean isMonoColor(@RawRes int resId) {\n")
        sb.append("        long packed = find(resId);\n")
        sb.append("        return packed != NOT_FOUND && isMonoColorOf(packed);\n")
        sb.append("    }\n\n")

        sb.append("    public static int fpsOf(long packed) {\n")
        sb.append("        return (int) ((packed >>> 24) & 0xFF);\n")
        sb.append("    }\n\n")

        sb.append("    public static boolean isMonoColorOf(long packed) {\n")
        sb.append("        return (packed & (1L << 23)) != 0L;\n")
        sb.append("    }\n\n")

        sb.append("    public static int frameCountOf(long packed) {\n")
        sb.append("        return (int) (packed & 0x7FFFFF);\n")
        sb.append("    }\n\n")

        sb.append("    public static int resIdOf(long packed) {\n")
        sb.append("        return (int) (packed >>> 32);\n")
        sb.append("    }\n")

        sb.append("}\n")
        return sb.toString()
    }

    private companion object {
        const val FPS_MAX = 0xFF          // 8 bits
        const val FRAMES_MAX = 0x7FFFFF   // 23 bits
    }
}
