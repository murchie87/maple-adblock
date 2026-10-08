package com.murchie.adblock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQ_VPN = 1001;

    private static final int BG = 0xFF0E1116;
    private static final int CARD = 0xFF1A2029;
    private static final int RED = 0xFFE63946;
    private static final int GREEN = 0xFF2ECC71;
    private static final int GREY = 0xFF3A4048;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int SUBTEXT = 0xFF9AA0A6;

    private Button toggleBtn;
    private TextView statusView;
    private TextView blockedNum;
    private TextView passedNum;
    private TextView domainsView;

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

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(32), dp(20), dp(32));
        scroll.addView(root,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        // Header
        root.addView(text("\uD83C\uDF41 Maple Adblock", 30, TEXT, true));
        TextView sub = text("Device-wide ad blocking", 15, SUBTEXT, false);
        root.addView(sub);
        setMargins(sub, 0, dp(4), 0, 0);

        // Status card with big toggle
        LinearLayout statusCard = card();
        root.addView(statusCard);
        setMargins(statusCard, 0, dp(24), 0, 0);

        toggleBtn = new Button(this);
        toggleBtn.setTextSize(26);
        toggleBtn.setTypeface(Typeface.DEFAULT_BOLD);
        toggleBtn.setTextColor(TEXT);
        toggleBtn.setStateListAnimator(null);
        toggleBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(176), dp(176)));
        toggleBtn.setOnClickListener(v -> toggleVpn());
        statusCard.addView(toggleBtn);

        statusView = text("", 16, SUBTEXT, false);
        statusCard.addView(statusView);
        setMargins(statusView, 0, dp(12), 0, 0);

        // Stats row
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);
        setMargins(row, 0, dp(16), 0, 0);

        LinearLayout blockedCard = card();
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cp.rightMargin = dp(8);
        blockedCard.setLayoutParams(cp);
        blockedNum = text("0", 26, RED, true);
        blockedCard.addView(blockedNum);
        blockedCard.addView(text("Blocked", 14, SUBTEXT, false));
        row.addView(blockedCard);

        LinearLayout passedCard = card();
        LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cp2.leftMargin = dp(8);
        passedCard.setLayoutParams(cp2);
        passedNum = text("0", 26, GREEN, true);
        passedCard.addView(passedNum);
        passedCard.addView(text("Allowed", 14, SUBTEXT, false));
        row.addView(passedCard);

        // Info card
        LinearLayout infoCard = card();
        root.addView(infoCard);
        setMargins(infoCard, 0, dp(16), 0, 0);
        domainsView = text("", 15, TEXT, true);
        infoCard.addView(domainsView);
        TextView note = text(
                "Doesn't block YouTube or Twitch video ads \u2014 those come from the same servers as the videos themselves.",
                13, SUBTEXT, false);
        infoCard.addView(note);
        setMargins(note, 0, dp(8), 0, 0);

        // Donate
        Button donateBtn = new Button(this);
        donateBtn.setText("Donate");
        donateBtn.setTextSize(18);
        donateBtn.setTextColor(TEXT);
        donateBtn.setStateListAnimator(null);
        GradientDrawable dd = new GradientDrawable();
        dd.setColor(RED);
        dd.setCornerRadius(dp(14));
        donateBtn.setBackground(dd);
        donateBtn.setPadding(0, dp(14), 0, dp(14));
        donateBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.paypal.com/ncp/payment/3WUS6NU6GH9SL"));
            startActivity(i);
        });
        root.addView(donateBtn);
        setMargins(donateBtn, 0, dp(24), 0, 0);

        TextView ver = text("v1.2.0", 12, SUBTEXT, false);
        root.addView(ver);
        setMargins(ver, 0, dp(16), 0, 0);

        refresh();
    }

    private void toggleVpn() {
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
        handler.postDelayed(this::refresh, 400);
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
        handler.postDelayed(this::refresh, 400);
    }

    private void refresh() {
        boolean on = AdBlockVpnService.isRunning;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(on ? GREEN : GREY);
        toggleBtn.setBackground(d);
        toggleBtn.setText(on ? "ON" : "OFF");
        statusView.setText(on ? "Protection is ON" : "Protection is OFF");
        statusView.setTextColor(on ? GREEN : SUBTEXT);
        blockedNum.setText(String.valueOf(AdBlockVpnService.blockedCount));
        passedNum.setText(String.valueOf(AdBlockVpnService.allowedCount));
        int n = AdBlockVpnService.blocklistSize;
        domainsView.setText(n > 0
                ? n + " ad domains in blocklist"
                : (on ? "Loading blocklist..." : "72,288 ad domains ready"));
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

    // ---- UI helpers ----

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        c.setBackground(bg);
        int p = dp(20);
        c.setPadding(p, p, p, p);
        return c;
    }

    private void setMargins(View v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (p == null) {
            p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        p.leftMargin = l;
        p.topMargin = t;
        p.rightMargin = r;
        p.bottomMargin = b;
        v.setLayoutParams(p);
    }
}
