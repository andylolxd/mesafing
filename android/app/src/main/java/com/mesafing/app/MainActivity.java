package com.mesafing.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NetScannerPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
