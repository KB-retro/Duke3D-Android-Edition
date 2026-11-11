package com.kbretro.duke3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * Controles táctiles con aspecto de Duke: botones metálicos con borde amarillo radiactivo,
 * letras rojas del propio juego y un joystick con el símbolo nuclear.
 *
 * - Izquierda: joystick (aparece donde se apoya el dedo) para andar y desplazarse de lado.
 * - Derecha: arrastrar para mirar/girar (también mientras se mantiene DISPARAR).
 * - Botones: disparar, saltar, usar, agacharse, patada, arma siguiente, objeto, mapa y menú.
 *
 * Solo se ven en plena partida; fuera de ella (menús del juego) los toques pasan al juego.
 */
final class TouchControls extends View {
    /** Lo que hace la vista con el juego (lo pone GameActivity). */
    interface Host {
        boolean inGame();

        void move(float x, float y);

        void look(float dx, float dy);

        void button(int gamefunc, boolean down);

        void openMenu();
    }

    static final int MENU = -3;

    private static final class Button {
        final String es, en;
        final int func;
        final float fx, fy, fr; // posición (desde la derecha/arriba en unidades) y radio
        final boolean fromRight, fromTop;
        float cx, cy, r;
        boolean pressed;

        Button(String es, String en, int func, float fx, float fy, float fr, boolean fromRight, boolean fromTop) {
            this.es = es;
            this.en = en;
            this.func = func;
            this.fx = fx;
            this.fy = fy;
            this.fr = fr;
            this.fromRight = fromRight;
            this.fromTop = fromTop;
        }
    }

    // gamefunc_* (function.h)
    private static final int FIRE = 5, OPEN = 6, JUMP = 9, CROUCH = 10, INVENTORY = 29, MAP = 38,
            KICK = 49, NEXT_WEAPON = 50;

    private final Button[] buttons = {
            new Button("FUEGO", "FIRE", FIRE, 1.65f, 1.75f, 0.95f, true, false),
            new Button("SALTO", "JUMP", JUMP, 0.9f, 3.7f, 0.6f, true, false),
            new Button("USAR", "USE", OPEN, 2.35f, 3.6f, 0.6f, true, false),
            new Button("AGACHA", "CROUCH", CROUCH, 3.45f, 0.95f, 0.6f, true, false),
            new Button("PATADA", "KICK", KICK, 3.75f, 2.4f, 0.6f, true, false),
            new Button("ARMA", "WEAPON", NEXT_WEAPON, 0.85f, 0.85f, 0.48f, true, true),
            new Button("OBJETO", "ITEM", INVENTORY, 2.0f, 0.85f, 0.48f, true, true),
            new Button("MAPA", "MAP", MAP, 3.15f, 0.85f, 0.48f, true, true),
            new Button("MENU", "MENU", MENU, 0.85f, 0.85f, 0.48f, false, true),
    };

    private final Host host;
    private DukeArt art;
    private boolean enabled = true;
    private boolean shown;
    private float unit;

    // Joystick
    private int stickPointer = -1;
    private float stickBaseX, stickBaseY, stickX, stickY, stickR;
    private float stickHomeX, stickHomeY;
    // Dedos que miran (y el botón que llevan, si es FUEGO)
    private final SparseArray<float[]> lookers = new SparseArray<>();
    private final SparseArray<Button> pressedBy = new SparseArray<>();
    private float sensitivity = 1f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tile = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final Path trefoil = new Path();

    TouchControls(Context c, Host host) {
        super(c);
        this.host = host;
        setFocusable(false);
        ring.setStyle(Paint.Style.STROKE);
        glow.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.rgb(230, 40, 20));
        text.setShadowLayer(3, 1, 1, Color.BLACK);
    }

    void setArt(DukeArt art) {
        this.art = art;
        postInvalidate();
    }

    /** Si se pueden usar (ajuste "Controles táctiles" y si hay mando, ver GameActivity). */
    void setControlsEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        if (!on) releaseAll();
        postInvalidate();
    }

    void setSensitivity(float s) {
        sensitivity = s;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        unit = Math.min(w, h) / 9.5f;
        for (Button b : buttons) {
            b.r = b.fr * unit;
            b.cx = b.fromRight ? w - b.fx * unit : b.fx * unit;
            b.cy = b.fromTop ? b.fy * unit : h - b.fy * unit;
        }
        stickR = 1.25f * unit;
        stickHomeX = 2.0f * unit;
        stickHomeY = h - 2.0f * unit;
        if (stickPointer < 0) {
            stickBaseX = stickX = stickHomeX;
            stickBaseY = stickY = stickHomeY;
        }
    }

    private void releaseAll() {
        for (int i = 0; i < pressedBy.size(); i++) {
            Button b = pressedBy.valueAt(i);
            b.pressed = false;
            if (b.func >= 0) host.button(b.func, false);
        }
        pressedBy.clear();
        lookers.clear();
        if (stickPointer >= 0) {
            stickPointer = -1;
            stickBaseX = stickX = stickHomeX;
            stickBaseY = stickY = stickHomeY;
            host.move(0, 0);
        }
    }

    private Button hit(float x, float y) {
        for (Button b : buttons) {
            float dx = x - b.cx, dy = y - b.cy, rr = b.r * 1.15f;
            if (dx * dx + dy * dy <= rr * rr) return b;
        }
        return null;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean active = enabled && host.inGame();
        if (!active) {
            if (stickPointer >= 0 || pressedBy.size() > 0 || lookers.size() > 0) releaseAll();
            return false; // en los menús del juego, el toque es para el juego
        }
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = e.getPointerId(idx);
                float x = e.getX(idx), y = e.getY(idx);
                Button b = hit(x, y);
                if (b != null) {
                    b.pressed = true;
                    pressedBy.put(id, b);
                    if (b.func >= 0) host.button(b.func, true);
                    if (b.func == FIRE) lookers.put(id, new float[]{x, y});
                } else if (x < getWidth() * 0.42f && stickPointer < 0) {
                    stickPointer = id;
                    stickBaseX = stickX = x;
                    stickBaseY = stickY = y;
                } else {
                    lookers.put(id, new float[]{x, y});
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    float x = e.getX(i), y = e.getY(i);
                    if (id == stickPointer) {
                        float dx = x - stickBaseX, dy = y - stickBaseY;
                        float len = (float) Math.hypot(dx, dy);
                        if (len > stickR) {
                            dx *= stickR / len;
                            dy *= stickR / len;
                        }
                        stickX = stickBaseX + dx;
                        stickY = stickBaseY + dy;
                        float mx = dx / stickR, my = dy / stickR;
                        if (Math.hypot(mx, my) < 0.12) mx = my = 0;
                        host.move(mx, my);
                    }
                    float[] last = lookers.get(id);
                    if (last != null) {
                        host.look((x - last[0]) * sensitivity, (y - last[1]) * sensitivity);
                        last[0] = x;
                        last[1] = y;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (action == MotionEvent.ACTION_CANCEL) {
                    releaseAll();
                    break;
                }
                int id = e.getPointerId(idx);
                Button b = pressedBy.get(id);
                if (b != null) {
                    b.pressed = false;
                    pressedBy.remove(id);
                    if (b.func >= 0) host.button(b.func, false);
                    else if (b.func == MENU) host.openMenu();
                }
                lookers.remove(id);
                if (id == stickPointer) {
                    stickPointer = -1;
                    stickBaseX = stickX = stickHomeX;
                    stickBaseY = stickY = stickHomeY;
                    host.move(0, 0);
                }
                break;
            }
            default:
                break;
        }
        invalidate();
        return true;
    }

    // ---- Dibujo ----

    @Override
    protected void onDraw(Canvas c) {
        boolean visible = enabled && host.inGame();
        if (visible != shown) {
            shown = visible;
            if (!visible) releaseAll();
        }
        if (visible) {
            drawStick(c);
            for (Button b : buttons) drawButton(c, b);
        }
        // Se vuelve a mirar si se está en plena partida (para aparecer y desaparecer a tiempo).
        postInvalidateDelayed(visible ? 100 : 300);
    }

    private void drawButton(Canvas c, Button b) {
        float r = b.r;
        fill.setShader(new RadialGradient(b.cx - r * 0.3f, b.cy - r * 0.3f, r * 1.3f,
                b.pressed ? new int[]{Color.argb(220, 255, 170, 40), Color.argb(200, 170, 40, 0)}
                        : new int[]{Color.argb(150, 95, 95, 100), Color.argb(150, 20, 20, 24)},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(b.cx, b.cy, r, fill);
        fill.setShader(null);
        // Borde amarillo radiactivo con un halo
        glow.setStrokeWidth(r * 0.18f);
        glow.setColor(b.pressed ? Color.argb(120, 255, 120, 0) : Color.argb(60, 255, 200, 0));
        c.drawCircle(b.cx, b.cy, r, glow);
        ring.setStrokeWidth(Math.max(2f, r * 0.07f));
        ring.setColor(b.pressed ? Color.rgb(255, 230, 120) : Color.argb(220, 255, 196, 0));
        c.drawCircle(b.cx, b.cy, r, ring);
        drawIcon(c, b, r * 0.62f);
    }

    private void drawStick(Canvas c) {
        float r = stickR;
        // Base
        fill.setShader(new RadialGradient(stickBaseX, stickBaseY, r, new int[]{Color.argb(70, 60, 60, 60),
                Color.argb(130, 15, 15, 15)}, null, Shader.TileMode.CLAMP));
        c.drawCircle(stickBaseX, stickBaseY, r, fill);
        fill.setShader(null);
        ring.setStrokeWidth(Math.max(2f, r * 0.04f));
        ring.setColor(Color.argb(170, 255, 196, 0));
        c.drawCircle(stickBaseX, stickBaseY, r, ring);
        // Marcas de dirección
        ring.setColor(Color.argb(110, 255, 196, 0));
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 2 * i;
            float ix = stickBaseX + (float) Math.cos(a) * r * 0.82f, iy = stickBaseY + (float) Math.sin(a) * r * 0.82f;
            c.drawCircle(ix, iy, r * 0.05f, ring);
        }
        // Botón: amarillo con el símbolo nuclear
        float kr = r * 0.46f;
        fill.setShader(new RadialGradient(stickX - kr * 0.3f, stickY - kr * 0.3f, kr * 1.4f,
                new int[]{Color.rgb(255, 225, 70), Color.rgb(200, 140, 0)}, null, Shader.TileMode.CLAMP));
        fill.setAlpha(stickPointer >= 0 ? 235 : 170);
        c.drawCircle(stickX, stickY, kr, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        fill.setColor(Color.argb(stickPointer >= 0 ? 230 : 170, 25, 20, 10));
        drawTrefoil(c, stickX, stickY, kr * 0.85f);
        ring.setColor(Color.argb(200, 30, 25, 10));
        ring.setStrokeWidth(Math.max(1.5f, kr * 0.06f));
        c.drawCircle(stickX, stickY, kr, ring);
        fill.setColor(Color.WHITE);
    }

    /** El símbolo de radiación: tres aspas y un círculo en el centro. */
    private void drawTrefoil(Canvas c, float x, float y, float r) {
        RectF outer = new RectF(x - r, y - r, x + r, y + r);
        float inner = r * 0.3f;
        RectF in = new RectF(x - inner, y - inner, x + inner, y + inner);
        for (int i = 0; i < 3; i++) {
            float start = -90 - 30 + i * 120;
            trefoil.reset();
            trefoil.arcTo(outer, start, 60);
            trefoil.arcTo(in, start + 60, -60);
            trefoil.close();
            c.drawPath(trefoil, fill);
        }
        c.drawCircle(x, y, r * 0.2f, fill);
    }

    private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path icon = new Path();

    /**
     * Iconos dibujados (no texto): amarillo brillante con contorno negro para que se vean sobre
     * cualquier fondo; blancos mientras se pulsan.
     */
    private void drawIcon(Canvas c, Button b, float s) {
        float x = b.cx, y = b.cy;
        int color = b.pressed ? Color.WHITE : Color.rgb(255, 214, 30);
        iconFill.setStyle(Paint.Style.FILL);
        iconFill.setColor(color);
        iconLine.setStyle(Paint.Style.STROKE);
        iconLine.setStrokeJoin(Paint.Join.ROUND);
        iconLine.setStrokeCap(Paint.Cap.ROUND);
        icon.reset();
        switch (b.func) {
            case FIRE: { // mira
                float r = s * 0.62f;
                strokeBoth(c, () -> {
                    c.drawCircle(x, y, r, iconLine);
                    c.drawLine(x, y - s, x, y - r * 0.45f, iconLine);
                    c.drawLine(x, y + r * 0.45f, x, y + s, iconLine);
                    c.drawLine(x - s, y, x - r * 0.45f, y, iconLine);
                    c.drawLine(x + r * 0.45f, y, x + s, y, iconLine);
                }, s * 0.16f, color);
                outlineFill(c, () -> c.drawCircle(x, y, s * 0.12f, iconFill), color);
                return;
            }
            case JUMP:
            case CROUCH: { // dos flechas hacia arriba / abajo
                float d = b.func == JUMP ? -1 : 1;
                for (int i = 0; i < 2; i++) {
                    float oy = y + d * (i == 0 ? -s * 0.38f : s * 0.22f);
                    icon.moveTo(x - s * 0.75f, oy - d * s * 0.32f);
                    icon.lineTo(x, oy + d * s * 0.38f);
                    icon.lineTo(x + s * 0.75f, oy - d * s * 0.32f);
                }
                strokeBoth(c, () -> c.drawPath(icon, iconLine), s * 0.26f, color);
                return;
            }
            case OPEN: { // puerta con pomo
                RectF door = new RectF(x - s * 0.55f, y - s * 0.95f, x + s * 0.55f, y + s * 0.95f);
                strokeBoth(c, () -> c.drawRect(door, iconLine), s * 0.18f, color);
                outlineFill(c, () -> c.drawCircle(x + s * 0.22f, y + s * 0.08f, s * 0.13f, iconFill), color);
                return;
            }
            case KICK: { // bota
                icon.moveTo(x - s * 0.55f, y - s * 0.95f);
                icon.lineTo(x + s * 0.05f, y - s * 0.95f);
                icon.lineTo(x + s * 0.05f, y + s * 0.05f);
                icon.lineTo(x + s * 0.85f, y + s * 0.3f);
                icon.quadTo(x + s * 1.0f, y + s * 0.55f, x + s * 0.85f, y + s * 0.8f);
                icon.lineTo(x - s * 0.55f, y + s * 0.8f);
                icon.close();
                outlineFill(c, () -> c.drawPath(icon, iconFill), color);
                return;
            }
            case NEXT_WEAPON: { // pistola
                icon.moveTo(x - s * 0.95f, y - s * 0.5f);
                icon.lineTo(x + s * 0.95f, y - s * 0.5f);
                icon.lineTo(x + s * 0.95f, y - s * 0.05f);
                icon.lineTo(x - s * 0.05f, y - s * 0.05f);
                icon.lineTo(x - s * 0.15f, y + s * 0.85f);
                icon.lineTo(x - s * 0.75f, y + s * 0.85f);
                icon.lineTo(x - s * 0.55f, y - s * 0.05f);
                icon.lineTo(x - s * 0.95f, y - s * 0.05f);
                icon.close();
                outlineFill(c, () -> c.drawPath(icon, iconFill), color);
                return;
            }
            case INVENTORY: { // botiquín
                RectF box = new RectF(x - s * 0.9f, y - s * 0.6f, x + s * 0.9f, y + s * 0.75f);
                outlineFill(c, () -> c.drawRoundRect(box, s * 0.15f, s * 0.15f, iconFill), color);
                Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
                cross.setColor(Color.rgb(200, 20, 20));
                c.drawRect(x - s * 0.14f, y - s * 0.4f, x + s * 0.14f, y + s * 0.55f, cross);
                c.drawRect(x - s * 0.48f, y - s * 0.06f, x + s * 0.48f, y + s * 0.22f, cross);
                return;
            }
            case MAP: { // mapa plegado
                icon.moveTo(x - s * 0.95f, y - s * 0.65f);
                icon.lineTo(x - s * 0.32f, y - s * 0.85f);
                icon.lineTo(x + s * 0.32f, y - s * 0.65f);
                icon.lineTo(x + s * 0.95f, y - s * 0.85f);
                icon.lineTo(x + s * 0.95f, y + s * 0.65f);
                icon.lineTo(x + s * 0.32f, y + s * 0.85f);
                icon.lineTo(x - s * 0.32f, y + s * 0.65f);
                icon.lineTo(x - s * 0.95f, y + s * 0.85f);
                icon.close();
                icon.moveTo(x - s * 0.32f, y - s * 0.85f);
                icon.lineTo(x - s * 0.32f, y + s * 0.65f);
                icon.moveTo(x + s * 0.32f, y - s * 0.65f);
                icon.lineTo(x + s * 0.32f, y + s * 0.85f);
                strokeBoth(c, () -> c.drawPath(icon, iconLine), s * 0.15f, color);
                return;
            }
            default: { // menú: tres rayas
                strokeBoth(c, () -> {
                    for (int i = -1; i <= 1; i++) c.drawLine(x - s * 0.75f, y + i * s * 0.5f, x + s * 0.75f, y + i * s * 0.5f, iconLine);
                }, s * 0.22f, color);
            }
        }
    }

    /** Trazo grueso negro debajo y el de color encima. */
    private void strokeBoth(Canvas c, Runnable draw, float width, int color) {
        iconLine.setColor(Color.argb(220, 0, 0, 0));
        iconLine.setStrokeWidth(width * 1.9f);
        draw.run();
        iconLine.setColor(color);
        iconLine.setStrokeWidth(width);
        draw.run();
    }

    /** Relleno de color con contorno negro. */
    private void outlineFill(Canvas c, Runnable draw, int color) {
        iconFill.setStyle(Paint.Style.STROKE);
        iconFill.setStrokeJoin(Paint.Join.ROUND);
        iconFill.setStrokeWidth(Math.max(3f, unit * 0.07f));
        iconFill.setColor(Color.argb(220, 0, 0, 0));
        draw.run();
        iconFill.setStyle(Paint.Style.FILL);
        iconFill.setColor(color);
        draw.run();
    }

    /** Texto con las letras rojas grandes del juego (o una negrita roja si no hay gráficos). */
    private void drawLabel(Canvas c, String s, float cx, float cy, float maxW, float maxH) {
        if (art != null && art.has(DukeArt.BIGALPHANUM)) {
            float w = 0, h = art.height(DukeArt.BIGALPHANUM);
            for (char ch : s.toCharArray()) {
                int t = DukeMenuView.bigTile(ch);
                w += (t >= 0 && art.has(t)) ? art.width(t) : 5;
            }
            if (w > 0 && h > 0) {
                float k = Math.min(maxW / w, maxH / h);
                float x = cx - w * k / 2f, top = cy - h * k / 2f;
                for (char ch : s.toCharArray()) {
                    int t = DukeMenuView.bigTile(ch);
                    Bitmap b = t >= 0 ? art.tile(t) : null;
                    if (b == null) {
                        x += 5 * k;
                        continue;
                    }
                    float bt = top + (h - b.getHeight()) * k;
                    dst.set(x, bt, x + b.getWidth() * k, bt + b.getHeight() * k);
                    c.drawBitmap(b, null, dst, tile);
                    x += b.getWidth() * k;
                }
                return;
            }
        }
        text.setTextSize(maxH * 0.8f);
        float tw = text.measureText(s);
        if (tw > maxW) text.setTextSize(text.getTextSize() * maxW / tw);
        c.drawText(s, cx, cy - (text.descent() + text.ascent()) / 2f, text);
    }
}
