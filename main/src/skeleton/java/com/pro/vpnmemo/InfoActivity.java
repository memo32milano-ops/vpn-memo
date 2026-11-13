/*
 * VPN Memo Pro - GPLv2 (based on OpenVPN for Android by Arne Schwabe)
 */
package com.pro.vpnmemo;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import de.blinkt.openvpn.R;

public class InfoActivity extends Activity {
    public static final String PRIVACY_URL =
            "https://github.com/memo32milano-ops/vpn-memo/blob/master/PRIVACY.md";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.memo_activity_info);
        findViewById(R.id.memo_privacy_link).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)));
            } catch (Exception ignored) {
            }
        });
    }
}
