/*
 * VPN Memo Pro - GPLv2 (based on OpenVPN for Android by Arne Schwabe)
 */
package com.pro.vpnmemo;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads the public VPN Gate server list and keeps only servers that really answer.
 * Nothing about the user is sent anywhere: the list is a plain public download.
 */
public class ServerRepository {

    public interface Progress {
        void onProgress(int checked, int total);
    }

    private static final String[] LIST_URLS = {
            "https://www.vpngate.net/api/iphone/",
            "http://www.vpngate.net/api/iphone/"
    };
    private static final String CACHE_FILE = "vpngate_list.csv";
    private static final long CACHE_MAX_AGE_MS = 20 * 60 * 1000L;
    private static final int PROBE_TIMEOUT_MS = 2000;

    private final Context ctx;

    public ServerRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Blocking. Downloads (or reuses a fresh cache), parses and probes. Returns alive servers, best first. */
    public List<Server> load(boolean forceDownload, Progress progress) throws Exception {
        String csv = null;
        File cache = new File(ctx.getCacheDir(), CACHE_FILE);
        if (!forceDownload && cache.exists()
                && System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS) {
            csv = readFile(cache);
        }
        if (csv == null) {
            Exception last = null;
            for (String u : LIST_URLS) {
                try {
                    csv = download(u);
                    if (csv != null && csv.contains("OpenVPN_ConfigData_Base64")) break;
                } catch (Exception e) {
                    last = e;
                }
                csv = null;
            }
            if (csv == null) {
                // offline fallback: an older cache is better than nothing
                if (cache.exists()) csv = readFile(cache);
                else throw last != null ? last : new Exception("empty list");
            } else {
                writeFile(cache, csv);
            }
        }
        List<Server> all = parse(csv);
        probeAll(all, progress);
        List<Server> alive = new ArrayList<>();
        for (Server s : all)
            if (s.isAlive()) alive.add(s);
        sortBest(alive);
        return alive;
    }

    public static void sortBest(List<Server> list) {
        Collections.sort(list, (a, b) -> Double.compare(a.rank(), b.rank()));
    }

    static List<Server> parse(String csv) {
        List<Server> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : csv.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#")) continue;
            String[] f = line.split(",");
            if (f.length < 15) continue;
            try {
                Server s = new Server();
                s.hostName = f[0];
                s.ip = f[1];
                s.score = parseLong(f[2]);
                s.apiPing = (int) parseLong(f[3]);
                s.speedBps = parseLong(f[4]);
                s.countryLong = f[5];
                s.countryCode = f[6].toUpperCase(Locale.ROOT);
                s.sessions = (int) parseLong(f[7]);
                String b64 = f[f.length - 1];
                s.ovpnConfig = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
                if (!readRemote(s)) continue;
                if (seen.add(s.key())) out.add(s);
            } catch (Exception ignored) {
                // skip malformed entries
            }
        }
        return out;
    }

    private static boolean readRemote(Server s) {
        for (String raw : s.ovpnConfig.split("\n")) {
            String l = raw.trim();
            if (l.startsWith("#") || l.startsWith(";")) continue;
            String[] p = l.split("\\s+");
            if (p.length >= 2 && p[0].equals("proto"))
                s.proto = p[1].startsWith("tcp") ? "tcp" : "udp";
            if (p.length >= 3 && p[0].equals("remote")) {
                s.remoteHost = p[1];
                try {
                    s.remotePort = Integer.parseInt(p[2]);
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return s.remoteHost != null && s.remotePort > 0;
    }

    /**
     * Checks every server in parallel. TCP servers are checked on their OpenVPN port. VPN Gate
     * servers (SoftEther) also listen on TCP 443/992 by default, so UDP servers are
     * checked on those ports to know the machine is up right now.
     */
    private void probeAll(List<Server> list, Progress progress) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(24);
        AtomicInteger done = new AtomicInteger();
        int total = list.size();
        if (progress != null) progress.onProgress(0, total);
        for (Server s : list) {
            pool.execute(() -> {
                s.probeMs = probe(s);
                int d = done.incrementAndGet();
                if (progress != null && (d % 5 == 0 || d == total)) progress.onProgress(d, total);
            });
        }
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        pool.shutdownNow();
    }

    static int probe(Server s) {
        String host = s.remoteHost != null ? s.remoteHost : s.ip;
        int[] ports = "tcp".equals(s.proto)
                ? new int[]{s.remotePort}
                : new int[]{443, 992};
        for (int port : ports) {
            long t0 = System.nanoTime();
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
                return (int) ((System.nanoTime() - t0) / 1_000_000L);
            } catch (Exception ignored) {
                // try next port
            }
        }
        return -1;
    }

    private static String download(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "VPNMemoPro/2.0 (Android)");
        try {
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private static String readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File f, String s) {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
