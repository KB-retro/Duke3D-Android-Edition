package com.kbretro.duke3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Barra de teclas en pantalla para los menús de letras de los juegos de DOS (por ejemplo el
 * "Duke Help Menu" de Duke Nukem 1: S, I, G, H, F10). Se maneja con el mando: izquierda/derecha
 * eligen la tecla, A la pulsa, B pulsa Esc y cierra la barra. También se puede tocar.
 */
final class DosKeyBar extends View {
    interface Host {
        void tap(int key);
    }

    static final String[] LABELS;
    static final int[] KEYS;

    static {
        String first = "SRIGH";
        java.util.List<String> l = new java.util.ArrayList<>();
        java.util.List<Integer> k = new java.util.ArrayList<>();
        l.add("Esc"); k.add(DosActivity.K_ESC);
        l.add("Intro"); k.add(DosActivity.K_ENTER);
        l.add("Y"); k.add((int) 'y');
        l.add("N"); k.add((int) 'n');
        for (char c : first.toCharArray()) { l.add(String.valueOf(c)); k.add((int) Character.toLowerCase(c)); }
        l.add("F10"); k.add(DosActivity.K_F1 + 9);
        for (char c = '1'; c <= '9'; c++) { l.add(String.valueOf(c)); k.add((int) c); }
        l.add("0"); k.add((int) '0');
        for (char c = 'A'; c <= 'Z'; c++) {
            if (first.indexOf(c) >= 0 || c == 'Y' || c == 'N') continue;
            l.add(String.valueOf(c)); k.add((int) Character.toLowerCase(c));
        }
        l.add("Espacio"); k.add(DosActivity.K_SPACE);
        for (int f = 1; f <= 9; f++) { l.add("F" + f); k.add(DosActivity.K_F1 + f - 1); }
        LABELS = l.toArray(new String[0]);
        KEYS = new int[k.size()];
        for (int i = 0; i < KEYS.length; i++) KEYS[i] = k.get(i);
    }

    private static final int SHOWN = 11;
    private final Host host;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), cell = new Paint(Paint.ANTI_ALIAS_FLAG),
            sel = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int selected, first;

    DosKeyBar(Context c, Host host) {
        super(c);
        this.host = host;
        bg.setColor(0xD0101018);
        cell.setColor(0xFF2A2A38);
        sel.setColor(0xFFC81810);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
    }

    void move(int d) {
        selected = (selected + d + KEYS.length) % KEYS.length;
        invalidate();
    }

    int selectedKey() {
        return KEYS[selected];
    }

    private float barTop() {
        return getHeight() * 0.86f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), top = barTop(), h = getHeight() - top;
        c.drawRect(0, top, w, getHeight(), bg);
        if (selected < first) first = selected;
        if (selected >= first + SHOWN) first = selected - SHOWN + 1;
        float cw = w / SHOWN, pad = h * 0.12f;
        text.setTextSize(h * 0.36f);
        for (int i = 0; i < SHOWN && first + i < KEYS.length; i++) {
            int idx = first + i;
            r.set(i * cw + pad, top + pad, (i + 1) * cw - pad, getHeight() - pad);
            c.drawRoundRect(r, pad, pad, idx == selected ? sel : cell);
            c.drawText(LABELS[idx], r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text);
        }
        text.setTextSize(h * 0.22f);
        c.drawText(I18n.t("dos_keybar_help"), w / 2, top - h * 0.15f, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getY() < barTop()) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int i = first + (int) (e.getX() / (getWidth() / (float) SHOWN));
            if (i >= 0 && i < KEYS.length) {
                selected = i;
                invalidate();
                host.tap(KEYS[i]);
            }
        }
        return true;
    }
}
