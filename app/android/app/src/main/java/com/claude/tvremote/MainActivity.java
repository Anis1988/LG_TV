package com.claude.tvremote;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TvSocketPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
