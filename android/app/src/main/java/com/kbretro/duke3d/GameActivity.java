package com.kbretro.duke3d;

import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.libsdl.app.SDLActivity;

/**
 * Pantalla del juego: EDuke32 dentro de SDLActivity. Va en su propio proceso (":game"), así
 * cada partida arranca limpia y, al salir del juego, se cierra el proceso entero.
 */
public class GameActivity extends SDLActivity {
    public static final String EXTRA_DIR = "dir";
    public static final String EXTRA_ARGS = "args";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // android_main.c entra en esta carpeta antes de arrancar el juego.
        String dir = getIntent().getStringExtra(EXTRA_DIR);
        if (dir != null) {
            try {
                Os.setenv("DUKE3D_DIR", dir, true);
            } catch (ErrnoException ignored) {
            }
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getArguments() {
        String[] args = getIntent().getStringArrayExtra(EXTRA_ARGS);
        return args != null ? args : new String[0];
    }

    /** El botón Atrás del sistema hace de Escape (abre y cierra el menú del juego). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean fromGamepad = (e.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && !fromGamepad) {
            KeyEvent esc = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                    KeyEvent.KEYCODE_ESCAPE, e.getRepeatCount(), e.getMetaState(), e.getDeviceId(),
                    e.getScanCode(), e.getFlags(), InputDevice.SOURCE_KEYBOARD);
            return super.dispatchKeyEvent(esc);
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // EDuke32 no está pensado para arrancar dos veces en el mismo proceso.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
