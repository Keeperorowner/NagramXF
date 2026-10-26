package com.exteragram.messenger.ai.ui.activities;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.data.Message;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Read-only view of the conversation history kept for generation: one block per turn with the
 * request on top and the answer below. The whole history can be cleared from the action bar.
 */
public class AiHistoryActivity extends BaseFragment {

    private static final int MENU_CLEAR = 1;

    private LinearLayout content;
    private TextView emptyView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.AIChatMessageHistory));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CLEAR) {
                    AiConfig.clearConversationHistory();
                    rebuild(context);
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_CLEAR, R.drawable.msg_delete).setContentDescription(LocaleController.getString(R.string.AIChatHistoryCleared));

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        ((FrameLayout) fragmentView).addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        scrollView.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setText(LocaleController.getString(R.string.AIChatHistoryEmpty));
        ((FrameLayout) fragmentView).addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        rebuild(context);
        return fragmentView;
    }

    private void rebuild(Context context) {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        ArrayList<Message> history = AiConfig.getConversationHistory();
        boolean empty = history == null || history.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            return;
        }
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            boolean outgoing = message != null && "user".equals(message.role());
            content.addView(createBlock(context, outgoing, message == null ? "" : message.content()));
        }
    }

    private View createBlock(Context context, boolean outgoing, String text) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12),
                Theme.getColor(outgoing ? Theme.key_chat_outBubble : Theme.key_windowBackgroundWhite, resourceProvider)));
        int padding = AndroidUtilities.dp(12);
        block.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
        title.setText(outgoing ? LocaleController.getString(R.string.AIChatPromptHint) : LocaleController.getString(R.string.AIChat));
        block.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView body = new TextView(context);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        body.setTextIsSelectable(true);
        body.setText(TextUtils.isEmpty(text) ? " " : text);
        block.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(4), AndroidUtilities.dp(12), AndroidUtilities.dp(4));
        wrapper.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return wrapper;
    }
}
