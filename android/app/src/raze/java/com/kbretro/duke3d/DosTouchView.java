package com.kbretro.duke3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * Controles táctiles de los juegos de MS-DOS (DosActivity): cruceta a la izquierda, saltar,
 * disparar y usar a la derecha, Esc e Intro arriba, y el botón del menú rápido.
 * Todo llega al juego como teclas.
 */
final class DosTouchView extends View {

    interface Host {
        void key(int key, boolean down);

        void menu();
    }

    private static final int PAD = 0, JUMP = 1, FIRE = 2, USE = 3, ESC = 4, ENTER = 5, MENU = 6, N = 7;
    private static final int[] KEYS = {-1, DosActivity.K_CTRL, DosActivity.K_ALT, DosActivity.K_UP,
            DosActivity.K_ESC, DosActivity.K_ENTER, -1};

    private final Host host;
    private final RectF[] area = new RectF[N];
    private final boolean[] pressed = new boolean[N];
    private final SparseIntArray pointers = new SparseIntArray();  // dedo → control
    private float padX, padY, padR;
    private int padH = -1, padV = -1;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillOn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    DosTouchView(Context c, Host host) {
        super(c);
        this.host = host;
        for (int i = 0; i < N; i++) area[i] = new RectF();
        fill.setColor(Color.argb(70, 20, 20, 20));
        fillOn.setColor(Color.argb(120, 255, 194, 26));
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.argb(150, 255, 194, 26));
        text.setColor(Color.argb(200, 255, 255, 255));
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float u = Math.min(w, h);
        ring.setStrokeWidth(u * 0.006f);
        padR = u * 0.2f;
        padX = u * 0.06f + padR;
        padY = h - u * 0.06f - padR;
        area[PAD].set(padX - padR * 1.3f, padY - padR * 1.3f, padX + padR * 1.3f, padY + padR * 1.3f);
        float b = u * 0.095f;
        circle(JUMP, w - u * 0.06f - b, h - u * 0.07f - b, b);
        circle(FIRE, w - u * 0.06f - b * 3.4f, h - u * 0.04f - b, b);
        circle(USE, w - u * 0.06f - b, h - u * 0.07f - b * 3.4f, b * 0.8f);
        float ph = u * 0.075f, pw = u * 0.17f, m = u * 0.04f;
        area[ESC].set(m, m, m + pw, m + ph);
        area[ENTER].set(m * 1.5f + pw, m, m * 1.5f + pw * 2, m + ph);
        area[MENU].set(w - m - ph, m, w - m, m + ph);
    }

    private void circle(int i, float x, float y, float r) {
        area[i].set(x - r, y - r, x + r, y + r);
    }

    @Override
    protected void onDraw(Canvas c) {
        // Cruceta.
        c.drawCircle(padX, padY, padR, fill);
        c.drawCircle(padX, padY, padR, ring);
        float a = padR * 0.55f, s = padR * 0.16f;
        drawArrow(c, padX, padY - a, 0, -1, s, padV == DosActivity.K_UP);
        drawArrow(c, padX, padY + a, 0, 1, s, padV == DosActivity.K_DOWN);
        drawArrow(c, padX - a, padY, -1, 0, s, padH == DosActivity.K_LEFT);
        drawArrow(c, padX + a, padY, 1, 0, s, padH == DosActivity.K_RIGHT);
        // Botones redondos.
        button(c, JUMP, I18n.t("dos_jump"));
        button(c, FIRE, I18n.t("dos_fire"));
        button(c, USE, I18n.t("dos_use"));
        // Arriba.
        pill(c, ESC, "ESC");
        pill(c, ENTER, I18n.t("dos_enter"));
        pill(c, MENU, "≡");
    }

    private void drawArrow(Canvas c, float x, float y, int dx, int dy, float s, boolean on) {
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x + dx * s, y + dy * s);
        p.lineTo(x - dx * s + dy * s, y - dy * s + dx * s);
        p.lineTo(x - dx * s - dy * s, y - dy * s - dx * s);
        p.close();
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(on ? Color.argb(230, 255, 194, 26) : Color.argb(170, 255, 255, 255));
        c.drawPath(p, pt);
    }

    private void button(Canvas c, int i, String label) {
        RectF r = area[i];
        float rad = r.width() / 2;
        c.drawCircle(r.centerX(), r.centerY(), rad, pressed[i] ? fillOn : fill);
        c.drawCircle(r.centerX(), r.centerY(), rad, ring);
        label(c, r, label, rad * 0.42f);
    }

    private void pill(Canvas c, int i, String label) {
        RectF r = area[i];
        float rad = r.height() / 2;
        c.drawRoundRect(r, rad, rad, pressed[i] ? fillOn : fill);
        c.drawRoundRect(r, rad, rad, ring);
        label(c, r, label, r.height() * 0.42f);
    }

    private void label(Canvas c, RectF r, String s, float size) {
        text.setTextSize(size);
        while (text.measureText(s) > r.width() * 0.86f && size > 6) text.setTextSize(size *= 0.9f);
        c.drawText(s, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text);
    }

    // ---- Toques ----

    private int hit(float x, float y) {
        for (int i = N - 1; i >= 0; i--) {
            RectF r = area[i];
            float grow = i == PAD ? 0 : r.width() * 0.15f;
            if (x >= r.left - grow && x <= r.right + grow && y >= r.top - grow && y <= r.bottom + grow) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int ctl = hit(e.getX(idx), e.getY(idx));
                if (ctl < 0) return act != MotionEvent.ACTION_DOWN || pointers.size() > 0;
                pointers.put(e.getPointerId(idx), ctl);
                if (ctl == PAD) pad(e.getX(idx), e.getY(idx));
                else press(ctl, true);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int ctl = pointers.get(e.getPointerId(i), -1);
                    if (ctl == PAD) pad(e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (act == MotionEvent.ACTION_CANCEL) {
                    releaseAll();
                    break;
                }
                int id = e.getPointerId(idx);
                int ctl = pointers.get(id, -1);
                pointers.delete(id);
                if (ctl == PAD) padRelease();
                else if (ctl >= 0) press(ctl, false);
                break;
            }
        }
        invalidate();
        return true;
    }

    private void press(int ctl, boolean down) {
        if (pressed[ctl] == down) return;
        pressed[ctl] = down;
        if (ctl == MENU) {
            if (!down) host.menu();
            return;
        }
        if (KEYS[ctl] >= 0) host.key(KEYS[ctl], down);
    }

    /** Cruceta de 8 direcciones: flechas según el ángulo del dedo respecto al centro. */
    private void pad(float x, float y) {
        float dx = x - padX, dy = y - padY;
        float dead = padR * 0.25f;
        int h = -1, v = -1;
        if (Math.hypot(dx, dy) > dead) {
            double ang = Math.toDegrees(Math.atan2(dy, dx));  // 0 = derecha, 90 = abajo
            if (ang > -67.5 && ang < 67.5) h = DosActivity.K_RIGHT;
            if (ang > 112.5 || ang < -112.5) h = DosActivity.K_LEFT;
            if (ang > 22.5 && ang < 157.5) v = DosActivity.K_DOWN;
            if (ang < -22.5 && ang > -157.5) v = DosActivity.K_UP;
        }
        if (h != padH) {
            if (padH >= 0) host.key(padH, false);
            if (h >= 0) host.key(h, true);
            padH = h;
        }
        if (v != padV) {
            if (padV >= 0) host.key(padV, false);
            if (v >= 0) host.key(v, true);
            padV = v;
        }
    }

    private void padRelease() {
        if (padH >= 0) host.key(padH, false);
        if (padV >= 0) host.key(padV, false);
        padH = padV = -1;
    }

    /** Suelta todo (al ocultarse o al abrir el menú). */
    void releaseAll() {
        padRelease();
        for (int i = 0; i < N; i++) {
            if (pressed[i] && i != MENU) press(i, false);
            pressed[i] = false;
        }
        pointers.clear();
        invalidate();
    }
}
