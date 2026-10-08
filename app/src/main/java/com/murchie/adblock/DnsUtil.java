package com.murchie.adblock;

import java.util.Locale;

/** Minimal IPv4/UDP/DNS packet helpers for the DNS-filter VPN. */
public final class DnsUtil {
    private DnsUtil() {}

    public static final int IP_PROTO_UDP = 17;

    /** Parsed inbound DNS packet. Null if not IPv4/UDP. */
    public static class DnsPacket {
        public byte[] srcIp = new byte[4];
        public byte[] dstIp = new byte[4];
        public int srcPort;
        public int dstPort;
        public byte[] dnsPayload;
        public int dnsLen;
    }

    public static DnsPacket parse(byte[] buf, int len) {
        if (len < 28) return null;
        int verIhl = buf[0] & 0xFF;
        if ((verIhl >> 4) != 4) return null;
        int ihl = (verIhl & 0x0F) * 4;
        if (ihl < 20 || len < ihl + 8) return null;
        if ((buf[9] & 0xFF) != IP_PROTO_UDP) return null;
        DnsPacket p = new DnsPacket();
        System.arraycopy(buf, 12, p.srcIp, 0, 4);
        System.arraycopy(buf, 16, p.dstIp, 0, 4);
        p.srcPort = ((buf[ihl] & 0xFF) << 8) | (buf[ihl + 1] & 0xFF);
        p.dstPort = ((buf[ihl + 2] & 0xFF) << 8) | (buf[ihl + 3] & 0xFF);
        int udpLen = ((buf[ihl + 4] & 0xFF) << 8) | (buf[ihl + 5] & 0xFF);
        int dnsOff = ihl + 8;
        int dnsLen = Math.min(udpLen - 8, len - dnsOff);
        if (dnsLen < 12) return null;
        p.dnsPayload = new byte[dnsLen];
        System.arraycopy(buf, dnsOff, p.dnsPayload, 0, dnsLen);
        p.dnsLen = dnsLen;
        return p;
    }

    /** Extract the QNAME of the first question, lowercased. Null if malformed. */
    public static String parseQname(byte[] dns, int dnsLen) {
        if (dnsLen < 13) return null;
        StringBuilder sb = new StringBuilder();
        int pos = 12;
        while (pos < dnsLen) {
            int labelLen = dns[pos++] & 0xFF;
            if (labelLen == 0) break;
            if ((labelLen & 0xC0) == 0xC0) return null; // compressed: not expected in queries
            if (labelLen > 63 || pos + labelLen > dnsLen) return null;
            if (sb.length() > 0) sb.append('.');
            for (int i = 0; i < labelLen; i++) {
                char c = (char) (dns[pos + i] & 0xFF);
                sb.append(c);
                if (sb.length() > 253) return null;
            }
            pos += labelLen;
        }
        if (sb.length() == 0) return null;
        return sb.toString().toLowerCase(Locale.US);
    }

    public static int txnId(byte[] dns) {
        return ((dns[0] & 0xFF) << 8) | (dns[1] & 0xFF);
    }

    /** Build an NXDOMAIN response for the given query. */
    public static byte[] buildNxDomain(byte[] query, int queryLen) {
        int pos = 12;
        int qdcount = ((query[4] & 0xFF) << 8) | (query[5] & 0xFF);
        for (int q = 0; q < qdcount && pos < queryLen; q++) {
            while (pos < queryLen && query[pos] != 0) {
                int l = query[pos] & 0xFF;
                if ((l & 0xC0) == 0xC0) { pos += 2; break; }
                pos += 1 + l;
            }
            if (pos < queryLen && query[pos] == 0) pos++;
            pos += 4; // QTYPE + QCLASS
        }
        int questionLen = Math.max(0, Math.min(pos, queryLen) - 12);
        byte[] resp = new byte[12 + questionLen];
        resp[0] = query[0];
        resp[1] = query[1];
        int rd = query[2] & 0x01;
        resp[2] = (byte) 0x80;           // QR=1
        resp[3] = (byte) (rd | 0x03);    // RCODE=3 (NXDOMAIN)
        resp[4] = 0; resp[5] = 1;        // QDCOUNT=1
        // ANCOUNT=NSCOUNT=ARCOUNT=0
        System.arraycopy(query, 12, resp, 12, questionLen);
        return resp;
    }

    /** Build a full IPv4/UDP packet. UDP checksum left 0 (allowed for IPv4). */
    public static byte[] buildUdpPacket(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort,
                                       byte[] payload, int payloadLen) {
        int totalLen = 20 + 8 + payloadLen;
        byte[] p = new byte[totalLen];
        p[0] = 0x45;
        p[1] = 0;
        p[2] = (byte) (totalLen >> 8);
        p[3] = (byte) totalLen;
        p[4] = 0; p[5] = 0;
        p[6] = 0; p[7] = 0;
        p[8] = 64;
        p[9] = (byte) IP_PROTO_UDP;
        System.arraycopy(srcIp, 0, p, 12, 4);
        System.arraycopy(dstIp, 0, p, 16, 4);
        int csum = ipChecksum(p, 0, 20);
        p[10] = (byte) (csum >> 8);
        p[11] = (byte) csum;
        p[20] = (byte) (srcPort >> 8);
        p[21] = (byte) srcPort;
        p[22] = (byte) (dstPort >> 8);
        p[23] = (byte) dstPort;
        int udpLen = 8 + payloadLen;
        p[24] = (byte) (udpLen >> 8);
        p[25] = (byte) udpLen;
        p[26] = 0; p[27] = 0;
        System.arraycopy(payload, 0, p, 28, payloadLen);
        return p;
    }

    private static int ipChecksum(byte[] buf, int off, int len) {
        long sum = 0;
        for (int i = 0; i < len; i += 2) {
            sum += ((buf[off + i] & 0xFF) << 8) | (buf[off + i + 1] & 0xFF);
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum) & 0xFFFF;
    }
}
