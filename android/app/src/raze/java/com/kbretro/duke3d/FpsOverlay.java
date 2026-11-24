package com.kbretro.duke3d;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.function.IntSupplier;

/** Contador de FPS (imágenes nuevas del juego por segundo) en la esquina, para los emuladores. */
final class FpsOverlay {
    private final TextView view;
    private final IntSupplier frames;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int lastFrames;
    private long lastTime;
    private boolean on;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!on) return;
            long now = System.nanoTime();
            int f = frames.getAsInt();
            double secs = (now - lastTime) / 1e9;
            if (secs > 0) view.setText(String.format(java.util.Locale.ROOT, "%.0f FPS", (f - lastFrames) / secs));
            lastFrames = f;
            lastTime = now;
            ui.postDelayed(this, 1000);
        }
    };

    FpsOverlay(Activity a, IntSupplier frames) {
        this.frames = frames;
        view = new TextView(a);
        view.setTextColor(Color.rgb(255, 220, 60));
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setShadowLayer(3f, 1.5f, 1.5f, Color.BLACK);
        int pad = (int) (8 * a.getResources().getDisplayMetrics().density);
        view.setPadding(pad * 2, pad, pad, pad);
        view.setVisibility(View.GONE);
        view.setFocusable(false);
        view.setClickable(false);
        a.addContentView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
    }

    void set(boolean show) {
        if (show == on) return;
        on = show;
        ui.removeCallbacks(tick);
        if (show) {
            view.setText("-- FPS");
            view.setVisibility(View.VISIBLE);
            view.bringToFront();
            lastFrames = frames.getAsInt();
            lastTime = System.nanoTime();
            ui.postDelayed(tick, 1000);
        } else {
            view.setVisibility(View.GONE);
        }
    }

    boolean isOn() {
        return on;
    }
}
