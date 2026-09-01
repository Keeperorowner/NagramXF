package xyz.nextalone.nagram.helper

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.telegram.messenger.R
import org.telegram.ui.Adapters.DrawerLayoutAdapter
import xyz.nextalone.nagram.NaConfig

/**
 * Data model for the ayuGram-style sidebar ("main menu") manager.
 *
 * The drawer content is driven by two persisted id lists instead of the old
 * per-item boolean toggles:
 *  - [NaConfig.mainMenuLayout]       : ordered ids of the *visible* rows, [DIVIDER] entries included
 *  - [NaConfig.mainMenuHiddenItems]  : ids of the *hidden* rows
 *
 * On first run (both lists blank) the previous DrawerItem* boolean toggles are
 * migrated into the new model so existing user settings are preserved.
 */
object DrawerMenuHelper {

    const val DIVIDER = -1

    // Stock Telegram drawer ids (used as literals inside DrawerLayoutAdapter).
    const val ID_NEW_GROUP = 2
    const val ID_NEW_CHANNEL = 4
    const val ID_CONTACTS = 6
    const val ID_SETTINGS = 8
    const val ID_CALLS = 10
    const val ID_SAVED = 11
    const val ID_EMOJI_STATUS = 15
    const val ID_MY_PROFILE = 16

    class Entry(
        val id: Int,
        @field:StringRes @param:StringRes val labelRes: Int,
        @field:DrawableRes @param:DrawableRes val iconRes: Int
    )

    /** Every configurable row, in canonical (stock) order. Dividers are not entries. */
    @JvmStatic
    val entries: List<Entry> = listOf(
        Entry(DrawerLayoutAdapter.nkbtnGhostMode, R.string.GhostMode, R.drawable.ayu_ghost),
        Entry(ID_MY_PROFILE, R.string.MyProfile, R.drawable.left_status_profile),
        Entry(ID_EMOJI_STATUS, R.string.SetEmojiStatus, R.drawable.msg_status_set),
        Entry(DrawerLayoutAdapter.nkbtnArchivedChats, R.string.ArchivedChats, R.drawable.msg_archive),
        Entry(ID_NEW_GROUP, R.string.NewGroup, R.drawable.msg_groups),
        Entry(ID_NEW_CHANNEL, R.string.NewChannel, R.drawable.msg_channel),
        Entry(ID_CONTACTS, R.string.Contacts, R.drawable.msg_contacts),
        Entry(ID_CALLS, R.string.Calls, R.drawable.msg_calls),
        Entry(DrawerLayoutAdapter.nkbtnRecentChats, R.string.RecentChats, R.drawable.msg_recent),
        Entry(ID_SAVED, R.string.SavedMessages, R.drawable.msg_saved),
        Entry(DrawerLayoutAdapter.nkbtnBookmarks, R.string.BookmarksManager, R.drawable.msg_fave),
        Entry(ID_SETTINGS, R.string.Settings, R.drawable.msg_settings_old),
        Entry(DrawerLayoutAdapter.nkbtnSettings, R.string.NekoSettings, R.drawable.nagramx_outline),
        Entry(DrawerLayoutAdapter.nkbtnBrowser, R.string.InappBrowser, R.drawable.web_browser),
        Entry(DrawerLayoutAdapter.nkbtnQrLogin, R.string.ImportLogin, R.drawable.msg_qrcode),
        Entry(DrawerLayoutAdapter.nkbtnSessions, R.string.Devices, R.drawable.msg2_devices),
        Entry(DrawerLayoutAdapter.nkbtnMainTabsCustomize, R.string.MainTabsCustomize, R.drawable.tabs_reorder),
        Entry(DrawerLayoutAdapter.nkbtnFeed, R.string.Feed, R.drawable.ic_feed),
        Entry(DrawerLayoutAdapter.nkbtnRestartApp, R.string.RestartApp, R.drawable.msg_retry)
    )

    @JvmStatic
    fun entryFor(id: Int): Entry? = entries.firstOrNull { it.id == id }

    @JvmStatic
    fun isConfigurable(id: Int): Boolean = entryFor(id) != null

    /** Stock layout: what a fresh install shows, dividers included. */
    @JvmStatic
    fun defaultLayout(): MutableList<Int> = mutableListOf(
        DrawerLayoutAdapter.nkbtnGhostMode, DIVIDER,
        ID_MY_PROFILE, ID_EMOJI_STATUS, ID_NEW_GROUP, ID_CONTACTS, ID_CALLS,
        DrawerLayoutAdapter.nkbtnRecentChats, ID_SAVED, ID_SETTINGS, DIVIDER,
        DrawerLayoutAdapter.nkbtnSettings
    )

    @JvmStatic
    fun defaultHidden(): MutableList<Int> = mutableListOf(
        DrawerLayoutAdapter.nkbtnArchivedChats, ID_NEW_CHANNEL,
        DrawerLayoutAdapter.nkbtnBookmarks, DrawerLayoutAdapter.nkbtnBrowser,
        DrawerLayoutAdapter.nkbtnQrLogin, DrawerLayoutAdapter.nkbtnSessions,
        DrawerLayoutAdapter.nkbtnMainTabsCustomize, DrawerLayoutAdapter.nkbtnRestartApp,
        DrawerLayoutAdapter.nkbtnFeed
    )

    // --- persistence -------------------------------------------------------

    @JvmStatic
    fun getLayout(): MutableList<Int> {
        ensureMigrated()
        val layout = decode(NaConfig.mainMenuLayout.String())
        val hidden = decode(NaConfig.mainMenuHiddenItems.String())
        if (sanitize(layout, hidden)) {
            save(layout, hidden)
        }
        return layout
    }

    @JvmStatic
    fun getHidden(): MutableList<Int> {
        ensureMigrated()
        val hidden = decode(NaConfig.mainMenuHiddenItems.String())
        val layout = decode(NaConfig.mainMenuLayout.String())
        if (sanitize(layout, hidden)) {
            save(layout, hidden)
        }
        return hidden
    }

    /**
     * Drops unknown ids (e.g. legacy ids no longer in the registry) from the layout and
     * makes sure every configurable item lives in exactly one of the two lists — new
     * items are appended to the hidden list so they can be enabled from the manager.
     */
    private fun sanitize(layout: MutableList<Int>, hidden: MutableList<Int>): Boolean {
        var changed = false
        val iterator = layout.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (id != DIVIDER && entryFor(id) == null) {
                iterator.remove()
                changed = true
            }
        }
        for (entry in entries) {
            if (!layout.contains(entry.id) && !hidden.contains(entry.id)) {
                hidden.add(entry.id)
                changed = true
            }
        }
        return changed
    }

    @JvmStatic
    fun save(layout: List<Int>, hidden: List<Int>) {
        NaConfig.mainMenuLayout.setConfigString(encode(layout))
        NaConfig.mainMenuHiddenItems.setConfigString(encode(hidden))
    }

    @JvmStatic
    fun resetToDefault() {
        save(defaultLayout(), defaultHidden())
    }

    // --- migration ---------------------------------------------------------

    @Volatile
    private var migrated = false

    @Synchronized
    private fun ensureMigrated() {
        if (migrated) return
        migrated = true
        if (NaConfig.mainMenuLayout.String().isNotEmpty()
            || NaConfig.mainMenuHiddenItems.String().isNotEmpty()
        ) return
        migrateFromLegacyToggles()
    }

    /** Build the new lists once from the previous per-item boolean toggles. */
    private fun migrateFromLegacyToggles() {
        val visible = mutableListOf<Int>()
        val hidden = mutableListOf<Int>()

        fun put(id: Int, show: Boolean) = (if (show) visible else hidden).add(id)

        put(DrawerLayoutAdapter.nkbtnGhostMode, NaConfig.drawerItemGhost.Bool())
        put(ID_MY_PROFILE, NaConfig.drawerItemMyProfile.Bool())
        put(ID_EMOJI_STATUS, NaConfig.drawerItemSetEmojiStatus.Bool())
        put(DrawerLayoutAdapter.nkbtnArchivedChats, NaConfig.drawerItemArchivedChats.Bool())
        put(ID_NEW_GROUP, NaConfig.drawerItemNewGroup.Bool())
        put(ID_NEW_CHANNEL, NaConfig.drawerItemNewChannel.Bool())
        put(ID_CONTACTS, NaConfig.drawerItemContacts.Bool())
        put(ID_CALLS, NaConfig.drawerItemCalls.Bool())
        put(DrawerLayoutAdapter.nkbtnRecentChats, NaConfig.drawerItemRecentChats.Bool())
        put(ID_SAVED, NaConfig.drawerItemSaved.Bool())
        put(DrawerLayoutAdapter.nkbtnBookmarks, NaConfig.showAddToBookmark.Bool())
        put(ID_SETTINGS, NaConfig.drawerItemSettings.Bool())
        put(DrawerLayoutAdapter.nkbtnSettings, NaConfig.drawerItemNSettings.Bool())
        put(DrawerLayoutAdapter.nkbtnBrowser, NaConfig.drawerItemBrowser.Bool())
        put(DrawerLayoutAdapter.nkbtnQrLogin, NaConfig.drawerItemQrLogin.Bool())
        put(DrawerLayoutAdapter.nkbtnSessions, NaConfig.drawerItemSessions.Bool())
        put(DrawerLayoutAdapter.nkbtnRestartApp, NaConfig.drawerItemRestartApp.Bool())

        // Introduced after the legacy toggles; default to hidden, the manager can enable it.
        if (!hidden.contains(DrawerLayoutAdapter.nkbtnMainTabsCustomize)) {
            hidden.add(DrawerLayoutAdapter.nkbtnMainTabsCustomize)
        }

        // Re-insert the structural dividers so the migrated drawer keeps the stock look:
        // one right after Ghost, one right before Neko settings.
        val ghostIndex = visible.indexOf(DrawerLayoutAdapter.nkbtnGhostMode)
        if (ghostIndex >= 0) visible.add(ghostIndex + 1, DIVIDER)
        val nSettingsIndex = visible.indexOf(DrawerLayoutAdapter.nkbtnSettings)
        if (nSettingsIndex >= 0) visible.add(nSettingsIndex, DIVIDER)

        save(visible, hidden)
    }

    // --- encoding ----------------------------------------------------------

    private fun encode(list: List<Int>): String = list.joinToString(",")

    private fun decode(raw: String): MutableList<Int> {
        if (raw.isBlank()) return mutableListOf()
        val out = mutableListOf<Int>()
        for (part in raw.split(",")) {
            part.trim().toIntOrNull()?.let { out.add(it) }
        }
        return out
    }
}
