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
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_CODE = 100;
    private static final int REFRESH_MS = 2000;

    // Hidden API string constants (cannot reference BluetoothA2dp.ACTION_* directly)
    private static final String ACTION_CODEC_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    private static final String ACTION_CONN_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";

    private BluetoothAdapter btAdapter;
    private BluetoothA2dp btA2dp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvStatus, tvDevice, tvCodec, tvBitrate;
    private TextView tvSampleRate, tvBitDepth, tvChannel, tvMode, tvVerdict, tvLog;
    private ProgressBar progressBitrate;
    private Button btnRefresh;
    private View cardInfo;
    private final StringBuilder logBuf = new StringBuilder();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_CODEC_CHANGED.equals(action)) {
                addLog("Codec config changed event");
                refreshCodecInfo();
            } else if (ACTION_CONN_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                addLog("A2DP state → " + stateStr(state));
                refreshCodecInfo();
            }
        }
    };

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
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
        cardInfo     = findViewById(R.id.card_ldac);

        btnRefresh.setOnClickListener(v -> { addLog("Manual refresh"); refreshCodecInfo(); });

        unlockHiddenApis();
        checkPermsAndInit();
    }

    /** Use LSPosed HiddenApiBypass — more reliable than VMRuntime trick on Android 12+ / EMUI */
    private void unlockHiddenApis() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            HiddenApiBypass.addHiddenApiExemptions("L");
            addLog("HiddenApiBypass: OK (all exempt)");
        } catch (Throwable t) {
            addLog("HiddenApiBypass failed: " + t.getClass().getSimpleName());
            // Fallback: legacy VMRuntime trick
            try {
                Class<?> vm = Class.forName("dalvik.system.VMRuntime");
                Method getRT = vm.getDeclaredMethod("getRuntime");
                getRT.setAccessible(true);
                Object runtime = getRT.invoke(null);
                Method setEx = vm.getDeclaredMethod("setHiddenApiExemptions", String[].class);
                setEx.setAccessible(true);
                setEx.invoke(runtime, new Object[]{new String[]{"L"}});
                addLog("VMRuntime bypass: OK");
            } catch (Exception e2) {
                addLog("VMRuntime bypass failed: " + e2.getClass().getSimpleName());
            }
        }
    }

    private void checkPermsAndInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] perms = {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
            boolean need = false;
            for (String p : perms)
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                    need = true;
            if (need) { ActivityCompat.requestPermissions(this, perms, PERM_CODE); return; }
        }
        initBluetooth();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == PERM_CODE) {
            boolean ok = true;
            for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) initBluetooth();
            else { tvStatus.setText("Cần cấp quyền Bluetooth!"); tvStatus.setTextColor(Color.RED); }
        }
    }

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

    private void refreshCodecInfo() {
        if (btAdapter == null || !btAdapter.isEnabled()) {
            showStatus("Bluetooth chưa bật", Color.parseColor("#FF6600"));
            return;
        }
        if (btA2dp == null) {
            showStatus("Đang kết nối A2DP...", Color.GRAY);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            showStatus("Thiếu quyền BLUETOOTH_CONNECT", Color.RED);
            return;
        }

        List<BluetoothDevice> devices = btA2dp.getConnectedDevices();
        if (devices.isEmpty()) {
            showStatus("Chưa kết nối tai nghe A2DP", Color.parseColor("#FF6600"));
            resetFields();
            tvVerdict.setText("Kết nối tai nghe LDAC rồi mở nhạc để đo");
            tvVerdict.setTextColor(Color.GRAY);
            addLog("No A2DP device");
            return;
        }

        BluetoothDevice device = devices.get(0);
        showStatus("Đã kết nối", Color.parseColor("#00C853"));
        String name = device.getName();
        tvDevice.setText(name != null ? name : device.getAddress());

        // Check if audio is actually streaming — codec only active when playing
        boolean playing = isA2dpPlaying(device);
        addLog("A2DP playing: " + playing);

        if (!playing) {
            tvCodec.setText("Chờ phát nhạc...");
            tvBitrate.setText("—");
            tvMode.setText("—");
            tvVerdict.setText("Tai nghe đã kết nối!\n\nHãy mở app nhạc và phát nhạc,\nrồi quay lại đây xem kết quả.\n(App tự cập nhật mỗi 2 giây)");
            tvVerdict.setTextColor(Color.parseColor("#2979FF"));
            progressBitrate.setProgress(0);
            return;
        }

        // Music is playing — get codec status via reflection
        BluetoothCodecStatus status = getCodecStatusViaReflection(device);
        if (status == null) {
            tvCodec.setText("Không đọc được codec");
            tvVerdict.setText("Không lấy được thông tin codec.\n\nHave you tried:\n• Tắt/bật Bluetooth\n• Mở Developer Options → Bluetooth codec → chọn LDAC\n• Thử thiết bị khác");
            tvVerdict.setTextColor(Color.parseColor("#FF6600"));
            addLog("getCodecStatus = null (music playing but API blocked or unsupported)");
            return;
        }

        BluetoothCodecConfig cfg = status.getCodecConfig();
        if (cfg == null) {
            tvCodec.setText("Codec config null");
            addLog("getCodecConfig = null");
            return;
        }

        displayCodecInfo(cfg);
    }

    private boolean isA2dpPlaying(BluetoothDevice device) {
        try {
            return btA2dp.isA2dpPlaying(device);
        } catch (Exception e) {
            addLog("isA2dpPlaying error: " + e.getMessage());
            return false;
        }
    }

    private BluetoothCodecStatus getCodecStatusViaReflection(BluetoothDevice device) {
        try {
            Method m = BluetoothA2dp.class.getDeclaredMethod("getCodecStatus", BluetoothDevice.class);
            m.setAccessible(true);
            Object result = m.invoke(btA2dp, device);
            addLog("getCodecStatus via reflection: " + (result != null ? "OK" : "null"));
            return (BluetoothCodecStatus) result;
        } catch (Exception e) {
            addLog("Reflection error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private void displayCodecInfo(BluetoothCodecConfig cfg) {
        int type    = cfg.getCodecType();
        int sr      = cfg.getSampleRate();
        int bits    = cfg.getBitsPerSample();
        int ch      = cfg.getChannelMode();
        long s1     = cfg.getCodecSpecific1();

        tvCodec.setText(codecName(type));
        tvSampleRate.setText(srStr(sr));
        tvBitDepth.setText(bitsStr(bits));
        tvChannel.setText(chStr(ch));
        addLog(String.format("Codec=%s SR=%s Bits=%s s1=%d", codecName(type), srStr(sr), bitsStr(bits), s1));

        if (type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
            showLdac(s1);
        } else {
            int kbps = estimateKbps(type);
            tvBitrate.setText(kbps > 0 ? kbps + " kbps (ước tính)" : "N/A");
            tvMode.setText("N/A — không phải LDAC");
            tvVerdict.setText("Đang dùng " + codecName(type) + ", chưa phải LDAC.\n\nVào Settings → Developer options → Bluetooth audio codec → chọn LDAC.");
            tvVerdict.setTextColor(Color.parseColor("#FF6600"));
            progressBitrate.setProgress(kbps > 0 ? (int)(kbps / 9.9f) : 0);
        }
    }

    private void showLdac(long s1) {
        String mode; int kbps; int color; String verdict;

        if (s1 == 1000 || s1 == 0) {
            mode    = "High Quality";
            kbps    = 990;
            color   = Color.parseColor("#00C853");
            verdict = "CHÍNH XÁC! LDAC đang chạy tốc độ tối đa 990 kbps.\nChất lượng âm thanh được đảm bảo hoàn toàn!";
        } else if (s1 == 1001 || s1 == 1) {
            mode    = "Mid Quality";
            kbps    = 660;
            color   = Color.parseColor("#FFD600");
            verdict = "LDAC đang ở 660 kbps (mức trung bình).\n\nĐể đạt 990 kbps:\nSettings → Developer options → LDAC quality → \"Optimize for audio quality\"";
        } else if (s1 == 1002 || s1 == 2) {
            mode    = "Low / Connection Priority";
            kbps    = 330;
            color   = Color.parseColor("#FF1744");
            verdict = "LDAC chỉ 330 kbps — ưu tiên kết nối ổn định.\nChất lượng còn thấp hơn SBC!\n\nĐổi sang \"Optimize for audio quality\" trong Developer options.";
        } else if (s1 == 1003 || s1 == 3) {
            mode    = "Adaptive (330–990 kbps)";
            kbps    = -1;
            color   = Color.parseColor("#2979FF");
            verdict = "LDAC Adaptive — tự điều chỉnh bitrate theo chất lượng kết nối.\nĐể cố định 990 kbps, chọn \"Optimize for audio quality\".";
        } else {
            mode    = "Unknown (s1=" + s1 + ")";
            kbps    = -1;
            color   = Color.GRAY;
            verdict = "Không xác định được chế độ LDAC. specific1=" + s1;
        }

        tvMode.setText(mode);
        if (kbps > 0) { tvBitrate.setText(kbps + " kbps"); progressBitrate.setProgress((int)(kbps / 9.9f)); }
        else           { tvBitrate.setText("330–990 kbps"); progressBitrate.setProgress(66); }
        tvVerdict.setText(verdict);
        tvVerdict.setTextColor(color);
    }

    private void showStatus(String msg, int color) {
        tvStatus.setText(msg);
        tvStatus.setTextColor(color);
    }

    private void resetFields() {
        tvDevice.setText("—"); tvCodec.setText("—"); tvBitrate.setText("—");
        tvMode.setText("—"); tvSampleRate.setText("—"); tvBitDepth.setText("—"); tvChannel.setText("—");
        progressBitrate.setProgress(0);
    }

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
            case BluetoothCodecConfig.SAMPLE_RATE_176400: return "176.4 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_192000: return "192 kHz";
            default: return "Unknown(" + sr + ")";
        }
    }

    private String bitsStr(int b) {
        switch (b) {
            case BluetoothCodecConfig.BITS_PER_SAMPLE_16: return "16-bit";
            case BluetoothCodecConfig.BITS_PER_SAMPLE_24: return "24-bit";
            case BluetoothCodecConfig.BITS_PER_SAMPLE_32: return "32-bit";
            default: return "Unknown(" + b + ")";
        }
    }

    private String chStr(int c) {
        switch (c) {
            case BluetoothCodecConfig.CHANNEL_MODE_MONO:   return "Mono";
            case BluetoothCodecConfig.CHANNEL_MODE_STEREO: return "Stereo";
            default: return "Unknown(" + c + ")";
        }
    }

    private String stateStr(int s) {
        switch (s) {
            case BluetoothProfile.STATE_CONNECTED:     return "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING:    return "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED:  return "DISCONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING: return "DISCONNECTING";
            default: return "UNKNOWN(" + s + ")";
        }
    }

    private void addLog(String msg) {
        String ts = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        logBuf.insert(0, "[" + ts + "] " + msg + "\n");
        if (logBuf.length() > 4000) logBuf.setLength(4000);
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
