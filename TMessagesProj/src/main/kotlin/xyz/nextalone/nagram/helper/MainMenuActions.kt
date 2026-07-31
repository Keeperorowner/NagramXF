package xyz.nextalone.nagram.helper

import android.content.Intent
import android.os.Bundle
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionIntroActivity
import org.telegram.ui.CallLogActivity
import org.telegram.ui.ChannelCreateActivity
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.SharedMediaLayout
import org.telegram.ui.Components.MediaActivity
import org.telegram.ui.ContactsActivity
import org.telegram.ui.DialogsActivity
import org.telegram.ui.GroupCreateActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity
import org.telegram.ui.SessionsActivity
import org.telegram.ui.SettingsActivity
import org.telegram.ui.web.WebBrowserSettings
import org.telegram.ui.Adapters.DrawerLayoutAdapter
import tw.nekomimi.nekogram.ChatHistoryActivity
import tw.nekomimi.nekogram.helpers.AppRestartHelper
import tw.nekomimi.nekogram.settings.GhostModeActivity
import tw.nekomimi.nekogram.settings.NekoSettingsActivity
import tw.nekomimi.nekogram.ui.BookmarkManagerActivity
import tw.nekomimi.nekogram.utils.BrowserUtils

/**
 * Resolves the "open the matching page" action for a configurable sidebar/main-menu
 * item id. Shared by the navigation drawer (via [DrawerLayoutAdapter]) and the
 * top-right overflow ("three dots") menu so both surfaces honour the same
 * [DrawerMenuHelper] layout, mirroring ayuGram's MainMenuHelper.resolveMenuItem.
 *
 * Items that need a [LaunchActivity]-specific context (emoji status, QR login) are
 * not resolvable here and return `false`, so the overflow menu simply skips them.
 */
object MainMenuActions {

    /**
     * Whether the overflow menu can offer this item. Emoji status and QR login need a
     * LaunchActivity-scoped context, so they are only available from the drawer.
     */
    @JvmStatic
    fun isSupported(id: Int): Boolean {
        return id != DrawerMenuHelper.ID_EMOJI_STATUS &&
                id != DrawerLayoutAdapter.nkbtnQrLogin &&
                DrawerMenuHelper.entryFor(id) != null
    }

    @JvmStatic
    fun openItem(id: Int, fragment: BaseFragment, currentAccount: Int): Boolean {
        when {
            id == DrawerLayoutAdapter.nkbtnGhostMode -> toggleGhostMode(fragment)
            id == DrawerLayoutAdapter.nkbtnRecentChats ->
                fragment.presentFragment(ChatHistoryActivity())
            id == DrawerMenuHelper.ID_NEW_GROUP ->
                fragment.presentFragment(GroupCreateActivity(Bundle()))
            id == DrawerMenuHelper.ID_NEW_CHANNEL -> openNewChannel(fragment)
            id == DrawerMenuHelper.ID_CONTACTS -> {
                val args = Bundle()
                args.putBoolean("needFinishFragment", false)
                fragment.presentFragment(ContactsActivity(args))
            }
            id == DrawerMenuHelper.ID_CALLS ->
                fragment.presentFragment(CallLogActivity())
            id == DrawerMenuHelper.ID_SAVED -> openSavedMessages(fragment, currentAccount)
            id == DrawerMenuHelper.ID_SETTINGS ->
                fragment.presentFragment(SettingsActivity())
            id == DrawerMenuHelper.ID_MY_PROFILE -> {
                val args = Bundle()
                args.putLong("user_id", UserConfig.getInstance(currentAccount).clientUserId)
                args.putBoolean("my_profile", true)
                fragment.presentFragment(ProfileActivity(args, null))
            }
            id == DrawerLayoutAdapter.nkbtnArchivedChats -> {
                val args = Bundle()
                args.putInt("folderId", 1)
                fragment.presentFragment(DialogsActivity(args))
            }
            id == DrawerLayoutAdapter.nkbtnBookmarks ->
                fragment.presentFragment(BookmarkManagerActivity())
            id == DrawerLayoutAdapter.nkbtnSettings ->
                fragment.presentFragment(NekoSettingsActivity())
            id == DrawerLayoutAdapter.nkbtnBrowser -> BrowserUtils.openBrowserHome(null, true)
            id == DrawerLayoutAdapter.nkbtnSessions ->
                fragment.presentFragment(SessionsActivity(SessionsActivity.TYPE_DEVICES))
            id == DrawerLayoutAdapter.nkbtnRestartApp -> AppRestartHelper.triggerRebirth(
                ApplicationLoader.applicationContext,
                Intent(ApplicationLoader.applicationContext, LaunchActivity::class.java)
            )
            else -> return false
        }
        return true
    }

    /** Long-press action for an item, or `false` when the item has none. */
    @JvmStatic
    fun longClickItem(id: Int, fragment: BaseFragment, currentAccount: Int): Boolean {
        when {
            id == DrawerLayoutAdapter.nkbtnGhostMode ->
                fragment.presentFragment(GhostModeActivity())
            id == DrawerLayoutAdapter.nkbtnBrowser ->
                fragment.presentFragment(WebBrowserSettings(null))
            else -> return false
        }
        return true
    }

    /** Primary Ghost action: toggle ghost mode, mirroring the drawer/overflow behaviour. */
    private fun toggleGhostMode(fragment: BaseFragment) {
        val message = if (tw.nekomimi.nekogram.NekoConfig.isGhostModeActive())
            org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.GhostModeDisabled)
        else
            org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.GhostModeEnabled)
        tw.nekomimi.nekogram.NekoConfig.toggleGhostMode()
        org.telegram.ui.Components.BulletinFactory.of(fragment).createSuccessBulletin(message).show()
        org.telegram.messenger.NotificationCenter.getInstance(org.telegram.messenger.UserConfig.selectedAccount)
            .postNotificationName(org.telegram.messenger.NotificationCenter.mainUserInfoChanged)
    }

    private fun openNewChannel(fragment: BaseFragment) {
        val preferences = MessagesController.getGlobalMainSettings()
        if (!BuildVars.DEBUG_VERSION && preferences.getBoolean("channel_intro", false)) {
            val args = Bundle()
            args.putInt("step", 0)
            fragment.presentFragment(ChannelCreateActivity(args))
        } else {
            fragment.presentFragment(ActionIntroActivity(ActionIntroActivity.ACTION_TYPE_CHANNEL_CREATE))
            preferences.edit().putBoolean("channel_intro", true).apply()
        }
    }

    private fun openSavedMessages(fragment: BaseFragment, currentAccount: Int) {
        if (MessagesController.getInstance(currentAccount).savedViewAsChats) {
            val args = Bundle()
            args.putLong("dialog_id", UserConfig.getInstance(currentAccount).clientUserId)
            args.putInt("type", MediaActivity.TYPE_MEDIA)
            args.putInt("start_from", SharedMediaLayout.TAB_SAVED_DIALOGS)
            fragment.presentFragment(MediaActivity(args, null))
        } else {
            val args = Bundle()
            args.putLong("user_id", UserConfig.getInstance(currentAccount).clientUserId)
            fragment.presentFragment(ChatActivity(args))
        }
    }
}
