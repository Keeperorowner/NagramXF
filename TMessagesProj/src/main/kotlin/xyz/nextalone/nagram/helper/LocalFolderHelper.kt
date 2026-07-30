package xyz.nextalone.nagram.helper

import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import xyz.nextalone.nagram.NaConfig

/**
 * NagramX: built-in local folders.
 *
 * They are ordinary [MessagesController.DialogFilter] entries flagged as `local`, generated from
 * the [NaConfig.builtInFolders] recipe instead of from the server, so they are never uploaded and
 * never removed by a remote filter sync. The recipe is the single source of truth: the rows in
 * `dialog_filter_neko` are just a device-local cache rebuilt by [ensureLocalFilters].
 *
 * Because the recipe lives in `nkmrcfg`, it is carried by the existing cloud settings sync for
 * free — another device restores the recipe and rebuilds identical folders on next launch.
 */
object LocalFolderHelper {

    /** Deterministic filter id per type, far above the 2..255 range the server hands out. */
    private const val FILTER_ID_BASE = 1000

    private const val DEFAULT_RECIPE = "!USERS,!GROUPS,!SUPERGROUPS,!BASIC_GROUPS,!CHANNELS,!BOTS,!ADMIN,!UNREAD,!UNMUTED"

    enum class FolderType(val filterType: Int) {
        USERS(MessagesController.DIALOG_FILTER_TYPE_USERS),
        GROUPS(MessagesController.DIALOG_FILTER_TYPE_GROUPS_ALL),
        SUPERGROUPS(MessagesController.DIALOG_FILTER_TYPE_MEGAGROUPS),
        BASIC_GROUPS(MessagesController.DIALOG_FILTER_TYPE_GROUPS),
        CHANNELS(MessagesController.DIALOG_FILTER_TYPE_CHANNELS),
        BOTS(MessagesController.DIALOG_FILTER_TYPE_BOTS),
        ADMIN(MessagesController.DIALOG_FILTER_TYPE_ADMIN),
        UNREAD(MessagesController.DIALOG_FILTER_TYPE_UNREAD),
        UNMUTED(MessagesController.DIALOG_FILTER_TYPE_UNMUTED);

        companion object {
            fun of(filterType: Int): FolderType? {
                for (type in values()) {
                    if (type.filterType == filterType) {
                        return type
                    }
                }
                return null
            }
        }
    }

    class FolderState(@JvmField val type: FolderType, @JvmField var enabled: Boolean)

    @JvmStatic
    fun filterId(type: FolderType): Int = FILTER_ID_BASE + type.filterType

    @JvmStatic
    fun isLocalFilterId(id: Int): Boolean = id >= FILTER_ID_BASE

    @JvmStatic
    fun getName(type: FolderType): String = when (type) {
        FolderType.USERS -> LocaleController.getString(R.string.PrivateChats)
        FolderType.GROUPS -> LocaleController.getString(R.string.FilterGroups)
        FolderType.SUPERGROUPS -> LocaleController.getString(R.string.BuiltInFolderSupergroups)
        FolderType.BASIC_GROUPS -> LocaleController.getString(R.string.BuiltInFolderBasicGroups)
        FolderType.CHANNELS -> LocaleController.getString(R.string.FilterChannels)
        FolderType.BOTS -> LocaleController.getString(R.string.FilterBots)
        FolderType.ADMIN -> LocaleController.getString(R.string.BuiltInFolderAdmin)
        FolderType.UNREAD -> LocaleController.getString(R.string.BuiltInFolderUnread)
        FolderType.UNMUTED -> LocaleController.getString(R.string.BuiltInFolderUnmuted)
    }

    @JvmStatic
    fun getEmoticon(type: FolderType): String = when (type) {
        FolderType.USERS -> "👤"
        FolderType.GROUPS -> "👥"
        FolderType.SUPERGROUPS -> "✴️"
        FolderType.BASIC_GROUPS -> "🇴"
        FolderType.CHANNELS -> "📢"
        FolderType.BOTS -> "🤖"
        FolderType.ADMIN -> "👑"
        FolderType.UNREAD -> "💬"
        FolderType.UNMUTED -> "🔔"
    }

    @JvmStatic
    fun getFlags(type: FolderType): Int {
        val excludeArchived = MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED
        return when (type) {
            FolderType.USERS ->
                MessagesController.DIALOG_FILTER_FLAG_CONTACTS or
                    MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS or excludeArchived
            FolderType.GROUPS ->
                MessagesController.DIALOG_FILTER_FLAG_GROUPS or excludeArchived
            FolderType.SUPERGROUPS ->
                MessagesController.DIALOG_FILTER_FLAG_GROUPS or excludeArchived
            FolderType.BASIC_GROUPS ->
                MessagesController.DIALOG_FILTER_FLAG_GROUPS or excludeArchived
            FolderType.CHANNELS ->
                MessagesController.DIALOG_FILTER_FLAG_CHANNELS or excludeArchived
            FolderType.BOTS ->
                MessagesController.DIALOG_FILTER_FLAG_BOTS or excludeArchived
            FolderType.ADMIN ->
                MessagesController.DIALOG_FILTER_FLAG_GROUPS or
                    MessagesController.DIALOG_FILTER_FLAG_CHANNELS or excludeArchived
            FolderType.UNREAD ->
                MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS or
                    MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ or excludeArchived
            FolderType.UNMUTED ->
                MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS or
                    MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED or excludeArchived
        }
    }

    /** Short type summary shown under the name, e.g. "Contacts, Non Contacts". */
    @JvmStatic
    fun getDescription(type: FolderType): String = when (type) {
        FolderType.USERS ->
            LocaleController.getString(R.string.FilterContacts) + ", " + LocaleController.getString(R.string.FilterNonContacts)
        FolderType.GROUPS -> LocaleController.getString(R.string.FilterGroups)
        FolderType.SUPERGROUPS -> LocaleController.getString(R.string.BuiltInFolderSupergroups)
        FolderType.BASIC_GROUPS -> LocaleController.getString(R.string.BuiltInFolderBasicGroups)
        FolderType.CHANNELS -> LocaleController.getString(R.string.FilterChannels)
        FolderType.BOTS -> LocaleController.getString(R.string.FilterBots)
        FolderType.ADMIN -> LocaleController.getString(R.string.BuiltInFolderAdmin)
        FolderType.UNREAD -> LocaleController.getString(R.string.BuiltInFolderUnread)
        FolderType.UNMUTED -> LocaleController.getString(R.string.BuiltInFolderUnmuted)
    }

    /**
     * The second line under a local folder in the chat-folders screen: the "Local folder" tag plus
     * what the folder contains, and how many chats the user added or removed as exceptions.
     */
    @JvmStatic
    fun getSubtitle(filter: MessagesController.DialogFilter): String {
        val type = FolderType.of(filter.type) ?: return ""
        var subtitle = LocaleController.getString(R.string.LocalFolder) + ": " + getDescription(type)
        val exceptions = filter.alwaysShow.size + filter.neverShow.size
        if (exceptions > 0) {
            subtitle += ", " + LocaleController.formatPluralString("Exception", exceptions)
        }
        return subtitle
    }

    /** Every folder type in the user's order, carrying its enabled flag. */
    @JvmStatic
    fun getAllFolders(): ArrayList<FolderState> {
        var recipe = NaConfig.builtInFolders.String()
        if (recipe.isNullOrBlank()) {
            recipe = DEFAULT_RECIPE
        }

        val result = ArrayList<FolderState>()
        val missing = FolderType.values().toMutableList()

        for (rawPart in recipe.split(",")) {
            val part = rawPart.trim()
            if (part.isEmpty()) {
                continue
            }
            val enabled = !part.startsWith("!")
            val name = if (enabled) part else part.substring(1)
            val type = try {
                FolderType.valueOf(name)
            } catch (ignore: Exception) {
                continue
            }
            if (!missing.remove(type)) {
                continue
            }
            result.add(FolderState(type, enabled))
        }
        for (type in missing) {
            result.add(FolderState(type, false))
        }
        return result
    }

    @JvmStatic
    fun getEnabledFolders(): ArrayList<FolderState> {
        val enabled = ArrayList<FolderState>()
        for (state in getAllFolders()) {
            if (state.enabled) {
                enabled.add(state)
            }
        }
        return enabled
    }

    @JvmStatic
    fun saveFolders(states: List<FolderState>) {
        val builder = StringBuilder()
        for (state in states) {
            if (builder.isNotEmpty()) {
                builder.append(',')
            }
            if (!state.enabled) {
                builder.append('!')
            }
            builder.append(state.type.name)
        }
        val recipe = builder.toString()
        // avoid rewriting an identical value: every write wakes the cloud settings sync up
        if (recipe != NaConfig.builtInFolders.String()) {
            NaConfig.builtInFolders.setConfigString(recipe)
        }
    }

    @JvmStatic
    fun setFolderEnabled(type: FolderType, enabled: Boolean) {
        val states = getAllFolders()
        for (state in states) {
            if (state.type == type) {
                state.enabled = enabled
            }
        }
        saveFolders(states)
    }

    /** Turns a built-in folder off so [ensureLocalFilters] will not bring it back. */
    @JvmStatic
    fun disableFolder(filterType: Int) {
        val type = FolderType.of(filterType) ?: return
        setFolderEnabled(type, false)
    }

    /**
     * The built-in folders the user has not enabled yet, shaped as "Recommended folders" entries so
     * they can be offered inside Telegram's own chat folders screen.
     */
    @JvmStatic
    fun getSuggestions(): ArrayList<TLRPC.TL_dialogFilterSuggested> {
        val result = ArrayList<TLRPC.TL_dialogFilterSuggested>()
        for (state in getAllFolders()) {
            if (state.enabled) {
                continue
            }
            val filter = TLRPC.TL_dialogFilter()
            filter.id = filterId(state.type)
            filter.title = TLRPC.TL_textWithEntities()
            filter.title.text = getName(state.type)
            filter.emoticon = getEmoticon(state.type)
            filter.flags = getFlags(state.type)

            val suggested = TLRPC.TL_dialogFilterSuggested()
            suggested.filter = filter
            suggested.description = LocaleController.getString(R.string.LocalFolder) + ": " + getDescription(state.type)
            result.add(suggested)
        }
        return result
    }

    /** Non-null when the suggestion is one of ours rather than one the server sent. */
    @JvmStatic
    fun folderTypeOf(suggested: TLRPC.TL_dialogFilterSuggested?): FolderType? {
        val id = suggested?.filter?.id ?: return null
        if (!isLocalFilterId(id)) {
            return null
        }
        return FolderType.of(id - FILTER_ID_BASE)
    }

    /** The built-in folder type for a raw DialogFilter.type, or null for a server folder. */
    @JvmStatic
    fun folderTypeOf(filterType: Int): FolderType? = FolderType.of(filterType)

    /**
     * Reconciles the folders in [MessagesController.dialogFilters] with the recipe: creates what is
     * missing, drops what is no longer wanted and refreshes name/flags/icon. Idempotent, so it can
     * run on every filter load — that is what makes a restored cloud recipe converge.
     *
     * Must run on the UI thread.
     */
    @JvmStatic
    fun ensureLocalFilters(currentAccount: Int) {
        val controller = MessagesController.getInstance(currentAccount)
        val storage = MessagesStorage.getInstance(currentAccount)
        val wanted = getEnabledFolders()

        var changed = false

        for (i in controller.dialogFilters.indices.reversed()) {
            val filter = controller.dialogFilters[i]
            if (!filter.local) {
                continue
            }
            val type = FolderType.of(filter.type)
            if (type == null || wanted.none { it.type == type }) {
                controller.removeFilter(filter)
                storage.deleteDialogFilter(filter)
                changed = true
            }
        }

        // New folders land at the end; afterwards the order belongs to the user, who can drag the
        // tabs around. saveDialogFiltersOrder() renumbers everything, so never force it back here.
        var nextOrder = 0
        for (filter in controller.dialogFilters) {
            nextOrder = maxOf(nextOrder, filter.order)
        }

        for (state in wanted) {
            val type = state.type
            val name = getName(type)
            val flags = getFlags(type)
            val emoticon = getEmoticon(type)

            val existing = controller.dialogFiltersById.get(filterId(type))
            if (existing == null) {
                val filter = MessagesController.DialogFilter()
                filter.id = filterId(type)
                filter.local = true
                filter.type = type.filterType
                filter.order = ++nextOrder
                filter.name = name
                filter.flags = flags
                filter.emoticon = emoticon
                filter.color = -1
                filter.unreadCount = -1
                filter.pendingUnreadCount = -1
                controller.dialogFilters.add(filter)
                controller.dialogFiltersById.put(filter.id, filter)
                storage.saveDialogFilter(filter, false, true)
                changed = true
            } else if (existing.name != name || existing.flags != flags || existing.emoticon != emoticon) {
                // keep the folder in step with the current locale and code
                existing.name = name
                existing.flags = flags
                existing.emoticon = emoticon
                storage.saveDialogFilter(existing, false, false)
                changed = true
            }
        }

        if (!changed) {
            return
        }
        controller.dialogFilters.sortWith(compareBy { it.order })
        controller.lockFiltersInternal()
        NotificationCenter.getInstance(currentAccount)
            .postNotificationName(NotificationCenter.dialogFiltersUpdated)
    }

    /**
     * Persists the on-screen order of the local folders back into the recipe, so a tab drag
     * survives a restart and reaches the user's other devices through the settings sync.
     *
     * Called from the single choke point that saves folder order.
     */
    @JvmStatic
    fun saveOrderFromFilters(filters: List<MessagesController.DialogFilter>) {
        // the list order is the on-screen order: saveDialogFiltersOrder() renumbers by index
        val ordered = ArrayList<FolderState>()
        for (filter in filters) {
            if (!filter.local) {
                continue
            }
            val type = FolderType.of(filter.type) ?: continue
            ordered.add(FolderState(type, true))
        }
        if (ordered.isEmpty()) {
            return
        }
        for (state in getAllFolders()) {
            if (ordered.none { it.type == state.type }) {
                ordered.add(FolderState(state.type, false))
            }
        }
        saveFolders(ordered)
    }
}
