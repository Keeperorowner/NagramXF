package tw.nekomimi.nekogram.menu.ghostmode;

import static org.telegram.messenger.LocaleController.getString;

import com.radolyn.ayugram.utils.AyuGhostPreferences;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.PopupSwipeBackLayout;

public class GhostModeExclusionPopupWrapper {

    private final long chatId;
    private final ActionBarMenuSubItem defaultItem;
    private final ActionBarMenuSubItem readExclusionItem;
    private final ActionBarMenuSubItem typingExclusionItem;
    public ActionBarPopupWindow.ActionBarPopupWindowLayout windowLayout;

    public GhostModeExclusionPopupWrapper(BaseFragment fragment, PopupSwipeBackLayout swipeBackLayout, long chatId, Theme.ResourcesProvider resourcesProvider) {
        var context = fragment.getParentActivity();
        windowLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, resourcesProvider);
        windowLayout.setFitItems(true);
        this.chatId = chatId;

        if (swipeBackLayout != null) {
            var backItem = ActionBarMenuItem.addItem(windowLayout, R.drawable.msg_arrow_back, getString(R.string.Back), false, resourcesProvider);
            backItem.setOnClickListener(view -> swipeBackLayout.closeForeground());
            ActionBarMenuItem.addColoredGap(windowLayout, resourcesProvider);
        }

        defaultItem = ActionBarMenuItem.addItem(windowLayout, 0, getString(R.string.Default), true, resourcesProvider);
        defaultItem.setOnClickListener(view -> {
            AyuGhostPreferences.setReadException(chatId, AyuGhostPreferences.TYPE_DEFAULT);
            AyuGhostPreferences.setTypingException(chatId, AyuGhostPreferences.TYPE_DEFAULT);
            updateItems();
        });

        readExclusionItem = ActionBarMenuItem.addItem(windowLayout, 0, getReadLabel(AyuGhostPreferences.getReadException(chatId)), true, resourcesProvider);
        readExclusionItem.setOnClickListener(view -> {
            int next = nextType(AyuGhostPreferences.getReadException(chatId));
            AyuGhostPreferences.setReadException(chatId, next);
            updateItems();
        });

        typingExclusionItem = ActionBarMenuItem.addItem(windowLayout, 0, getTypingLabel(AyuGhostPreferences.getTypingException(chatId)), true, resourcesProvider);
        typingExclusionItem.setOnClickListener(view -> {
            int next = nextType(AyuGhostPreferences.getTypingException(chatId));
            AyuGhostPreferences.setTypingException(chatId, next);
            updateItems();
        });

        updateItems();
    }

    public void updateItems() {
        int readType = AyuGhostPreferences.getReadException(chatId);
        int typingType = AyuGhostPreferences.getTypingException(chatId);

        defaultItem.setChecked(readType == AyuGhostPreferences.TYPE_DEFAULT && typingType == AyuGhostPreferences.TYPE_DEFAULT);

        readExclusionItem.setText(getReadLabel(readType));
        readExclusionItem.setChecked(readType != AyuGhostPreferences.TYPE_DEFAULT);

        typingExclusionItem.setText(getTypingLabel(typingType));
        typingExclusionItem.setChecked(typingType != AyuGhostPreferences.TYPE_DEFAULT);
    }

    private static int nextType(int type) {
        if (type == AyuGhostPreferences.TYPE_DEFAULT) {
            return AyuGhostPreferences.TYPE_FORCE_BLOCK;
        }
        if (type == AyuGhostPreferences.TYPE_FORCE_BLOCK) {
            return AyuGhostPreferences.TYPE_FORCE_ALLOW;
        }
        return AyuGhostPreferences.TYPE_DEFAULT;
    }

    private static String getReadLabel(int type) {
        if (type == AyuGhostPreferences.TYPE_FORCE_BLOCK) {
            return getString(R.string.GhostModeExceptionForceBlockRead);
        }
        if (type == AyuGhostPreferences.TYPE_FORCE_ALLOW) {
            return getString(R.string.GhostModeExceptionForceAllowRead);
        }
        return getString(R.string.GhostModeExcludeRead) + ": " + getString(R.string.GhostModeExceptionDefault);
    }

    private static String getTypingLabel(int type) {
        if (type == AyuGhostPreferences.TYPE_FORCE_BLOCK) {
            return getString(R.string.GhostModeExceptionForceBlockTyping);
        }
        if (type == AyuGhostPreferences.TYPE_FORCE_ALLOW) {
            return getString(R.string.GhostModeExceptionForceAllowTyping);
        }
        return getString(R.string.GhostModeExcludeTyping) + ": " + getString(R.string.GhostModeExceptionDefault);
    }
}
