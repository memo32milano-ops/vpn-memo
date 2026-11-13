/*
 * VPN Memo Pro - GPLv2 (based on OpenVPN for Android by Arne Schwabe)
 */
package com.pro.vpnmemo;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;
import de.blinkt.openvpn.core.VpnStatus;

public class MainActivity extends Activity implements VpnStatus.StateListener, VpnStatus.ByteCountListener {

    private static final int REQ_VPN = 70;
    private static final int REQ_NOTIF = 71;
    private static final long CONNECT_TIMEOUT_MS = 25_000;
    private static final int MAX_AUTO_TRIES = 5;

    private static final int TAB_FASTEST = 0, TAB_ARAB = 1, TAB_ALL = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private TextView button, status, detail, selectedFlag, selectedName, listInfo;
    private TextView tabFastest, tabArab, tabAll;
    private final ServerAdapter adapter = new ServerAdapter();

    private List<Server> servers = new ArrayList<>();
    private Server chosen;            // null = automatic (fastest)
    private Server current;           // server we are connecting / connected to
    private int tab = TAB_FASTEST;
    private boolean loading;

    // connection attempt state
    private boolean attempting;       // we started a connection and wait for the result
    private boolean userStopped;
    private boolean switching;        // between two automatic attempts
    private int triesLeft;
    private long attemptStart;
    private final Set<String> triedKeys = new HashSet<>();
    private ConnectionStatus lastLevel = ConnectionStatus.LEVEL_NOTCONNECTED;

    private IOpenVPNServiceInternal service;
    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IOpenVPNServiceInternal.Stub.asInterface(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final Runnable timeoutCheck = new Runnable() {
        @Override
        public void run() {
            if (attempting && System.currentTimeMillis() - attemptStart >= CONNECT_TIMEOUT_MS)
                attemptFailed();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.memo_activity_main);

        button = findViewById(R.id.memo_button);
        status = findViewById(R.id.memo_status);
        detail = findViewById(R.id.memo_detail);
        selectedFlag = findViewById(R.id.memo_selected_flag);
        selectedName = findViewById(R.id.memo_selected_name);
        listInfo = findViewById(R.id.memo_list_info);
        tabFastest = findViewById(R.id.memo_tab_fastest);
        tabArab = findViewById(R.id.memo_tab_arab);
        tabAll = findViewById(R.id.memo_tab_all);
        ListView list = findViewById(R.id.memo_list);
        list.setAdapter(adapter);

        list.setOnItemClickListener((p, v, pos, id) -> {
            Object item = adapter.getItem(pos);
            if (item instanceof Server) {
                chosen = (Server) item;
                updateSelected();
                adapter.notifyDataSetChanged();
                if (isConnectedOrConnecting()) {
                    // switch server right away
                    startConnect();
                }
            }
        });
        findViewById(R.id.memo_selected_card).setOnClickListener(v -> {
            chosen = null;
            updateSelected();
            adapter.notifyDataSetChanged();
        });

        button.setOnClickListener(v -> {
            if (isConnectedOrConnecting()) disconnect();
            else startConnect();
        });
        findViewById(R.id.memo_refresh).setOnClickListener(v -> loadServers(true));
        findViewById(R.id.memo_info).setOnClickListener(v -> startActivity(new Intent(this, InfoActivity.class)));
        tabFastest.setOnClickListener(v -> setTab(TAB_FASTEST));
        tabArab.setOnClickListener(v -> setTab(TAB_ARAB));
        tabAll.setOnClickListener(v -> setTab(TAB_ALL));
        setTab(getPrefs().getInt("tab", TAB_FASTEST));

        updateSelected();
        renderState(ConnectionStatus.LEVEL_NOTCONNECTED);
        loadServers(false);
        askNotificationPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, OpenVPNService.class);
        intent.setAction(OpenVPNService.START_SERVICE);
        bindService(intent, conn, Context.BIND_AUTO_CREATE);
        VpnStatus.addStateListener(this);
        VpnStatus.addByteCountListener(this);
    }

    @Override
    protected void onStop() {
        VpnStatus.removeStateListener(this);
        VpnStatus.removeByteCountListener(this);
        try {
            unbindService(conn);
        } catch (Exception ignored) {
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        bg.shutdownNow();
        super.onDestroy();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("memo", MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- servers

    private void loadServers(boolean force) {
        if (loading) return;
        loading = true;
        listInfo.setText(R.string.memo_loading);
        bg.execute(() -> {
            try {
                List<Server> result = new ServerRepository(this).load(force, (checked, total) ->
                        ui.post(() -> listInfo.setText(getString(R.string.memo_checking, total))));
                ui.post(() -> {
                    loading = false;
                    servers = result;
                    if (chosen != null) {
                        // keep the user's choice if it is still alive
                        Server keep = null;
                        for (Server s : servers)
                            if (s.key().equals(chosen.key())) keep = s;
                        chosen = keep;
                        updateSelected();
                    }
                    Set<String> countries = new HashSet<>();
                    for (Server s : servers) countries.add(s.countryCode);
                    listInfo.setText(getString(R.string.memo_servers_ready, servers.size(), countries.size()));
                    refreshList();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    loading = false;
                    listInfo.setText(R.string.memo_load_failed);
                });
            }
        });
    }

    private void setTab(int t) {
        tab = t;
        getPrefs().edit().putInt("tab", t).apply();
        tabFastest.setSelected(t == TAB_FASTEST);
        tabArab.setSelected(t == TAB_ARAB);
        tabAll.setSelected(t == TAB_ALL);
        refreshList();
    }

    private void refreshList() {
        List<Object> rows = new ArrayList<>();
        if (tab == TAB_FASTEST) {
            for (int i = 0; i < servers.size() && i < 25; i++) rows.add(servers.get(i));
        } else if (tab == TAB_ARAB) {
            for (Server s : servers) if (s.isArab()) rows.add(s);
            if (rows.isEmpty() && !servers.isEmpty()) rows.add(getString(R.string.memo_no_arab));
        } else {
            // best server of every country first, sorted by country name, then the rest
            List<Server> sorted = new ArrayList<>(servers);
            java.util.Collections.sort(sorted, (a, b) -> {
                int c = a.countryName().compareToIgnoreCase(b.countryName());
                return c != 0 ? c : Double.compare(a.rank(), b.rank());
            });
            rows.addAll(sorted);
        }
        adapter.setRows(rows);
    }

    private void updateSelected() {
        if (chosen == null) {
            selectedFlag.setText("⚡");
            selectedName.setText(R.string.memo_auto);
        } else {
            selectedFlag.setText(chosen.flag());
            selectedName.setText(chosen.countryName());
        }
    }

    // ---------------------------------------------------------------- connect

    private boolean isConnectedOrConnecting() {
        return attempting || switching || lastLevel == ConnectionStatus.LEVEL_CONNECTED
                || lastLevel == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED
                || lastLevel == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET
                || lastLevel == ConnectionStatus.LEVEL_START;
    }

    private void startConnect() {
        if (servers.isEmpty()) {
            loadServers(true);
            Toast.makeText(this, R.string.memo_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            try {
                startActivityForResult(prepare, REQ_VPN);
            } catch (Exception e) {
                Toast.makeText(this, R.string.memo_permission_denied, Toast.LENGTH_LONG).show();
            }
            return;
        }
        userStopped = false;
        triedKeys.clear();
        triesLeft = chosen != null ? 1 + MAX_AUTO_TRIES : MAX_AUTO_TRIES;
        connectNext();
    }

    private Server pickNext() {
        if (chosen != null && !triedKeys.contains(chosen.key())) return chosen;
        // automatic: best alive server we did not try yet; prefer same country as the chosen one
        if (chosen != null) {
            for (Server s : servers)
                if (s.isAlive() && !triedKeys.contains(s.key()) && s.countryCode.equals(chosen.countryCode))
                    return s;
        }
        for (Server s : servers)
            if (s.isAlive() && !triedKeys.contains(s.key())) return s;
        return null;
    }

    private void connectNext() {
        if (userStopped) {
            switching = false;
            return;
        }
        switching = false;
        Server s = pickNext();
        if (s == null || triesLeft <= 0) {
            attempting = false;
            detail.setText(R.string.memo_all_failed);
            renderState(ConnectionStatus.LEVEL_NOTCONNECTED);
            return;
        }
        triesLeft--;
        triedKeys.add(s.key());
        current = s;
        try {
            ConfigParser cp = new ConfigParser();
            cp.parseConfig(new StringReader(s.ovpnConfig));
            VpnProfile profile = cp.convertProfile();
            profile.mName = s.countryName() + " · " + s.hostName;
            // VPN Gate servers negotiate AES-128-CBC; modern clients need it listed explicitly.
            profile.mDataCiphers = "AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-128-CBC:AES-256-CBC";
            profile.mTlSCertProfile = "legacy";
            ProfileManager.setTemporaryProfile(this, profile);
            attempting = true;
            attemptStart = System.currentTimeMillis();
            renderState(ConnectionStatus.LEVEL_START);
            detail.setText(s.flag() + "  " + s.countryName());
            VPNLaunchHelper.startOpenVpn(profile, getBaseContext(), "memo", true);
            ui.removeCallbacks(timeoutCheck);
            ui.postDelayed(timeoutCheck, CONNECT_TIMEOUT_MS);
        } catch (Exception e) {
            s.failedThisSession = true;
            connectNext();
        }
    }

    private void attemptFailed() {
        if (!attempting || userStopped) return;
        ui.removeCallbacks(timeoutCheck);
        if (current != null) current.failedThisSession = true;
        attempting = false;
        switching = true;
        stopService();
        detail.setText(R.string.memo_trying_next);
        // give the old tunnel a moment to close
        ui.postDelayed(this::connectNext, 1200);
    }

    private void disconnect() {
        userStopped = true;
        attempting = false;
        switching = false;
        ui.removeCallbacks(timeoutCheck);
        ProfileManager.setConnectedVpnProfileDisconnected(this);
        stopService();
        detail.setText("");
        renderState(ConnectionStatus.LEVEL_NOTCONNECTED);
    }

    private void stopService() {
        if (service != null) {
            try {
                service.stopVPN(false);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startConnect();
            else Toast.makeText(this, R.string.memo_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPrefs().getBoolean("asked_notif", false)) {
            getPrefs().edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    // ---------------------------------------------------------------- status from the VPN process

    @Override
    public void updateState(String state, String logmessage, int localizedResId, ConnectionStatus level, Intent intent) {
        ui.post(() -> onLevel(level));
    }

    @Override
    public void setConnectedVPN(String uuid) {
    }

    private void onLevel(ConnectionStatus level) {
        lastLevel = level;
        if (level == ConnectionStatus.LEVEL_CONNECTED) {
            attempting = false;
            ui.removeCallbacks(timeoutCheck);
            if (current != null) detail.setText(current.flag() + "  " + current.countryName());
        } else if (attempting && !userStopped) {
            boolean failedState = level == ConnectionStatus.LEVEL_AUTH_FAILED
                    || (level == ConnectionStatus.LEVEL_NOTCONNECTED
                    && System.currentTimeMillis() - attemptStart > 3000);
            if (failedState) {
                attemptFailed();
                return;
            }
        }
        renderState((attempting || switching) && level == ConnectionStatus.LEVEL_NOTCONNECTED
                ? ConnectionStatus.LEVEL_START : level);
    }

    private void renderState(ConnectionStatus level) {
        switch (level) {
            case LEVEL_CONNECTED:
                button.setBackgroundResource(R.drawable.memo_button_on);
                button.setText(R.string.memo_disconnect);
                status.setText(R.string.memo_connected);
                status.setTextColor(getColor(R.color.memo_green));
                break;
            case LEVEL_START:
            case LEVEL_CONNECTING_SERVER_REPLIED:
            case LEVEL_CONNECTING_NO_SERVER_REPLY_YET:
            case LEVEL_WAITING_FOR_USER_INPUT:
                button.setBackgroundResource(R.drawable.memo_button_connecting);
                button.setText(R.string.memo_disconnect);
                status.setText(R.string.memo_connecting);
                status.setTextColor(getColor(R.color.memo_orange));
                break;
            default:
                button.setBackgroundResource(R.drawable.memo_button_off);
                button.setText(R.string.memo_connect);
                status.setText(R.string.memo_disconnected);
                status.setTextColor(getColor(R.color.memo_text));
        }
    }

    @Override
    public void updateByteCount(long in, long out, long diffIn, long diffOut) {
        ui.post(() -> {
            if (lastLevel == ConnectionStatus.LEVEL_CONNECTED && current != null) {
                detail.setText(current.flag() + "  " + current.countryName() + "\n"
                        + getString(R.string.memo_traffic,
                        Formatter.formatShortFileSize(this, in),
                        Formatter.formatShortFileSize(this, out)));
            }
        });
    }

    // ---------------------------------------------------------------- list adapter

    private class ServerAdapter extends BaseAdapter {
        private List<Object> rows = new ArrayList<>();

        void setRows(List<Object> r) {
            rows = r;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null)
                v = LayoutInflater.from(parent.getContext()).inflate(R.layout.memo_item_server, parent, false);
            TextView flag = v.findViewById(R.id.memo_item_flag);
            TextView country = v.findViewById(R.id.memo_item_country);
            TextView sub = v.findViewById(R.id.memo_item_sub);
            TextView ping = v.findViewById(R.id.memo_item_ping);
            Object o = rows.get(position);
            if (o instanceof String) {
                flag.setText("ℹ");
                country.setText((String) o);
                country.setTextSize(13);
                sub.setText("");
                ping.setText("");
                v.setBackgroundResource(R.drawable.memo_card_bg);
                return v;
            }
            Server s = (Server) o;
            country.setTextSize(15);
            flag.setText(s.flag());
            country.setText(s.countryName());
            sub.setText(getString(R.string.memo_speed, s.speedMbps()) + "  ·  " + s.proto.toUpperCase());
            ping.setText(getString(R.string.memo_ping, s.probeMs));
            ping.setTextColor(getColor(s.probeMs < 150 ? R.color.memo_green
                    : s.probeMs < 400 ? R.color.memo_orange : R.color.memo_red));
            boolean sel = chosen != null && chosen.key().equals(s.key());
            v.setBackgroundColor(0);
            v.setBackgroundResource(sel ? R.drawable.memo_card_selected_bg : R.drawable.memo_card_bg);
            return v;
        }
    }
}
