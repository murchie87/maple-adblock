package com.murchie.adblock;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;

public class MainActivity extends Activity {

    private static final int REQ_VPN = 1001;

    private Button toggleBtn;
    private TextView statusView;
    private TextView statsView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Maple Adblock");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Device-wide ad blocking (72k domains)");
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        toggleBtn = new Button(this);
        toggleBtn.setTextSize(20);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = pad;
        root.addView(toggleBtn, bp);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = pad;
        root.addView(statusView, sp);

        statsView = new TextView(this);
        statsView.setTextSize(14);
        statsView.setGravity(Gravity.CENTER);
        root.addView(statsView, sp);

        TextView note = new TextView(this);
        note.setText("Blocks ad/tracker domains. Doesn't block YouTube or Twitch video ads — those come from the same servers as the videos themselves.");
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wp.topMargin = pad * 2;
        root.addView(note, wp);

        Button donateBtn = new Button(this);
        donateBtn.setText("Donate");
        donateBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.paypal.com/ncp/payment/3WUS6NU6GH9SL"));
            startActivity(i);
        });
        root.addView(donateBtn, bp);

        setContentView(root);

        toggleBtn.setOnClickListener(v -> {
            if (AdBlockVpnService.isRunning) {
                Intent stop = new Intent(this, AdBlockVpnService.class);
                stop.setAction(AdBlockVpnService.ACTION_STOP);
                startService(stop);
            } else {
                Intent prep = VpnService.prepare(this);
                if (prep != null) {
                    startActivityForResult(prep, REQ_VPN);
                } else {
                    startVpn();
                }
            }
            refreshSoon();
        });
    }

    private void startVpn() {
        Intent i = new Intent(this, AdBlockVpnService.class);
        startForegroundService(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            startVpn();
        }
        refreshSoon();
    }

    private void refreshSoon() {
        handler.postDelayed(this::refresh, 400);
    }

    private void refresh() {
        boolean on = AdBlockVpnService.isRunning;
        toggleBtn.setText(on ? "Turn OFF" : "Turn ON");
        statusView.setText(AdBlockVpnService.statusText);
        if (AdBlockVpnService.blocklistSize > 0) {
            statsView.setText("Blocked: " + AdBlockVpnService.blockedCount
                    + "   Passed: " + AdBlockVpnService.allowedCount);
        } else {
            statsView.setText(on ? "Loading blocklist..." : "72,289 ad domains ready");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        handler.post(poller);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poller);
    }
}
