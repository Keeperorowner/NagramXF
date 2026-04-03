package tw.nekomimi.nekogram.ui.cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.ButtonBounce;
import org.telegram.ui.Components.CubicBezierInterpolator;

import tw.nekomimi.nekogram.helpers.TimeStringHelper;

@SuppressLint("ViewConstructor")
public class DeletedMessagesColorPickerCell extends View {

    private static final int BUTTONS_COUNT = 8;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ColorButton[] buttons = new ColorButton[BUTTONS_COUNT];
    private ColorButton pressedButton;
    private int selectedColorId;
    private OnColorSelectedListener onColorSelectedListener;

    public DeletedMessagesColorPickerCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        backgroundPaint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < BUTTONS_COUNT; i++) {
            buttons[i] = new ColorButton();
            buttons[i].id = i;
        }
        updateButtonColors();
    }

    public void setOnColorSelected(OnColorSelectedListener listener) {
        onColorSelectedListener = listener;
    }

    public void setSelectedColorId(int colorId, boolean animated) {
        selectedColorId = Math.max(0, Math.min(colorId, BUTTONS_COUNT - 1));
        for (ColorButton button : buttons) {
            button.setSelected(button.id == selectedColorId, animated);
        }
        invalidate();
    }

    private void updateButtonColors() {
        buttons[0].set(Theme.getColor(Theme.key_chat_inTimeText, resourcesProvider));
        for (int i = 1; i < BUTTONS_COUNT; i++) {
            buttons[i].set(TimeStringHelper.DELETED_COLORS[i - 1]);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        float iconSize = Math.min(dp(38 + 16), width / (BUTTONS_COUNT + (BUTTONS_COUNT + 1) * 0.28947f));
        float horizontalSeparator = Math.min(iconSize * 0.28947f, dp(8));
        float verticalSeparator = Math.min(iconSize * 0.315789474f, dp(11.33f));
        int height = (int) (iconSize + verticalSeparator * 2);
        setMeasuredDimension(width, height);

        float itemsWidth = iconSize * BUTTONS_COUNT + horizontalSeparator * (BUTTONS_COUNT + 1);
        float startX = (width - itemsWidth) / 2f + horizontalSeparator;
        float x = startX;
        float y = verticalSeparator;
        for (ColorButton button : buttons) {
            AndroidUtilities.rectTmp.set(x, y, x + iconSize, y + iconSize);
            button.layout(AndroidUtilities.rectTmp);
            AndroidUtilities.rectTmp.inset(-horizontalSeparator / 2f, -verticalSeparator / 2f);
            button.layoutClickBounds(AndroidUtilities.rectTmp);
            button.setSelected(button.id == selectedColorId, false);
            x += iconSize + horizontalSeparator;
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        updateButtonColors();
        for (ColorButton button : buttons) {
            button.draw(canvas);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        ColorButton button = findButton(event.getX(), event.getY());
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            pressedButton = button;
            if (pressedButton != null) {
                pressedButton.setPressed(true);
            }
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (pressedButton != button) {
                if (pressedButton != null) {
                    pressedButton.setPressed(false);
                }
                if (button != null) {
                    button.setPressed(true);
                }
                if (pressedButton != null && button != null && onColorSelectedListener != null && selectedColorId != button.id) {
                    onColorSelectedListener.onColorSelected(button.id);
                }
                pressedButton = button;
            }
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (event.getAction() == MotionEvent.ACTION_UP && pressedButton != null && onColorSelectedListener != null) {
                onColorSelectedListener.onColorSelected(pressedButton.id);
            }
            for (ColorButton colorButton : buttons) {
                colorButton.setPressed(false);
            }
            pressedButton = null;
        }
        return true;
    }

    private ColorButton findButton(float x, float y) {
        for (ColorButton button : buttons) {
            if (button.clickBounds.contains(x, y)) {
                return button;
            }
        }
        return null;
    }

    public interface OnColorSelectedListener {
        void onColorSelected(int colorId);
    }

    private class ColorButton {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ButtonBounce bounce = new ButtonBounce(DeletedMessagesColorPickerCell.this);
        private final AnimatedFloat selectedT = new AnimatedFloat(DeletedMessagesColorPickerCell.this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final RectF bounds = new RectF();
        private final RectF clickBounds = new RectF();
        private boolean selected;
        private int id;

        public void set(int color) {
            paint.setColor(color);
        }

        public void setSelected(boolean selected, boolean animated) {
            this.selected = selected;
            if (!animated) {
                selectedT.set(selected, true);
            }
        }

        public void layout(RectF bounds) {
            this.bounds.set(bounds);
        }

        public void layoutClickBounds(RectF bounds) {
            this.clickBounds.set(bounds);
        }

        public void draw(Canvas canvas) {
            canvas.save();
            float scale = bounce.getScale(.05f);
            canvas.scale(scale, scale, bounds.centerX(), bounds.centerY());

            float radius = Math.min(bounds.height() / 2f, bounds.width() / 2f);
            canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius, paint);

            float t = selectedT.set(selected);
            if (t > 0) {
                backgroundPaint.setStrokeWidth(AndroidUtilities.dpf2(2));
                backgroundPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
                canvas.drawCircle(
                        bounds.centerX(),
                        bounds.centerY(),
                        radius + backgroundPaint.getStrokeWidth() * AndroidUtilities.lerp(.5f, -2f, t),
                        backgroundPaint
                );
            }

            canvas.restore();
        }

        public void setPressed(boolean pressed) {
            bounce.setPressed(pressed);
        }
    }
}
