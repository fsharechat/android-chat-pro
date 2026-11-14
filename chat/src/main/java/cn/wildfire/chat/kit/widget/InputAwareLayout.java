package cn.wildfire.chat.kit.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import cn.wildfire.chat.kit.utils.ServiceUtil;

/** Serializes keyboard/custom-panel handoffs. Late IME callbacks cannot resurrect a dismissed panel. */
public class InputAwareLayout extends KeyboardAwareLinearLayout implements KeyboardAwareLinearLayout.OnKeyboardShownListener {
    private InputView current, requested;
    private EditText lastTarget;
    private boolean keyboardRequested;
    private int transition;

    public InputAwareLayout(Context context) { this(context, null); }
    public InputAwareLayout(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public InputAwareLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        addOnKeyboardShownListener(this);
        addOnKeyboardHiddenListener(() -> { if (requested == null) keyboardRequested = false; });
    }
    @Override public void onKeyboardShown() {
        hideVisiblePanel(true);
        if (requested != null && lastTarget != null) {
            final int token = transition;
            final InputView panel = requested;
            hideSoftkey(lastTarget, () -> { if (token == transition && requested == panel) present(panel); });
        }
    }
    public void show(@NonNull EditText imeTarget, @NonNull InputView input) {
        final int token = ++transition;
        lastTarget = imeTarget;
        keyboardRequested = false;
        requested = input;
        if (isKeyboardOpen()) {
            hideSoftkey(imeTarget, () -> { if (token == transition && requested == input) present(input); });
        } else {
            present(input);
        }
    }
    private void present(InputView input) {
        if (current != null && current != input) current.hide(true);
        input.show(getKeyboardHeight(), true);
        current = input;
    }
    public InputView getCurrentInput() { return requested != null ? requested : current; }
    public boolean isKeyboardRequested() { return keyboardRequested; }
    public void hideCurrentInput(EditText imeTarget) {
        hideAttachedInput(true);
        hideSoftkey(imeTarget, null);
    }
    public void hideAttachedInput(boolean instant) {
        transition++;
        requested = null;
        keyboardRequested = false;
        hideVisiblePanel(instant);
    }
    private void hideVisiblePanel(boolean instant) {
        if (current != null) current.hide(instant);
        current = null;
    }
    public boolean isInputOpen() { return isKeyboardOpen() || keyboardRequested || getCurrentInput() != null; }
    public void showSoftkey(EditText target) {
        final int token = ++transition;
        lastTarget = target;
        requested = null;
        keyboardRequested = true;
        target.post(() -> {
            if (token != transition || !keyboardRequested) return;
            target.requestFocus();
            ServiceUtil.getInputMethodManager(target.getContext()).showSoftInput(target, 0);
        });
        // A hardware/floating keyboard might not resize the window or send a shown callback.
        target.postDelayed(() -> {
            if (token == transition && keyboardRequested && !isKeyboardOpen()) hideVisiblePanel(true);
        }, 400);
    }
    public void hideSoftkey(EditText target, @Nullable Runnable after) {
        if (after != null) postOnKeyboardClose(after);
        ServiceUtil.getInputMethodManager(target.getContext()).hideSoftInputFromWindow(target.getWindowToken(), 0);
    }
    public interface InputView {
        void show(int height, boolean immediate);
        void hide(boolean immediate);
        boolean isShowing();
    }
}
