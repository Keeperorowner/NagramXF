package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Components.TextStyleSpan;
import org.telegram.ui.Components.TypefaceSpan;
import org.telegram.ui.Components.UndoView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import kotlin.Unit;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCheckBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellSelectBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck2;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheckIcon;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextDetailIcon;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextInput;
import tw.nekomimi.nekogram.helpers.TranscribeHelper;
import tw.nekomimi.nekogram.helpers.remote.EmojiHelper;
import tw.nekomimi.nekogram.ui.PopupBuilder;
import tw.nekomimi.nekogram.ui.cells.DoubleTapPreviewCell;
import tw.nekomimi.nekogram.ui.cells.EmojiSetCell;
import tw.nekomimi.nekogram.ui.cells.MessageSettingsPreviewCell;
import tw.nekomimi.nekogram.ui.cells.StickerShapePreviewCell;
import tw.nekomimi.nekogram.ui.cells.StickerSizePreviewMessagesCell;
import org.telegram.ui.Stories.recorder.DualCameraView;
import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.helper.DoubleTap;

@SuppressLint("RtlHardcoded")
@SuppressWarnings({"unused", "FieldCanBeLocal"})
public class NekoChatSettingsActivity extends BaseNekoXSettingsActivity implements NotificationCenter.NotificationCenterDelegate, EmojiHelper.EmojiPacksLoadedListener {

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @Override
    protected String getSettingsPrefix() {
        return "chat";
    }

    private final CellGroup cellGroup = new CellGroup(this);
    private final String[] doubleTapSeekDurations = new String[]{
            LocaleController.formatPluralString("Seconds", 5),
            LocaleController.formatPluralString("Seconds", 10),
            LocaleController.formatPluralString("Seconds", 15),
            LocaleController.formatPluralString("Seconds", 30)
    };

    private final AbstractConfigCell stickerSizeRow = cellGroup.appendCell(new ConfigCellCustom("StickerSize", ConfigCellCustom.CUSTOM_ITEM_StickerSize, false));
    private final AbstractConfigCell hideTimeForStickerRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.hideTimeForSticker));
    private final AbstractConfigCell dontSendGreetingStickerRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.dontSendGreetingSticker));
    private final AbstractConfigCell hideGroupStickerRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.hideGroupSticker));
    private final AbstractConfigCell maxRecentStickerCountRow = cellGroup.appendCell(new ConfigCellCustom("maxRecentStickerCount", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final AbstractConfigCell dividerRecentStickers = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell headerStickerShape = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.StickerShape)));
    private final AbstractConfigCell stickerShapeRow = cellGroup.appendCell(new ConfigCellCustom("StickerShape", ConfigCellCustom.CUSTOM_ITEM_StickerShapePreview, false));
    private final AbstractConfigCell dividerSticker = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell aiChatRow = cellGroup.appendCell(new ConfigCellTextDetailIcon("AIChat", getString(R.string.AIChat), getString(R.string.AIChatInfo), R.drawable.ai_chat, true, () ->
            presentFragment(new com.exteragram.messenger.ai.ui.activities.AiPreferencesActivity())));
    private final AbstractConfigCell dividerAiChat = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerTranscribe = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.PremiumPreviewVoiceToText)));
    private final AbstractConfigCell transcribeProviderRow = cellGroup.appendCell(new ConfigCellSelectBox("TranscribeProviderShort", NaConfig.INSTANCE.getTranscribeProvider(), new String[]{
            getString(R.string.TranscribeProviderAuto),
            getString(R.string.TelegramPremium),
            getString(R.string.TranscribeProviderWorkersAI),
            getString(R.string.TranscribeProviderGemini),
            getString(R.string.TranscribeProviderOpenAI),
    }, null));
    private final AbstractConfigCell transcribeProviderCfCredentialsRow = cellGroup.appendCell(new ConfigCellCustom("CloudflareCredentials", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final AbstractConfigCell transcribeProviderGeminiApiKeyRow = cellGroup.appendCell(new ConfigCellCustom("LlmProviderGeminiKey", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final AbstractConfigCell transcribeProviderOpenAiRow = cellGroup.appendCell(new ConfigCellCustom("TranscribeProviderOpenAI", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final AbstractConfigCell dividerTranscribe = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerMessages = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.MessagesChartTitle)));
    private final AbstractConfigCell messagePreviewRow = cellGroup.appendCell(new ConfigCellCustom("MessagePreview", ConfigCellCustom.CUSTOM_ITEM_MessagePreview, false));
    private final AbstractConfigCell showTimeHintRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTimeHint()));
    private final AbstractConfigCell messageColoredBackgroundRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getMessageColoredBackground(), null));
    private final AbstractConfigCell removeMessageTailRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getRemoveMessageTail()));
    private final AbstractConfigCell useEditedIconRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getUseEditedIcon()));
    private final AbstractConfigCell customEditedMessageRow = cellGroup.appendCell(new ConfigCellTextInput(null, NaConfig.INSTANCE.getCustomEditedMessage(), "", null));
    private final AbstractConfigCell showOnlineStatusRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getShowOnlineStatus(), getString(R.string.ShowOnlineStatusNotice)));
    private final AbstractConfigCell aiFeaturesRow = cellGroup.appendCell(new ConfigCellTextCheck2("HideAi", getString(R.string.AiHideHeader), new ArrayList<>() {{
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getHideAiEditor(), "HideAiEditor", getString(R.string.HideAiEditor), 0, true));
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getHideAiSummary(), "HideAiSummary", getString(R.string.HideAiSummary), 0, false));
    }}, null));
    private final AbstractConfigCell hideShareButtonInChannelRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideShareButtonInChannel()));
    private final AbstractConfigCell groupedMessageMenuRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getGroupedMessageMenu(), getString(R.string.GroupedMessageMenuNotice)));
    private final AbstractConfigCell showMessageIDRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getShowMessageID()));
    private final AbstractConfigCell dateOfForwardMsgRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDateOfForwardedMsg()));
    private final AbstractConfigCell dividerMessages = cellGroup.appendCell(new ConfigCellDivider());

    // Chats
    private final AbstractConfigCell headerChats = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.Chat)));
    private final AbstractConfigCell emojiSetsRow = cellGroup.appendCell(new ConfigCellCustom("EmojiSets", ConfigCellCustom.CUSTOM_ITEM_EmojiSet, true));
    private final AbstractConfigCell premiumElementsToggleRow = cellGroup.appendCell(new ConfigCellTextCheck2("PremiumElements", getString(R.string.PremiumElements), new ArrayList<>() {{
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getPremiumItemEmojiStatus()));
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getPremiumItemEmojiInReplies()));
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getPremiumItemCustomColorInReplies()));
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getPremiumItemCustomWallpaper()));
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getPremiumItemVideoAvatar()));
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getPremiumItemStarInReactions()));
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getPremiumItemStickerEffects()));
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getPremiumItemBoosts()));
    }}, null));
    ArrayList<ConfigCellCheckBox> premiumElementsRows = ((ConfigCellTextCheck2) premiumElementsToggleRow).getCheckBox();
    private final AbstractConfigCell unreadBadgeOnBackButton = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.unreadBadgeOnBackButton));
    private final AbstractConfigCell sendCommentAfterForwardRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.sendCommentAfterForward));
    private final AbstractConfigCell useChatAttachMediaMenuRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.useChatAttachMediaMenu, getString(R.string.UseChatAttachEnterMenuNotice)));
    private final AbstractConfigCell iOSMessageInputFieldRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.iOSMessageInputField, getString(R.string.iOSMessageInputFieldNotice)));
    private final AbstractConfigCell fixLinkPreviewRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getFixLinkPreview(), "x.com -> fixupx.com"));
    private final AbstractConfigCell disableLinkPreviewByDefaultRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.disableLinkPreviewByDefault));
    private final AbstractConfigCell deleteChatForBothSidesRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDeleteChatForBothSides()));
    private final AbstractConfigCell showSeconds = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.showSeconds));
    private final AbstractConfigCell showFullAboutRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getShowFullAbout()));
    private final AbstractConfigCell disableTrendingRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.disableTrending));
    private final AbstractConfigCell disableZalgoSymbolsRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getZalgoFilter(), getString(R.string.ZalgoFilterNotice)));
    private final AbstractConfigCell leftButtonActionRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getLeftBottomButton(), new String[]{
            getString(R.string.NoQuoteForward),
            getString(R.string.Reply),
            getString(R.string.AddToSavedMessages),
            getString(R.string.DirectShare),
    }, null));
    private final AbstractConfigCell markdownParserRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getMarkdownParser(), new String[]{
            getString(R.string.Official),
            "Nekogram",
    }, null));
    private final AbstractConfigCell dividerChats = cellGroup.appendCell(new ConfigCellDivider());

    // Double Tap
    private final AbstractConfigCell headerDoubleTap = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.DoubleTapAction)));
    private final AbstractConfigCell doubleTapPreviewRow = cellGroup.appendCell(new ConfigCellCustom("DoubleTapPreview", ConfigCellCustom.CUSTOM_ITEM_DoubleTapPreview, false));
    private final AbstractConfigCell doubleTapActionRow = cellGroup.appendCell(new ConfigCellCustom("DoubleTapIncoming", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final AbstractConfigCell doubleTapActionOutRow = cellGroup.appendCell(new ConfigCellCustom("DoubleTapOutgoing", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final AbstractConfigCell dividerDoubleTap = cellGroup.appendCell(new ConfigCellDivider());
    private DoubleTapPreviewCell doubleTapPreviewCell;

    // Camera
    private final AbstractConfigCell headerCamera = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.VoipCamera)));
    private final AbstractConfigCell cameraTypeRow = cellGroup.appendCell(new ConfigCellCustom("CameraType", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final ConfigItem cameraDual = new ConfigItem("rounddual_available", ConfigItem.configTypeBool, false) {
        @Override
        public void saveConfig() {
        }
    };
    private final AbstractConfigCell cameraExtensionsRow = cellGroup.appendCell(new ConfigCellTextCheck2("CameraSettings", getString(R.string.ExtendedSettings), new ArrayList<>() {{
        add(new ConfigCellCheckBox(cameraDual, null, getString(R.string.SeamlessSwitching), 0, true));
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getExtendedFramesPerSecond(), null, getString(R.string.ExtendedFramesPerSecond), 0, true));
        add(new ConfigCellCheckBox(NaConfig.INSTANCE.getCameraStabilization(), null, getString(R.string.CameraStabilization), 0, false));
    }}, null) {
        @Override
        protected boolean isCheckBoxVisible(ConfigCellCheckBox item) {
            return item.getBindConfig() != cameraDual || DualCameraView.dualAvailableStatic(getCameraCapabilityContext());
        }
    });
    private final ArrayList<ConfigCellCheckBox> cameraExtensionRows = ((ConfigCellTextCheck2) cameraExtensionsRow).getCheckBox();
    private final AbstractConfigCell cameraInVideoMessages = cellGroup.appendCell(new ConfigCellSelectBox("VideoMessagesCamera", NaConfig.INSTANCE.getCameraInVideoMessages(), new String[]{
            getString(R.string.VideoMessagesCameraFront),
            getString(R.string.VideoMessagesCameraRear),
            getString(R.string.VideoMessagesCameraAsk)
    }, null));
    private final AbstractConfigCell moveAttachCameraToBottomRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.moveAttachCameraToBottom, getString(R.string.MoveAttachCameraToBottomNotice)));
    private final AbstractConfigCell rememberLastUsedCameraRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getRememberLastUsedCamera(), getString(R.string.RememberLastUsedCameraInfo), getString(R.string.RememberLastUsedCamera)));
    private final AbstractConfigCell staticZoomRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getStaticZoom(), null, getString(R.string.StaticZoom)));
    private final AbstractConfigCell staticZoomInfoRow = cellGroup.appendCell(new ConfigCellCustom("StaticZoomInfo", CellGroup.ITEM_TYPE_TEXT, false));

    // Media
    private final AbstractConfigCell headerMedia = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AutoDownloadPhotos)));
    private final AbstractConfigCell sendHighQualityPhotoRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSendHighQualityPhoto(), null, getString(R.string.AlwaysSendInHD)));
    private final AbstractConfigCell hidePhotoCounterRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHidePhotoCounter(), null, getString(R.string.HidePhotoCounter)));
    private final AbstractConfigCell dividerPhoto = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell headerVideo = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AutoDownloadVideos)));
    private final AbstractConfigCell showSmallGifRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getShowSmallGIF()));
    private final AbstractConfigCell takeGIFasVideoRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.takeGIFasVideo));
    private final AbstractConfigCell autoPauseVideoRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.autoPauseVideo, getString(R.string.AutoPauseVideoAbout)));
    private final AbstractConfigCell doubleTapSeekDurationRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getDoubleTapSeekDuration(), doubleTapSeekDurations, null));
    private final AbstractConfigCell disablePreviewVideoSoundShortcutRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisablePreviewVideoSoundShortcut(), getString(R.string.DisablePreviewVideoSoundShortcutNotice)));
    private final AbstractConfigCell dontAutoPlayNextVoiceRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDontAutoPlayNextVoice()));
    private final AbstractConfigCell showSpoilersDirectlyRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.showSpoilersDirectly));
    private final AbstractConfigCell dividerMedia = cellGroup.appendCell(new ConfigCellDivider());

    // MenuAndButtons
    private final AbstractConfigCell headerMenuAndButtons = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.MenuAndButtons)));
    private final AbstractConfigCell chatMenuRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "ChatMenu", null, R.drawable.menu_chats, false, () ->
            showDialog(showConfigMenuWithIconAlert(this, R.string.ChatMenu, new ArrayList<>() {{
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getChatMenuItemBoostGroup(), getString(R.string.BoostingBoostGroupMenu), R.drawable.boost_channel));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getChatMenuItemLinkedChat(), getString(R.string.LinkedGroupChat), R.drawable.msg_discussion));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getChatMenuItemToBeginning(), getString(R.string.ToTheBeginning), R.drawable.ic_upward));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getChatMenuItemGoToMessage(), getString(R.string.ToTheMessage), R.drawable.msg_go_up));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getChatMenuItemHideTitle(), getString(R.string.HideTitle), R.drawable.hide_title));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getChatMenuItemViewDeleted(), getString(R.string.ViewDeleted), R.drawable.msg_view_file));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getChatMenuItemClearDeleted(), getString(R.string.ClearDeleted), R.drawable.msg_clear));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getChatMenuItemDeleteOwnMessages(), getString(R.string.DeleteAllFromSelf), R.drawable.msg_delete));
            }}))
    ));
    private final AbstractConfigCell messageMenuRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "MessageMenu", null, R.drawable.msg_list, false, () ->
            showDialog(showConfigMenuWithIconAlert(this, R.string.MessageMenu, new ArrayList<>() {{
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowReactions(), R.drawable.msg_reactions2));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowReplyInPrivate(), R.drawable.menu_reply));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowCopyLink(), R.drawable.msg_link));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowCopyFrame(), getString(R.string.CopyVideoFrame), R.drawable.msg_copy_photo));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowCopyPhoto(), R.drawable.msg_copy_photo));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowCopyAsSticker(), R.drawable.msg_copy_photo));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowAddToStickers(), R.drawable.msg_sticker));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowAddToFavorites(), R.drawable.msg_fave));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowNoQuoteForward(), R.drawable.msg_forward_noquote));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowSetReminder(), R.drawable.msg_calendar2));
                add(new ConfigCellTextCheckIcon(NekoConfig.showAddToSavedMessages, getString(R.string.AddToSavedMessages), R.drawable.msg_saved));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowAddToBookmark(), getString(R.string.AddBookmark), R.drawable.msg_fave));
                add(new ConfigCellTextCheckIcon(NekoConfig.showRepeat, getString(R.string.Repeat), R.drawable.msg_repeat));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowRepeatAsCopy(), R.drawable.msg_repeat));
                add(new ConfigCellTextCheckIcon(NekoConfig.showDeleteDownloadedFile, getString(R.string.DeleteDownloadedFile), R.drawable.msg_clear));
                add(new ConfigCellTextCheckIcon(NekoConfig.showViewHistory, getString(R.string.ViewHistory), R.drawable.menu_recent));
                add(new ConfigCellTextCheckIcon(NekoConfig.showTranslate, getString(R.string.Translate), R.drawable.msg_translate));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getShowTranslateMessageLLM(), R.drawable.magic_stick));
                add(new ConfigCellTextCheckIcon(NekoConfig.showShareMessages, getString(R.string.ShareMessages), R.drawable.msg_shareout));
                add(new ConfigCellTextCheckIcon(NekoConfig.showMessageHide, getString(R.string.Hide), R.drawable.msg_disable));
                add(new ConfigCellTextCheckIcon(NekoConfig.showReport, getString(R.string.ReportChat), R.drawable.msg_report));
                add(new ConfigCellTextCheckIcon(NekoConfig.showAdminActions, getString(R.string.EditAdminRights), R.drawable.profile_admin));
                add(new ConfigCellTextCheckIcon(NekoConfig.showChangePermissions, getString(R.string.ChangePermissions), R.drawable.msg_permissions));
                add(new ConfigCellTextCheckIcon(NekoConfig.showMessageDetails, getString(R.string.MessageDetails), R.drawable.msg_info));
            }}))
    ));
    private final AbstractConfigCell mediaViewerMenuRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "MediaViewerMenu", null, R.drawable.msg_photos, false, () ->
            showDialog(showConfigMenuWithIconAlert(this, R.string.MediaViewerMenu, new ArrayList<>() {{
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getMediaViewerMenuItemForward(), getString(R.string.Forward), R.drawable.msg_forward));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getMediaViewerMenuItemNoQuoteForward(), getString(R.string.NoQuoteForward), R.drawable.msg_forward_noquote));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getMediaViewerMenuItemCopyFrame(), getString(R.string.CopyVideoFrame), R.drawable.msg_copy_photo));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getMediaViewerMenuItemCopyPhoto(), getString(R.string.CopyPhoto), R.drawable.msg_copy_photo));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getMediaViewerMenuItemSetProfilePhoto(), getString(R.string.SetProfilePhoto), R.drawable.msg_openprofile));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getMediaViewerMenuItemScanQRCode(), getString(R.string.ScanQRCode), R.drawable.msg_qrcode));
            }}))
    ));
    private final AbstractConfigCell actionBarButtonRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "ActionBarButtons", null, R.drawable.msg_media, false, () ->
            showDialog(showConfigMenuWithIconAlert(this, R.string.ActionBarButtons, new ArrayList<>() {{
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getActionBarButtonReply(), R.drawable.menu_reply));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getActionBarButtonEdit(), R.drawable.msg_edit));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getActionBarButtonSelectBetween(), R.drawable.ic_select_between));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getActionBarButtonCopy(), R.drawable.msg_copy));
                add(new ConfigCellTextCheckIcon(NaConfig.INSTANCE.getActionBarButtonForward(), R.drawable.msg_forward_noquote));
            }}))
    ));
    private final AbstractConfigCell defaultDeleteMenuRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "DefaultDeleteMenu", null, R.drawable.msg_admins, false, () -> {
        if (getParentActivity() == null) return;
        showDialog(showConfigMenuAlert(getParentActivity(), NaConfig.INSTANCE.getDefaultDeleteMenu().getKey(), new ArrayList<>() {{
            add(new ConfigCellTextCheck(NaConfig.INSTANCE.getDefaultDeleteMenuBanUsers()));
            add(new ConfigCellTextCheck(NaConfig.INSTANCE.getDefaultDeleteMenReportSpam()));
            add(new ConfigCellTextCheck(NaConfig.INSTANCE.getDefaultDeleteMenuDeleteAll()));
            add(new ConfigCellTextCheck(NaConfig.INSTANCE.getDefaultDeleteMenuDoActionsInCommonGroups()));
        }}));
    }));

    @SuppressLint("NotifyDataSetChanged")
    private final AbstractConfigCell textStyleRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "TextStyle", null, R.drawable.msg_photo_text_framed3, false, () -> {
        if (getParentActivity() == null) return;
        Context ctx = getParentActivity();
        record Item(String key, CharSequence title, ConfigCellTextCheck bind) {
        }
        ArrayList<Item> items = new ArrayList<>();
        SpannableStringBuilder sb;
        items.add(new Item("translate", getString(R.string.TranslateMessage), new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextTranslate(), null, getString(R.string.TranslateMessage))));
        sb = new SpannableStringBuilder(getString(R.string.Bold));
        sb.setSpan(new TypefaceSpan(AndroidUtilities.bold()), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        items.add(new Item("bold", sb, new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextBold(), null, sb)));
        sb = new SpannableStringBuilder(getString(R.string.Italic));
        sb.setSpan(new TypefaceSpan(AndroidUtilities.getTypeface("fonts/ritalic.ttf")), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        items.add(new Item("italic", sb, new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextItalic(), null, sb)));
        sb = new SpannableStringBuilder(getString(R.string.Mono));
        sb.setSpan(new TypefaceSpan(Typeface.MONOSPACE), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        items.add(new Item("mono", sb, new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextMono(), null, sb)));
        sb = new SpannableStringBuilder(getString(R.string.MonoCode));
        sb.setSpan(new TypefaceSpan(Typeface.MONOSPACE), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        items.add(new Item("code", sb, new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextMonoCode(), null, sb)));
        sb = new SpannableStringBuilder(getString(R.string.Strike));
        TextStyleSpan.TextStyleRun run = new TextStyleSpan.TextStyleRun();
        run.flags |= TextStyleSpan.FLAG_STYLE_STRIKE;
        sb.setSpan(new TextStyleSpan(run), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        items.add(new Item("strike", sb, new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextStrikethrough(), null, sb)));
        sb = new SpannableStringBuilder(getString(R.string.Underline));
        run = new TextStyleSpan.TextStyleRun();
        run.flags |= TextStyleSpan.FLAG_STYLE_UNDERLINE;
        sb.setSpan(new TextStyleSpan(run), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        items.add(new Item("underline", sb, new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextUnderline(), null, sb)));
        items.add(new Item("quote", getString(R.string.Quote), new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextQuote(), null, getString(R.string.Quote))));
        items.add(new Item("spoiler", getString(R.string.Spoiler), new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextSpoiler(), null, getString(R.string.Spoiler))));
        items.add(new Item("link", getString(R.string.CreateLink), new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextCreateLink(), null, getString(R.string.CreateLink))));
        items.add(new Item("mention", getString(R.string.CreateMention), new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextCreateMention(), null, getString(R.string.CreateMention))));
        items.add(new Item("date", getString(R.string.FormattedDate), new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextCreateDate(), null, getString(R.string.FormattedDate))));
        items.add(new Item("regular", getString(R.string.Regular), new ConfigCellTextCheck(NaConfig.INSTANCE.getShowTextRegular(), null, getString(R.string.Regular))));

        // recover saved order
        String orderStr = NaConfig.INSTANCE.getTextStyleOrder().String();
        ArrayList<Item> ordered = new ArrayList<>();
        if (!TextUtils.isEmpty(orderStr)) {
            String[] keys = orderStr.split(",");
            for (String k : keys) {
                for (Item it : items) {
                    if (it.key.equals(k) && !ordered.contains(it)) ordered.add(it);
                }
            }
            for (Item it : items) {
                if (!ordered.contains(it)) ordered.add(it);
            }
        } else {
            ordered.addAll(items);
        }

        RecyclerView rv = new RecyclerView(ctx);
        rv.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.VERTICAL, false));
        class VH extends RecyclerView.ViewHolder {
            final TextCheckCell cell;

            VH(TextCheckCell c) {
                super(c);
                cell = c;
            }
        }

        class Adapter extends RecyclerView.Adapter<VH> {
            final ArrayList<Item> data;

            Adapter(ArrayList<Item> d) {
                data = d;
            }

            @NonNull
            @Override
            public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                TextCheckCell c = new TextCheckCell(ctx);
                c.setBackground(Theme.getSelectorDrawable(false));
                return new VH(c);
            }

            @Override
            public void onBindViewHolder(VH holder, int position) {
                Item it = data.get(position);
                holder.cell.setTextAndCheck(it.title, it.bind.getBindConfig().Bool(), false);
                holder.cell.setOnClickListener(v -> {
                    int pos = holder.getAdapterPosition();
                    if (pos < 0 || pos >= data.size()) {
                        return;
                    }
                    Item ii = data.get(pos);
                    ii.bind.getBindConfig().toggleConfigBool();
                    holder.cell.setChecked(ii.bind.getBindConfig().Bool());
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
                });
            }

            @Override
            public int getItemCount() {
                return data.size();
            }
        }
        Adapter adapter = new Adapter(ordered);
        rv.setAdapter(adapter);

        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                Item it = ordered.remove(from);
                ordered.add(to, it);
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        });
        helper.attachToRecyclerView(rv);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(getString(R.string.TextStyle));
        builder.setView(rv);
        builder.setPositiveButton(getString(R.string.OK), (d, which) -> {
            // save order
            StringBuilder sbOrder = new StringBuilder();
            for (int i = 0; i < ordered.size(); i++) {
                if (i > 0) sbOrder.append(",");
                sbOrder.append(ordered.get(i).key);
            }
            NaConfig.INSTANCE.getTextStyleOrder().setConfigString(sbOrder.toString());
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setNeutralButton(getString(R.string.Reset), (d, which) -> {
            String def = "translate,bold,italic,mono,code,strike,underline,quote,spoiler,link,mention,date,regular";
            NaConfig.INSTANCE.getTextStyleOrder().setConfigString(def);
            ordered.clear();
            String[] keys = def.split(",");
            for (String k : keys) {
                for (Item it : items) {
                    if (it.key.equals(k) && !ordered.contains(it)) ordered.add(it);
                }
            }
            for (Item it : items) {
                if (!ordered.contains(it)) ordered.add(it);
            }
            adapter.notifyDataSetChanged();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        });
        builder.create().show();
    }));
    private final AbstractConfigCell dividerMenuAndButtons = cellGroup.appendCell(new ConfigCellDivider());

    // Interactions
    private final AbstractConfigCell headerInteractions = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.InteractionSettings)));
    private final AbstractConfigCell hideKeyboardOnChatScrollRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.hideKeyboardOnChatScroll));
    private final AbstractConfigCell disableVibrationRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.disableVibration));
    private final AbstractConfigCell disableMarkdownRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableMarkdown()));
    private final AbstractConfigCell disableProximityEventsRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.disableProximityEvents));
    private final AbstractConfigCell rememberAllBackMessagesRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.rememberAllBackMessages));
    private final AbstractConfigCell showRecentChatsSidebarRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getShowRecentChatsSidebar()));
    private final AbstractConfigCell typeMessageHintUseGroupNameRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getTypeMessageHintUseGroupName()));
    private final AbstractConfigCell showSendAsUnderMessageHintRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getShowSendAsUnderMessageHint()));
    private final AbstractConfigCell showQuickReplyInBotCommandsRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getShowQuickReplyInBotCommands()));
    private final AbstractConfigCell hideBotButtonInInputFieldRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideBotButtonInInputField()));
    private final AbstractConfigCell hideReactionsRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideReactions()));
    private final AbstractConfigCell dividerInteractions = cellGroup.appendCell(new ConfigCellDivider());

    // Channels
    private final AbstractConfigCell headerChannels = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.ChannelsTab)));
    private final AbstractConfigCell hideSendAsChannelRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.hideSendAsChannel));
    private final AbstractConfigCell disableChannelMuteButtonRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableChannelMuteButton()));
    private final AbstractConfigCell disableSwipeToNextRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.disableSwipeToNext));
    private final AbstractConfigCell dividerChannels = cellGroup.appendCell(new ConfigCellDivider());

    // Confirmations
    private final AbstractConfigCell headerConfirmation = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.ConfirmSettings)));
    private final AbstractConfigCell skipOpenLinkConfirmRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.skipOpenLinkConfirm));
    private final AbstractConfigCell askBeforeCallRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.askBeforeCall));
    private final AbstractConfigCell repeatConfirmRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.repeatConfirm));
    private final AbstractConfigCell disableClickCommandToSendRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableClickCommandToSend()));
    private final AbstractConfigCell confirmAVRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.confirmAVMessage));
    private final AbstractConfigCell confirmAllLinksRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getConfirmAllLinks(), getString(R.string.ConfirmAllLinksDescription)));
    private final AbstractConfigCell dividerConfirmation = cellGroup.appendCell(new ConfigCellDivider());

    // Search tag
    private final AbstractConfigCell headerSearchTag = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.SavedTagSearchHint)));
    private final String[] searchPagesString = new String[]{
            getString(R.string.SearchThisChat),
            getString(R.string.SearchMyMessages),
            getString(R.string.SearchPublicPosts),
    };
    private final AbstractConfigCell searchHashtagDefaultPageChannelRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getSearchHashtagDefaultPageChannel(), searchPagesString, null));
    private final AbstractConfigCell searchHashtagDefaultPageChatRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getSearchHashtagDefaultPageChat(), searchPagesString, null));
    private final AbstractConfigCell dividerSearchTag  = cellGroup.appendCell(new ConfigCellDivider());

    private ListAdapter listAdapter;
    private ActionBarMenuItem menuItem;
    private StickerSizeCell stickerSizeCell;
    private StickerShapePreviewCell stickerShapePreviewCell;
    private MessageSettingsPreviewCell messageSettingsPreviewCell;

    public NekoChatSettingsActivity() {
        if (NaConfig.INSTANCE.getUseEditedIcon().Bool()) {
            cellGroup.rows.remove(customEditedMessageRow);
        }
        if (NaConfig.INSTANCE.getTranscribeProvider().Int() != TranscribeHelper.TRANSCRIBE_OPENAI) {
            cellGroup.rows.remove(transcribeProviderOpenAiRow);
        }
        if (!BuildVars.LOGS_ENABLED) {
            cellGroup.rows.remove(markdownParserRow);
        }
        checkSkipOpenLinkConfirmRows();
        checkConfirmAVRows();
        syncAyuCameraRows();
        addRowsToMap(cellGroup);
    }

    private boolean isUsingCamera2Setting() {
        return SharedConfig.isUsingCamera2(currentAccount);
    }

    private Context getCameraCapabilityContext() {
        Context context = getContext();
        return context != null ? context : ApplicationLoader.applicationContext;
    }

    private void syncAyuCameraRows() {
        boolean useCamera2 = isUsingCamera2Setting();
        Context context = getCameraCapabilityContext();
        if (useCamera2 && !DualCameraView.dualAvailableStatic(context)) {
            MessagesController.getGlobalMainSettings().edit().putBoolean("rounddual_available", false).apply();
        }
        cameraDual.changed(DualCameraView.roundDualAvailableStatic(context));
        if (useCamera2) {
            addAfterIfMissing(cameraExtensionsRow, cameraTypeRow);
        } else {
            removeCameraExtensionRows();
            removeIfPresent(cameraExtensionsRow);
        }
        if (NaConfig.INSTANCE.getCameraInVideoMessages().Int() != 2) {
            addAfterIfMissing(rememberLastUsedCameraRow, cameraInVideoMessages);
        } else {
            removeIfPresent(rememberLastUsedCameraRow);
        }
        rebuildVisibleCameraExtensionRows();
    }

    private void rebuildVisibleCameraExtensionRows() {
        if (!(cameraExtensionsRow instanceof ConfigCellTextCheck2 row) || row.isCollapsed() || !cellGroup.rows.contains(cameraExtensionsRow)) {
            removeCameraExtensionRows();
            return;
        }
        int index = cellGroup.rows.indexOf(cameraExtensionsRow);
        ArrayList<ConfigCellCheckBox> visibleRows = row.getVisibleCheckBox();
        for (ConfigCellCheckBox checkBox : cameraExtensionRows) {
            cellGroup.rows.remove(checkBox);
        }
        insertCameraExtensionRows(index, visibleRows);
    }

    private int insertCameraExtensionRows(int cameraSettingsIndex, ArrayList<ConfigCellCheckBox> visibleRows) {
        for (int i = 0; i < visibleRows.size(); i++) {
            ConfigCellCheckBox checkBox = visibleRows.get(i);
            checkBox.bindCellGroup(cellGroup);
            cellGroup.rows.add(cameraSettingsIndex + 1 + i, checkBox);
        }
        return visibleRows.size();
    }

    private void removeCameraExtensionRows() {
        for (ConfigCellCheckBox checkBox : cameraExtensionRows) {
            cellGroup.rows.remove(checkBox);
        }
    }

    private int getVisibleCameraExtensionRowsInListCount() {
        int count = 0;
        for (ConfigCellCheckBox checkBox : cameraExtensionRows) {
            if (cellGroup.rows.contains(checkBox)) {
                count++;
            }
        }
        return count;
    }

    private void syncAyuCameraRowsAnimated() {
        boolean useCamera2 = isUsingCamera2Setting();
        Context context = getCameraCapabilityContext();
        if (useCamera2 && !DualCameraView.dualAvailableStatic(context)) {
            MessagesController.getGlobalMainSettings().edit().putBoolean("rounddual_available", false).apply();
        }
        cameraDual.changed(DualCameraView.roundDualAvailableStatic(context));

        int cameraTypeIndex = cellGroup.rows.indexOf(cameraTypeRow);
        boolean hasCameraSettings = cellGroup.rows.contains(cameraExtensionsRow);
        if (useCamera2 && !hasCameraSettings) {
            int insertIndex = cameraTypeIndex + 1;
            cellGroup.rows.add(insertIndex, cameraExtensionsRow);
            int inserted = 1;
            if (cameraExtensionsRow instanceof ConfigCellTextCheck2 row && !row.isCollapsed()) {
                inserted += insertCameraExtensionRows(insertIndex, row.getVisibleCheckBox());
            }
            addRowsToMap(cellGroup);
            listAdapter.notifyItemChanged(cameraTypeIndex);
            listAdapter.notifyItemRangeInserted(insertIndex, inserted);
        } else if (!useCamera2 && hasCameraSettings) {
            int removeIndex = cellGroup.rows.indexOf(cameraExtensionsRow);
            int removed = 1 + getVisibleCameraExtensionRowsInListCount();
            removeCameraExtensionRows();
            removeIfPresent(cameraExtensionsRow);
            addRowsToMap(cellGroup);
            listAdapter.notifyItemChanged(cameraTypeIndex);
            listAdapter.notifyItemRangeRemoved(removeIndex, removed);
        } else {
            syncAyuCameraRows();
            addRowsToMap(cellGroup);
            listAdapter.notifyItemChanged(cameraTypeIndex);
        }
    }

    private void addAfterIfMissing(AbstractConfigCell row, AbstractConfigCell anchor) {
        if (cellGroup.rows.contains(row)) {
            return;
        }
        int index = cellGroup.rows.indexOf(anchor);
        if (index >= 0) {
            cellGroup.rows.add(index + 1, row);
        }
    }

    private void removeIfPresent(AbstractConfigCell row) {
        cellGroup.rows.remove(row);
    }

    @Override
    public boolean onFragmentCreate() {
        EmojiHelper.getInstance().loadEmojisInfo(this);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
        return super.onFragmentCreate();
    }

    @SuppressLint("NewApi")
    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        ActionBarMenu menu = actionBar.createMenu();
        menuItem = menu.addItem(0, R.drawable.ic_ab_other);
        menuItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
        menuItem.addSubItem(1, R.drawable.msg_reset, getString(R.string.ResetStickerSize));
        menuItem.setVisibility(NekoConfig.stickerSize.Float() != 14.0f ? View.VISIBLE : View.GONE);

        listAdapter = new ListAdapter(context);

        listView.setAdapter(listAdapter);

        setupDefaultListeners();

        // Cells: Set OnSettingChanged Callbacks
        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (key.equals(NekoConfig.disableProximityEvents.getKey())) {
                MediaController.getInstance().recreateProximityWakeLock();
            } else if (key.equals(NekoConfig.showSeconds.getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getConfirmAllLinks().getKey())) {
                checkSkipOpenLinkConfirmRows();
            } else if (key.equals(NekoConfig.useChatAttachMediaMenu.getKey())) {
                checkConfirmAVRows();
            } else if (key.equals(NaConfig.INSTANCE.getUseEditedIcon().getKey())) {
                if ((boolean) newValue) {
                    if (cellGroup.rows.contains(customEditedMessageRow)) {
                        final int index = cellGroup.rows.indexOf(customEditedMessageRow);
                        cellGroup.rows.remove(customEditedMessageRow);
                        listAdapter.notifyItemRemoved(index);
                    }
                } else {
                    if (!cellGroup.rows.contains(customEditedMessageRow)) {
                        final int index = cellGroup.rows.indexOf(useEditedIconRow) + 1;
                        cellGroup.rows.add(index, customEditedMessageRow);
                        listAdapter.notifyItemInserted(index);
                    }
                }
                if (messageSettingsPreviewCell != null) {
                    messageSettingsPreviewCell.refresh();
                }
            } else if (key.equals(NekoConfig.hideTimeForSticker.getKey())) {
                if (stickerSizeCell != null) {
                    stickerSizeCell.invalidate();
                }
            } else if (key.equals(NaConfig.INSTANCE.getMessageColoredBackground().getKey())) {
                if (stickerSizeCell != null) {
                    stickerSizeCell.invalidate();
                }
                if (messageSettingsPreviewCell != null) {
                    messageSettingsPreviewCell.refresh();
                }
            } else if (key.equals(NaConfig.INSTANCE.getRemoveMessageTail().getKey())) {
                if (getParentActivity() != null) {
                    Theme.chat_msgInDrawable = null;
                    Theme.createChatResources(getParentActivity(), false);
                }
                if (stickerSizeCell != null) {
                    stickerSizeCell.invalidate();
                }
                if (messageSettingsPreviewCell != null) {
                    messageSettingsPreviewCell.refresh();
                }
            } else if (key.equals(NaConfig.INSTANCE.getShowOnlineStatus().getKey())
                    || key.equals(NaConfig.INSTANCE.getHideShareButtonInChannel().getKey())
                    || key.equals(NaConfig.INSTANCE.getCustomEditedMessage().getKey())
                    || key.equals(NaConfig.INSTANCE.getShowMessageID().getKey())) {
                if (messageSettingsPreviewCell != null) {
                    messageSettingsPreviewCell.refresh();
                }
            } else if (key.equals("PremiumElements" + "_check")) {
                if (stickerSizeCell != null) {
                    stickerSizeCell.invalidate();
                }
            } else if (key.equals(NaConfig.INSTANCE.getPremiumItemEmojiInReplies().getKey())) {
                if (stickerSizeCell != null) {
                    stickerSizeCell.invalidate();
                }
            } else if (key.equals(NaConfig.INSTANCE.getPremiumItemCustomColorInReplies().getKey())) {
                if (stickerSizeCell != null) {
                    stickerSizeCell.invalidate();
                }
            } else if (key.equals(NaConfig.INSTANCE.getTranscribeProvider().getKey())) {
                if ((int) newValue == TranscribeHelper.TRANSCRIBE_OPENAI) {
                    if (!cellGroup.rows.contains(transcribeProviderOpenAiRow)) {
                        final int index = cellGroup.rows.indexOf(transcribeProviderGeminiApiKeyRow) + 1;
                        cellGroup.rows.add(index, transcribeProviderOpenAiRow);
                        listAdapter.notifyItemInserted(index);
                    }
                } else {
                    if (cellGroup.rows.contains(transcribeProviderOpenAiRow)) {
                        final int index = cellGroup.rows.indexOf(transcribeProviderOpenAiRow);
                        cellGroup.rows.remove(transcribeProviderOpenAiRow);
                        listAdapter.notifyItemRemoved(index);
                    }
                }
            } else if (key.equals(NaConfig.INSTANCE.getCameraInVideoMessages().getKey())) {
                syncAyuCameraRows();
                listAdapter.notifyDataSetChanged();
                addRowsToMap(cellGroup);
            } else if (key.equals("PremiumElements")) {
                addRowsToMap(cellGroup);
            } else if (key.equals("CameraSettings")) {
                addRowsToMap(cellGroup);
            } else if (key.equals("CameraSettings_check")) {
                syncCameraExtensionPreferences((boolean) newValue);
                syncAyuCameraRows();
                int cameraToggleRowIndex = cellGroup.rows.indexOf(cameraExtensionsRow);
                if (cameraToggleRowIndex >= 0) {
                    listAdapter.notifyItemChanged(cameraToggleRowIndex);
                    int visibleRows = getVisibleCameraExtensionRowsInListCount();
                    if (visibleRows > 0) {
                        listAdapter.notifyItemRangeChanged(cameraToggleRowIndex + 1, visibleRows);
                    }
                }
                addRowsToMap(cellGroup);
            } else if (key.equals(cameraDual.getKey())) {
                MessagesController.getGlobalMainSettings().edit().putBoolean("rounddual_available", (boolean) newValue).apply();
                cameraDual.changed((boolean) newValue);
            }
        };

        return superView;
    }

    @Override
    protected void onActionBarItemClick(int id) {
        if (id == 1) {
            NekoConfig.stickerSize.setConfigFloat(14.0f);
            menuItem.setVisibility(View.GONE);
            stickerSizeCell.invalidate();
        }
    }

    private void onStickerShapeChanged() {
        if (stickerSizeCell != null) {
            stickerSizeCell.invalidate();
        }
        if (stickerShapePreviewCell != null) {
            stickerShapePreviewCell.invalidate();
        }
    }

    @Override
    protected void onCustomCellClick(View view, int position, float x, float y) {
        if (position == cellGroup.rows.indexOf(maxRecentStickerCountRow)) {
            final int[] counts = {20, 30, 40, 50, 80, 100, 120, 150, 180, 200};
            List<String> types = Arrays.stream(counts)
                    .filter(i -> i <= getMessagesController().maxRecentStickersCount)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.toList());
            int currentCount = NekoConfig.maxRecentStickerCount.Int();
            int selectedIndex = -1;
            for (int i = 0; i < types.size(); i++) {
                if (Integer.parseInt(types.get(i)) == currentCount) {
                    selectedIndex = i;
                    break;
                }
            }
            showSingleChoiceDialog(getParentActivity(), R.string.maxRecentStickerCount, types.toArray(new String[0]), selectedIndex, getResourceProvider(), i -> {
                NekoConfig.maxRecentStickerCount.setConfigInt(Integer.parseInt(types.get(i)));
                listAdapter.notifyItemChanged(position);
            });
        } else if (position == cellGroup.rows.indexOf(doubleTapActionRow) || position == cellGroup.rows.indexOf(doubleTapActionOutRow)) {
            ArrayList<String> arrayList = new ArrayList<>();
            ArrayList<Integer> types = new ArrayList<>();
            arrayList.add(getString(R.string.Disable));
            types.add(DoubleTap.DOUBLE_TAP_ACTION_NONE);
            arrayList.add(getString(R.string.SendReactions));
            types.add(DoubleTap.DOUBLE_TAP_ACTION_SEND_REACTIONS);
            arrayList.add(getString(R.string.TranslateMessage));
            types.add(DoubleTap.DOUBLE_TAP_ACTION_TRANSLATE);
            arrayList.add(getString(R.string.TranslateMessageLLM));
            types.add(DoubleTap.DOUBLE_TAP_ACTION_TRANSLATE_LLM);
            arrayList.add(getString(R.string.Reply));
            types.add(DoubleTap.DOUBLE_TAP_ACTION_REPLY);
            arrayList.add(getString(R.string.AddToSavedMessages));
            types.add(DoubleTap.DOUBLE_TAP_ACTION_SAVE);
            arrayList.add(getString(R.string.Repeat));
            types.add(DoubleTap.DOUBLE_TAP_ACTION_REPEAT);
            arrayList.add(getString(R.string.RepeatAsCopy));
            types.add(DoubleTap.DOUBLE_TAP_ACTION_REPEAT_AS_COPY);
            if (position == cellGroup.rows.indexOf(doubleTapActionOutRow)) {
                arrayList.add(getString(R.string.Edit));
                types.add(DoubleTap.DOUBLE_TAP_ACTION_EDIT);
            }
            arrayList.add(getString(R.string.Delete));
            types.add(DoubleTap.DOUBLE_TAP_ACTION_DELETE);
            arrayList.add(getString(R.string.GhostReadMessage));
            types.add(DoubleTap.DOUBLE_TAP_ACTION_READ);
            boolean isIncoming = position == cellGroup.rows.indexOf(doubleTapActionRow);

            int[] icons = new int[types.size()];
            for (int i = 0; i < types.size(); i++) {
                Integer iconRes = DoubleTap.doubleTapActionIconMap.get(types.get(i));
                icons[i] = iconRes != null ? iconRes : 0;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            builder.setTitle(getString(isIncoming ? R.string.DoubleTapIncoming : R.string.DoubleTapOutgoing));
            builder.setItems(arrayList.toArray(new CharSequence[0]), icons, (dialog, which) -> {
                if (isIncoming) {
                    NaConfig.INSTANCE.getDoubleTapAction().setConfigInt(types.get(which));
                } else {
                    NaConfig.INSTANCE.getDoubleTapActionOut().setConfigInt(types.get(which));
                }
                listAdapter.notifyItemChanged(position);
                if (doubleTapPreviewCell != null) {
                    doubleTapPreviewCell.updateIcons(isIncoming ? 1 : 2, true);
                }
            });
            builder.setNegativeButton(getString(R.string.Cancel), null);
            showDialog(builder.create());
        } else if (position == cellGroup.rows.indexOf(cameraTypeRow)) {
            showSingleChoiceDialog(getParentActivity(), R.string.CameraType, new String[]{"Camera 1", "Camera 2"}, SharedConfig.isUsingCamera2(currentAccount) ? 1 : 0, getResourceProvider(), i -> {
                boolean useCamera2 = i == 1;
                if (SharedConfig.isUsingCamera2(currentAccount) != useCamera2) {
                    SharedConfig.toggleUseCamera2(currentAccount);
                }
                syncAyuCameraRowsAnimated();
            });
        } else if (position == cellGroup.rows.indexOf(emojiSetsRow)) {
            presentFragment(new NekoEmojiSettingsActivity());
        } else if (position == cellGroup.rows.indexOf(transcribeProviderCfCredentialsRow)) {
            TranscribeHelper.showCfCredentialsDialog(this);
        } else if (position == cellGroup.rows.indexOf(transcribeProviderGeminiApiKeyRow)) {
            TranscribeHelper.showGeminiApiKeyDialog(this);
        } else if (position == cellGroup.rows.indexOf(transcribeProviderOpenAiRow)) {
            TranscribeHelper.showOpenAiCredentialsDialog(this);
        }
    }

    @Override
    protected void onCheckBoxCellClick(View view, int position) {
        AbstractConfigCell a = cellGroup.rows.get(position);
        ((ConfigCellCheckBox) a).onClick((CheckBoxCell) view);
        int toggleRowIndex = cellGroup.rows.indexOf(premiumElementsToggleRow);
        if (position > toggleRowIndex && position <= toggleRowIndex + premiumElementsRows.size()) {
            listAdapter.notifyItemRangeChanged(toggleRowIndex, premiumElementsRows.size());
        }
        int cameraToggleRowIndex = cellGroup.rows.indexOf(cameraExtensionsRow);
        if (cameraToggleRowIndex >= 0 && position > cameraToggleRowIndex && position <= cameraToggleRowIndex + cameraExtensionRows.size()) {
            listAdapter.notifyItemChanged(cameraToggleRowIndex);
        }
        int aiToggleRowIndex = cellGroup.rows.indexOf(aiFeaturesRow);
        if (aiToggleRowIndex >= 0 && position > aiToggleRowIndex && position <= aiToggleRowIndex + ((ConfigCellTextCheck2) aiFeaturesRow).getVisibleCheckBox().size()) {
            listAdapter.notifyItemChanged(aiToggleRowIndex);
        }
    }

    private void syncCameraExtensionPreferences(boolean enabled) {
        if (DualCameraView.dualAvailableStatic(getCameraCapabilityContext())) {
            MessagesController.getGlobalMainSettings().edit().putBoolean("rounddual_available", enabled).apply();
            cameraDual.changed(enabled);
        } else {
            MessagesController.getGlobalMainSettings().edit().putBoolean("rounddual_available", false).apply();
            cameraDual.changed(false);
        }
        NaConfig.INSTANCE.getExtendedFramesPerSecond().setConfigBool(enabled);
        NaConfig.INSTANCE.getCameraStabilization().setConfigBool(enabled);
    }

    @Override
    public int getDrawable() {
        return R.drawable.menu_chats;
    }

    @Override
    public String getTitle() {
        return getString(R.string.Chat);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell2.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell2.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell2.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switch2Track));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell2.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switch2TrackChecked));

        return themeDescriptions;
    }

    public static boolean[] getDeleteMenuChecks() {
        return new boolean[]{
                NaConfig.INSTANCE.getDefaultDeleteMenuBanUsers().Bool(),
                NaConfig.INSTANCE.getDefaultDeleteMenReportSpam().Bool(),
                NaConfig.INSTANCE.getDefaultDeleteMenuDeleteAll().Bool(),
                NaConfig.INSTANCE.getDefaultDeleteMenuDoActionsInCommonGroups().Bool(),
        };
    }

    @Override
    public void emojiPacksLoaded(String error) {
        if (listAdapter != null) {
            listAdapter.notifyItemChanged(cellGroup.rows.indexOf(emojiSetsRow));
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.emojiLoaded && listAdapter != null) {
            listAdapter.notifyItemChanged(cellGroup.rows.indexOf(emojiSetsRow));
        }
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
        super.onFragmentDestroy();
    }

    private class StickerSizeCell extends FrameLayout {

        private final StickerSizePreviewMessagesCell messagesCell;
        private final SeekBarView sizeBar;
        private final int startStickerSize = 2;
        private final int endStickerSize = 20;

        private final AnimatedTextView headerValueChip;
        private final TextView leftTextView;
        private final TextView rightTextView;

        public StickerSizeCell(Context context) {
            super(context);

            LinearLayout titleRow = new LinearLayout(context);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);

            TextView titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            titleView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            titleView.setText(LocaleController.getString(R.string.StickerSize));
            titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            titleRow.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            headerValueChip = new AnimatedTextView(context, false, true, true) {
                final Drawable backgroundDrawable = Theme.createRoundRectDrawable(
                        AndroidUtilities.dp(4f),
                        Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader), 0.15f));

                @Override
                protected void onDraw(Canvas canvas) {
                    backgroundDrawable.setBounds(0, 0,
                            (int) (getPaddingLeft() + getDrawable().getCurrentWidth() + getPaddingRight()),
                            getMeasuredHeight());
                    backgroundDrawable.draw(canvas);
                    super.onDraw(canvas);
                }
            };
            headerValueChip.setAnimationProperties(0.45f, 0L, 240L, CubicBezierInterpolator.EASE_OUT_QUINT);
            headerValueChip.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
            headerValueChip.setPadding(AndroidUtilities.dp(5.33f), AndroidUtilities.dp(2f), AndroidUtilities.dp(5.33f), AndroidUtilities.dp(2f));
            headerValueChip.setTextSize(AndroidUtilities.dp(12f));
            headerValueChip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            titleRow.addView(headerValueChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 17, Gravity.CENTER_VERTICAL, 6, 1, 0, 0));

            addView(titleRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 21, 17, 21, 0));

            FrameLayout labelsRow = new FrameLayout(context);
            leftTextView = new TextView(context);
            leftTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            leftTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            leftTextView.setGravity(Gravity.LEFT);
            leftTextView.setText(LocaleController.getString(R.string.StickerSizeLeft));
            labelsRow.addView(leftTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            rightTextView = new TextView(context);
            rightTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            rightTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            rightTextView.setGravity(Gravity.RIGHT);
            rightTextView.setText(LocaleController.getString(R.string.StickerSizeRight));
            labelsRow.addView(rightTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

            addView(labelsRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 21, 52, 21, 0));

            sizeBar = new SeekBarView(context);
            sizeBar.setReportChanges(true);
            sizeBar.setSeparatorsCount(endStickerSize - startStickerSize + 1);
            sizeBar.setDelegate((stop, progress) -> {
                NekoConfig.stickerSize.setConfigFloat(startStickerSize + (endStickerSize - startStickerSize) * progress);
                updateChip();
                updateLabelColors();
                StickerSizeCell.this.invalidate();
                menuItem.setVisibility(View.VISIBLE);
            });
            addView(sizeBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.LEFT | Gravity.TOP, 6, 71, 6, 0));

            messagesCell = new StickerSizePreviewMessagesCell(context, NekoChatSettingsActivity.this);
            addView(messagesCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 0, 119, 0, 0));

            updateChip();
            updateLabelColors();
        }

        private void updateChip() {
            if (headerValueChip == null) return;
            headerValueChip.cancelAnimation();
            headerValueChip.setText(String.valueOf(Math.round(NekoConfig.stickerSize.Float())), true);
        }

        private void updateLabelColors() {
            if (leftTextView == null || rightTextView == null) return;
            float current = NekoConfig.stickerSize.Float();
            int min = startStickerSize;
            int max = endStickerSize;
            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
            float mid = (((max - min) / 2f) + min) * 1.5f;
            float minHalf = min * 0.5f;
            float midPair = (min + max) * 0.5f;
            if (current >= mid - minHalf) {
                float denom = max - (mid - minHalf);
                float t = denom == 0 ? 0 : (current - (mid - minHalf)) / denom;
                rightTextView.setTextColor(ColorUtils.blendARGB(gray, blue, Math.max(0f, Math.min(1f, t))));
                leftTextView.setTextColor(gray);
            } else if (current <= midPair) {
                float denom = min - midPair;
                float t = denom == 0 ? 0 : (current - midPair) / denom;
                leftTextView.setTextColor(ColorUtils.blendARGB(gray, blue, Math.max(0f, Math.min(1f, t))));
                rightTextView.setTextColor(gray);
            } else {
                leftTextView.setTextColor(gray);
                rightTextView.setTextColor(gray);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            sizeBar.setProgress((NekoConfig.stickerSize.Float() - startStickerSize) / (float) (endStickerSize - startStickerSize));
        }

        @Override
        public void invalidate() {
            super.invalidate();
            if (messagesCell != null) messagesCell.invalidate();
            if (sizeBar != null) sizeBar.invalidate();
            updateChip();
            updateLabelColors();
        }
    }

    //impl ListAdapter
    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof TextSettingsCell textCell) {
                if (position == cellGroup.rows.indexOf(maxRecentStickerCountRow)) {
                    textCell.setTextAndValue(getString(R.string.maxRecentStickerCount), String.valueOf(NekoConfig.maxRecentStickerCount.Int()), false, cellGroup.needSetDivider(maxRecentStickerCountRow));
                } else if (position == cellGroup.rows.indexOf(cameraTypeRow)) {
                    textCell.setTextAndValue(
                            getString(R.string.CameraType),
                            SharedConfig.isUsingCamera2(currentAccount) ? "Camera 2" : "Camera 1",
                            true,
                            cellGroup.needSetDivider(cameraTypeRow));
                } else if (position == cellGroup.rows.indexOf(doubleTapActionRow)) {
                    textCell.setTextAndValue(getString(R.string.DoubleTapIncoming), DoubleTap.doubleTapActionMap.get(NaConfig.INSTANCE.getDoubleTapAction().Int()), true);
                } else if (position == cellGroup.rows.indexOf(doubleTapActionOutRow)) {
                    textCell.setTextAndValue(getString(R.string.DoubleTapOutgoing), DoubleTap.doubleTapActionMap.get(NaConfig.INSTANCE.getDoubleTapActionOut().Int()), true);
                } else if (position == cellGroup.rows.indexOf(transcribeProviderCfCredentialsRow)) {
                    textCell.setTextAndValue(getString(R.string.CloudflareCredentials), "", true);
                } else if (position == cellGroup.rows.indexOf(transcribeProviderGeminiApiKeyRow)) {
                    textCell.setTextAndValue(getString(R.string.LlmProviderGeminiKey), "", true);
                } else if (position == cellGroup.rows.indexOf(transcribeProviderOpenAiRow)) {
                    textCell.setTextAndValue(getString(R.string.TranscribeProviderOpenAI), "", true);
                }
            } else if (holder.itemView instanceof TextInfoPrivacyCell textInfoPrivacyCell) {
                if (position == cellGroup.rows.indexOf(staticZoomInfoRow)) {
                    textInfoPrivacyCell.setText(getString(R.string.StaticZoomInfo));
                }
            } else if (holder.itemView instanceof EmojiSetCell v1) {
                v1.setData(EmojiHelper.getInstance().getCurrentEmojiPackInfo(), false, cellGroup.needSetDivider(emojiSetsRow));
            }
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = null;
            switch (viewType) {
                case ConfigCellCustom.CUSTOM_ITEM_StickerSize:
                    view = stickerSizeCell = new StickerSizeCell(mContext);
                    break;
                case ConfigCellCustom.CUSTOM_ITEM_StickerShapePreview:
                    view = stickerShapePreviewCell = new StickerShapePreviewCell(mContext, NekoChatSettingsActivity.this::onStickerShapeChanged);
                    break;
                case ConfigCellCustom.CUSTOM_ITEM_MessagePreview:
                    view = messageSettingsPreviewCell = new MessageSettingsPreviewCell(mContext, getParentLayout());
                    break;
                case ConfigCellCustom.CUSTOM_ITEM_EmojiSet:
                    view = new EmojiSetCell(mContext, false);
                    break;
                case ConfigCellCustom.CUSTOM_ITEM_DoubleTapPreview:
                    view = doubleTapPreviewCell = new DoubleTapPreviewCell(mContext);
                    break;
            }
            return view;
        }
    }

    private void checkSkipOpenLinkConfirmRows() {
        boolean confirmAllLinks = NaConfig.INSTANCE.getConfirmAllLinks().Bool();
        if (listAdapter == null) {
            if (confirmAllLinks) {
                cellGroup.rows.remove(skipOpenLinkConfirmRow);
            }
            return;
        }
        if (!confirmAllLinks) {
            final int index = cellGroup.rows.indexOf(headerConfirmation);
            if (!cellGroup.rows.contains(skipOpenLinkConfirmRow)) {
                cellGroup.rows.add(index + 1, skipOpenLinkConfirmRow);
                listAdapter.notifyItemInserted(index + 1);
            }
        } else {
            int rowIndex = cellGroup.rows.indexOf(skipOpenLinkConfirmRow);
            if (rowIndex != -1) {
                cellGroup.rows.remove(skipOpenLinkConfirmRow);
                listAdapter.notifyItemRemoved(rowIndex);
            }
        }
        addRowsToMap(cellGroup);
    }

    private void checkConfirmAVRows() {
        boolean useChatAttachMediaMenu = NekoConfig.useChatAttachMediaMenu.Bool();
        if (listAdapter == null) {
            if (useChatAttachMediaMenu) {
                cellGroup.rows.remove(confirmAVRow);
            }
            return;
        }
        if (!useChatAttachMediaMenu) {
            final int index = cellGroup.rows.indexOf(disableClickCommandToSendRow);
            if (!cellGroup.rows.contains(confirmAVRow)) {
                cellGroup.rows.add(index + 1, confirmAVRow);
                listAdapter.notifyItemInserted(index + 1);
            }
        } else {
            int rowIndex = cellGroup.rows.indexOf(confirmAVRow);
            if (rowIndex != -1) {
                cellGroup.rows.remove(confirmAVRow);
                listAdapter.notifyItemRemoved(rowIndex);
            }
        }
        addRowsToMap(cellGroup);
    }
}
