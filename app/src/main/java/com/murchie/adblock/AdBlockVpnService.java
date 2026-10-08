package com.murchie.adblock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local DNS-filter VPN. Only DNS traffic (to our virtual DNS IP) enters the
 * TUN; everything else bypasses the VPN untouched. Blocked domains get
 * NXDOMAIN, everything else is forwarded to a real upstream resolver.
 */
public class AdBlockVpnService extends VpnService {

    public static final String ACTION_STOP = "com.murchie.adblock.STOP";

    public static volatile boolean isRunning = false;
    public static volatile long blockedCount = 0;
    public static volatile long allowedCount = 0;
    public static volatile int blocklistSize = 0;
    public static volatile String statusText = "Stopped";

    private static final String VPN_IF_ADDRESS = "10.215.173.2";
    private static final String DNS_IP = "10.215.173.1";
    private static final int DNS_PORT = 53;
    private static final String UPSTREAM1 = "1.1.1.1";
    private static final String UPSTREAM2 = "8.8.8.8";
    private static final int NOTIF_ID = 42;
    private static final String CHANNEL_ID = "adblock_status";

    private static class QueryInfo {
        int origTxnId;
        byte[] clientIp = new byte[4];
        int clientPort;
        long when;
    }

    private volatile boolean running = false;
    private ParcelFileDescriptor tun;
    private FileOutputStream tunOut;
    private DatagramSocket upstream;
    private Thread tunThread;
    private Thread upstreamThread;
    private final AtomicInteger nextLocalId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, QueryInfo> pending = new ConcurrentHashMap<>();
    private volatile Set<String> blocklist = Collections.emptySet();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        running = true;
        isRunning = true;
        statusText = "Starting...";
        startForeground(NOTIF_ID, buildNotification("Starting ad blocking..."));

        new Thread(() -> {
            try {
                // 1. Load blocklist (background; queries just pass through until ready)
                loadBlocklist();

                // 2. Upstream socket, protected from our own VPN
                upstream = new DatagramSocket();
                upstream.setSoTimeout(5000);
                if (!protect(upstream)) {
                    statusText = "Error: could not protect socket";
                    stopVpn();
                    return;
                }
                InetSocketAddress upstreamAddr =
                        new InetSocketAddress(InetAddress.getByName(UPSTREAM1), DNS_PORT);

                // 3. TUN interface: only our virtual DNS IP is routed through us
                Builder b = new Builder();
                b.setSession("Maple Adblock");
                b.addAddress(VPN_IF_ADDRESS, 32);
                b.addDnsServer(DNS_IP);
                b.addRoute(DNS_IP, 32);
                b.setMtu(1500);
                if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
                tun = b.establish();
                if (tun == null) {
                    statusText = "Error: VPN permission denied";
                    stopVpn();
                    return;
                }
                tunOut = new FileOutputStream(tun.getFileDescriptor());

                statusText = "Protection ON";
                startForeground(NOTIF_ID, buildNotification(
                        "Blocking ads (" + blocklistSize + " domains)"));

                startTunLoop(upstreamAddr);
                startUpstreamLoop();
            } catch (Exception e) {
                statusText = "Error: " + e.getMessage();
                stopVpn();
            }
        }, "AdBlock-Setup").start();
    }

    private void loadBlocklist() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                getAssets().open("blocklist.txt")))) {
            HashSet<String> set = new HashSet<>(75000);
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) set.add(line);
            }
            blocklist = set;
            blocklistSize = set.size();
        } catch (IOException e) {
            blocklist = Collections.emptySet();
            blocklistSize = 0;
        }
    }

    private void startTunLoop(InetSocketAddress upstreamAddr) {
        tunThread = new Thread(() -> {
            byte[] buf = new byte[8192];
            try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
                while (running) {
                    int len;
                    try {
                        len = in.read(buf);
                    } catch (IOException e) {
                        break;
                    }
                    if (len <= 0) continue;
                    try {
                        handlePacket(buf, len, upstreamAddr);
                    } catch (Exception ignored) {}
                }
            } catch (IOException ignored) {}
        }, "AdBlock-TUN");
        tunThread.start();
    }

    private void handlePacket(byte[] buf, int len, InetSocketAddress upstreamAddr) throws IOException {
        DnsUtil.DnsPacket pkt = DnsUtil.parse(buf, len);
        if (pkt == null || pkt.dstPort != DNS_PORT) return;

        String domain = DnsUtil.parseQname(pkt.dnsPayload, pkt.dnsLen);
        if (domain == null) return; // malformed: drop

        if (isBlocked(domain)) {
            blockedCount++;
            byte[] nx = DnsUtil.buildNxDomain(pkt.dnsPayload, pkt.dnsLen);
            byte[] reply = DnsUtil.buildUdpPacket(pkt.dstIp, pkt.srcIp, DNS_PORT, pkt.srcPort, nx, nx.length);
            synchronized (tunOut) {
                tunOut.write(reply);
            }
            return;
        }

        // Forward to upstream with a locally-unique transaction id
        allowedCount++;
        int localId = nextLocalId.getAndIncrement() & 0xFFFF;
        if (localId == 0) localId = nextLocalId.getAndIncrement() & 0xFFFF;
        QueryInfo qi = new QueryInfo();
        qi.origTxnId = DnsUtil.txnId(pkt.dnsPayload);
        System.arraycopy(pkt.srcIp, 0, qi.clientIp, 0, 4);
        qi.clientPort = pkt.srcPort;
        qi.when = System.currentTimeMillis();
        pending.put(localId, qi);

        byte[] fwd = pkt.dnsPayload.clone();
        fwd[0] = (byte) (localId >> 8);
        fwd[1] = (byte) localId;
        DatagramPacket dp = new DatagramPacket(fwd, fwd.length, upstreamAddr);
        try {
            upstream.send(dp);
        } catch (IOException e) {
            pending.remove(localId);
        }
    }

    private void startUpstreamLoop() {
        upstreamThread = new Thread(() -> {
            byte[] buf = new byte[8192];
            DatagramPacket rp = new DatagramPacket(buf, buf.length);
            byte[] dnsIp = ipToBytes(DNS_IP);
            while (running) {
                try {
                    upstream.receive(rp);
                } catch (IOException e) {
                    sweepPending();
                    continue; // timeout: keep looping
                }
                int rlen = rp.getLength();
                if (rlen < 12) continue;
                int localId = DnsUtil.txnId(buf);
                QueryInfo qi = pending.remove(localId);
                if (qi == null) continue;
                // restore original transaction id
                buf[0] = (byte) (qi.origTxnId >> 8);
                buf[1] = (byte) qi.origTxnId;
                byte[] reply = DnsUtil.buildUdpPacket(dnsIp, qi.clientIp, DNS_PORT, qi.clientPort,
                        buf, rlen);
                try {
                    synchronized (tunOut) {
                        tunOut.write(reply);
                    }
                } catch (IOException ignored) {}
                sweepPending();
            }
        }, "AdBlock-Upstream");
        upstreamThread.start();
    }

    private void sweepPending() {
        long cutoff = System.currentTimeMillis() - 30000;
        for (ConcurrentHashMap.Entry<Integer, QueryInfo> e : pending.entrySet()) {
            if (e.getValue().when < cutoff) pending.remove(e.getKey());
        }
    }

    private boolean isBlocked(String domain) {
        Set<String> bl = blocklist;
        if (bl.isEmpty()) return false;
        String d = domain;
        while (true) {
            if (bl.contains(d)) return true;
            int dot = d.indexOf('.');
            if (dot < 0) return false;
            d = d.substring(dot + 1);
        }
    }

    private static byte[] ipToBytes(String ip) {
        String[] parts = ip.split("\\.");
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = (byte) Integer.parseInt(parts[i]);
        return b;
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "AdBlock status",
                    NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return nb.setContentTitle("Maple Adblock")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void stopVpn() {
        running = false;
        isRunning = false;
        statusText = "Stopped";
        try {
            if (tun != null) tun.close();
        } catch (IOException ignored) {}
        tun = null;
        if (upstream != null) upstream.close();
        if (tunThread != null) tunThread.interrupt();
        if (upstreamThread != null) upstreamThread.interrupt();
        pending.clear();
        stopForeground(true);
    }

    @Override
    public void onRevoke() {
        stopVpn();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
