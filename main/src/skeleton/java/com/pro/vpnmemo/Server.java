/*
 * VPN Memo Pro - GPLv2 (based on OpenVPN for Android by Arne Schwabe)
 */
package com.pro.vpnmemo;

import java.util.Locale;

/** One public VPN Gate server and the result of our own reachability check. */
public class Server {
    public String hostName;
    public String ip;
    public long score;
    public int apiPing;          // ping reported by VPN Gate (ms), 0 if unknown
    public long speedBps;        // line speed reported by VPN Gate
    public String countryLong;
    public String countryCode;   // ISO 3166 alpha-2, upper case
    public int sessions;
    public String ovpnConfig;    // decoded OpenVPN config
    public String proto = "udp";
    public String remoteHost;
    public int remotePort;

    public int probeMs = -1;     // our measured TCP connect time, -1 = not reachable
    public boolean failedThisSession = false;

    public boolean isAlive() {
        return probeMs >= 0 && !failedThisSession;
    }

    public String key() {
        return ip + ":" + remotePort + "/" + proto;
    }

    public String countryName() {
        if (countryCode != null && countryCode.length() == 2) {
            String n = new Locale("", countryCode).getDisplayCountry();
            if (n != null && !n.isEmpty() && !n.equalsIgnoreCase(countryCode))
                return n;
        }
        return countryLong != null ? countryLong : "?";
    }

    public String flag() {
        if (countryCode == null || countryCode.length() != 2) return "🌐";
        String cc = countryCode.toUpperCase(Locale.ROOT);
        int first = Character.codePointAt(cc, 0) - 'A' + 0x1F1E6;
        int second = Character.codePointAt(cc, 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }

    public String speedMbps() {
        double mbps = speedBps / 1_000_000.0;
        if (mbps >= 10) return String.format(Locale.US, "%.0f", mbps);
        return String.format(Locale.US, "%.1f", mbps);
    }

    /** Lower is better. Combines our real measurement with the line speed. */
    public double rank() {
        double latency = probeMs >= 0 ? probeMs : 5000;
        double mbps = Math.max(1, speedBps / 1_000_000.0);
        return latency - Math.min(mbps, 500) * 0.6 + Math.min(sessions, 200) * 0.5;
    }

    private static final String[] ARAB = {"EG", "SA", "AE", "QA", "KW", "BH", "OM", "JO", "LB",
            "SY", "IQ", "PS", "YE", "MA", "DZ", "TN", "LY", "SD", "MR", "SO", "DJ", "KM"};

    public boolean isArab() {
        if (countryCode == null) return false;
        for (String a : ARAB)
            if (a.equalsIgnoreCase(countryCode)) return true;
        return false;
    }
}
