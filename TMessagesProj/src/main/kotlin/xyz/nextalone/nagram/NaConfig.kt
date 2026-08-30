package xyz.nextalone.nagram

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.SharedConfig
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.config.ConfigItem
import tw.nekomimi.nekogram.config.ConfigItemKeyLinked
import tw.nekomimi.nekogram.config.ConfigItemKeyLinkedGroup
import tw.nekomimi.nekogram.llm.utils.LlmUrlNormalizer
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream


object NaConfig {
    @Volatile
    private var initialized = false

    @JvmStatic
    fun getPreferences(): SharedPreferences {
        return NekoConfig.getPreferences()
    }

    @JvmStatic
    fun init() {
        if (initialized) return
        synchronized(sync) {
            if (initialized) return
            if (ApplicationLoader.applicationContext == null) return

            if (getPreferences().contains("IgnoreUnreadCount")) {
                try {
                    getPreferences().getBoolean("IgnoreUnreadCount", false)
                } catch (_: Exception) {
                    val legacyInt = getPreferences().getInt("IgnoreUnreadCount", 0)
                    getPreferences().edit {
                        remove("IgnoreUnreadCount")
                        putBoolean("IgnoreUnreadCount", legacyInt == NekoConfig.DIALOG_FILTER_EXCLUDE_ALL)
                    }
                }
            }

            loadConfig(false)
            updatePreferredTranslateTargetLangList()
            fixConfig()
            if (!BuildVars.LOGS_ENABLED) {
                showRPCError.setConfigBool(false)
            }
            initialized = true
        }
    }

    val sync = Any()
    private var configLoaded = false
    private val configs = ArrayList<ConfigItem>()

    // Configs
    val showTextBold =
        addConfig(
            "TextBold",
            ConfigItem.configTypeBool,
            true
        )
    val showTextItalic =
        addConfig(
            "TextItalic",
            ConfigItem.configTypeBool,
            true
        )
    val showTextMono =
        addConfig(
            "TextMonospace",
            ConfigItem.configTypeBool,
            true
        )
    val showTextStrikethrough =
        addConfig(
            "TextStrikethrough",
            ConfigItem.configTypeBool,
            true
        )
    val showTextUnderline =
        addConfig(
            "TextUnderline",
            ConfigItem.configTypeBool,
            true
        )
    val showTextQuote =
        addConfig(
            "TextQuote",
            ConfigItem.configTypeBool,
            true
        )
    val showTextSpoiler =
        addConfig(
            "TextSpoiler",
            ConfigItem.configTypeBool,
            true
        )
    val showTextCreateLink =
        addConfig(
            "TextLink",
            ConfigItem.configTypeBool,
            true
        )
    val showTextCreateMention =
        addConfig(
            "TextCreateMention",
            ConfigItem.configTypeBool,
            true
        )
    val showTextCreateDate =
        addConfig(
            "TextCreateDate",
            ConfigItem.configTypeBool,
            true
        )
    val showTextRegular =
        addConfig(
            "TextRegular",
            ConfigItem.configTypeBool,
            true
        )
    val showTextTranslate =
        addConfig(
            "TextTranslate",
            ConfigItem.configTypeBool,
            true
        )
    val textStyleOrder =
        addConfig(
            "TextStyleOrder",
            ConfigItem.configTypeString,
            "translate,bold,italic,mono,code,strike,underline,quote,spoiler,link,mention,date,regular"
        )
    val combineMessage =
        addConfig(
            "CombineMessage",
            ConfigItem.configTypeInt,
            0
        )
    val noiseSuppressAndVoiceEnhance =
        addConfig(
            "NoiseSuppressAndVoiceEnhance",
            ConfigItem.configTypeBool,
            false
        )
    val showNoQuoteForward =
        addConfig(
            "NoQuoteForward",
            ConfigItem.configTypeBool,
            false
        )
    val showRepeatAsCopy =
        addConfig(
            "RepeatAsCopy",
            ConfigItem.configTypeBool,
            false
        )
    val doubleTapAction =
        addConfig(
            "DoubleTapAction",
            ConfigItem.configTypeInt,
            3
        )
    val doubleTapActionOut =
        addConfig(
            "DoubleTapActionOut",
            ConfigItem.configTypeInt,
            8
        )
    val doubleTapSeekDuration =
        addConfig(
            "doubleTapSeekDuration",
            ConfigItem.configTypeInt,
            1
        )
    val showCopyPhoto =
        addConfig(
            "CopyPhoto",
            ConfigItem.configTypeBool,
            false
        )
    val showReactions =
        addConfig(
            "Reactions",
            ConfigItem.configTypeBool,
            true
        )
    val customTitle =
        addConfig(
            "CustomTitle",
            ConfigItem.configTypeString,
            "Nagram XF"
        )
    val dateOfForwardedMsg =
        addConfig(
            "DateOfForwardedMsg",
            ConfigItem.configTypeBool,
            false
        )
    val showMessageID =
        addConfig(
            "ShowMessageID",
            ConfigItem.configTypeBool,
            false
        )
    val showRPCError =
        addConfig(
            "ShowRPCError",
            ConfigItem.configTypeBool,
            false
        )
    val zalgoFilter =
        addConfig(
            "ZalgoFilter",
            ConfigItem.configTypeBool,
            false
        )
    val alwaysShowDownloadIcon =
        addConfig(
            "AlwaysShowDownloadIcon",
            ConfigItem.configTypeBool,
            false
        )
    val customEditedMessage =
        addConfig(
            "CustomEditedMessage",
            ConfigItem.configTypeString,
            ""
        )
    val disableProxyWhenVpnEnabled =
        addConfig(
            "DisableProxyWhenVpnEnabled",
            ConfigItem.configTypeBool,
            false
        )
    val notificationIcon =
        addConfig(
            "NotificationIcon",
            ConfigItem.configTypeInt,
            1
        )
    val showSetReminder =
        addConfig(
            "SetReminder",
            ConfigItem.configTypeBool,
            false
        )
    val showOnlineStatus =
        addConfig(
            "ShowOnlineStatus",
            ConfigItem.configTypeBool,
            false
        )
    val nowPlayingServiceType =
        addConfig(
            "NowPlayingServiceType",
            ConfigItem.configTypeInt,
            0
        )
    val nowPlayingLastFmUsername =
        addConfig(
            "NowPlayingLastFmUsername",
            ConfigItem.configTypeString,
            ""
        )
    val nowPlayingStatsFmUsername =
        addConfig(
            "NowPlayingStatsFmUsername",
            ConfigItem.configTypeString,
            ""
        )
    val replaceBlockedMyInfo =
        addConfig(
            "ReplaceBlockedMyInfo",
            ConfigItem.configTypeBool,
            false
        )
    val showFullAbout =
        addConfig(
            "ShowFullAbout",
            ConfigItem.configTypeBool,
            true
        )
    val typeMessageHintUseGroupName =
        addConfig(
            "TypeMessageHintUseGroupName",
            ConfigItem.configTypeBool,
            false
        )
    val showSendAsUnderMessageHint =
        addConfig(
            "ShowSendAsUnderMessageHint",
            ConfigItem.configTypeBool,
            false
        )
    val hideBotButtonInInputField =
        addConfig(
            "HideBotButtonInInputField",
            ConfigItem.configTypeBool,
            false
        )
    val chatDecoration =
        addConfig(
            "ChatDecoration",
            ConfigItem.configTypeInt,
            0
        )

    /**
     * 聊天里贴纸的形状：0 方形、1 圆角、2 跟随气泡圆角。
     * 取值见 [org.telegram.messenger.StickerShapeHelper]。
     */
    val stickerShape =
        addConfig(
            "StickerShape",
            ConfigItem.configTypeInt,
            1
        )
    val forceSnowfall =
        addConfig(
            "ForceSnowfall",
            ConfigItem.configTypeBool,
            false
        )
    val doNotUnarchiveBySwipe =
        addConfig(
            "DoNotUnarchiveBySwipe",
            ConfigItem.configTypeBool,
            false
        )
    val defaultDeleteMenu =
        addConfig(
            "DefaultDeleteMenu",
            ConfigItem.configTypeInt,
            0
        )
    val defaultDeleteMenuBanUsers =
        addConfig(
            "DeleteBanUsers",
            defaultDeleteMenu,
            3,
            false
        )
    val defaultDeleteMenReportSpam =
        addConfig(
            "DeleteReportSpam",
            defaultDeleteMenu,
            2,
            false
        )
    val defaultDeleteMenuDeleteAll =
        addConfig(
            "DeleteAll",
            defaultDeleteMenu,
            1,
            false
        )
    val defaultDeleteMenuDoActionsInCommonGroups =
        addConfig(
            "DoActionsInCommonGroups",
            defaultDeleteMenu,
            0,
            false
        )
    val disableStories =
        addConfig(
            "DisableStories",
            ConfigItem.configTypeBool,
            false
        )
    private val disableTrendingFlags =
        addConfig(
            "DisableTrendingFlags",
            ConfigItem.configTypeInt,
            0x1C0
        )
    val disableStarsSubscription =
        addConfig(
            "DisableStarsSubscription",
            disableTrendingFlags,
            0,
            false
        )
    val disablePremiumExpiring =
        addConfig(
            "DisablePremiumExpiring",
            disableTrendingFlags,
            1,
            false
        )
    val disablePremiumUpgrade =
        addConfig(
            "DisablePremiumUpgrade",
            disableTrendingFlags,
            2,
            false
        )
    val disablePremiumChristmas =
        addConfig(
            "DisablePremiumChristmas",
            disableTrendingFlags,
            3,
            false
        )
    val disableBirthdayContact =
        addConfig(
            "DisableBirthdayContact",
            disableTrendingFlags,
            4,
            false
        )
    val disablePremiumRestore =
        addConfig(
            "DisablePremiumRestore",
            disableTrendingFlags,
            5,
            false
        )
    val disableFeatuerdEmojis =
        addConfig(
            "DisableFeatuerdEmojis",
            disableTrendingFlags,
            6,
            false
        )
    val disableFeaturedStickers =
        addConfig(
            "DisableFeaturedStickers",
            disableTrendingFlags,
            7,
            false
        )
    val disableFeaturedGifs =
        addConfig(
            "DisableFeaturedGifs",
            disableTrendingFlags,
            8,
            false
        )
    val disablePremiumFavoriteEmojiTags =
        addConfig(
            "DisablePremiumFavoriteEmojiTags",
            disableTrendingFlags,
            9,
            false
        )
    val disableFavoriteSearchEmojiTags =
        addConfig(
            "DisableFavoriteSearchEmojiTags",
            disableTrendingFlags,
            10,
            false
        )
    val disableNonPremiumChannelChatShow =
        addConfig(
            "DisableNonPremiumChannelChatShow",
            disableTrendingFlags,
            11,
            false
        )
    val disableShortcutTagActions =
        addConfig(
            "DisableShortcutTagActions",
            disableTrendingFlags,
            12,
            false
        )
    val disablePhoneSharePrompt =
        addConfig(
            "DisablePhoneSharePrompt",
            disableTrendingFlags,
            13,
            false
        )
    val disablePremiumSendTodo =
        addConfig(
            "DisablePremiumSendTodo",
            disableTrendingFlags,
            14,
            false
        )
    val disableEmptyStarButton =
        addConfig(
            "DisableEmptyStarButton",
            disableTrendingFlags,
            15,
            false
        )
    val disableGifts =
        addConfig(
            "DisableGifts",
            disableTrendingFlags,
            16,
            false
        )
    val disableTrendingFeaturedStickersGroup =
        ConfigItemKeyLinkedGroup(
            "DisableTrendingFeaturedStickers",
            listOf(disableFeaturedStickers)
        )
    val disableTrendingFeaturedGifsGroup =
        ConfigItemKeyLinkedGroup(
            "DisableTrendingFeaturedGifs",
            listOf(disableFeaturedGifs)
        )
    val disableTrendingFeaturedEmojisGroup =
        ConfigItemKeyLinkedGroup(
            "DisableTrendingFeaturedEmojis",
            listOf(disableFeatuerdEmojis)
        )
    val disableTrendingPremiumHintsGroup =
        ConfigItemKeyLinkedGroup(
            "DisableTrendingPremiumHints",
            listOf(
                disablePremiumUpgrade,
                disablePremiumExpiring,
                disablePremiumRestore,
                disablePremiumChristmas,
                disableStarsSubscription
            )
        )
    val disableTrendingBirthdayGroup =
        ConfigItemKeyLinkedGroup(
            "DisableTrendingBirthday",
            listOf(disableBirthdayContact)
        )
    val disableTrendingEmojiTagsGroup =
        ConfigItemKeyLinkedGroup(
            "DisableTrendingEmojiTags",
            listOf(
                disableFavoriteSearchEmojiTags,
                disablePremiumFavoriteEmojiTags,
                disableShortcutTagActions
            )
        )
    val disableTrendingChannelPremiumBannerGroup =
        ConfigItemKeyLinkedGroup(
            "DisableTrendingChannelPremiumBanner",
            listOf(disableNonPremiumChannelChatShow)
        )
    val disableTrendingPhoneShareGroup =
        ConfigItemKeyLinkedGroup(
            "DisableTrendingPhoneShare",
            listOf(disablePhoneSharePrompt)
        )
    val disableTrendingGiftsPremiumGroup =
        ConfigItemKeyLinkedGroup(
            "DisableTrendingGiftsPremium",
            listOf(
                disableGifts,
                disableEmptyStarButton,
                disablePremiumSendTodo
            )
        )
    val useLocalQuoteColorData =
        addConfig(
            "useLocalQuoteColorData",
            ConfigItem.configTypeString,
            ""
        )
    val useLocalEmojiStatusData =
        addConfig(
            "useLocalEmojiStatusData",
            ConfigItem.configTypeString,
            ""
        )
    val disableMarkdown =
        addConfig(
            "DisableMarkdown",
            ConfigItem.configTypeBool,
            false
        )
    val showSmallGIF =
        addConfig(
            "ShowSmallGIF",
            ConfigItem.configTypeBool,
            false
        )
    val disableClickCommandToSend =
        addConfig(
            "DisableClickCommandToSend",
            ConfigItem.configTypeBool,
            false
        )
    val disableDialogsFloatingButton =
        addConfig(
            "DisableDialogsFloatingButton",
            ConfigItem.configTypeBool,
            false
        )
    val squareFloatingButton =
        addConfig(
            "SquareFloatingButton",
            ConfigItem.configTypeBool,
            false
        )
    val hideHomeSearchField =
        addConfig(
            "HideHomeSearchField",
            ConfigItem.configTypeBool,
            false
        )
    val centerActionBarTitle =
        addConfig(
            "CenterActionBarTitle",
            ConfigItem.configTypeBool,
            false
        )
    val showQuickReplyInBotCommands =
        addConfig(
            "ShowQuickReplyInBotCommands",
            ConfigItem.configTypeBool,
            false
        )
    val showRecentChatsSidebar =
        addConfig(
            "ShowRecentChatsSidebar",
            ConfigItem.configTypeBool,
            true
        )
    val pushServiceType =
        addConfig(
            "PushServiceType",
            ConfigItem.configTypeInt,
            1
        )
    val pushServiceTypeInAppDialog =
        addConfig(
            "PushServiceTypeInAppDialog",
            ConfigItem.configTypeBool,
            false
        )
    val pushServiceTypeUnifiedGateway =
        addConfig(
            "PushServiceTypeUnifiedGateway",
            ConfigItem.configTypeString,
            ""
        )
    val sendMp4DocumentAsVideo =
        addConfig(
            "SendMp4DocumentAsVideo",
            ConfigItem.configTypeBool,
            true
        )
    val disableChannelMuteButton =
        addConfig(
            "DisableChannelMuteButton",
            ConfigItem.configTypeBool,
            false
        )
    val disablePreviewVideoSoundShortcut =
        addConfig(
            "DisablePreviewVideoSoundShortcut",
            ConfigItem.configTypeBool,
            true
        )
    val regexFiltersEnabled =
        addConfig(
            "RegexFilters",
            ConfigItem.configTypeBool,
            false
        )
    val regexFiltersData =
        addConfig(
            "RegexFiltersData",
            ConfigItem.configTypeString,
            "[]"
        )
    val regexFiltersEnableInChats =
        addConfig(
            "RegexFiltersEnableInChats",
            ConfigItem.configTypeBool,
            false
        )
    val regexFiltersMaskMessages =
        addConfig(
            "RegexFiltersMaskMessages",
            ConfigItem.configTypeBool,
            false
        )
    val regexFiltersHideOnlyMatched =
        addConfig(
            "RegexFiltersHideOnlyMatched",
            ConfigItem.configTypeBool,
            false
        )
    val regexChatFiltersData =
        addConfig(
            "RegexChatFiltersData",
            ConfigItem.configTypeString,
            "[]"
        )
    val regexFiltersExcludedDialogs =
        addConfig(
            "RegexFiltersExcludedDialogs",
            ConfigItem.configTypeString,
            "[]"
        )
    val regexFiltersExcludedEntriesData =
        addConfig(
            "RegexFiltersExcludedEntriesData",
            ConfigItem.configTypeString,
            "[]"
        )
    val blockedChannelsData =
        addConfig(
            "BlockedChannelsData",
            ConfigItem.configTypeString,
            "[]"
        )
    val customFilteredUsersData =
        addConfig(
            "CustomFilteredUsersData",
            ConfigItem.configTypeString,
            "[]"
        )
    val regexFiltersLastImportLink =
        addConfig(
            "RegexFiltersLastImportLink",
            ConfigItem.configTypeString,
            ""
        )
    val showTimeHint =
        addConfig(
            "ShowTimeHint",
            ConfigItem.configTypeBool,
            false
        )
    val avatarCorners =
        addConfig(
            "avatarCorners",
            ConfigItem.configTypeFloat,
            28.0f
        )
    val singleCornerRadius =
        addConfig(
            "singleCornerRadius",
            ConfigItem.configTypeBool,
            false
        )
    val searchHashtagDefaultPageChannel =
        addConfig(
            "SearchHashtagDefaultPageChannel",
            ConfigItem.configTypeInt,
            0
        )
    val searchHashtagDefaultPageChat =
        addConfig(
            "SearchHashtagDefaultPageChat",
            ConfigItem.configTypeInt,
            0
        )
    val enablePanguOnSending =
        addConfig(
            "EnablePanguOnSending",
            ConfigItem.configTypeBool,
            false
        )
    val defaultHlsVideoQuality =
        addConfig(
            "DefaultHlsVideoQuality",
            ConfigItem.configTypeInt,
            0
        )
    val disableBotOpenButton =
        addConfig(
            "DisableBotOpenButton",
            ConfigItem.configTypeBool,
            false
        )
    val userAvatarsInMessagePreview =
        addConfig(
            "UserAvatarsInMessagePreview",
            ConfigItem.configTypeBool,
            false
        )
    val customTitleUserName =
        addConfig(
            "CustomTitleUserName",
            ConfigItem.configTypeBool,
            true
        )
    val enhancedVideoBitrate =
        addConfig(
            "EnhancedVideoBitrate",
            ConfigItem.configTypeBool,
            false
        )
    val useSystemAiService =
        addConfig(
            "UseSystemAiService",
            ConfigItem.configTypeBool,
            false
        )
    val hideAiEditor =
        addConfig(
            "HideAiEditor",
            ConfigItem.configTypeBool,
            false
        )
    val hideAiSummary =
        addConfig(
            "HideAiSummary",
            ConfigItem.configTypeBool,
            false
        )
    val ActionBarButtonReply =
        addConfig(
            "Reply",
            ConfigItem.configTypeBool,
            false
        )
    val ActionBarButtonEdit =
        addConfig(
            "Edit",
            ConfigItem.configTypeBool,
            true
        )
    val ActionBarButtonSelectBetween =
        addConfig(
            "SelectBetween",
            ConfigItem.configTypeBool,
            true
        )
    val ActionBarButtonCopy =
        addConfig(
            "Copy",
            ConfigItem.configTypeBool,
            true
        )
    val ActionBarButtonForward =
        addConfig(
            "Forward",
            ConfigItem.configTypeBool,
            true
        )
    val playerDecoder =
        addConfig(
            "VideoPlayerDecoder",
            ConfigItem.configTypeInt,
            1
        )

    // NagramX
    val enableSaveDeletedMessages =
        addConfig(
            "EnableSaveDeletedMessages",
            ConfigItem.configTypeBool,
            false
        )
    val enableSaveEditsHistory =
        addConfig(
            "EnableSaveEditsHistory",
            ConfigItem.configTypeBool,
            false
        )
    val saveLocalLastSeen =
        addConfig(
            "SaveLocalLastSeen",
            ConfigItem.configTypeBool,
            false
        )
    val saveReadDate =
        addConfig(
            "SaveReadDate",
            ConfigItem.configTypeBool,
            false
        )
    val messageSavingSaveMedia =
        addConfig(
            "MessageSavingSaveMedia",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPrivateChats =
        addConfig(
            "SaveMediaInPrivateChats",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPublicChannels =
        addConfig(
            "SaveMediaInPublicChannels",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPrivateChannels =
        addConfig(
            "SaveMediaInPrivateChannels",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPublicGroups =
        addConfig(
            "SaveMediaInPublicGroups",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPrivateGroups =
        addConfig(
            "SaveMediaInPrivateGroups",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaOnCellularDataLimit =
        addConfig(
            "SaveMediaOnCellularDataLimit",
            ConfigItem.configTypeLong,
            16L * 1024L * 1024L
        )
    val saveMediaOnWiFiLimit =
        addConfig(
            "SaveMediaOnWiFiLimit",
            ConfigItem.configTypeLong,
            64L * 1024L * 1024L
        )
    val attachmentFolderSizeLimitPreset =
        addConfig(
            "AttachmentFolderSizeLimitPreset",
            ConfigItem.configTypeInt,
            3
        )
    val attachmentFolderPath =
        addConfig(
            "AttachmentFolderPath",
            ConfigItem.configTypeString,
            ""
        )
    val saveDeletedMessageForBot =
        addConfig(
            "SaveDeletedMessageForBot", // save in bot chats
            ConfigItem.configTypeBool,
            false
        )
    val saveDeletedMessageForBotUser =
        addConfig(
            "SaveDeletedMessageForBotUser", // all messages from bot
            ConfigItem.configTypeBool,
            false
        )
    val customDeletedMark =
        addConfig(
            "CustomDeletedMark",
            ConfigItem.configTypeString,
            ""
        )
    val deletedIconStyle =
        addConfig(
            "DeletedIconStyle",
            ConfigItem.configTypeInt,
            -1
        )
    val deletedIconColor =
        addConfig(
            "DeletedIconColor",
            ConfigItem.configTypeInt,
            0
        )
    val hidePremiumSection =
        addConfig(
            "HidePremiumSection",
            ConfigItem.configTypeBool,
            false
        )
    val hideHelpSection =
        addConfig(
            "HideHelpSection",
            ConfigItem.configTypeBool,
            true
        )
    val llmApiUrl =
        addConfig(
            "LlmApiUrl",
            ConfigItem.configTypeString,
            ""
        )
    val llmApiKey =
        addConfig(
            "LlmApiKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmModelName =
        addConfig(
            "LlmModelName",
            ConfigItem.configTypeString,
            ""
        )
    val llmSystemPrompt =
        addConfig(
            "LlmSystemPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val llmUserPrompt =
        addConfig(
            "LlmUserPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderPreset =
        addConfig(
            "LlmProviderPreset",
            ConfigItem.configTypeInt,
            0
        )
    val llmProviderOpenAIKey =
        addConfig(
            "LlmProviderOpenAIKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOpenAIModel =
        addConfig(
            "LlmProviderOpenAIModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGeminiKey =
        addConfig(
            "LlmProviderGeminiKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGeminiModel =
        addConfig(
            "LlmProviderGeminiModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderXAIKey =
        addConfig(
            "LlmProviderXAIKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderXAIModel =
        addConfig(
            "LlmProviderXAIModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGroqKey =
        addConfig(
            "LlmProviderGroqKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGroqModel =
        addConfig(
            "LlmProviderGroqModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderDeepSeekKey =
        addConfig(
            "LlmProviderDeepSeekKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderDeepSeekModel =
        addConfig(
            "LlmProviderDeepSeekModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderCerebrasKey =
        addConfig(
            "LlmProviderCerebrasKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderCerebrasModel =
        addConfig(
            "LlmProviderCerebrasModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOllamaCloudKey =
        addConfig(
            "LlmProviderOllamaCloudKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOllamaCloudModel =
        addConfig(
            "LlmProviderOllamaCloudModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOpenRouterKey =
        addConfig(
            "LlmProviderOpenRouterKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOpenRouterModel =
        addConfig(
            "LlmProviderOpenRouterModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderVercelAIGatewayKey =
        addConfig(
            "LlmProviderVercelAIGatewayKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderVercelAIGatewayModel =
        addConfig(
            "LlmProviderVercelAIGatewayModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmTemperature =
        addConfig(
            "LlmTemperature",
            ConfigItem.configTypeFloat,
            0.7f
        )
    val llmUseContext =
        addConfig(
            "LlmUseContext",
            ConfigItem.configTypeBool,
            false
        )
    val llmContextSize =
        addConfig(
            "LlmContextSize",
            ConfigItem.configTypeInt,
            2
        )
    val llmUseContextInAutoTranslate =
        addConfig(
            "LlmUseContextInAutoTranslate",
            ConfigItem.configTypeBool,
            false
        )
    val translucentDeletedMessages =
        addConfig(
            "TranslucentDeletedMessages",
            ConfigItem.configTypeBool,
            true
        )
    val enableSeparateArticleTranslator =
        addConfig(
            "EnableSeparateArticleTranslator",
            ConfigItem.configTypeBool,
            false
        )
    val articleTranslationProvider =
        addConfig(
            "ArticleTranslationProvider",
            ConfigItem.configTypeInt,
            1
        )
    val disableCrashlyticsCollection =
        addConfig(
            "DisableCrashlyticsCollection",
            ConfigItem.configTypeBool,
            false
        )
    val showStickersRowToplevel =
        addConfig(
            "ShowStickersRowToplevel",
            ConfigItem.configTypeBool,
            true
        )
    val hideShareButtonInChannel =
        addConfig(
            "HideShareButtonInChannel",
            ConfigItem.configTypeBool,
            false
        )
    val preferredTranslateTargetLang =
        addConfig(
            "PreferredTranslateTargetLang",
            ConfigItem.configTypeString,
            ""
        )
    val telegramUIAutoTranslate =
        addConfig(
            "TelegramUIAutoTranslate",
            ConfigItem.configTypeBool,
            true
        )
    val translatorMode =
        addConfig(
            "TranslatorMode",
            ConfigItem.configTypeInt,
            0 // 0: off; 1: manual only; 2: all
        )
    val translatorModeWithOriginalMigrated =
        addConfig(
            "TranslatorModeWithOriginalMigrated",
            ConfigItem.configTypeBool,
            false
        )
    val centerActionBarTitleType =
        addConfig(
            "CenterActionBarTitleType",
            ConfigItem.configTypeInt,
            1 // 0: off; 1: always on; 2: settings only; 3: chats only
        )
    val sectionsSeparatedHeaders =
        addConfig(
            "sectionsSeparatedHeaders",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemMyProfile =
        addConfig(
            "DrawerItemMyProfile",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemSetEmojiStatus =
        addConfig(
            "DrawerItemSetEmojiStatus",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemNewGroup =
        addConfig(
            "DrawerItemNewGroup",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemNewChannel =
        addConfig(
            "DrawerItemNewChannel",
            ConfigItem.configTypeBool,
            false
        )
    val drawerItemContacts =
        addConfig(
            "DrawerItemContacts",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemCalls =
        addConfig(
            "DrawerItemCalls",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemRecentChats =
        addConfig(
            "DrawerItemRecentChats",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemSaved =
        addConfig(
            "DrawerItemSaved",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemSettings =
        addConfig(
            "DrawerItemSettings",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemNSettings =
        addConfig(
            "DrawerItemNSettings",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemQrLogin =
        addConfig(
            "DrawerItemQrLogin",
            ConfigItem.configTypeBool,
            false
        )
    val drawerItemArchivedChats =
        addConfig(
            "DrawerItemArchivedChats",
            ConfigItem.configTypeBool,
            false
        )
    val drawerItemRestartApp =
        addConfig(
            "DrawerItemRestartApp",
            ConfigItem.configTypeBool,
            false
        )
    val drawerItemBrowser =
        addConfig(
            "DrawerItemBrowser",
            ConfigItem.configTypeBool,
            false
        )
    val drawerItemSessions =
        addConfig(
            "DrawerItemSessions",
            ConfigItem.configTypeBool,
            false
        )
    val drawerItemGhost =
        addConfig(
            "DrawerItemGhost",
            ConfigItem.configTypeBool,
            true
        )
    val mainMenuLayout =
        addConfig(
            "MainMenuLayout",
            ConfigItem.configTypeString,
            ""
        )
    val mainMenuHiddenItems =
        addConfig(
            "MainMenuHiddenItems",
            ConfigItem.configTypeString,
            ""
        )
    val hideArchive =
        addConfig(
            "HideArchive",
            ConfigItem.configTypeBool,
            false
        )
    val confirmAllLinks =
        addConfig(
            "ConfirmAllLinks",
            ConfigItem.configTypeBool,
            false
        )
    val useDeletedIcon =
        addConfig(
            "UseDeletedIcon",
            ConfigItem.configTypeBool,
            true
        )
    val useEditedIcon =
        addConfig(
            "UseEditedIcon",
            ConfigItem.configTypeBool,
            true
        )
    val saveToChatSubfolder =
        addConfig(
            "SaveToChatSubfolder",
            ConfigItem.configTypeBool,
            false
        )
    val silentMessageByDefault =
        addConfig(
            "SilentMessageByDefault",
            ConfigItem.configTypeBool,
            false
        )
    val folderNameAsTitle =
        addConfig(
            "FolderNameAsTitle",
            ConfigItem.configTypeBool,
            false
        )
    val foldersAtBottom =
        addConfig(
            "FoldersAtBottom",
            ConfigItem.configTypeBool,
            false
        )

    val builtInFolders =
        addConfig(
            "BuiltInFolders",
            ConfigItem.configTypeString,
            ""
        )
    val translatorKeepMarkdown =
        addConfig(
            "TranslatorKeepMarkdown",
            ConfigItem.configTypeBool,
            true
        )
    val googleTranslateExp =
        addConfig(
            "GoogleTranslateExp",
            ConfigItem.configTypeBool,
            true
        )
    val springAnimationCrossfade =
        addConfig(
            "SpringAnimationCrossfade",
            ConfigItem.configTypeBool,
            true
        )
    val dontAutoPlayNextVoice =
        addConfig(
            "DontAutoPlayNextVoice",
            ConfigItem.configTypeBool,
            false
        )
    val messageColoredBackground =
        addConfig(
            "MessageColoredBackground",
            ConfigItem.configTypeBool,
            true
        )
    val removeMessageTail =
        addConfig(
            "RemoveMessageTail",
            ConfigItem.configTypeBool,
            false
        )
    val chatMenuItemBoostGroup =
        addConfig(
            "ChatMenuItemBoostGroup",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemLinkedChat =
        addConfig(
            "ChatMenuItemLinkedChat",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemToBeginning =
        addConfig(
            "ChatMenuItemToBeginning",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemGoToMessage =
        addConfig(
            "ChatMenuItemGoToMessage",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemHideTitle =
        addConfig(
            "ChatMenuItemHideTitle",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemViewDeleted =
        addConfig(
            "ChatMenuItemViewDeleted",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemClearDeleted =
        addConfig(
            "ChatMenuItemClearDeleted",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemDeleteOwnMessages =
        addConfig(
            "ChatMenuItemDeleteOwnMessages",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemForward =
        addConfig(
            "MediaViewerMenuItemForward",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemNoQuoteForward =
        addConfig(
            "MediaViewerMenuItemNoQuoteForward",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemCopyFrame =
        addConfig(
            "MediaViewerMenuItemCopyFrame",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemCopyPhoto =
        addConfig(
            "MediaViewerMenuItemCopyPhoto",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemSetProfilePhoto =
        addConfig(
            "MediaViewerMenuItemSetProfilePhoto",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemScanQRCode =
        addConfig(
            "MediaViewerMenuItemScanQRCode",
            ConfigItem.configTypeBool,
            true
        )
    val hideReactions =
        addConfig(
            "HideReactions",
            ConfigItem.configTypeBool,
            false
        )
    val performanceClass =
        addConfig(
            "PerformanceClass",
            ConfigItem.configTypeInt,
            0
        )
    val transcribeProvider =
        addConfig(
            "TranscribeProvider",
            ConfigItem.configTypeInt,
            0
        )
    val transcribeProviderCfAccountID =
        addConfig(
            "TranscribeProviderCfAccountID",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderCfApiToken =
        addConfig(
            "TranscribeProviderCfApiToken",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderGeminiApiKey =
        addConfig(
            "TranscribeProviderGeminiApiKey",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderOpenAiApiBase =
        addConfig(
            "TranscribeProviderOpenAiApiBase",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderOpenAiModel =
        addConfig(
            "TranscribeProviderOpenAiModel",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderOpenAiApiKey =
        addConfig(
            "TranscribeProviderOpenAiApiKey",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderOpenAiPrompt =
        addConfig(
            "TranscribeProviderOpenAiPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val showReplyInPrivate =
        addConfig(
            "ReplyInPrivate",
            ConfigItem.configTypeBool,
            false
        )
    val transcribeProviderGeminiPrompt =
        addConfig(
            "TranscribeProviderGeminiPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val hideDividers =
        addConfig(
            "HideDividers",
            ConfigItem.configTypeBool,
            false
        )
    val iconReplacements =
        addConfig(
            "IconReplacements",
            ConfigItem.configTypeInt,
            1
        )
    val showCopyAsSticker =
        addConfig(
            "CopyPhotoAsSticker",
            ConfigItem.configTypeBool,
            false
        )
    val showAddToStickers =
        addConfig(
            "AddToStickers",
            ConfigItem.configTypeBool,
            false
        )
    val showAddToFavorites =
        addConfig(
            "AddToFavorites",
            ConfigItem.configTypeBool,
            true
        )
    val showTranslateMessageLLM =
        addConfig(
            "TranslateMessageLLM",
            ConfigItem.configTypeBool,
            false
        )
    val leftBottomButton =
        addConfig(
            "LeftBottomButtonAction",
            ConfigItem.configTypeInt,
            0
        )
    val mainTabsOrder =
        addConfig(
            "MainTabsOrder",
            ConfigItem.configTypeString,
            "CHATS,CONTACTS,CALLS_SETTINGS,PROFILE"
        )
    val mainTabsShowTitles =
        addConfig(
            "MainTabsShowTitles",
            ConfigItem.configTypeBool,
            true
        )
    val mainTabsDisplayMode =
        addConfig(
            "MainTabsDisplayMode",
            ConfigItem.configTypeInt,
            0
        )
    val mainTabsShowSearchButton =
        addConfig(
            "MainTabsShowSearchButton",
            ConfigItem.configTypeBool,
            false
        )
    val mainTabsForceOpenChats =
        addConfig(
            "MainTabsForceOpenChats",
            ConfigItem.configTypeBool,
            false
        )
    val showTextMonoCode =
        addConfig(
            "TextMonoCode",
            ConfigItem.configTypeBool,
            true
        )
    val showCopyLink =
        addConfig(
            "CopyLink",
            ConfigItem.configTypeBool,
            true
        )
    val preferCommonGroupsTab =
        addConfig(
            "PreferCommonGroupsTab",
            ConfigItem.configTypeBool,
            true
        )
    val sendHighQualityPhoto =
        addConfig(
            "alwaysSendInHD",
            ConfigItem.configTypeBool,
            true
        )
    val groupedMessageMenu =
        addConfig(
            "GroupedMessageMenu",
            ConfigItem.configTypeBool,
            true
        )
    val autoUpdateChannel =
        addConfig(
            "AutoUpdateChannel",
            ConfigItem.configTypeInt,
            1 // 0: off; 1: release; 2: beta
        )
    val sendLockedCustomEmojiAsSticker =
        addConfig(
            "SendLockedCustomEmojiAsSticker",
            ConfigItem.configTypeBool,
            false
        )
    val premiumItemEmojiStatus =
        addConfig(
            "PremiumItemEmojiStatus",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemEmojiInReplies =
        addConfig(
            "PremiumItemEmojiInReplies",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemCustomColorInReplies =
        addConfig(
            "PremiumItemCustomColorInReplies",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemCustomWallpaper =
        addConfig(
            "PremiumItemCustomWallpaper",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemVideoAvatar =
        addConfig(
            "PremiumItemVideoAvatar",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemStarInReactions =
        addConfig(
            "PremiumItemStarInReactions",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemStickerEffects =
        addConfig(
            "PremiumItemStickerEffects",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemBoosts =
        addConfig(
            "PremiumItemBoosts",
            ConfigItem.configTypeBool,
            true
        )
    val switchStyle =
        addConfig(
            "SwitchStyle",
            ConfigItem.configTypeInt,
            1
        )
    val sliderStyle =
        addConfig(
            "SliderStyle",
            ConfigItem.configTypeInt,
            2
        )
    val ignoreUnreadCount =
        addConfig(
            "IgnoreUnreadCount",
            ConfigItem.configTypeBool,
            false
        )
    val markdownParser =
        addConfig(
            "MarkdownParser",
            ConfigItem.configTypeInt,
            NekoConfig.MARKDOWN_PARSER_NEKO
        )
    val keepTranslatorPreferences =
        addConfig(
            "KeepTranslatorPreferences",
            ConfigItem.configTypeBool,
            false
        )
    val usePinnedReactionsChats =
        addConfig(
            "UsePinnedReactionsChats",
            ConfigItem.configTypeBool,
            false
        )
    val pinnedReactionsChats =
        addConfig(
            "PinnedReactionsChats",
            ConfigItem.configTypeString,
            "[]"
        )
    val usePinnedReactionsChannels =
        addConfig(
            "UsePinnedReactionsChannels",
            ConfigItem.configTypeBool,
            false
        )
    val pinnedReactionsChannels =
        addConfig(
            "PinnedReactionsChannels",
            ConfigItem.configTypeString,
            "[]"
        )
    val hideStoriesFromHeader =
        addConfig(
            "HideStoriesFromHeader",
            ConfigItem.configTypeBool,
            true
        )
    val disableAvatarBlur =
        addConfig(
            "DisableAvatarBlur",
            ConfigItem.configTypeBool,
            false
        )
    val disableInAppBrowserGestures =
        addConfig(
            "DisableInAppBrowserGestures",
            ConfigItem.configTypeBool,
            false
        )
    val idDcType =
        addConfig(
            "IdDcType",
            ConfigItem.configTypeInt,
            1
        )
    val fixLinkPreview =
        addConfig(
            "FixLinkPreview",
            ConfigItem.configTypeBool,
            true
        )
    val showAddToBookmark =
        addConfig(
            "ShowAddToBookmark",
            ConfigItem.configTypeBool,
            false
        )
    val sortByUnread =
        addConfig(
            "SortByUnread",
            ConfigItem.configTypeBool,
            false
        )
    val cameraInVideoMessages =
        addConfig(
            "videoMessagesCamera",
            ConfigItem.configTypeInt,
            0
        )
    val extendedFramesPerSecond =
        addConfig(
            "extendedFramesPerSecond",
            ConfigItem.configTypeBool,
            false
        )
    val cameraStabilization =
        addConfig(
            "cameraStabilization",
            ConfigItem.configTypeBool,
            false
        )
    val rememberLastUsedCamera =
        addConfig(
            "rememberLastUsedCamera",
            ConfigItem.configTypeBool,
            false
        )
    val staticZoom =
        addConfig(
            "staticZoom",
            ConfigItem.configTypeBool,
            false
        )
    val hidePhotoCounter =
        addConfig(
            "hidePhotoCounter",
            ConfigItem.configTypeBool,
            true
        )
    val showCopyFrame =
        addConfig(
            "MessageMenuCopyFrame",
            ConfigItem.configTypeBool,
            false
        )
    val deleteChatForBothSides =
        addConfig(
            "DeleteChatForBothSides",
            ConfigItem.configTypeBool,
            true
        )
    val backAnimationStyle =
        addConfig(
            "BackAnimationStyle",
            ConfigItem.configTypeInt,
            0 // 0: Classic, 1: Spring, 2: Predictive Back
        )
    val mainTabsHideTitles =
        addConfig(
            "MainTabsHideTitles",
            ConfigItem.configTypeBool,
            false
        )
    val mainTabsHideContacts =
        addConfig(
            "MainTabsHideContacts",
            ConfigItem.configTypeBool,
            false
        )
    val showNotificationPreviewWhenLocked =
        addConfig(
            "ShowNotificationPreviewWhenLocked",
            ConfigItem.configTypeBool,
            false
        )
    val strokeOnViews =
        addConfig(
            "StrokeOnViews",
            ConfigItem.configTypeBool,
            true
        )
    val hideBottomNavigationBar =
        addConfig(
            "HideBottomNavigationBar",
            ConfigItem.configTypeBool,
            false
        )
    val hideDialogsSearchField =
        addConfig(
            "HideDialogsSearchField",
            ConfigItem.configTypeBool,
            false
        )
    val deepLTranslateKey =
        addConfig(
            "DeepLTranslateKey",
            ConfigItem.configTypeString,
            ""
        )

    val preferredTranslateTargetLangList = ArrayList<String>()
    fun updatePreferredTranslateTargetLangList() {
        AndroidUtilities.runOnUIThread({
            preferredTranslateTargetLangList.clear()
            val str = preferredTranslateTargetLang.String().trim()

            if (str.isEmpty()) return@runOnUIThread

            val languages = str.replace('-', '_').split(",")
            if (languages.isEmpty() || languages[0].trim().isEmpty()) return@runOnUIThread

            languages.forEach { lang ->
                preferredTranslateTargetLangList.add(lang.trim().lowercase())
            }
        }, 1000)
    }



    private fun fixConfig() {
        if (ApplicationLoader.applicationContext == null) {
            return
        }
        if (!getPreferences().contains(disableTrendingFlags.key) && getPreferences().contains("DisableTrending")) {
            val legacy = getPreferences().getBoolean("DisableTrending", true)
            val flags = if (legacy) 0x1C0 else 0
            disableTrendingFlags.setConfigInt(flags)
            getPreferences().edit { remove("DisableTrending") }
        }
        if (!translatorModeWithOriginalMigrated.Bool()) {
            if (getPreferences().contains(translatorMode.key)) {
                translatorMode.setConfigInt(
                    when (translatorMode.Int()) {
                        0 -> 1
                        1 -> 0
                        else -> 0
                    }
                )
            }
            translatorModeWithOriginalMigrated.setConfigBool(true)
        }
        if (translatorMode.Int() !in 0..2) {
            translatorMode.setConfigInt(0)
        }
        if (!getPreferences().contains(idDcType.key) && !getPreferences().getBoolean(
                "ShowIdAndDc", true
            )
        ) {
            idDcType.setConfigInt(0)
        }
        if (!getPreferences().contains(cameraInVideoMessages.key)) {
            val legacyNagramValue = getPreferences().takeIf { it.contains("CameraInVideoMessages") }
                ?.getInt("CameraInVideoMessages", 0)
            val legacyRear = getPreferences().getBoolean("RearVideoMessages", false)
            cameraInVideoMessages.setConfigInt(legacyNagramValue ?: if (legacyRear) 1 else 0)
        }
        migrateBoolConfig("SendHighQualityPhoto", sendHighQualityPhoto)
        migrateBoolConfig("CameraStabilization", cameraStabilization)
        migrateBoolConfig("RememberLastUsedCamera", rememberLastUsedCamera)
        migrateBoolConfig("StaticZoom", staticZoom)
        migrateBoolConfig("HidePhotoCounter", hidePhotoCounter)
        if (!getPreferences().contains(backAnimationStyle.key) &&
            getPreferences().contains("SpringAnimation")
        ) {
            val legacySpring = getPreferences().getBoolean("SpringAnimation", false)
            if (legacySpring) {
                backAnimationStyle.setConfigInt(1) // SPRING
            }
            getPreferences().edit { remove("SpringAnimation") }
        }
        if (!getPreferences().contains(mainTabsDisplayMode.key) &&
            (getPreferences().contains("MainTabsHideBottomBar") || getPreferences().contains("MainTabsHideOnScroll"))
        ) {
            val legacyHideBottomBar = getPreferences().getBoolean("MainTabsHideBottomBar", false)
            val legacyHideOnScroll = getPreferences().getBoolean("MainTabsHideOnScroll", false)
            mainTabsDisplayMode.setConfigInt(
                when {
                    legacyHideBottomBar -> 1
                    legacyHideOnScroll -> 2
                    else -> 0
                }
            )
        }
        if (getPreferences().contains("MainTabsHideBottomBar") || getPreferences().contains("MainTabsHideOnScroll")) {
            getPreferences().edit {
                remove("MainTabsHideBottomBar")
                remove("MainTabsHideOnScroll")
            }
        }
        if (!getPreferences().contains(strokeOnViews.key)) {
            strokeOnViews.changed(SharedConfig.getDevicePerformanceClass() != SharedConfig.PERFORMANCE_CLASS_LOW)
        }

        val currentLlmApiUrl = llmApiUrl.String()
        val normalizedLlmApiUrl = LlmUrlNormalizer.normalizeBaseUrl(currentLlmApiUrl)
        if (normalizedLlmApiUrl != currentLlmApiUrl) {
            llmApiUrl.setConfigString(normalizedLlmApiUrl)
        }

        if (!getPreferences().getBoolean("SwitchStyleModernRemoved", false)) {
            when (switchStyle.Int()) {
                1 -> switchStyle.setConfigInt(0)
                2 -> switchStyle.setConfigInt(1)
                3 -> switchStyle.setConfigInt(2)
            }
            getPreferences().edit { putBoolean("SwitchStyleModernRemoved", true) }
        }
    }

    private fun addConfig(
        k: String, t: Int, d: Any?
    ): ConfigItem {
        val a = ConfigItem(
            k, t, d
        )
        configs.add(
            a
        )
        return a
    }

    @Suppress("SameParameterValue")
    private fun addConfig(
        k: String, t: ConfigItem, d: Int, e: Any?
    ): ConfigItemKeyLinked {
        val a = ConfigItemKeyLinked(
            k,
            t,
            d,
            e,
        )
        configs.add(
            a
        )
        return a
    }

    fun loadConfig(
        force: Boolean
    ) {
        synchronized(
            sync
        ) {
            if (configLoaded && !force) {
                return
            }
            if (ApplicationLoader.applicationContext == null) {
                return
            }
            for (i in configs.indices) {
                val o = configs[i]
                if (o.type == ConfigItem.configTypeBool) {
                    o.value = getPreferences().getBoolean(
                        o.key, o.defaultValue as Boolean
                    )
                }
                if (o.type == ConfigItem.configTypeInt) {
                    o.value = getPreferences().getInt(
                        o.key, o.defaultValue as Int
                    )
                }
                if (o.type == ConfigItem.configTypeLong) {
                    o.value = getPreferences().getLong(
                        o.key, (o.defaultValue as Long)
                    )
                }
                if (o.type == ConfigItem.configTypeFloat) {
                    o.value = getPreferences().getFloat(
                        o.key, (o.defaultValue as Float)
                    )
                }
                if (o.type == ConfigItem.configTypeString) {
                    o.value = getPreferences().getString(
                        o.key, o.defaultValue as String
                    )
                }
                if (o.type == ConfigItem.configTypeSetInt) {
                    val ss = getPreferences().getStringSet(
                        o.key, HashSet()
                    )
                    val si = HashSet<Int>()
                    for (s in ss!!) {
                        si.add(
                            s.toInt()
                        )
                    }
                    o.value = si
                }
                if (o.type == ConfigItem.configTypeMapIntInt) {
                    val cv = getPreferences().getString(
                        o.key, ""
                    )
                    // Log.e("NC", String.format("Getting pref %s val %s", o.key, cv));
                    if (cv!!.isEmpty()) {
                        o.value = HashMap<Int, Int>()
                    } else {
                        try {
                            val data = Base64.decode(
                                cv, Base64.DEFAULT
                            )
                            val ois = ObjectInputStream(
                                ByteArrayInputStream(
                                    data
                                )
                            )
                            o.value = ois.readObject() as HashMap<*, *>
                            if (o.value == null) {
                                o.value = HashMap<Int, Int>()
                            }
                            ois.close()
                        } catch (_: Exception) {
                            o.value = HashMap<Int, Int>()
                        }
                    }
                }
                if (o.type == ConfigItem.configTypeBoolLinkInt) {
                    o as ConfigItemKeyLinked
                    o.changedFromKeyLinked(getPreferences().getInt(o.keyLinked.key, o.keyLinked.defaultValue as Int))
                }
            }
            configLoaded = true
        }
    }

    fun getAllKeys(): Set<String> {
        synchronized(sync) {
            return configs.map { it.key }.toSet()
        }
    }

    private fun migrateBoolConfig(
        legacyKey: String,
        config: ConfigItem
    ) {
        if (!getPreferences().contains(config.key) && getPreferences().contains(legacyKey)) {
            config.setConfigBool(getPreferences().getBoolean(legacyKey, config.defaultValue as Boolean))
        }
    }

    @JvmStatic
    fun getDoubleTapSeekDurationMs(): Int {
        val value = doubleTapSeekDuration.Int()
        return if (value in 0..2) {
            (value + 1) * 5000
        } else {
            30000
        }
    }

    init {
        init()
    }

}
