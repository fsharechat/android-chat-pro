package cn.wildfire.chat.kit.widget;

import android.graphics.Canvas;
import android.widget.EdgeEffect;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Keeps offscreen backdrop draws from consuming the live list's overscroll animation. */
public final class GlassEdgeEffectFactory extends RecyclerView.EdgeEffectFactory {
    @NonNull
    @Override
    protected EdgeEffect createEdgeEffect(@NonNull RecyclerView view, int direction) {
        return new EdgeEffect(view.getContext()) {
            @Override
            public boolean draw(Canvas canvas) {
                // EdgeEffect.draw mutates animation state. A backdrop sample must not advance
                // or finish it; only the actual on-screen canvas owns that animation.
                if (canvas instanceof GlassBackdropRenderer.CaptureCanvas) return false;
                return super.draw(canvas);
            }
        };
    }
}
