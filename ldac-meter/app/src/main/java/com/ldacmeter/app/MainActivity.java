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

    // Hidden API action strings
    private static final String ACTION_CODEC_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    private static final String ACTION_CONN_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    // Hidden extra key carrying BluetoothCodecStatus in the broadcast
    private static final String EXTRA_CODEC_STATUS =
            "android.bluetooth.codec.extra.CODEC_STATUS";

    private BluetoothAdapter btAdapter;
    private BluetoothA2dp btA2dp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Cache the last codec status received from a broadcast — survives getCodecStatus() null
    private BluetoothCodecStatus cachedCodecStatus = null;

    private TextView tvStatus, tvDevice, tvCodec, tvBitrate;
    private TextView tvSampleRate, tvBitDepth, tvChannel, tvMode, tvVerdict, tvLog;
    private ProgressBar progressBitrate;
    private Button btnRefresh;
    private final StringBuilder logBuf = new StringBuilder();

    // ── Broadcast receiver ──────────────────────────────────────────────────
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_CODEC_CHANGED.equals(action)) {
                // Extract codec status embedded in the broadcast Intent — works even
                // when getCodecStatus() returns null on HUAWEI/HarmonyOS devices.
                try {
                    BluetoothCodecStatus s = intent.getParcelableExtra(EXTRA_CODEC_STATUS);
                    if (s != null) {
                        cachedCodecStatus = s;
                        addLog("Codec from broadcast extra: OK → " +
                                codecName(s.getCodecConfig().getCodecType()));
                    }
                } catch (Exception e) {
                    addLog("Broadcast extra parse error: " + e.getMessage());
                }
                refreshCodecInfo();

            } else if (ACTION_CONN_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                addLog("A2DP state → " + stateStr(state));
                if (state == BluetoothProfile.STATE_DISCONNECTED) cachedCodecStatus = null;
                refreshCodecInfo();
            }
        }
    };

    // ── A2DP profile listener ───────────────────────────────────────────────
    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                btA2dp = (BluetoothA2dp) proxy;
                addLog("A2DP service connected");
                refreshCodecInfo();
            }
        }
        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) {
                btA2dp = null;
                addLog("A2DP service disconnected");
            }
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

        btnRefresh.setOnClickListener(v -> {
            addLog("Manual refresh");
            cachedCodecStatus = null; // clear cache, try fresh
            refreshCodecInfo();
            tryDumpsysAsync();       // also probe dumpsys
            trySystemProps();        // also probe system props
        });

        unlockHiddenApis();
        checkPermsAndInit();
    }

    // ── Hidden API bypass ───────────────────────────────────────────────────
    private void unlockHiddenApis() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            HiddenApiBypass.addHiddenApiExemptions("L");
            addLog("HiddenApiBypass OK");
        } catch (Throwable t) {
            addLog("HiddenApiBypass failed: " + t.getClass().getSimpleName());
            try {
                Class<?> vm = Class.forName("dalvik.system.VMRuntime");
                Method gr = vm.getDeclaredMethod("getRuntime");
                gr.setAccessible(true);
                Object rt = gr.invoke(null);
                Method se = vm.getDeclaredMethod("setHiddenApiExemptions", String[].class);
                se.setAccessible(true);
                se.invoke(rt, new Object[]{new String[]{"L"}});
                addLog("VMRuntime bypass OK");
            } catch (Exception e2) {
                addLog("VMRuntime bypass failed: " + e2.getClass().getSimpleName());
            }
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
        registerReceiver(receiver, f);
        btAdapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP);
        addLog("Bluetooth init OK");
        handler.postDelayed(periodicRefresh, REFRESH_MS);
    }

    // ── Main refresh logic ──────────────────────────────────────────────────
    private void refreshCodecInfo() {
        if (btAdapter == null || !btAdapter.isEnabled()) {
            tvStatus.setText("Bluetooth chưa bật"); tvStatus.setTextColor(Color.parseColor("#FF6600")); return;
        }
        if (btA2dp == null) {
            tvStatus.setText("Đang kết nối A2DP..."); tvStatus.setTextColor(Color.GRAY); return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setText("Thiếu quyền BLUETOOTH_CONNECT"); return;
        }

        List<BluetoothDevice> devices = btA2dp.getConnectedDevices();
        if (devices.isEmpty()) {
            tvStatus.setText("Chưa kết nối tai nghe"); tvStatus.setTextColor(Color.parseColor("#FF6600"));
            resetFields();
            tvVerdict.setText("Kết nối tai nghe Bluetooth rồi mở nhạc để đo");
            tvVerdict.setTextColor(Color.GRAY);
            addLog("No A2DP device");
            return;
        }

        BluetoothDevice device = devices.get(0);
        tvStatus.setText("Đã kết nối"); tvStatus.setTextColor(Color.parseColor("#00C853"));
        String name = device.getName();
        tvDevice.setText(name != null ? name : device.getAddress());

        boolean playing = isA2dpPlaying(device);
        addLog("A2DP playing: " + playing);

        if (!playing) {
            tvCodec.setText("Chờ phát nhạc...");
            tvBitrate.setText("—"); tvMode.setText("—");
            progressBitrate.setProgress(0);
            tvVerdict.setText("Tai nghe đã kết nối!\nHãy mở app nhạc và phát nhạc,\nrồi app sẽ tự cập nhật.");
            tvVerdict.setTextColor(Color.parseColor("#2979FF"));
            return;
        }

        // Strategy 1: reflection getCodecStatus
        BluetoothCodecStatus status = getCodecStatusReflection(device);

        // Strategy 2: use cached status from last broadcast (HUAWEI fallback)
        if (status == null && cachedCodecStatus != null) {
            addLog("Using cached broadcast codec status");
            status = cachedCodecStatus;
        }

        if (status == null) {
            // Strategy 3: probe dumpsys + system props (async, results shown in log)
            tvCodec.setText("Đang thử phương pháp khác...");
            tvVerdict.setText("API Bluetooth bị chặn trên thiết bị này.\n\nĐang thử đọc từ hệ thống...\nNhấn \"Đo lại ngay\" hoặc\ntắt/bật lại Bluetooth để buộc app nhận codec mới.");
            tvVerdict.setTextColor(Color.parseColor("#FF6600"));
            tryDumpsysAsync();
            trySystemProps();
            return;
        }

        BluetoothCodecConfig cfg = status.getCodecConfig();
        if (cfg == null) { tvCodec.setText("Codec config null"); addLog("getCodecConfig = null"); return; }
        displayCodecInfo(cfg);
    }

    // ── Codec reading strategies ────────────────────────────────────────────

    private boolean isA2dpPlaying(BluetoothDevice device) {
        try { return btA2dp.isA2dpPlaying(device); }
        catch (Exception e) { addLog("isA2dpPlaying err: " + e.getMessage()); return false; }
    }

    private BluetoothCodecStatus getCodecStatusReflection(BluetoothDevice device) {
        try {
            Method m = BluetoothA2dp.class.getDeclaredMethod("getCodecStatus", BluetoothDevice.class);
            m.setAccessible(true);
            BluetoothCodecStatus s = (BluetoothCodecStatus) m.invoke(btA2dp, device);
            addLog("getCodecStatus reflection: " + (s != null ? "OK" : "null"));
            return s;
        } catch (Exception e) {
            addLog("getCodecStatus reflection error: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Parse `dumpsys bluetooth_manager` output to extract codec info. */
    private void tryDumpsysAsync() {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "bluetooth_manager"});
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                int lineCount = 0;
                while ((line = br.readLine()) != null && lineCount < 2000) {
                    lineCount++;
                    String low = line.toLowerCase();
                    if (low.contains("ldac") || low.contains("codec") ||
                        low.contains("bitrate") || low.contains("sbc") ||
                        low.contains("aptx") || low.contains("aac")) {
                        sb.append(line.trim()).append("\n");
                    }
                }
                p.destroy();
                if (sb.length() > 0) {
                    addLog("── dumpsys codec lines ──\n" + sb.toString().trim());
                    parseDumpsysCodec(sb.toString());
                } else {
                    addLog("dumpsys: no codec lines found");
                }
            } catch (Exception e) {
                addLog("dumpsys error: " + e.getMessage());
            }
        }).start();
    }

    private void parseDumpsysCodec(String dump) {
        // Try to detect LDAC and quality from dumpsys text
        String lower = dump.toLowerCase();
        int detectedBitrate = -1;
        String detectedMode = null;

        if (lower.contains("ldac")) {
            if (lower.contains("990") || lower.contains("high quality") || lower.contains("high_quality")) {
                detectedBitrate = 990; detectedMode = "High Quality (990 kbps)";
            } else if (lower.contains("660") || lower.contains("mid quality") || lower.contains("mid_quality")) {
                detectedBitrate = 660; detectedMode = "Mid Quality (660 kbps)";
            } else if (lower.contains("330") || lower.contains("low quality") || lower.contains("low_quality")) {
                detectedBitrate = 330; detectedMode = "Low Quality (330 kbps)";
            } else {
                detectedMode = "LDAC (bitrate không xác định)";
            }
        }

        if (detectedMode != null) {
            final String mode = detectedMode;
            final int br = detectedBitrate;
            runOnUiThread(() -> {
                tvCodec.setText("LDAC (via dumpsys)");
                if (br > 0) {
                    tvBitrate.setText(br + " kbps");
                    progressBitrate.setProgress((int)(br / 9.9f));
                    showLdacVerdict(br == 990 ? 1000 : br == 660 ? 1001 : 1002);
                } else {
                    tvBitrate.setText("LDAC active");
                    tvMode.setText(mode);
                    tvVerdict.setText("LDAC đang hoạt động (đọc từ dumpsys).\nBitrate chính xác không xác định được.");
                    tvVerdict.setTextColor(Color.parseColor("#2979FF"));
                }
                tvMode.setText(mode);
            });
        }
    }

    /** Probe system properties for LDAC/codec info. */
    private void trySystemProps() {
        String[] props = {
            "persist.bluetooth.a2dp_codec.value",
            "persist.bluetooth.a2dp.ldac.quality",
            "persist.bluetooth.ldac.quality",
            "ro.bluetooth.a2dp_offload.supported",
            "persist.vendor.bt.a2dp_codec",
            "hw.bluetooth.codec",
            "persist.sys.bt.a2dp.ldac",
            "ro.bluetooth.library_name",
        };
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getDeclaredMethod("get", String.class, String.class);
            get.setAccessible(true);
            for (String key : props) {
                String val = (String) get.invoke(null, key, "");
                if (!val.isEmpty()) addLog("sysprop " + key + " = " + val);
            }
        } catch (Exception e) {
            addLog("SystemProperties probe error: " + e.getClass().getSimpleName());
        }
    }

    // ── Display helpers ─────────────────────────────────────────────────────

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
        addLog(String.format("Codec=%s SR=%s Bits=%s s1=%d", codecName(type), srStr(sr), bitsStr(bits), s1));

        if (type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
            showLdacVerdict(s1);
        } else {
            int kbps = estimateKbps(type);
            tvBitrate.setText(kbps > 0 ? kbps + " kbps" : "N/A");
            tvMode.setText("N/A — không phải LDAC");
            tvVerdict.setText("Đang dùng " + codecName(type) + ", chưa phải LDAC.\n\nVào Settings → Developer options → Bluetooth audio codec → chọn LDAC.");
            tvVerdict.setTextColor(Color.parseColor("#FF6600"));
            progressBitrate.setProgress(kbps > 0 ? (int)(kbps / 9.9f) : 0);
        }
    }

    private void showLdacVerdict(long s1) {
        String mode; int kbps; int color; String verdict;
        if (s1 == 1000 || s1 == 0) {
            mode = "High Quality"; kbps = 990; color = Color.parseColor("#00C853");
            verdict = "CHÍNH XÁC! LDAC 990 kbps — tốc độ tối đa!\nChất lượng âm thanh cao nhất được đảm bảo.";
        } else if (s1 == 1001 || s1 == 1) {
            mode = "Mid Quality"; kbps = 660; color = Color.parseColor("#FFD600");
            verdict = "LDAC 660 kbps — mức trung bình.\nVào Developer options → LDAC quality → \"Optimize for audio quality\" để đạt 990 kbps.";
        } else if (s1 == 1002 || s1 == 2) {
            mode = "Low / Connection Priority"; kbps = 330; color = Color.parseColor("#FF1744");
            verdict = "LDAC chỉ 330 kbps!\nChất lượng còn thấp hơn cả SBC.\nĐổi sang \"Optimize for audio quality\" ngay.";
        } else if (s1 == 1003 || s1 == 3) {
            mode = "Adaptive (330–990 kbps)"; kbps = -1; color = Color.parseColor("#2979FF");
            verdict = "LDAC Adaptive — tự điều chỉnh bitrate.\nĐể cố định 990 kbps, chọn \"Optimize for audio quality\".";
        } else {
            mode = "LDAC (unknown s1=" + s1 + ")"; kbps = -1; color = Color.GRAY;
            verdict = "LDAC đang hoạt động, không xác định được chế độ. s1=" + s1;
        }
        tvMode.setText(mode);
        if (kbps > 0) { tvBitrate.setText(kbps + " kbps"); progressBitrate.setProgress((int)(kbps / 9.9f)); }
        else           { tvBitrate.setText("330–990 kbps"); progressBitrate.setProgress(66); }
        tvVerdict.setText(verdict);
        tvVerdict.setTextColor(color);
    }

    private void resetFields() {
        tvDevice.setText("—"); tvCodec.setText("—"); tvBitrate.setText("—");
        tvMode.setText("—"); tvSampleRate.setText("—"); tvBitDepth.setText("—");
        tvChannel.setText("—"); progressBitrate.setProgress(0);
    }

    // ── String helpers ──────────────────────────────────────────────────────
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
            default: return "STATE(" + s + ")";
        }
    }

    private void addLog(String msg) {
        String ts = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
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
