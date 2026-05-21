package com.exteragram.messenger.ai.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.network.Client;
import com.exteragram.messenger.ai.network.GenerationCallback;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.XiaomiUtilities;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkPath;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.LoadingDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class ResponseAlert extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private static final Pattern URL_PATTERN = Pattern.compile("\\[([^]]+)]\\((https?://[^)]+)\\)");

    private final PaddedAdapter adapter;
    private final Client client;
    private String currentRequestId;
    private CharSequence currentResponse;
    private BaseFragment fragment;
    private final HeaderView headerView;
    private String imagePath;
    private final RecyclerListView listView;
    private final LoadingTextView loadingTextView;
    private final ButtonWithCounterView mainButton;
    private Utilities.Callback2<String, CharSequence> onInsertPress;
    private Utilities.CallbackReturn<URLSpan, Boolean> onLinkPress;
    private String prompt;
    private final AnimatedFloat sheetTopAnimated;
    private boolean sheetTopNotAnimate;
    private Spanned spannedPrompt;
    private final LinkSpanDrawable.LinksTextView textView;
    private final FrameLayout textViewContainer;
    private boolean useHistory;

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }

    private ResponseAlert(Context context, final Client client, String str, String imagePath, boolean useHistory, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        this.client = client;
        this.imagePath = imagePath;
        this.useHistory = useHistory;
        this.backgroundPaddingLeft = 0;
        fixNavigationBar();

        ContainerView containerView = new ContainerView(context);
        this.containerView = containerView;
        this.sheetTopAnimated = new AnimatedFloat(containerView, 320L, CubicBezierInterpolator.EASE_OUT_QUINT);

        loadingTextView = new LoadingTextView(context);
        loadingTextView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(12), AndroidUtilities.dp(22), AndroidUtilities.dp(6));
        loadingTextView.setTextSize(1, SharedConfig.fontSize);
        loadingTextView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        loadingTextView.setLinkTextColor(Theme.multAlpha(getThemedColor(Theme.key_dialogTextBlack), 0.2f));
        setPrompt(str.trim());

        textViewContainer = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), heightMeasureSpec);
            }
        };

        textView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
        textView.setDisablePaddingsOffsetY(true);
        textView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(12), AndroidUtilities.dp(22), AndroidUtilities.dp(6));
        textView.setTextSize(1, SharedConfig.fontSize);
        textView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        textView.setLinkTextColor(getThemedColor(Theme.key_chat_messageLinkIn));
        textView.setTextIsSelectable(true);
        textView.setHighlightColor(getThemedColor(Theme.key_chat_inTextSelectionHighlight));
        int cursorColor = getThemedColor(Theme.key_chat_TextSelectionCursor);
        try {
            if (Build.VERSION.SDK_INT >= 29 && !XiaomiUtilities.isMIUI()) {
                Drawable left = textView.getTextSelectHandleLeft();
                if (left != null) {
                    left.setColorFilter(cursorColor, PorterDuff.Mode.SRC_IN);
                    textView.setTextSelectHandleLeft(left);
                }
                Drawable right = textView.getTextSelectHandleRight();
                if (right != null) {
                    right.setColorFilter(cursorColor, PorterDuff.Mode.SRC_IN);
                    textView.setTextSelectHandleRight(right);
                }
            }
        } catch (Exception ignored) {}
        textViewContainer.addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context, resourcesProvider) {
            @Override
            protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
                return true;
            }

            @Override
            public void requestChildFocus(View child, View focused) {
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN && ev.getY() < ResponseAlert.this.getSheetTop() - getTop()) {
                    dismiss();
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        listView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        listView.setPadding(0, AndroidUtilities.statusBarHeight + AndroidUtilities.dp(56), 0, AndroidUtilities.dp(80));
        listView.setClipToPadding(true);
        listView.setLayoutManager(new LinearLayoutManager(context));

        adapter = new PaddedAdapter(context, loadingTextView);
        listView.setAdapter(adapter);

        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                ResponseAlert.this.containerView.invalidate();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    sheetTopNotAnimate = false;
                }
                if ((newState == RecyclerView.SCROLL_STATE_IDLE || newState == RecyclerView.SCROLL_STATE_SETTLING)
                        && getSheetTop(false) > 0f
                        && getSheetTop(false) < AndroidUtilities.dp(96)
                        && listView.canScrollVertically(1)
                        && hasEnoughHeight()) {
                    sheetTopNotAnimate = true;
                    listView.smoothScrollBy(0, (int) getSheetTop(false));
                }
            }
        });

        DefaultItemAnimator itemAnimator = new DefaultItemAnimator() {
            @Override
            protected void onChangeAnimationUpdate(@NonNull RecyclerView.ViewHolder holder) {
                ResponseAlert.this.containerView.invalidate();
            }

            @Override
            protected void onMoveAnimationUpdate(@NonNull RecyclerView.ViewHolder holder) {
                ResponseAlert.this.containerView.invalidate();
            }
        };
        itemAnimator.setDurations(180L);
        itemAnimator.setInterpolator(new LinearInterpolator());
        listView.setItemAnimator(itemAnimator);
        this.containerView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT));

        headerView = new HeaderView(context);
        this.containerView.addView(headerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 78, Gravity.TOP | Gravity.LEFT));

        mainButton = new ButtonWithCounterView(context, resourcesProvider);
        mainButton.setRound();
        mainButton.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
        mainButton.setText(LocaleController.getString(R.string.Close), false);
        mainButton.setLoading(true);
        mainButton.setOnClickListener(v -> {
            client.stopRequest(currentRequestId);
            dismiss();
        });
        this.containerView.addView(mainButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48f, Gravity.BOTTOM | Gravity.LEFT, 16f, 16f, 16f, 16f));
    }

    public static ResponseAlert showAlert(BaseFragment fragment, Client client, String prompt, boolean useHistory, boolean noForwards,
                                          Utilities.CallbackReturn<URLSpan, Boolean> onLinkPress, Runnable onDismiss,
                                          Utilities.Callback2<String, CharSequence> onInsertPress) {
        return showAlert(fragment, client, prompt, null, useHistory, noForwards, onLinkPress, onDismiss, onInsertPress);
    }

    public static ResponseAlert showAlert(BaseFragment fragment, Client client, String prompt, String imagePath, boolean useHistory, boolean noForwards,
                                          Utilities.CallbackReturn<URLSpan, Boolean> onLinkPress, final Runnable onDismiss,
                                          Utilities.Callback2<String, CharSequence> onInsertPress) {
        ResponseAlert alert = new ResponseAlert(fragment.getContext(), client, prompt, imagePath, useHistory, fragment.getResourceProvider()) {
            @Override
            public void dismiss() {
                super.dismiss();
                if (onDismiss != null) {
                    onDismiss.run();
                }
            }
        };
        alert.setNoforwards(noForwards);
        alert.setFragment(fragment);
        alert.setOnLinkPress(onLinkPress);
        alert.setOnInsertPress(onInsertPress);
        if (fragment.getParentActivity() != null) {
            fragment.showDialog(alert);
        }
        alert.generate();
        return alert;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
        loadingTextView.setText(Emoji.replaceEmoji(prompt, loadingTextView.getPaint().getFontMetricsInt(), true));
        formatPrompt();
    }

    private void updateMainButton(boolean loading) {
        updateMainButton(loading, false);
    }

    private void updateMainButton(boolean loading, boolean error) {
        mainButton.setLoading(loading);
        AndroidUtilities.updateViewVisibilityAnimated(headerView.optionsButton, !loading && !error, 0.5f, true);
        if (onInsertPress != null) {
            AndroidUtilities.updateViewVisibilityAnimated(headerView.insertButton, !loading && !error, 0.5f, true);
        }
    }

    private void formatPrompt() {
        ColoredImageSpan arrow = new ColoredImageSpan(R.drawable.msg_mini_arrow_mediathin);
        arrow.setColorKey(Theme.key_player_actionBarSubtitle);
        arrow.setScale(1f, 1f);
        arrow.setTranslateY(AndroidUtilities.dpf2(1.5f));
        arrow.spaceScaleX = 0.95f;
        SpannableStringBuilder sb = new SpannableStringBuilder("→ " + prompt);
        sb.setSpan(arrow, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new DialogCell.FixedWidthSpan(AndroidUtilities.dp(4)), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.8f), 2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_player_actionBarSubtitle)), 2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannedPrompt = sb;
    }

    private CharSequence formatResponse(CharSequence response) {
        if (response == null) return "";
        if (AiConfig.showResponseOnly || spannedPrompt == null) {
            return replaceLinks(response);
        }
        return new SpannableStringBuilder(spannedPrompt).append("\n\n").append(replaceLinks(response));
    }

    private void generate() {
        currentRequestId = client.getResponse(prompt, useHistory, AiConfig.responseStreaming, imagePath, new GenerationCallback() {
            @Override
            public void onResponse(String response) {
                if (TextUtils.isEmpty(response)) return;
                currentResponse = response;
                textView.setText(formatResponse(response));
                adapter.updateMainView(textViewContainer);
                updateMainButton(false);
            }

            @Override
            public void onChunk(String chunk) {
                if (TextUtils.isEmpty(chunk)) return;
                currentResponse = chunk;
                textView.setText(formatResponse(chunk));
                adapter.updateMainView(textViewContainer);
            }

            @Override
            public void onError(int code, String message) {
                AiController.showErrorBulletin(containerView, resourcesProvider, code);
                adapter.updateMainView(textViewContainer);
                updateMainButton(false, true);
            }
        });
    }

    public CharSequence replaceLinks(CharSequence text) {
        if (text == null) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Matcher m = URL_PATTERN.matcher(text);
        int end = 0;
        while (m.find()) {
            sb.append(text, end, m.start());
            String label = m.group(1);
            final String url = m.group(2);
            sb.append(label != null ? label : "");
            sb.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    if (onLinkPress != null) {
                        if (Boolean.TRUE.equals(onLinkPress.run(new URLSpan(url)))) {
                            dismiss();
                        }
                    } else if (fragment != null) {
                        AlertsCreator.showOpenUrlAlert(fragment, url, false, false);
                    }
                }

                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    int alpha = Math.min(ds.getAlpha(), (ds.getColor() >> 24) & 0xFF);
                    ds.setUnderlineText(false);
                    ds.setColor(Theme.getColor(Theme.key_dialogTextLink));
                    ds.setAlpha(alpha);
                }
            }, sb.length() - (label != null ? label.length() : 0), sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            end = m.end();
        }
        sb.append(text, end, text.length());
        return Emoji.replaceEmoji((CharSequence) sb, textView.getPaint().getFontMetricsInt(), false, null);
    }

    private boolean hasEnoughHeight() {
        float height = 0f;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (listView.getChildAdapterPosition(child) == 1) {
                height += child.getHeight();
            }
        }
        return height >= (listView.getHeight() - listView.getPaddingTop() - listView.getPaddingBottom());
    }

    public void setFragment(BaseFragment fragment) {
        this.fragment = fragment;
    }

    public void setOnLinkPress(Utilities.CallbackReturn<URLSpan, Boolean> onLinkPress) {
        this.onLinkPress = onLinkPress;
    }

    private void setOnInsertPress(Utilities.Callback2<String, CharSequence> onInsertPress) {
        this.onInsertPress = onInsertPress;
    }

    public void setNoforwards(boolean noForwards) {
        if (textView != null) {
            textView.setTextIsSelectable(!noForwards);
        }
        if (getWindow() != null) {
            if (noForwards) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            }
        }
    }

    private float getSheetTop() {
        return getSheetTop(true);
    }

    private float getSheetTop(boolean animated) {
        float top = listView.getTop();
        if (listView.getChildCount() >= 1) {
            top += Math.max(0, listView.getChildAt(listView.getChildCount() - 1).getTop());
        }
        float result = Math.max(0f, top - AndroidUtilities.dp(78));
        if (animated && sheetTopAnimated != null) {
            if (!listView.scrollingByUser && !sheetTopNotAnimate) {
                return sheetTopAnimated.set(result);
            }
            sheetTopAnimated.set(result, true);
        }
        return result;
    }

    @Override
    public void show() {
        super.show();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
    }

    @Override
    public void dismiss() {
        super.dismiss();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.emojiLoaded) {
            loadingTextView.invalidate();
            textView.invalidate();
        }
    }


    private static class LoadingTextView extends TextView {
        private final LoadingDrawable loadingDrawable;
        private final LinkPath path;

        public LoadingTextView(Context context) {
            super(context);
            path = new LinkPath(true);
            loadingDrawable = new LoadingDrawable();
            loadingDrawable.usePath(path);
            loadingDrawable.setSpeed(0.65f);
            loadingDrawable.setRadiiDp(4f);
            setBackground(loadingDrawable);
        }

        @Override
        public void setTextColor(int color) {
            super.setTextColor(Theme.multAlpha(color, 0.2f));
            loadingDrawable.setColors(
                    Theme.multAlpha(color, 0.03f),
                    Theme.multAlpha(color, 0.175f),
                    Theme.multAlpha(color, 0.2f),
                    Theme.multAlpha(color, 0.45f));
        }

        private void updateDrawable() {
            if (path == null || loadingDrawable == null) return;
            path.rewind();
            if (getLayout() != null && getLayout().getText() != null) {
                path.setCurrentLayout(getLayout(), 0, getPaddingLeft(), getPaddingTop());
                getLayout().getSelectionPath(0, getLayout().getText().length(), path);
            }
            loadingDrawable.updateBounds();
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            updateDrawable();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            updateDrawable();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            loadingDrawable.reset();
        }
    }


    private static class PaddedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Context mContext;
        private View mMainView;
        private int mainViewType = 1;

        public PaddedAdapter(Context context, View view) {
            this.mContext = context;
            this.mMainView = view;
        }

        public void updateMainView(View view) {
            if (mMainView == view) return;
            mainViewType++;
            mMainView = view;
            notifyItemChanged(1);
        }

        @Override
        public int getItemCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 0 : mainViewType;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0) {
                View spacer = new View(mContext) {
                    @Override
                    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                        super.onMeasure(
                                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.4f), MeasureSpec.EXACTLY));
                    }
                };
                return new RecyclerListView.Holder(spacer);
            }
            return new RecyclerListView.Holder(mMainView);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        }
    }


    private class HeaderView extends FrameLayout {
        private final ImageView backButton;
        public final ImageView insertButton;
        private final AnimatedTextView modelSelector;
        public final ActionBarMenuItem optionsButton;
        private final View shadow;
        private final LinearLayout subtitleView;
        private final AnimatedTextView titleTextView;

        public HeaderView(Context context) {
            super(context);

            View bg = new View(context);
            bg.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));
            addView(bg, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44f, Gravity.TOP | Gravity.LEFT, 0f, 12f, 0f, 0f));

            backButton = new ImageView(context);
            backButton.setScaleType(ImageView.ScaleType.CENTER);
            backButton.setImageResource(R.drawable.ic_ab_back);
            backButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogTextBlack), PorterDuff.Mode.MULTIPLY));
            backButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector)));
            backButton.setAlpha(0f);
            backButton.setOnClickListener(v -> dismiss());
            addView(backButton, LayoutHelper.createFrame(54, 54f, Gravity.TOP | Gravity.LEFT, 1f, 1f, 1f, 1f));

            insertButton = new ImageView(context);
            ScaleStateListAnimator.apply(insertButton, 0.15f, 1.5f);
            insertButton.setScaleType(ImageView.ScaleType.CENTER);
            insertButton.setImageResource(R.drawable.msg_send);
            insertButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_player_actionBarSubtitle), PorterDuff.Mode.MULTIPLY));
            insertButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector)));
            insertButton.setVisibility(GONE);
            insertButton.setOnClickListener(v -> {
                if (onInsertPress != null) {
                    onInsertPress.run(prompt, formatResponse(currentResponse));
                }
                dismiss();
            });
            addView(insertButton, LayoutHelper.createFrame(48, 54f, Gravity.TOP | Gravity.RIGHT, 1f, 1f, 64f, 1f));

            optionsButton = new ActionBarMenuItem(context, null, 0, Theme.key_player_actionBarSubtitle, false, resourcesProvider);
            optionsButton.setLongClickEnabled(false);
            optionsButton.setShowSubmenuByMove(false);
            optionsButton.setIcon(R.drawable.ic_ab_other);
            optionsButton.setSubMenuOpenSide(2);
            optionsButton.setVisibility(GONE);
            optionsButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, AndroidUtilities.dp(18)));
            addView(optionsButton, LayoutHelper.createFrame(48, 54f, Gravity.TOP | Gravity.RIGHT, 1f, 1f, 16f, 1f));

            optionsButton.addSubItem(1, R.drawable.msg_copy, LocaleController.getString(R.string.Copy));
            optionsButton.addSubItem(2, R.drawable.msg_retry, LocaleController.getString(R.string.Retry));
            optionsButton.addSubItem(3, R.drawable.msg_edit, LocaleController.getString(R.string.Edit));
            if (useHistory) {
                optionsButton.addSubItem(4, R.drawable.menu_reply, LocaleController.getString(R.string.Reply));
            }
            optionsButton.setShowedFromBottom(false);
            optionsButton.setOnClickListener(v -> optionsButton.toggleSubMenu());
            optionsButton.setDelegate(id -> handleOptionClick(id, context));
            optionsButton.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));

            titleTextView = new AnimatedTextView(context, true, true, false);
            titleTextView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            titleTextView.setTextSize(AndroidUtilities.dp(20));
            titleTextView.setTypeface(AndroidUtilities.bold());
            titleTextView.setText(LocaleController.getString(R.string.AIChat));
            titleTextView.setPivotX(0f);
            titleTextView.setPivotY(0f);
            addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 30f, Gravity.TOP | Gravity.LEFT, 22f, 20f, 22f, 0f));

            subtitleView = new LinearLayout(context);
            subtitleView.setPivotX(0f);
            subtitleView.setPivotY(0f);

            modelSelector = new AnimatedTextView(context) {
                private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final LinkSpanDrawable.LinkCollector links = new LinkSpanDrawable.LinkCollector();

                @Override
                protected void onDraw(Canvas canvas) {
                    RectF rect = AndroidUtilities.rectTmp;
                    rect.set(0f, (getHeight() - AndroidUtilities.dp(18)) / 2f, width(), (getHeight() + AndroidUtilities.dp(18)) / 2f);
                    bgPaint.setColor(Theme.multAlpha(getThemedColor(Theme.key_player_actionBarSubtitle), 0.1175f));
                    canvas.drawRoundRect(rect, AndroidUtilities.dp(4), AndroidUtilities.dp(4), bgPaint);
                    if (links.draw(canvas)) {
                        invalidate();
                    }
                    super.onDraw(canvas);
                }

                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        LinkSpanDrawable<URLSpan> link = new LinkSpanDrawable<>(null, resourcesProvider, event.getX(), event.getY());
                        link.setColor(Theme.multAlpha(getThemedColor(Theme.key_player_actionBarSubtitle), 0.1175f));
                        LinkPath linkPath = link.obtainNewPath();
                        RectF rect = AndroidUtilities.rectTmp;
                        rect.set(0f, (getHeight() - AndroidUtilities.dp(18)) / 2f, width(), (getHeight() + AndroidUtilities.dp(18)) / 2f);
                        linkPath.addRect(rect, Path.Direction.CW);
                        links.addLink(link);
                        invalidate();
                        return true;
                    }
                    if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                        if (event.getAction() == MotionEvent.ACTION_UP) {
                            performClick();
                        }
                        links.clear();
                        invalidate();
                    }
                    return super.onTouchEvent(event);
                }
            };
            modelSelector.setAnimationProperties(0.25f, 0L, 350L, CubicBezierInterpolator.EASE_OUT_QUINT);
            modelSelector.setTextColor(getThemedColor(Theme.key_player_actionBarSubtitle));
            modelSelector.setTextSize(AndroidUtilities.dp(14));
            modelSelector.setText(AiController.getInstance().getSelected().getShortModel());
            modelSelector.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(2), AndroidUtilities.dp(4), AndroidUtilities.dp(2));
            modelSelector.setOnClickListener(v -> openModelSelect());
            subtitleView.addView(modelSelector, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 3, 0));
            addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 22f, 43f, 22f, 0f));

            shadow = new View(context);
            shadow.setBackgroundColor(getThemedColor(Theme.key_divider));
            shadow.setAlpha(0f);
            addView(shadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, AndroidUtilities.getShadowHeight() / AndroidUtilities.dpf2(1f), Gravity.TOP | Gravity.LEFT, 0f, 56f, 0f, 0f));
        }

        private void handleOptionClick(int id, Context context) {
            if (id == 1) {
                if (currentResponse != null && AndroidUtilities.addToClipboard(formatResponse(currentResponse))) {
                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                            .createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                }
            } else if (id == 2) {
                AiConfig.removeLastFromHistory();
                adapter.updateMainView(loadingTextView);
                updateMainButton(true);
                generate();
            } else if (id == 3) {
                new GenerateFromMessageBottomSheet(prompt, imagePath, fragment, context, data -> {
                    AiConfig.removeLastFromHistory();
                    setPrompt(data.prompt());
                    imagePath = data.imagePath();
                    useHistory = data.useHistory();
                    adapter.updateMainView(loadingTextView);
                    updateMainButton(true);
                    generate();
                }, useHistory).show();
            } else if (id == 4) {
                new GenerateFromMessageBottomSheet(fragment, context, data -> {
                    setPrompt(data.prompt());
                    imagePath = null;
                    useHistory = data.useHistory();
                    adapter.updateMainView(loadingTextView);
                    updateMainButton(true);
                    generate();
                }, false).show();
            }
        }

        public void openModelSelect() {
            if (client.isGenerating()) return;
            final ArrayList<Service> services = AiConfig.getServices();
            ActionBarPopupWindow.ActionBarPopupWindowLayout layout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(getContext()) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    if (services.size() >= 6) {
                        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(336), MeasureSpec.EXACTLY));
                    } else {
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    }
                }
            };
            Drawable bg = ContextCompat.getDrawable(getContext(), R.drawable.popup_fixed_alert).mutate();
            bg.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground), PorterDuff.Mode.MULTIPLY));
            layout.setBackground(bg);

            final ActionBarPopupWindow[] popup = new ActionBarPopupWindow[1];
            for (int i = 0; i < services.size(); i++) {
                final Service service = services.get(i);
                ActionBarMenuSubItem item = new ActionBarMenuSubItem(getContext(), 2, i == 0, i == services.size() - 1, resourcesProvider);
                item.setText(service.getModel());
                item.setSubtext(service.getUrl());
                item.subtextView.setPadding(0, 0, service.isSelected() ? AndroidUtilities.dp(34) : 0, 0);
                item.setMinimumWidth(AndroidUtilities.dp(196));
                item.setItemHeight(56);
                item.setChecked(service.isSelected());
                item.setOnClickListener(v -> {
                    if (popup[0] != null) popup[0].dismiss();
                    if (service.isSelected()) return;
                    modelSelector.setText(service.getShortModel());
                    adapter.updateMainView(loadingTextView);
                    AiConfig.setSelectedServices(service);
                    updateMainButton(true);
                    generate();
                });
                layout.addView(item);
            }

            popup[0] = new ActionBarPopupWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
            popup[0].setPauseNotifications(true);
            popup[0].setDismissAnimationDuration(220);
            popup[0].setOutsideTouchable(true);
            popup[0].setClippingEnabled(true);
            popup[0].setAnimationStyle(R.style.PopupContextAnimation);
            popup[0].setFocusable(true);

            int[] loc = new int[2];
            modelSelector.getLocationInWindow(loc);
            layout.measure(MeasureSpec.makeMeasureSpec(AndroidUtilities.displaySize.x, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(AndroidUtilities.displaySize.y, MeasureSpec.AT_MOST));
            int popupHeight = layout.getMeasuredHeight();
            int y = loc[1] > AndroidUtilities.displaySize.y * 0.9f - popupHeight
                    ? loc[1] - popupHeight + AndroidUtilities.dp(8)
                    : loc[1] + modelSelector.getMeasuredHeight() - AndroidUtilities.dp(8);
            popup[0].showAtLocation(containerView, Gravity.LEFT | Gravity.TOP, loc[0] - AndroidUtilities.dp(8), y);
        }

        @Override
        public void setTranslationY(float translationY) {
            super.setTranslationY(translationY);
            float t = MathUtils.clamp((translationY - AndroidUtilities.statusBarHeight) / AndroidUtilities.dp(64), 0f, 1f);
            if (!hasEnoughHeight()) t = 1f;
            float interp = CubicBezierInterpolator.EASE_OUT.getInterpolation(t);

            titleTextView.setScaleX(AndroidUtilities.lerp(0.85f, 1f, interp));
            titleTextView.setScaleY(AndroidUtilities.lerp(0.85f, 1f, interp));
            titleTextView.setTranslationY(AndroidUtilities.lerp(AndroidUtilities.dpf2(-12f), 0f, interp));
            titleTextView.setTranslationX(AndroidUtilities.lerp(AndroidUtilities.dpf2(50f), 0f, interp));

            subtitleView.setTranslationX(AndroidUtilities.lerp(AndroidUtilities.dpf2(50f), 0f, interp));
            subtitleView.setTranslationY(AndroidUtilities.lerp(AndroidUtilities.dpf2(-22f), 0f, interp));

            backButton.setTranslationX(AndroidUtilities.lerp(0f, AndroidUtilities.dpf2(-25f), interp));
            float invInterp = 1f - interp;
            backButton.setAlpha(invInterp);

            insertButton.setTranslationX(AndroidUtilities.lerp(AndroidUtilities.dpf2(14f), AndroidUtilities.dpf2(8f), interp));
            insertButton.setTranslationY(AndroidUtilities.lerp(AndroidUtilities.dpf2(0f), AndroidUtilities.dpf2(16f), interp));
            insertButton.setColorFilter(ColorUtils.blendARGB(
                    getThemedColor(Theme.key_dialogTextBlack),
                    getThemedColor(Theme.key_player_actionBarSubtitle), interp), PorterDuff.Mode.MULTIPLY);

            optionsButton.setTranslationX(AndroidUtilities.lerp(AndroidUtilities.dpf2(14f), AndroidUtilities.dpf2(8f), interp));
            optionsButton.setTranslationY(AndroidUtilities.lerp(AndroidUtilities.dpf2(0f), AndroidUtilities.dpf2(16f), interp));
            optionsButton.setIconColor(ColorUtils.blendARGB(
                    getThemedColor(Theme.key_dialogTextBlack),
                    getThemedColor(Theme.key_player_actionBarSubtitle), interp));

            shadow.setTranslationY(AndroidUtilities.lerp(0f, AndroidUtilities.dpf2(22f), interp));
            shadow.setAlpha(invInterp);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(78), MeasureSpec.EXACTLY));
        }
    }


    private class ContainerView extends FrameLayout {
        private final Paint bgPaint;
        private final Path bgPath;
        private Boolean lightStatusBarFull;

        public ContainerView(Context context) {
            super(context);
            bgPath = new Path();
            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(getThemedColor(Theme.key_dialogBackground));
            try {
                Theme.applyDefaultShadow(bgPaint);
            } catch (Throwable ignored) {}
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            float sheetTop = getSheetTop();
            float radius = AndroidUtilities.lerp(0, AndroidUtilities.dp(12), MathUtils.clamp(sheetTop / AndroidUtilities.dpf2(24f), 0f, 1f));
            headerView.setTranslationY(Math.max(AndroidUtilities.statusBarHeight, sheetTop));
            updateLightStatusBar(sheetTop <= ((float) AndroidUtilities.statusBarHeight) / 2f);
            bgPath.rewind();
            RectF rect = AndroidUtilities.rectTmp;
            rect.set(0f, sheetTop, getWidth(), getHeight() + radius);
            bgPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.drawPath(bgPath, bgPaint);
            super.dispatchDraw(canvas);
        }

        private void updateLightStatusBar(boolean full) {
            if (lightStatusBarFull != null && lightStatusBarFull == full) return;
            lightStatusBarFull = full;
            Window window = getWindow();
            if (window == null) return;
            int color;
            if (full) {
                color = getThemedColor(Theme.key_dialogBackground);
            } else {
                color = Theme.blendOver(getThemedColor(Theme.key_actionBarDefault), AndroidUtilities.DARK_STATUS_BAR_OVERLAY);
            }
            AndroidUtilities.setLightStatusBar(window, AndroidUtilities.computePerceivedBrightness(color) > 0.721f);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            Bulletin.addDelegate(this, new Bulletin.Delegate() {
                @Override
                public int getBottomOffset(int tag) {
                    return AndroidUtilities.dp(80);
                }
            });
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            Bulletin.removeDelegate(this);
        }
    }
}
