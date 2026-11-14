package com.kbretro.duke3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Menú del lanzador dibujado como el menú principal del juego: el fondo de la pantalla de
 * título, el logo de Duke Nukem 3D, las letras rojas del juego y el átomo que gira junto a la
 * opción elegida. Todo sale de los GRP del usuario (DukeArt).
 *
 * Las coordenadas son las del juego: 320x200 dentro de un rectángulo 4:3, como lo pinta EDuke32.
 */
final class DukeMenuView extends View {

    /** Una opción del menú. */
    static final class Entry {
        final String label;
        final Bitmap icon;
        final Runnable action;
        /** Si no es null, la opción tiene un valor (p. ej. el idioma) que se cambia con izquierda/derecha. */
        final Supplier<String> value;
        /** Imagen grande (la caja del juego) que se ve a la derecha mientras está elegida. */
        Bitmap cover;
        /** Si no es null, Start sobre la opción la restablece (p. ej. un botón del mando). */
        Runnable reset;
        /** Si no es null, se llama al tocar su caja grande (la de la derecha). */
        Runnable coverTap;
        /** Si no es null, izquierda/derecha llaman a esto con -1/+1 en vez de a action (p. ej. un nivel 1-10). */
        java.util.function.IntConsumer step;
        /** Si devuelve true, la opción se pinta de otro color (p. ej. la pista que suena). */
        java.util.function.BooleanSupplier active;

        Entry(String label, Bitmap icon, Runnable action, Supplier<String> value) {
            this.label = label;
            this.icon = icon;
            this.action = action;
            this.value = value;
        }
    }

    /** Una pantalla del menú (principal, opciones...). */
    static final class Page {
        final List<Entry> entries = new ArrayList<>();
        /** Texto pequeño opcional abajo (p. ej. la carpeta de los datos). */
        Supplier<String> footer;
        /** Se llama al salir de la página con B/Atrás. */
        Runnable onLeave;
        /** Página de texto (la guía): título arriba y párrafos en letra pequeña; las opciones, abajo. */
        String title;
        String[] body;
        int selected;
        /** Fondo propio de esta página (sección del menú principal); si no, el del menú. */
        Bitmap background;
        /** La caja del juego elegido a la izquierda y la lista a la derecha. */
        boolean coverLeft;
        /**
         * Menú principal en dos lados: el otro lado (se cambia con izquierda/derecha, L1/R1 o
         * tocando las pestañas de arriba) y los nombres de las dos pestañas (izquierda, derecha).
         */
        Page otherSide;
        String[] tabs;
        /** Este lado es la pestaña de la derecha. */
        boolean rightTab;

        Page add(String label, Bitmap icon, Runnable action) {
            entries.add(new Entry(label, icon, action, null));
            return this;
        }

        /** Un juego: solo el nombre en la lista y su caja grande a la derecha. */
        Page addGame(String label, Bitmap cover, Runnable action) {
            Entry e = new Entry(label, null, action, null);
            e.cover = cover;
            entries.add(e);
            return this;
        }

        boolean hasCovers() {
            for (Entry e : entries) if (e.cover != null) return true;
            return false;
        }

        Page addValue(String label, Supplier<String> value, Runnable change) {
            entries.add(new Entry(label, null, change, value));
            return this;
        }
    }

    private final DukeArt art;
    /** Fondo propio (imagen del APK); si es null, la pantalla de título del juego. */
    private Bitmap background;
    private final Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint frame = new Paint();
    private final List<Page> stack = new ArrayList<>();
    private Runnable onBackFromRoot;
    /** Texto de la versión (p. ej. «Duke3D Raze Edition 0.1.27»), o null. */
    String versionText;


    private final Paint pixel = new Paint();
    private final Paint dim = new Paint();
    private final Paint shade = new Paint();
    /** Los iconos se reducen mucho: con filtro se ven mejor que píxel a píxel. */
    private final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final List<RectF> hit = new ArrayList<>();
    private int touched = -1;

    // Rectángulo 4:3 donde va el "320x200" del juego.
    private float ax, ay, sx, sy;

    DukeMenuView(Context context, DukeArt art) {
        super(context);
        this.art = art;
        pixel.setFilterBitmap(false);
        pixel.setAntiAlias(false);
        dim.setColor(Color.argb(130, 0, 0, 0));
        shade.setFilterBitmap(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        // Sin esto Android tiñe de blanco toda la vista al moverse con el mando (resalte de
        // foco); el menú ya marca él mismo la opción elegida.
        setDefaultFocusHighlightEnabled(false);
        setBackgroundColor(Color.BLACK);
    }

    private boolean overlay;

    /** Menú encima de la partida (menú rápido): sin fondo propio. */
    void setOverlay(boolean on) {
        overlay = on;
        setBackgroundColor(on ? Color.TRANSPARENT : Color.BLACK);
        invalidate();
    }

    /** Para el mando: mover la selección desde fuera (crucetas que llegan como ejes). */
    void moveSelection(int d) {
        move(d);
    }

    private int axisKey = -1;
    private long axisTime;

    /**
     * Cruceta que llega como ejes (HAT, p. ej. la del mando de Xbox) y stick izquierdo: como las
     * flechas, las cuatro direcciones (izquierda/derecha cambian valores). Mantenido, repite.
     */
    void onAxis(android.view.MotionEvent e) {
        float x = e.getAxisValue(android.view.MotionEvent.AXIS_HAT_X), y = e.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f) {
            x = e.getAxisValue(android.view.MotionEvent.AXIS_X);
            y = e.getAxisValue(android.view.MotionEvent.AXIS_Y);
        }
        int k = -1;
        if (Math.abs(y) >= 0.5f && Math.abs(y) >= Math.abs(x)) k = y > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
        else if (Math.abs(x) >= 0.5f) k = x > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        long now = android.os.SystemClock.uptimeMillis();
        if (k >= 0 && (k != axisKey || now - axisTime > 180)) {
            onKeyDown(k, new KeyEvent(KeyEvent.ACTION_DOWN, k));
            axisTime = k != axisKey ? now + 220 : now; // primera repetición algo más tarde
        }
        axisKey = k;
    }

    void setBackground(Bitmap b) {
        background = b;
        invalidate();
    }

    void setRoot(Page root, Runnable onBack) {
        stack.clear();
        stack.add(root);
        onBackFromRoot = onBack;
        invalidate();
    }

    void push(Page page) {
        stack.add(page);
        invalidate();
    }

    /** La página que se ve ahora. */
    Page top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    void pop() {
        if (stack.size() > 1) {
            Page left = stack.remove(stack.size() - 1);
            if (left.onLeave != null) left.onLeave.run();
            invalidate();
        } else if (onBackFromRoot != null) {
            onBackFromRoot.run();
        }
    }

    private Page page() {
        return stack.get(stack.size() - 1);
    }

    /** En el menú principal con dos lados: pasa al otro. */
    private boolean switchSide(int dir) {
        if (stack.size() != 1) return false;
        Page p = page();
        if (p.otherSide == null || p.tabs == null) return false;
        if (dir > 0 && p.rightTab || dir < 0 && !p.rightTab) return true; // ya está en ese lado
        stack.set(0, p.otherSide);
        invalidate();
        return true;
    }

    /** En el menú principal con secciones: pasa a la siguiente (botón Y o tocando el pie). */
    boolean nextSection() {
        if (stack.size() != 1 || page().otherSide == null) return false;
        stack.set(0, page().otherSide);
        invalidate();
        return true;
    }

    private final RectF footerRect = new RectF();
    /** El icono de cambiar de sección (símbolo nuclear con la Y, 12 fotogramas en fila). */
    private Bitmap sectionIcon;
    private final RectF[] tabRects = {new RectF(), new RectF()};
    private final Paint tabLine = new Paint();

    {
        tabLine.setColor(Color.rgb(255, 200, 40));
    }

    // ---- Dibujo ----

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || stack.isEmpty()) return;

        // Fondo: la imagen propia cubriendo toda la pantalla, o la pantalla de título del juego.
        if (overlay) {
            // Encima de la partida: solo un velo oscuro, el juego se ve detrás en pausa.
            c.drawColor(Color.argb(175, 0, 0, 0));
        } else if (background != null) {
            // La sección del menú principal (y sus páginas) puede tener su propio fondo.
            Bitmap bgImg = stack.get(0).background != null ? stack.get(0).background : background;
            float k = Math.max(w / (float) bgImg.getWidth(), h / (float) bgImg.getHeight());
            float bw = bgImg.getWidth() * k, bh = bgImg.getHeight() * k;
            dst.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f);
            c.drawBitmap(bgImg, null, dst, smooth);
        }
        Bitmap bg = background != null || overlay ? null : art.tile(DukeArt.BETASCREEN);
        if (bg == null && background == null && !overlay) bg = art.tile(DukeArt.MENUSCREEN);
        if (bg != null) {
            float bw = w, bh = w * 3f / 4f;
            if (bh < h) {
                bh = h;
                bw = h * 4f / 3f;
            }
            dst.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f);
            c.drawBitmap(bg, null, dst, pixel);
        }
        if (!overlay) c.drawRect(0, 0, w, h, dim);

        // Zona 4:3 para el contenido.
        float aw = w, ah = w * 3f / 4f;
        if (ah > h) {
            ah = h;
            aw = h * 4f / 3f;
        }
        ax = (w - aw) / 2f;
        ay = (h - ah) / 2f;
        sx = aw / 320f;
        sy = ah / 200f;


        Page p = page();
        int n = p.entries.size();
        // Con cajas, la lista va a la izquierda y la caja del juego elegido a la derecha.
        boolean covers = p.hasCovers();
        // Con cajas, la pantalla entera (también en 16:9) se reparte en dos mitades: la lista
        // centrada en la izquierda y la caja centrada en la derecha.
        float leftEdge = -ax / sx, span = w / sx;
        float centerX = 160, maxW = 270;
        if (covers) {
            // La caja, centrada en la parte derecha; la lista, centrada en el hueco de la izquierda.
            float boxW = Math.min(span * 0.36f, 150), boxH = 150;
            float boxLeft = leftEdge + span * (p.coverLeft ? 0.27f : 0.73f) - boxW / 2f;
            float listLeft = p.coverLeft ? boxLeft + boxW + 10 : leftEdge + 8;
            float listRight = p.coverLeft ? leftEdge + span - 8 : boxLeft - 10;
            centerX = (listLeft + listRight) / 2f;
            maxW = Math.min(listRight - listLeft, 240);
            // Solo con un juego elegido (en Opciones o Salir no se ve ninguna caja).
            Entry cur = n > 0 ? p.entries.get(p.selected) : null;
            coverRect.setEmpty();
            if (cur != null && cur.cover != null) drawCover(c, cur.cover, boxLeft, 100 - boxH / 2f, boxW, boxH);
        }
        // Listas largas (las pistas de música): se ven unas cuantas y se desplazan con la elegida.
        int visible = Math.min(n, MAX_VISIBLE);
        int first = 0;
        if (n > MAX_VISIBLE) first = Math.max(0, Math.min(p.selected - MAX_VISIBLE / 2, n - MAX_VISIBLE));
        // En el lanzador la lista va centrada en vertical; en el menú rápido, bajo el logo.
        // Lista centrada en vertical (en el lanzador y en el menú rápido, que ya no lleva logo).
        float block = Math.min(19f, 156f / Math.max(1, visible)) * visible;
        float top = 100 - block / 2f;
        if (p.body != null) {
            drawBody(c, p);
            block = 13f * visible;
            top = 186 - block;
        }
        float bottom = top + block;
        float step = Math.min(19f, (bottom - top) / Math.max(1, visible));
        float textScale = step >= 17 ? 1f : step / 17f;
        // Todas las opciones de la página con el mismo tamaño de letra: el que necesita la más
        // ancha para caber (antes cada una se encogía por su cuenta y las de valor iban más pequeñas).
        float widest = 0;
        for (Entry e : p.entries) {
            String t = e.value != null ? e.label + ": " + e.value.get() : e.label;
            float tw = bigWidth(t) * textScale;
            if (e.icon != null) tw += 14 * textScale * 1.6f + 5;
            widest = Math.max(widest, tw);
        }
        if (widest > maxW) textScale *= maxW / widest;
        long now = SystemClock.uptimeMillis();
        int frame = (int) ((now / 66) % 7);

        hit.clear();
        hitIndex.clear();
        stepPx = step * sy;
        if (n > MAX_VISIBLE) {
            arrow.setColor(Color.rgb(255, 200, 40));
            if (first > 0) drawArrow(c, centerX, top - 3, true);
            if (first + visible < n) drawArrow(c, centerX, bottom + 2, false);
        }
        for (int i = first; i < first + visible; i++) {
            Entry e = p.entries.get(i);
            boolean sel = i == p.selected;
            float cy = top + step * (i - first) + step / 2f;
            String text = e.value != null ? e.label + ": " + e.value.get() : e.label;

            float scale = textScale;
            float textW = bigWidth(text) * scale;
            float iconW = 0, iconH = 0, gap = 0;
            if (e.icon != null) {
                iconH = 14 * textScale;
                // Las pantallas del juego son 320x200 (se ven 4:3); las cajas, con su proporción real.
                iconW = e.cover != null
                        ? iconH * e.icon.getWidth() / (float) e.icon.getHeight() * (sy / sx)
                        : iconH * 1.6f;
                gap = 5;
            }
            float total = iconW + gap + textW;
            if (total > maxW) {
                float k = maxW / total;
                scale *= k;
                textW *= k;
                iconW *= k;
                iconH *= k;
                total = maxW;
            }
            float left = centerX - total / 2f;

            // Las opciones no elegidas, algo más oscuras; la elegida, con un brillo que late.
            int lvl = sel ? 205 + (int) (50 * Math.abs(Math.sin(now / 300.0))) : 150;
            boolean on = e.active != null && e.active.getAsBoolean();
            LightingColorFilter lf = new LightingColorFilter(Color.rgb(lvl, lvl, lvl),
                    on ? Color.rgb(90, 80, 0) : 0);
            shade.setColorFilter(lf);
            iconPaint.setColorFilter(lf);

            if (e.icon != null) {
                float iy = cy - iconH / 2f;
                if (sel) {
                    Paint frameP = new Paint();
                    frameP.setColor(Color.rgb(255, 200, 40));
                    c.drawRect(vx(left - 1), vy(iy - 1), vx(left + iconW + 1), vy(iy + iconH + 1), frameP);
                }
                dst.set(vx(left), vy(iy), vx(left + iconW), vy(iy + iconH));
                c.drawBitmap(e.icon, null, dst, iconPaint);
            }
            drawBig(c, text, left + iconW + gap, cy, scale, shade);

            if (sel && art.has(DukeArt.SPINNINGNUKEICON)) {
                int tile = DukeArt.SPINNINGNUKEICON + frame;
                int tileR = DukeArt.SPINNINGNUKEICON + 6 - frame;
                float nh = art.height(DukeArt.SPINNINGNUKEICON);
                float ns = nh > 0 ? (16f * textScale) / nh : 0.5f;
                float nw = art.width(DukeArt.SPINNINGNUKEICON) * ns;
                drawTileCentered(c, tile, left - 4 - nw / 2f, cy, ns);
                drawTileCentered(c, tileR, left + total + 4 + nw / 2f, cy, ns);
            }
            hit.add(new RectF(covers && p.coverLeft ? vx(centerX - maxW / 2f - 12) : 0, vy(cy - step / 2f),
                    covers && !p.coverLeft ? vx(centerX + maxW / 2f + 12) : w, vy(cy + step / 2f)));
            hitIndex.add(i);
        }

        // Pestañas de los dos lados del menú principal, arriba: la de este lado iluminada.
        tabRects[0].setEmpty();
        tabRects[1].setEmpty();
        if (p.tabs != null && stack.size() == 1) {
            float ts = 0.6f;
            float cxs[] = {leftEdge + span * 0.27f, leftEdge + span * 0.73f};
            for (int t = 0; t < 2; t++) {
                boolean cur = (t == 1) == p.rightTab;
                int lvl = cur ? 205 + (int) (50 * Math.abs(Math.sin(now / 300.0))) : 110;
                shade.setColorFilter(new LightingColorFilter(Color.rgb(lvl, lvl, lvl), 0));
                float tw = bigWidth(p.tabs[t]) * ts;
                float tx = cxs[t] - tw / 2f;
                drawBig(c, p.tabs[t], tx, 12, ts, shade);
                tabRects[t].set(vx(tx - 6), vy(2), vx(tx + tw + 6), vy(22));
                if (cur) c.drawRect(vx(tx), vy(19), vx(tx + tw), vy(19) + Math.max(2, sy * 0.8f), tabLine);
            }
            shade.setColorFilter(null);
        }

        // Pie: una línea de información si la hay (carpeta, pista que suena...).
        String ft = p.footer != null ? p.footer.get() : "";
        if (!ft.isEmpty()) drawSmall(c, ft, 160, 191, 0.6f, true);
        // Menú principal con secciones: abajo en el centro, el símbolo nuclear girando con la Y en
        // el eje (icono propio, 12 fotogramas): Y cambia de sección (o tocarlo). Si hay un aviso,
        // va el aviso.
        if (stack.size() == 1 && p.otherSide != null && ft.isEmpty()) {
            if (sectionIcon == null)
                sectionIcon = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.icono_seccion);
            if (sectionIcon != null) {
                int frames = 12, fw = sectionIcon.getWidth() / frames, fh = sectionIcon.getHeight();
                int f = (int) ((now / 60) % frames);
                android.graphics.Rect src = new android.graphics.Rect(f * fw, 0, (f + 1) * fw, fh);
                float hpx = 26 * sy, cx = vx(160), cy = vy(186);
                dst.set(cx - hpx / 2f, cy - hpx / 2f, cx + hpx / 2f, cy + hpx / 2f);
                c.drawBitmap(sectionIcon, src, dst, smooth);
            }
        }
        footerRect.set(0, vy(183), w, h);

        // Versión de la app, pequeña, con las letras del juego, arriba a la derecha (lanzador).
        if (versionText != null && !overlay) {
            String vt = clean(versionText);
            float scale = 0.5f, wsum = 0;
            for (char ch : vt.toCharArray()) wsum += smallAdvance(ch);
            float right = -ax / sx + w / sx - 4;
            drawSmall(c, versionText, right - wsum * scale, 6, scale, false);
        }
        postInvalidateDelayed(50);
    }

    /** La caja entera dentro del rectángulo (x, y, w, h) del 320x200, con marco. */
    /** Dónde está la caja grande en pantalla (para tocarla). */
    private final RectF coverRect = new RectF();

    private void drawCover(Canvas c, Bitmap b, float x, float y, float bw, float bh) {
        // El 320x200 se ve estirado a 4:3: en píxeles de pantalla se mantiene la proporción real.
        float pw = bw * sx, ph = bh * sy;
        float k = Math.min(pw / b.getWidth(), ph / b.getHeight());
        float dw = b.getWidth() * k, dh = b.getHeight() * k;
        float l = vx(x) + (pw - dw) / 2f, t = vy(y) + (ph - dh) / 2f;
        frame.setColor(Color.argb(200, 0, 0, 0));
        c.drawRect(l - 6, t - 6, l + dw + 6, t + dh + 6, frame);
        frame.setColor(Color.rgb(255, 200, 40));
        c.drawRect(l - 3, t - 3, l + dw + 3, t + dh + 3, frame);
        coverRect.set(l, t, l + dw, t + dh);
        dst.set(l, t, l + dw, t + dh);
        c.drawBitmap(b, null, dst, smooth);
    }

    private static final int MAX_VISIBLE = 9;
    private final List<Integer> hitIndex = new ArrayList<>();
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float stepPx = 1;
    private float dragY;
    private boolean dragged;

    /** Flecha de "hay más" arriba o abajo de la lista. */
    private void drawArrow(Canvas c, float x, float y, boolean up) {
        android.graphics.Path path = new android.graphics.Path();
        float hw = 5, h = 3;
        if (up) {
            path.moveTo(vx(x - hw), vy(y));
            path.lineTo(vx(x + hw), vy(y));
            path.lineTo(vx(x), vy(y - h));
        } else {
            path.moveTo(vx(x - hw), vy(y));
            path.lineTo(vx(x + hw), vy(y));
            path.lineTo(vx(x), vy(y + h));
        }
        path.close();
        c.drawPath(path, arrow);
    }

    private float vx(float x) {
        return ax + x * sx;
    }

    private float vy(float y) {
        return ay + y * sy;
    }

    /** Como rotatesprite del juego: el punto (x, y) es el centro del gráfico más su desplazamiento. */
    private void drawTile(Canvas c, int tile, float x, float y, float scale, Paint paint) {
        Bitmap b = art.tile(tile);
        if (b == null) return;
        float px = b.getWidth() / 2f + art.xoff(tile);
        float py = b.getHeight() / 2f + art.yoff(tile);
        float l = x - px * scale, t = y - py * scale;
        dst.set(vx(l), vy(t), vx(l + b.getWidth() * scale), vy(t + b.getHeight() * scale));
        c.drawBitmap(b, null, dst, paint != null ? paint : pixel);
    }

    /** Centrado de verdad (sin el desplazamiento del gráfico). */
    private void drawTileCentered(Canvas c, int tile, float x, float y, float scale) {
        Bitmap b = art.tile(tile);
        if (b == null) return;
        float bw = b.getWidth() * scale, bh = b.getHeight() * scale;
        dst.set(vx(x - bw / 2f), vy(y - bh / 2f), vx(x + bw / 2f), vy(y + bh / 2f));
        c.drawBitmap(b, null, dst, pixel);
    }

    // ---- Letras del juego ----

    /** Mayúsculas y sin tildes (las letras del juego no las tienen). */
    static String clean(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace("¿", "").replace("¡", "").toUpperCase(Locale.ROOT);
    }

    /** Letra grande roja del menú (BIGALPHANUM), como en G_GetStringTileLegacy. */
    static int bigTile(char c) {
        if (c >= '0' && c <= '9') return DukeArt.BIGALPHANUM - 10 + (c - '0');
        if (c >= 'A' && c <= 'Z') return DukeArt.BIGALPHANUM + (c - 'A');
        switch (c) {
            case '-':
            case '_':
                return DukeArt.BIGALPHANUM - 11;
            case '.':
                return DukeArt.BIGPERIOD;
            case ',':
                return DukeArt.BIGCOMMA;
            case '!':
                return DukeArt.BIGX_;
            case '?':
                return DukeArt.BIGQ;
            case ';':
                return DukeArt.BIGSEMI;
            case ':':
                return DukeArt.BIGCOLIN;
            case '/':
            case '\\':
                return DukeArt.BIGALPHANUM + 68;
            case '%':
                return DukeArt.BIGALPHANUM + 69;
            case '\'':
            case '"':
            case '`':
                return DukeArt.BIGAPPOS;
            default:
                return -1;
        }
    }

    private float bigWidth(String s) {
        float wsum = 0;
        for (char ch : clean(s).toCharArray()) {
            int t = bigTile(ch);
            wsum += (t >= 0 && art.has(t)) ? art.width(t) : 5;
        }
        return wsum;
    }

    /** Texto grande con la parte izquierda en x y centrado en vertical en cy. */
    private void drawBig(Canvas c, String s, float x, float cy, float scale, Paint paint) {
        float lineH = art.height(DukeArt.BIGALPHANUM) > 0 ? art.height(DukeArt.BIGALPHANUM) : 15;
        float top = cy - lineH * scale / 2f;
        for (char ch : clean(s).toCharArray()) {
            int t = bigTile(ch);
            Bitmap b = (t >= 0) ? art.tile(t) : null;
            if (b == null) {
                x += 5 * scale;
                continue;
            }
            // Las letras se alinean por abajo, como en el juego.
            float bt = top + (lineH - b.getHeight()) * scale;
            dst.set(vx(x), vy(bt), vx(x + b.getWidth() * scale), vy(bt + b.getHeight() * scale));
            c.drawBitmap(b, null, dst, paint);
            x += b.getWidth() * scale;
        }
    }

    /** Texto de una página de la guía: título grande y párrafos ajustados al ancho. */
    private void drawBody(Canvas c, Page p) {
        float y0 = 8;
        if (p.title != null) {
            float ts = 0.8f, tw = bigWidth(p.title) * ts;
            if (tw > 290) {
                ts *= 290 / tw;
                tw = 290;
            }
            drawBig(c, p.title, 160 - tw / 2f, 16, ts, pixel);
            y0 = 30;
        }
        float glyphH = art.height(DukeArt.STARTALPHANUM) > 0 ? art.height(DukeArt.STARTALPHANUM) : 8;
        float avail = (186 - 13f * p.entries.size() - 6) - y0;
        float width = 296, left = 12;
        float s = 0.75f;
        java.util.List<String> lines = null;
        // Letra lo más grande posible que quepa en el hueco.
        for (int tries = 0; tries < 8; tries++) {
            lines = wrap(p.body, width / s);
            if (lines.size() * (glyphH + 3) * s <= avail) break;
            s *= 0.9f;
        }
        float lh = (glyphH + 3) * s;
        float y = y0 + lh / 2f;
        for (String l : lines) {
            if (!l.isEmpty()) drawSmall(c, l, left, y, s, false);
            y += lh;
        }
    }

    /** Parte los párrafos en líneas del ancho dado (en unidades de la letra pequeña). */
    private java.util.List<String> wrap(String[] paras, float maxW) {
        java.util.List<String> out = new ArrayList<>();
        for (String para : paras) {
            String indent = para.startsWith("- ") ? "  " : "";
            StringBuilder line = new StringBuilder();
            float lw = 0;
            for (String word : para.split(" ")) {
                float ww = 0;
                for (char ch : clean(word).toCharArray()) ww += smallAdvance(ch);
                float space = line.length() > 0 ? 5 : 0;
                if (line.length() > 0 && lw + space + ww > maxW) {
                    out.add(line.toString());
                    line = new StringBuilder(indent);
                    lw = indent.length() * 5;
                    space = 0;
                }
                if (space > 0) line.append(' ');
                line.append(word);
                lw += space + ww;
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }

    /** Letra pequeña del juego (STARTALPHANUM). */
    private void drawSmall(Canvas c, String s, float x, float cy, float scale, boolean center) {
        String txt = clean(s);
        float wsum = 0;
        for (char ch : txt.toCharArray()) wsum += smallAdvance(ch);
        float maxW = 300 / scale;
        if (wsum > maxW) {
            scale *= maxW / wsum;
        }
        if (center) x -= wsum * scale / 2f;
        float lineH = art.height(DukeArt.STARTALPHANUM) > 0 ? art.height(DukeArt.STARTALPHANUM) : 8;
        float top = cy - lineH * scale / 2f;
        for (char ch : txt.toCharArray()) {
            int t = ch > ' ' && ch <= '~' ? DukeArt.STARTALPHANUM + (ch - '!') : -1;
            Bitmap b = t >= 0 ? art.tile(t) : null;
            if (b != null) {
                dst.set(vx(x), vy(top), vx(x + b.getWidth() * scale), vy(top + b.getHeight() * scale));
                c.drawBitmap(b, null, dst, pixel);
            }
            x += smallAdvance(ch) * scale;
        }
    }

    private float smallAdvance(char ch) {
        int t = ch > ' ' && ch <= '~' ? DukeArt.STARTALPHANUM + (ch - '!') : -1;
        return (t >= 0 && art.has(t)) ? art.width(t) + 1 : 5;
    }

    // ---- Mando y teclado ----

    private void move(int d) {
        Page p = page();
        int n = p.entries.size();
        if (n == 0) return;
        if (Math.abs(d) > 1) p.selected = Math.max(0, Math.min(n - 1, p.selected + d));
        else p.selected = (p.selected + d + n) % n;
        invalidate();
    }

    private void activate() {
        Page p = page();
        if (p.entries.isEmpty()) return;
        Entry e = p.entries.get(p.selected);
        if (e.action != null) e.action.run();
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (!switchSide(-1)) move(-MAX_VISIBLE);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (!switchSide(1)) move(MAX_VISIBLE);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                Page p = page();
                Entry e = p.entries.isEmpty() ? null : p.entries.get(p.selected);
                if (e != null && e.step != null) {
                    e.step.accept(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    invalidate();
                } else if (e != null && e.value != null) {
                    activate();
                } else {
                    switchSide(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_START: {
                Page p = page();
                Entry e = p.entries.isEmpty() ? null : p.entries.get(p.selected);
                if (e != null && e.reset != null) {
                    e.reset.run();
                    invalidate();
                } else {
                    activate();
                }
                return true;
            }
            case KeyEvent.KEYCODE_BUTTON_A:
                activate();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_X: // el botón de arriba en mandos tipo Nintendo
                // Menú principal con secciones (Legend, Classic, Legacy): Y pasa a la siguiente.
                nextSection();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
                pop();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int idx = -1;
        for (int i = 0; i < hit.size(); i++) {
            if (hit.get(i).contains(ev.getX(), ev.getY())) {
                idx = hitIndex.get(i);
                break;
            }
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touched = idx;
                dragY = ev.getY();
                dragged = false;
                if (idx >= 0) {
                    page().selected = idx;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                // Arrastrar el dedo desplaza la lista una opción por cada alto de línea.
                float dy = ev.getY() - dragY;
                if (Math.abs(dy) >= stepPx && page().entries.size() > MAX_VISIBLE) {
                    Page p = page();
                    p.selected = Math.max(0, Math.min(p.entries.size() - 1, p.selected + (dy < 0 ? 1 : -1)));
                    invalidate();
                    dragY = ev.getY();
                    dragged = true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!dragged && idx < 0 && touched < 0) {
                    if (tabRects[0].contains(ev.getX(), ev.getY())) switchSide(-1);
                    else if (tabRects[1].contains(ev.getX(), ev.getY())) switchSide(1);
                    else if (footerRect.contains(ev.getX(), ev.getY())) nextSection();
                }
                if (!dragged && idx < 0 && touched < 0 && coverRect.contains(ev.getX(), ev.getY())) {
                    Page p = page();
                    Entry e = p.entries.isEmpty() ? null : p.entries.get(p.selected);
                    if (e != null && e.coverTap != null) e.coverTap.run();
                }
                if (!dragged && idx >= 0 && idx == touched) activate();
                touched = -1;
                return true;
            default:
                return true;
        }
    }
}
