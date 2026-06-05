package com.ldacmeter.app;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_CODE = 100;
    private static final int REFRESH_MS = 2000;

    private static final String ACTION_CODEC_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    private static final String ACTION_CONN_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final String EXTRA_CODEC_STATUS =
            "android.bluetooth.codec.extra.CODEC_STATUS";

    private BluetoothAdapter btAdapter;
    private BluetoothA2dp btA2dp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Codec info captured from broadcast (survives API blocks)
    private BluetoothCodecStatus cachedStatus = null;
    private boolean waitingForReconnect = false;

    private TextView tvStatus, tvDevice, tvCodec, tvBitrate;
    private TextView tvSampleRate, tvBitDepth, tvChannel, tvMode, tvVerdict, tvLog;
    private ProgressBar progressBitrate;
    private Button btnRefresh, btnReconnect;
    private final StringBuilder logBuf = new StringBuilder();

    // ── Broadcast receiver ──────────────────────────────────────────────────
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            addLog("Broadcast: " + action);

            if (ACTION_CODEC_CHANGED.equals(action)) {
                // Primary data source: codec status embedded in the Intent extra.
                // This is the ONLY reliable path on Android 14+ for non-system apps.
                try {
                    BluetoothCodecStatus s =
                            intent.getParcelableExtra(EXTRA_CODEC_STATUS, BluetoothCodecStatus.class);
                    if (s == null) {
                        // Fallback for older API
                        //noinspection deprecation
                        s = (BluetoothCodecStatus) intent.getParcelableExtra(EXTRA_CODEC_STATUS);
                    }
                    if (s != null) {
                        cachedStatus = s;
                        waitingForReconnect = false;
                        addLog("Codec from broadcast: "
                                + codecName(s.getCodecConfig().getCodecType())
                                + " s1=" + s.getCodecConfig().getCodecSpecific1());
                    } else {
                        addLog("Broadcast extra CODEC_STATUS is null");
                    }
                } catch (Exception e) {
                    addLog("Broadcast parse error: " + e.getMessage());
                }
                refreshCodecInfo();

            } else if (ACTION_CONN_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                addLog("A2DP → " + stateStr(state));
                if (state == BluetoothProfile.STATE_DISCONNECTED) cachedStatus = null;
                if (state == BluetoothProfile.STATE_CONNECTED) waitingForReconnect = false;
                refreshCodecInfo();
            }
        }
    };

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                btA2dp = (BluetoothA2dp) proxy;
                addLog("A2DP service ready");
                refreshCodecInfo();
            }
        }
        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) { btA2dp = null; }
        }
    };

    private final Runnable periodicRefresh = new Runnable() {
        @Override public void run() {
            refreshCodecInfo();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    // ── Lifecycle ───────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus     = findViewById(R.id.tv_status);
        tvDevice     = findViewById(R.id.tv_device_name);
        tvCodec      = findViewById(R.id.tv_codec);
        tvBitrate    = findViewById(R.id.tv_bitrate);
        tvSampleRate = findViewById(R.id.tv_sample_rate);
        tvBitDepth   = findViewById(R.id.tv_bit_depth);
        tvChannel    = findViewById(R.id.tv_channel_mode);
        tvMode       = findViewById(R.id.tv_quality_mode);
        tvVerdict    = findViewById(R.id.tv_verdict);
        tvLog        = findViewById(R.id.tv_log);
        progressBitrate = findViewById(R.id.progress_bitrate);
        btnRefresh   = findViewById(R.id.btn_refresh);
        btnReconnect = findViewById(R.id.btn_reconnect);

        btnRefresh.setOnClickListener(v -> {
            addLog("Manual refresh");
            cachedStatus = null;
            refreshCodecInfo();
        });

        btnReconnect.setOnClickListener(v -> {
            waitingForReconnect = true;
            tvVerdict.setText("Đang chờ kết nối lại...\n\nHãy ngắt kết nối tai nghe khỏi Bluetooth,\nrồi kết nối lại — app sẽ tự động hiển thị.");
            tvVerdict.setTextColor(Color.parseColor("#2979FF"));
            tvCodec.setText("Đang chờ...");
            addLog("Waiting for reconnect — will capture from broadcast");
        });

        unlockHiddenApis();
        checkPermsAndInit();
    }

    // ── Hidden API unlock ───────────────────────────────────────────────────
    private void unlockHiddenApis() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            HiddenApiBypass.addHiddenApiExemptions("L");
            addLog("HiddenApiBypass OK");
        } catch (Throwable t) {
            addLog("HiddenApiBypass err: " + t.getClass().getSimpleName());
        }
    }

    // ── Permissions ─────────────────────────────────────────────────────────
    private void checkPermsAndInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] perms = {Manifest.permission.BLUETOOTH_CONNECT,
                              Manifest.permission.BLUETOOTH_SCAN};
            boolean need = false;
            for (String p : perms)
                if (ContextCompat.checkSelfPermission(this, p)
                        != PackageManager.PERMISSION_GRANTED) need = true;
            if (need) { ActivityCompat.requestPermissions(this, perms, PERM_CODE); return; }
        }
        initBluetooth();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == PERM_CODE) {
            boolean ok = true;
            for (int x : r) if (x != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) initBluetooth();
            else { tvStatus.setText("Cần cấp quyền Bluetooth!"); tvStatus.setTextColor(Color.RED); }
        }
    }

    // ── Bluetooth init ──────────────────────────────────────────────────────
    private void initBluetooth() {
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) { tvStatus.setText("Không hỗ trợ Bluetooth"); return; }
        if (!btAdapter.isEnabled()) { tvStatus.setText("Hãy bật Bluetooth"); return; }

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_CODEC_CHANGED);
        f.addAction(ACTION_CONN_CHANGED);

        // Android 14+ (API 34) requires RECEIVER_EXPORTED to receive system broadcasts.
        // Without this flag, the broadcast is silently dropped — this was the root cause
        // of missing ACTION_CODEC_CONFIG_CHANGED on Android 14/15/16.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }

        btAdapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP);
        addLog("BT init OK — API " + Build.VERSION.SDK_INT);
        handler.postDelayed(periodicRefresh, REFRESH_MS);
        tryReadSettingsCodec();
    }

    // ── Main refresh ────────────────────────────────────────────────────────
    private void refreshCodecInfo() {
        if (btAdapter == null || !btAdapter.isEnabled()) {
            tvStatus.setText("Bluetooth chưa bật");
            tvStatus.setTextColor(Color.parseColor("#FF6600")); return;
        }
        if (btA2dp == null) {
            tvStatus.setText("Đang kết nối A2DP...");
            tvStatus.setTextColor(Color.GRAY); return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setText("Thiếu quyền BLUETOOTH_CONNECT"); return;
        }

        List<BluetoothDevice> devices = btA2dp.getConnectedDevices();
        if (devices.isEmpty()) {
            tvStatus.setText("Chưa kết nối tai nghe");
            tvStatus.setTextColor(Color.parseColor("#FF6600"));
            resetFields();
            tvVerdict.setText("Kết nối tai nghe Bluetooth rồi mở nhạc để đo.");
            tvVerdict.setTextColor(Color.GRAY);
            cachedStatus = null;
            addLog("No A2DP device");
            return;
        }

        BluetoothDevice device = devices.get(0);
        tvStatus.setText("Đã kết nối");
        tvStatus.setTextColor(Color.parseColor("#00C853"));
        tvDevice.setText(device.getName() != null ? device.getName() : device.getAddress());

        if (waitingForReconnect) return; // user pressed reconnect, waiting

        // Check if audio streaming
        boolean playing = false;
        try { playing = btA2dp.isA2dpPlaying(device); } catch (Exception e) { /* ignore */ }
        addLog("A2DP playing: " + playing);

        if (!playing) {
            tvCodec.setText("Chờ phát nhạc...");
            tvBitrate.setText("—"); tvMode.setText("—");
            progressBitrate.setProgress(0);
            tvVerdict.setText("Tai nghe đã kết nối!\nMở app nhạc và phát nhạc để đo.");
            tvVerdict.setTextColor(Color.parseColor("#2979FF"));
            return;
        }

        // Strategy 1: reflection (works pre-Android 14, blocked after)
        BluetoothCodecStatus status = getCodecStatusReflection(device);

        // Strategy 2: use cached broadcast result
        if (status == null && cachedStatus != null) {
            addLog("Using cached broadcast status");
            status = cachedStatus;
        }

        if (status != null) {
            btnReconnect.setVisibility(android.view.View.GONE);
            BluetoothCodecConfig cfg = status.getCodecConfig();
            if (cfg == null) { addLog("cfg = null"); return; }
            displayCodecInfo(cfg);
            return;
        }

        // Strategy 3: Settings.Global — stores the codec preference set in Developer Options.
        // Always readable, no special permissions needed.
        if (tryDisplayFromSettings()) return;

        // Strategy 4: async dumpsys — will update UI when result arrives
        tryDumpsysAsync();

        // All live strategies failed — prompt reconnect to trigger a fresh broadcast
        tvCodec.setText("Đang đọc...");
        tvBitrate.setText("—");
        tvMode.setText("—");
        progressBitrate.setProgress(0);
        tvVerdict.setText(
            "Android " + Build.VERSION.SDK_INT + " không cho đọc codec trực tiếp.\n\n" +
            "Cách 1: Nhấn KÍCH HOẠT → ngắt kết nối tai nghe → kết nối lại\n" +
            "Cách 2: Vào Settings → Developer options → chọn LDAC tại \"Bluetooth audio codec\"\n" +
            "        và \"LDAC quality\" (để app đọc cài đặt)");
        tvVerdict.setTextColor(Color.parseColor("#FF6600"));
        btnReconnect.setVisibility(android.view.View.VISIBLE);
    }

    // ── Codec reading ───────────────────────────────────────────────────────
    private BluetoothCodecStatus getCodecStatusReflection(BluetoothDevice device) {
        try {
            Method m = BluetoothA2dp.class.getDeclaredMethod(
                    "getCodecStatus", BluetoothDevice.class);
            m.setAccessible(true);
            BluetoothCodecStatus s = (BluetoothCodecStatus) m.invoke(btA2dp, device);
            addLog("getCodecStatus: " + (s != null ? "OK" : "null"));
            return s;
        } catch (Exception e) {
            addLog("getCodecStatus: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Strategy 3: read LDAC codec preference from Settings.Global.
     * These keys are written by Developer Options when the user selects a codec/quality,
     * and are always readable by third-party apps without any special permission.
     * Note: shows the configured preference, not the dynamically negotiated bitrate.
     */
    private boolean tryDisplayFromSettings() {
        try {
            android.content.ContentResolver cr = getContentResolver();

            // Key written by AOSP/Samsung Dev Options when user picks LDAC quality
            String ldacQualStr  = Settings.Global.getString(cr, "bluetooth_a2dp_codec_ldac_playback_quality");
            String codecTypeStr = Settings.Global.getString(cr, "bluetooth_a2dp_codec_type");
            String srStr2       = Settings.Global.getString(cr, "bluetooth_a2dp_codec_sample_rate");
            String bitsStr2     = Settings.Global.getString(cr, "bluetooth_a2dp_codec_bits_per_sample");
            addLog("Prefs codec=" + codecTypeStr + " ldacQ=" + ldacQualStr
                    + " sr=" + srStr2 + " bits=" + bitsStr2);

            if (ldacQualStr != null) {
                long s1 = Long.parseLong(ldacQualStr.trim());
                tvCodec.setText("LDAC †");
                showLdac(s1);
                // Append transparency note
                CharSequence existing = tvVerdict.getText();
                tvVerdict.setText(existing
                    + "\n\n† Cài đặt Developer Options (không phải đo trực tiếp).\n"
                    + "Trên Android 16 không thể đọc bitrate thực — giá trị trên là bạn đã chọn.");
                btnReconnect.setVisibility(android.view.View.GONE);
                return true;
            }

            // Fallback: at least show the selected codec type
            if (codecTypeStr != null) {
                try {
                    int ct = Integer.parseInt(codecTypeStr.trim());
                    if (ct == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
                        tvCodec.setText("LDAC †");
                        tvBitrate.setText("—");
                        tvMode.setText("Chưa cài chất lượng");
                        tvVerdict.setText(
                            "LDAC đang được dùng (từ cài đặt).\n\n" +
                            "Vào Developer Options → \"LDAC quality\" → chọn\n" +
                            "\"Optimize for audio quality\" để xem 990 kbps.");
                        tvVerdict.setTextColor(Color.parseColor("#2979FF"));
                        btnReconnect.setVisibility(android.view.View.GONE);
                        return true;
                    }
                    if (ct > 0) {
                        tvCodec.setText(codecName(ct) + " †");
                        tvVerdict.setText("Đang dùng " + codecName(ct)
                            + " (từ cài đặt).\nChuyển sang LDAC trong Developer Options.");
                        tvVerdict.setTextColor(Color.parseColor("#FF6600"));
                        return true;
                    }
                } catch (NumberFormatException ignored) {}
            }

            return false;
        } catch (Exception e) {
            addLog("Settings fallback: " + e.getMessage());
            return false;
        }
    }

    private void tryReadSettingsCodec() {
        // Initial probe — results logged and used by tryDisplayFromSettings()
        try {
            String v = Settings.Global.getString(getContentResolver(), "bluetooth_a2dp_codec_type");
            if (v != null) addLog("Settings codec_type = " + v);
        } catch (Exception ignored) {}
    }

    private void tryDumpsysAsync() {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(
                        new String[]{"dumpsys", "bluetooth_manager"});
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream()));
                StringBuilder collected = new StringBuilder();
                String line; int n = 0;
                // Look for mA2dpCodecConfig / CodecSpecific1 sections
                while ((line = br.readLine()) != null && n++ < 4000) {
                    String lo = line.toLowerCase();
                    if (lo.contains("ldac") || lo.contains("codec") ||
                        lo.contains("bitrate") || lo.contains("sbc") ||
                        lo.contains("aptx")   || lo.contains("aac") ||
                        lo.contains("codecspecific") || lo.contains("specific1")) {
                        collected.append(line.trim()).append("\n");
                    }
                }
                p.destroy();
                if (collected.length() == 0) {
                    addLog("dumpsys: no codec lines");
                    return;
                }
                String dump = collected.toString().trim();
                addLog("dumpsys:\n" + dump);
                // Try to extract CodecSpecific1 for LDAC mode
                parseDumpsysAndUpdateUI(dump);
            } catch (Exception e) {
                addLog("dumpsys: " + e.getMessage());
            }
        }).start();
    }

    private void parseDumpsysAndUpdateUI(String dump) {
        try {
            // Look for patterns like: CodecSpecific1: 1000  or  mCodecSpecific1=1000
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                    "(?i)(?:CodecSpecific1|mCodecSpecific1)[=:\\s]+(\\d+)");
            java.util.regex.Matcher m = pat.matcher(dump);
            boolean isLdacDump = dump.toLowerCase().contains("ldac");
            if (m.find() && isLdacDump) {
                long s1 = Long.parseLong(m.group(1));
                addLog("dumpsys LDAC s1=" + s1);
                runOnUiThread(() -> {
                    if (cachedStatus != null) return; // broadcast already gave us data
                    tvCodec.setText("LDAC ‡");
                    showLdac(s1);
                    CharSequence existing = tvVerdict.getText();
                    tvVerdict.setText(existing + "\n\n‡ Nguồn: dumpsys bluetooth_manager");
                    btnReconnect.setVisibility(android.view.View.GONE);
                });
            }
        } catch (Exception e) {
            addLog("dumpsys parse: " + e.getMessage());
        }
    }

    // ── Display ─────────────────────────────────────────────────────────────
    private void displayCodecInfo(BluetoothCodecConfig cfg) {
        int type = cfg.getCodecType();
        int sr   = cfg.getSampleRate();
        int bits = cfg.getBitsPerSample();
        int ch   = cfg.getChannelMode();
        long s1  = cfg.getCodecSpecific1();

        tvCodec.setText(codecName(type));
        tvSampleRate.setText(srStr(sr));
        tvBitDepth.setText(bitsStr(bits));
        tvChannel.setText(chStr(ch));
        addLog(String.format("→ %s SR=%s bits=%s s1=%d", codecName(type), srStr(sr), bitsStr(bits), s1));

        if (type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
            showLdac(s1);
        } else {
            int kbps = estimateKbps(type);
            tvBitrate.setText(kbps > 0 ? kbps + " kbps" : "N/A");
            tvMode.setText("—");
            tvVerdict.setText("Đang dùng " + codecName(type) + " — chưa phải LDAC.\n\n" +
                "Settings → Developer options → Bluetooth audio codec → chọn LDAC.");
            tvVerdict.setTextColor(Color.parseColor("#FF6600"));
            progressBitrate.setProgress(kbps > 0 ? (int)(kbps / 9.9f) : 0);
        }
    }

    private void showLdac(long s1) {
        String mode; int kbps; int color; String verdict;
        if (s1 == 1000 || s1 == 0) {
            mode = "High Quality"; kbps = 990;
            color = Color.parseColor("#00C853");
            verdict = "CHÍNH XÁC! LDAC 990 kbps — tốc độ tối đa!\nChất lượng âm thanh đỉnh cao!";
        } else if (s1 == 1001 || s1 == 1) {
            mode = "Mid Quality"; kbps = 660;
            color = Color.parseColor("#FFD600");
            verdict = "LDAC 660 kbps — mức trung bình.\nDeveloper options → LDAC quality → \"Optimize for audio quality\" để đạt 990 kbps.";
        } else if (s1 == 1002 || s1 == 2) {
            mode = "Low / Connection Priority"; kbps = 330;
            color = Color.parseColor("#FF1744");
            verdict = "LDAC 330 kbps — chỉ ưu tiên kết nối, chất lượng thấp hơn SBC!\nĐổi ngay sang \"Optimize for audio quality\".";
        } else if (s1 == 1003 || s1 == 3) {
            mode = "Adaptive (330–990 kbps)"; kbps = -1;
            color = Color.parseColor("#2979FF");
            verdict = "LDAC Adaptive — tự điều chỉnh bitrate.\nĐể cố định 990 kbps → \"Optimize for audio quality\".";
        } else {
            mode = "LDAC (s1=" + s1 + ")"; kbps = -1;
            color = Color.GRAY;
            verdict = "LDAC hoạt động — không xác định chế độ. s1=" + s1;
        }
        tvMode.setText(mode);
        if (kbps > 0) {
            tvBitrate.setText(kbps + " kbps");
            progressBitrate.setProgress((int)(kbps / 9.9f));
        } else {
            tvBitrate.setText("330–990 kbps");
            progressBitrate.setProgress(66);
        }
        tvVerdict.setText(verdict);
        tvVerdict.setTextColor(color);
    }

    private void resetFields() {
        tvDevice.setText("—"); tvCodec.setText("—"); tvBitrate.setText("—");
        tvMode.setText("—"); tvSampleRate.setText("—"); tvBitDepth.setText("—");
        tvChannel.setText("—"); progressBitrate.setProgress(0);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private int estimateKbps(int t) {
        switch (t) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:     return 328;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:     return 320;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:    return 352;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD: return 576;
            default: return 0;
        }
    }

    private String codecName(int t) {
        switch (t) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:     return "SBC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:     return "AAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:    return "aptX";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD: return "aptX HD";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC:    return "LDAC";
            default: return "Unknown(" + t + ")";
        }
    }

    private String srStr(int sr) {
        switch (sr) {
            case BluetoothCodecConfig.SAMPLE_RATE_44100:  return "44.1 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_48000:  return "48 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_88200:  return "88.2 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_96000:  return "96 kHz";
            default: return "SR(" + sr + ")";
        }
    }

    private String bitsStr(int b) {
        switch (b) {
            case BluetoothCodecConfig.BITS_PER_SAMPLE_16: return "16-bit";
            case BluetoothCodecConfig.BITS_PER_SAMPLE_24: return "24-bit";
            case BluetoothCodecConfig.BITS_PER_SAMPLE_32: return "32-bit";
            default: return "Bits(" + b + ")";
        }
    }

    private String chStr(int c) {
        switch (c) {
            case BluetoothCodecConfig.CHANNEL_MODE_MONO:   return "Mono";
            case BluetoothCodecConfig.CHANNEL_MODE_STEREO: return "Stereo";
            default: return "Ch(" + c + ")";
        }
    }

    private String stateStr(int s) {
        switch (s) {
            case BluetoothProfile.STATE_CONNECTED:     return "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING:    return "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED:  return "DISCONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING: return "DISCONNECTING";
            default: return "(" + s + ")";
        }
    }

    private void addLog(String msg) {
        String ts = android.text.format.DateFormat.format("HH:mm:ss",
                new java.util.Date()).toString();
        logBuf.insert(0, "[" + ts + "] " + msg + "\n");
        if (logBuf.length() > 6000) logBuf.setLength(6000);
        runOnUiThread(() -> tvLog.setText(logBuf.toString()));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(periodicRefresh);
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        if (btAdapter != null && btA2dp != null)
            btAdapter.closeProfileProxy(BluetoothProfile.A2DP, btA2dp);
    }
}
