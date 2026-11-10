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
        int selected;

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

    void pop() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            invalidate();
        } else if (onBackFromRoot != null) {
            onBackFromRoot.run();
        }
    }

    private Page page() {
        return stack.get(stack.size() - 1);
    }

    // ---- Dibujo ----

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || stack.isEmpty()) return;

        // Fondo: la imagen propia cubriendo toda la pantalla, o la pantalla de título del juego.
        if (background != null) {
            float k = Math.max(w / (float) background.getWidth(), h / (float) background.getHeight());
            float bw = background.getWidth() * k, bh = background.getHeight() * k;
            dst.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f);
            c.drawBitmap(background, null, dst, smooth);
        }
        Bitmap bg = background != null ? null : art.tile(DukeArt.BETASCREEN);
        if (bg == null && background == null) bg = art.tile(DukeArt.MENUSCREEN);
        if (bg != null) {
            float bw = w, bh = w * 3f / 4f;
            if (bh < h) {
                bh = h;
                bw = h * 4f / 3f;
            }
            dst.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f);
            c.drawBitmap(bg, null, dst, pixel);
        }
        c.drawRect(0, 0, w, h, dim);

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

        // Logo.
        if (art.has(DukeArt.INGAMEDUKETHREEDEE)) {
            drawTile(c, DukeArt.INGAMEDUKETHREEDEE, 160, 30, 1f, null);
        } else if (art.has(DukeArt.DUKENUKEM)) {
            drawTile(c, DukeArt.DUKENUKEM, 160, 28, 0.6f, null);
        }

        Page p = page();
        int n = p.entries.size();
        // Con cajas, la lista va a la izquierda y la caja del juego elegido a la derecha.
        boolean covers = p.hasCovers();
        float centerX = covers ? 108 : 160;
        float maxW = covers ? 196 : 270;
        if (covers) {
            // Solo con un juego elegido (en Opciones o Salir no se ve ninguna caja).
            Entry cur = n > 0 ? p.entries.get(p.selected) : null;
            if (cur != null && cur.cover != null) drawCover(c, cur.cover, 214, 50, 98, 136);
        }
        float top = 60, bottom = 182;
        float step = Math.min(19f, (bottom - top) / Math.max(1, n));
        float textScale = step >= 17 ? 1f : step / 17f;
        long now = SystemClock.uptimeMillis();
        int frame = (int) ((now / 66) % 7);

        hit.clear();
        for (int i = 0; i < n; i++) {
            Entry e = p.entries.get(i);
            boolean sel = i == p.selected;
            float cy = top + step * i + step / 2f;
            String text = e.value != null ? e.label + ": " + e.value.get() : e.label;

            float scale = textScale * (e.value != null ? 0.8f : 1f);
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
            LightingColorFilter lf = new LightingColorFilter(Color.rgb(lvl, lvl, lvl), 0);
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
            hit.add(new RectF(0, vy(cy - step / 2f), covers ? vx(206) : w, vy(cy + step / 2f)));
        }

        // Pie: ayuda de botones y, si la hay, una línea de información.
        if (p.footer != null) drawSmall(c, p.footer.get(), 160, 186, 0.55f, true);
        Entry cur = n > 0 ? p.entries.get(p.selected) : null;
        drawSmall(c, I18n.t(cur != null && cur.value != null ? "hint_value" : "hint"), 160, 193, 0.7f, true);

        postInvalidateDelayed(50);
    }

    /** La caja entera dentro del rectángulo (x, y, w, h) del 320x200, con marco. */
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
        dst.set(l, t, l + dw, t + dh);
        c.drawBitmap(b, null, dst, smooth);
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
    private static int bigTile(char c) {
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
        p.selected = (p.selected + d + n) % n;
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
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                Page p = page();
                if (!p.entries.isEmpty() && p.entries.get(p.selected).value != null) activate();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_START:
                activate();
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
                idx = i;
                break;
            }
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touched = idx;
                if (idx >= 0) {
                    page().selected = idx;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (idx >= 0 && idx == touched) activate();
                touched = -1;
                return true;
            default:
                return true;
        }
    }
}
