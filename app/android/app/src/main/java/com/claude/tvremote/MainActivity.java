package com.claude.tvremote;

import android.os.Bundle;
import android.view.KeyEvent;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TvSocketPlugin.class);
        super.onCreate(savedInstanceState);
    }

    // Phone volume keys control the TV while it's connected.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        TvSocketPlugin plugin = TvSocketPlugin.instance;
        if (plugin != null && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
            if (plugin.onVolumeKey(code == KeyEvent.KEYCODE_VOLUME_UP, pressed)) return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
