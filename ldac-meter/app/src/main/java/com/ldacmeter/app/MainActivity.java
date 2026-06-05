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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Button;
import android.widget.ScrollView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.graphics.Color;
import android.view.View;

import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REFRESH_INTERVAL_MS = 2000;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothA2dp bluetoothA2dp;
    private Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvStatus;
    private TextView tvDeviceName;
    private TextView tvCodec;
    private TextView tvBitrate;
    private TextView tvSampleRate;
    private TextView tvBitDepth;
    private TextView tvChannelMode;
    private TextView tvQualityMode;
    private TextView tvVerdict;
    private TextView tvLog;
    private ProgressBar progressBitrate;
    private Button btnRefresh;
    private View cardLdac;

    private StringBuilder logBuilder = new StringBuilder();

    private BroadcastReceiver codecReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED.equals(action)) {
                addLog("Codec config changed event received");
                refreshCodecInfo();
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                addLog("A2DP connection state changed: " + stateToString(state));
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

        tvStatus = findViewById(R.id.tv_status);
        tvDeviceName = findViewById(R.id.tv_device_name);
        tvCodec = findViewById(R.id.tv_codec);
        tvBitrate = findViewById(R.id.tv_bitrate);
        tvSampleRate = findViewById(R.id.tv_sample_rate);
        tvBitDepth = findViewById(R.id.tv_bit_depth);
        tvChannelMode = findViewById(R.id.tv_channel_mode);
        tvQualityMode = findViewById(R.id.tv_quality_mode);
        tvVerdict = findViewById(R.id.tv_verdict);
        tvLog = findViewById(R.id.tv_log);
        progressBitrate = findViewById(R.id.progress_bitrate);
        btnRefresh = findViewById(R.id.btn_refresh);
        cardLdac = findViewById(R.id.card_ldac);

        btnRefresh.setOnClickListener(v -> {
            addLog("Manual refresh triggered");
            refreshCodecInfo();
        });

        checkPermissionsAndInit();
    }

    private void checkPermissionsAndInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] permissions = {
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            };
            boolean needRequest = false;
            for (String perm : permissions) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    needRequest = true;
                    break;
                }
            }
            if (needRequest) {
                ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
                return;
            }
        }
        initBluetooth();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                initBluetooth();
            } else {
                tvStatus.setText("Cần cấp quyền Bluetooth để đo bitrate");
                tvStatus.setTextColor(Color.RED);
            }
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
        filter.addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(codecReceiver, filter);

        bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP);
        addLog("Bluetooth initialized, connecting to A2DP profile...");

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

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tvStatus.setText("Thiếu quyền BLUETOOTH_CONNECT");
            return;
        }

        List<BluetoothDevice> connectedDevices = bluetoothA2dp.getConnectedDevices();
        if (connectedDevices.isEmpty()) {
            tvStatus.setText("Không có thiết bị A2DP nào kết nối");
            tvStatus.setTextColor(Color.parseColor("#FF6600"));
            tvDeviceName.setText("—");
            tvCodec.setText("—");
            tvBitrate.setText("—");
            tvSampleRate.setText("—");
            tvBitDepth.setText("—");
            tvChannelMode.setText("—");
            tvQualityMode.setText("—");
            tvVerdict.setText("Kết nối tai nghe LDAC và mở nhạc để đo");
            tvVerdict.setTextColor(Color.GRAY);
            progressBitrate.setProgress(0);
            addLog("No A2DP devices connected");
            return;
        }

        BluetoothDevice device = connectedDevices.get(0);
        String deviceName = device.getName();
        tvDeviceName.setText(deviceName != null ? deviceName : device.getAddress());
        tvStatus.setText("Đã kết nối");
        tvStatus.setTextColor(Color.parseColor("#00C853"));
        addLog("Connected device: " + (deviceName != null ? deviceName : device.getAddress()));

        BluetoothCodecStatus codecStatus = bluetoothA2dp.getCodecStatus(device);
        if (codecStatus == null) {
            tvCodec.setText("Không đọc được codec");
            addLog("Codec status is null");
            return;
        }

        BluetoothCodecConfig config = codecStatus.getCodecConfig();
        if (config == null) {
            tvCodec.setText("Không đọc được codec config");
            addLog("Codec config is null");
            return;
        }

        displayCodecInfo(config);
    }

    private void displayCodecInfo(BluetoothCodecConfig config) {
        int codecType = config.getCodecType();
        String codecName = getCodecName(codecType);
        tvCodec.setText(codecName);

        int sampleRate = config.getSampleRate();
        int bitsPerSample = config.getBitsPerSample();
        int channelMode = config.getChannelMode();
        long specific1 = config.getCodecSpecific1();
        long specific2 = config.getCodecSpecific2();
        long specific3 = config.getCodecSpecific3();
        long specific4 = config.getCodecSpecific4();

        tvSampleRate.setText(getSampleRateString(sampleRate));
        tvBitDepth.setText(getBitDepthString(bitsPerSample));
        tvChannelMode.setText(getChannelModeString(channelMode));

        addLog(String.format("Codec: %s | SR: %s | Bits: %s | CH: %s | S1: %d S2: %d S3: %d S4: %d",
                codecName, getSampleRateString(sampleRate), getBitDepthString(bitsPerSample),
                getChannelModeString(channelMode), specific1, specific2, specific3, specific4));

        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
            cardLdac.setVisibility(View.VISIBLE);
            displayLdacInfo(specific1, sampleRate, bitsPerSample);
        } else {
            cardLdac.setVisibility(View.VISIBLE);
            int estimatedKbps = estimateNonLdacBitrate(codecType, sampleRate, bitsPerSample);
            tvBitrate.setText(estimatedKbps + " kbps (ước tính)");
            tvQualityMode.setText("N/A - Không phải LDAC");
            tvVerdict.setText("Tai nghe đang dùng " + codecName + ", không phải LDAC.\nKiểm tra cài đặt Bluetooth để bật LDAC.");
            tvVerdict.setTextColor(Color.parseColor("#FF6600"));
            progressBitrate.setProgress((int) (estimatedKbps / 9.9f));
        }
    }

    private void displayLdacInfo(long specific1, int sampleRate, int bitsPerSample) {
        String qualityMode;
        int bitrate;
        int verdictColor;
        String verdictText;

        // codecSpecific1 for LDAC: 1000=High, 1001=Mid, 1002=Low, 1003=Adaptive
        // Some devices use 0=high, 1=mid, 2=low, 3=adaptive
        if (specific1 == 1000 || specific1 == 0) {
            qualityMode = "High Quality (990 kbps)";
            bitrate = 990;
            verdictColor = Color.parseColor("#00C853");
            verdictText = "CHÍNH XAC! Tai nghe đang chạy LDAC tốc độ cao nhất 990 kbps.\nChat luong am thanh toi da duoc dam bao.";
        } else if (specific1 == 1001 || specific1 == 1) {
            qualityMode = "Mid Quality (660 kbps)";
            bitrate = 660;
            verdictColor = Color.parseColor("#FFD600");
            verdictText = "LDAC dang o muc trung binh 660 kbps.\nVao Settings > Bluetooth > LDAC Quality -> chon 'Optimize for audio quality' de dat 990 kbps.";
        } else if (specific1 == 1002 || specific1 == 2) {
            qualityMode = "Low Quality / Connection Priority (330 kbps)";
            bitrate = 330;
            verdictColor = Color.parseColor("#FF1744");
            verdictText = "LDAC dang o muc thap 330 kbps (uu tien ket noi).\nCha luong am thanh thap hon SBC. Hay doi LDAC Quality sang 'Optimize for audio quality'.";
        } else if (specific1 == 1003 || specific1 == 3) {
            qualityMode = "Adaptive (tự động 330-990 kbps)";
            bitrate = -1;
            verdictColor = Color.parseColor("#2979FF");
            verdictText = "LDAC dang o che do Adaptive - tu dong dieu chinh bitrate theo chat luong ket noi.\nDe chac 990 kbps, chuyen sang 'Optimize for audio quality'.";
        } else {
            qualityMode = "Unknown (raw value: " + specific1 + ")";
            bitrate = -1;
            verdictColor = Color.GRAY;
            verdictText = "Khong xac dinh duoc che do LDAC. Raw specific1=" + specific1;
        }

        tvQualityMode.setText(qualityMode);

        if (bitrate > 0) {
            tvBitrate.setText(bitrate + " kbps");
            progressBitrate.setProgress((int) (bitrate / 9.9f));
        } else {
            tvBitrate.setText("330 - 990 kbps (Adaptive)");
            progressBitrate.setProgress(66);
        }

        tvVerdict.setText(verdictText);
        tvVerdict.setTextColor(verdictColor);
    }

    private int estimateNonLdacBitrate(int codecType, int sampleRate, int bitsPerSample) {
        switch (codecType) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:
                return 328;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:
                return 320;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:
                return 352;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD:
                return 576;
            default:
                return 0;
        }
    }

    private String getCodecName(int codecType) {
        switch (codecType) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:  return "SBC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:  return "AAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX: return "aptX";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD: return "aptX HD";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC: return "LDAC";
            default: return "Unknown (" + codecType + ")";
        }
    }

    private String getSampleRateString(int sampleRate) {
        switch (sampleRate) {
            case BluetoothCodecConfig.SAMPLE_RATE_44100: return "44.1 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_48000: return "48 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_88200: return "88.2 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_96000: return "96 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_176400: return "176.4 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_192000: return "192 kHz";
            default: return "Unknown (" + sampleRate + ")";
        }
    }

    private String getBitDepthString(int bits) {
        switch (bits) {
            case BluetoothCodecConfig.BITS_PER_SAMPLE_16: return "16-bit";
            case BluetoothCodecConfig.BITS_PER_SAMPLE_24: return "24-bit";
            case BluetoothCodecConfig.BITS_PER_SAMPLE_32: return "32-bit";
            default: return "Unknown (" + bits + ")";
        }
    }

    private String getChannelModeString(int mode) {
        switch (mode) {
            case BluetoothCodecConfig.CHANNEL_MODE_MONO:   return "Mono";
            case BluetoothCodecConfig.CHANNEL_MODE_STEREO: return "Stereo";
            default: return "Unknown (" + mode + ")";
        }
    }

    private String stateToString(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:    return "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING:   return "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED: return "DISCONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING:return "DISCONNECTING";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    private void addLog(String message) {
        String timestamp = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        logBuilder.insert(0, "[" + timestamp + "] " + message + "\n");
        if (logBuilder.length() > 3000) {
            logBuilder.setLength(3000);
        }
        runOnUiThread(() -> tvLog.setText(logBuilder.toString()));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(periodicRefresh);
        try { unregisterReceiver(codecReceiver); } catch (Exception ignored) {}
        if (bluetoothAdapter != null && bluetoothA2dp != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp);
        }
    }
}
