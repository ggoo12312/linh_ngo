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

import java.lang.reflect.Method;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REFRESH_INTERVAL_MS = 2000;

    // Hidden API constants — must use string literals since they're @hide
    private static final String ACTION_CODEC_CONFIG_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    private static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothA2dp bluetoothA2dp;
    private Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvStatus, tvDeviceName, tvCodec, tvBitrate;
    private TextView tvSampleRate, tvBitDepth, tvChannelMode, tvQualityMode;
    private TextView tvVerdict, tvLog;
    private ProgressBar progressBitrate;
    private Button btnRefresh;
    private View cardLdac;

    private StringBuilder logBuilder = new StringBuilder();

    private BroadcastReceiver codecReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_CODEC_CONFIG_CHANGED.equals(action)) {
                addLog("Codec config changed");
                refreshCodecInfo();
            } else if (ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                addLog("A2DP state: " + stateToString(state));
                refreshCodecInfo();
            }
        }
    };

    private BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = (BluetoothA2dp) proxy;
                addLog("A2DP profile connected");
                refreshCodecInfo();
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = null;
                addLog("A2DP profile disconnected");
            }
        }
    };

    private Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            refreshCodecInfo();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus       = findViewById(R.id.tv_status);
        tvDeviceName   = findViewById(R.id.tv_device_name);
        tvCodec        = findViewById(R.id.tv_codec);
        tvBitrate      = findViewById(R.id.tv_bitrate);
        tvSampleRate   = findViewById(R.id.tv_sample_rate);
        tvBitDepth     = findViewById(R.id.tv_bit_depth);
        tvChannelMode  = findViewById(R.id.tv_channel_mode);
        tvQualityMode  = findViewById(R.id.tv_quality_mode);
        tvVerdict      = findViewById(R.id.tv_verdict);
        tvLog          = findViewById(R.id.tv_log);
        progressBitrate = findViewById(R.id.progress_bitrate);
        btnRefresh     = findViewById(R.id.btn_refresh);
        cardLdac       = findViewById(R.id.card_ldac);

        btnRefresh.setOnClickListener(v -> {
            addLog("Manual refresh");
            refreshCodecInfo();
        });

        bypassHiddenApiRestrictions();
        checkPermissionsAndInit();
    }

    /**
     * Bypass Android 9+ hidden API restrictions so reflection can access
     * BluetoothA2dp.getCodecStatus() and related @hide methods.
     */
    private void bypassHiddenApiRestrictions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method setExemptions = vmRuntime.getDeclaredMethod(
                    "setHiddenApiExemptions", String[].class);
            setExemptions.setAccessible(true);
            setExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
            addLog("Hidden API bypass OK");
        } catch (Exception e) {
            addLog("Hidden API bypass failed: " + e.getClass().getSimpleName());
        }
    }

    private void checkPermissionsAndInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] perms = {
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            };
            boolean need = false;
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    need = true;
                    break;
                }
            }
            if (need) {
                ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST_CODE);
                return;
            }
        }
        initBluetooth();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERMISSION_REQUEST_CODE) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            if (ok) initBluetooth();
            else { tvStatus.setText("Cần cấp quyền Bluetooth"); tvStatus.setTextColor(Color.RED); }
        }
    }

    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            tvStatus.setText("Thiết bị không hỗ trợ Bluetooth");
            tvStatus.setTextColor(Color.RED);
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            tvStatus.setText("Vui lòng bật Bluetooth");
            tvStatus.setTextColor(Color.parseColor("#FF6600"));
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CODEC_CONFIG_CHANGED);
        filter.addAction(ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(codecReceiver, filter);

        bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP);
        addLog("Bluetooth init OK");
        handler.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS);
    }

    private void refreshCodecInfo() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            tvStatus.setText("Bluetooth chưa bật");
            tvStatus.setTextColor(Color.parseColor("#FF6600"));
            return;
        }
        if (bluetoothA2dp == null) {
            tvStatus.setText("Đang kết nối A2DP profile...");
            tvStatus.setTextColor(Color.GRAY);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setText("Thiếu quyền BLUETOOTH_CONNECT");
            return;
        }

        List<BluetoothDevice> devices = bluetoothA2dp.getConnectedDevices();
        if (devices.isEmpty()) {
            tvStatus.setText("Không có thiết bị A2DP nào kết nối");
            tvStatus.setTextColor(Color.parseColor("#FF6600"));
            tvDeviceName.setText("—"); tvCodec.setText("—"); tvBitrate.setText("—");
            tvSampleRate.setText("—"); tvBitDepth.setText("—");
            tvChannelMode.setText("—"); tvQualityMode.setText("—");
            tvVerdict.setText("Kết nối tai nghe LDAC và mở nhạc để đo");
            tvVerdict.setTextColor(Color.GRAY);
            progressBitrate.setProgress(0);
            addLog("No A2DP devices");
            return;
        }

        BluetoothDevice device = devices.get(0);
        String name = device.getName();
        tvDeviceName.setText(name != null ? name : device.getAddress());
        tvStatus.setText("Đã kết nối");
        tvStatus.setTextColor(Color.parseColor("#00C853"));

        // getCodecStatus is @hide — access via reflection
        BluetoothCodecStatus codecStatus = getCodecStatusReflection(device);
        if (codecStatus == null) {
            tvCodec.setText("Không đọc được codec (hidden API bị chặn)");
            addLog("getCodecStatus returned null");
            return;
        }

        BluetoothCodecConfig config = codecStatus.getCodecConfig();
        if (config == null) {
            tvCodec.setText("Không đọc được codec config");
            addLog("getCodecConfig returned null");
            return;
        }

        displayCodecInfo(config);
    }

    private BluetoothCodecStatus getCodecStatusReflection(BluetoothDevice device) {
        try {
            Method m = BluetoothA2dp.class.getDeclaredMethod("getCodecStatus", BluetoothDevice.class);
            m.setAccessible(true);
            return (BluetoothCodecStatus) m.invoke(bluetoothA2dp, device);
        } catch (Exception e) {
            addLog("Reflection error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private void displayCodecInfo(BluetoothCodecConfig config) {
        int codecType    = config.getCodecType();
        int sampleRate   = config.getSampleRate();
        int bitsPerSample = config.getBitsPerSample();
        int channelMode  = config.getChannelMode();
        long specific1   = config.getCodecSpecific1();

        tvCodec.setText(getCodecName(codecType));
        tvSampleRate.setText(getSampleRateString(sampleRate));
        tvBitDepth.setText(getBitDepthString(bitsPerSample));
        tvChannelMode.setText(getChannelModeString(channelMode));

        addLog(String.format("Codec=%s SR=%s Bits=%s S1=%d",
                getCodecName(codecType), getSampleRateString(sampleRate),
                getBitDepthString(bitsPerSample), specific1));

        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
            displayLdacInfo(specific1);
        } else {
            int kbps = estimateNonLdacBitrate(codecType);
            tvBitrate.setText(kbps > 0 ? kbps + " kbps (ước tính)" : "N/A");
            tvQualityMode.setText("N/A — không phải LDAC");
            tvVerdict.setText("Tai nghe đang dùng " + getCodecName(codecType) +
                    ", không phải LDAC.\nVào Settings → Bluetooth để bật LDAC.");
            tvVerdict.setTextColor(Color.parseColor("#FF6600"));
            progressBitrate.setProgress(kbps > 0 ? (int)(kbps / 9.9f) : 0);
        }
    }

    private void displayLdacInfo(long specific1) {
        // specific1 for LDAC: 1000=High(990kbps), 1001=Mid(660kbps),
        //                     1002=Low(330kbps), 1003=Adaptive
        // Some OEMs use 0/1/2/3 instead
        String qualityMode;
        int bitrate;
        int color;
        String verdict;

        if (specific1 == 1000 || specific1 == 0) {
            qualityMode = "High Quality";
            bitrate     = 990;
            color       = Color.parseColor("#00C853");
            verdict     = "CHÍNH XÁC! LDAC đang chạy ở tốc độ cao nhất 990 kbps.\nChất lượng âm thanh tối đa được đảm bảo!";
        } else if (specific1 == 1001 || specific1 == 1) {
            qualityMode = "Mid Quality";
            bitrate     = 660;
            color       = Color.parseColor("#FFD600");
            verdict     = "LDAC đang ở mức trung bình 660 kbps.\nVào Settings → Developer options → Bluetooth audio codec\n→ LDAC quality → chọn \"Optimize for audio quality\".";
        } else if (specific1 == 1002 || specific1 == 2) {
            qualityMode = "Low Quality / Connection Priority";
            bitrate     = 330;
            color       = Color.parseColor("#FF1744");
            verdict     = "LDAC đang ở mức thấp 330 kbps (ưu tiên kết nối).\nChất lượng thậm chí thấp hơn SBC!\nĐổi LDAC Quality sang \"Optimize for audio quality\".";
        } else if (specific1 == 1003 || specific1 == 3) {
            qualityMode = "Adaptive (330–990 kbps)";
            bitrate     = -1;
            color       = Color.parseColor("#2979FF");
            verdict     = "LDAC đang ở chế độ Adaptive — tự điều chỉnh theo chất lượng kết nối.\nĐể đảm bảo 990 kbps, chọn \"Optimize for audio quality\".";
        } else {
            qualityMode = "Unknown (raw=" + specific1 + ")";
            bitrate     = -1;
            color       = Color.GRAY;
            verdict     = "Không xác định được chế độ LDAC. specific1=" + specific1;
        }

        tvQualityMode.setText(qualityMode);
        if (bitrate > 0) {
            tvBitrate.setText(bitrate + " kbps");
            progressBitrate.setProgress((int)(bitrate / 9.9f));
        } else {
            tvBitrate.setText("330–990 kbps (Adaptive)");
            progressBitrate.setProgress(66);
        }
        tvVerdict.setText(verdict);
        tvVerdict.setTextColor(color);
    }

    private int estimateNonLdacBitrate(int codecType) {
        switch (codecType) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:     return 328;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:     return 320;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:    return 352;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD: return 576;
            default: return 0;
        }
    }

    private String getCodecName(int t) {
        switch (t) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:     return "SBC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:     return "AAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:    return "aptX";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD: return "aptX HD";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC:    return "LDAC";
            default: return "Unknown(" + t + ")";
        }
    }

    private String getSampleRateString(int sr) {
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

    private String getBitDepthString(int b) {
        switch (b) {
            case BluetoothCodecConfig.BITS_PER_SAMPLE_16: return "16-bit";
            case BluetoothCodecConfig.BITS_PER_SAMPLE_24: return "24-bit";
            case BluetoothCodecConfig.BITS_PER_SAMPLE_32: return "32-bit";
            default: return "Unknown(" + b + ")";
        }
    }

    private String getChannelModeString(int ch) {
        switch (ch) {
            case BluetoothCodecConfig.CHANNEL_MODE_MONO:   return "Mono";
            case BluetoothCodecConfig.CHANNEL_MODE_STEREO: return "Stereo";
            default: return "Unknown(" + ch + ")";
        }
    }

    private String stateToString(int s) {
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
        logBuilder.insert(0, "[" + ts + "] " + msg + "\n");
        if (logBuilder.length() > 3000) logBuilder.setLength(3000);
        runOnUiThread(() -> tvLog.setText(logBuilder.toString()));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(periodicRefresh);
        try { unregisterReceiver(codecReceiver); } catch (Exception ignored) {}
        if (bluetoothAdapter != null && bluetoothA2dp != null)
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp);
    }
}
