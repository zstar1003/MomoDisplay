package com.zstar.rlcdble;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String TAG = "RLCD-BLE";
    private static final int REQ_PICK_IMAGE = 1001;
    private static final int REQ_PERMISSIONS = 1002;
    private static final int WIDTH = 400;
    private static final int HEIGHT = 300;
    private static final int IMAGE_BYTES = (WIDTH / 8) * HEIGHT;
    private static final String DEVICE_NAME = "RLCD-BLE-IMG";

    private static final UUID SERVICE_UUID = UUID.fromString("7f6b0001-5f02-4fd8-9f23-6f8e4c59a001");
    private static final UUID CONTROL_UUID = UUID.fromString("7f6b0002-5f02-4fd8-9f23-6f8e4c59a001");
    private static final UUID DATA_UUID = UUID.fromString("7f6b0003-5f02-4fd8-9f23-6f8e4c59a001");
    private static final UUID STATUS_UUID = UUID.fromString("7f6b0004-5f02-4fd8-9f23-6f8e4c59a001");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<DeviceEntry> devices = new ArrayList<>();
    private final ArrayDeque<WritePacket> writeQueue = new ArrayDeque<>();

    private TextView statusText;
    private TextView logText;
    private TextView progressText;
    private ImageView previewView;
    private ProgressBar progressBar;
    private DeviceAdapter deviceAdapter;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic controlCharacteristic;
    private BluetoothGattCharacteristic dataCharacteristic;
    private BluetoothGattCharacteristic statusCharacteristic;

    private byte[] selectedImage;
    private int negotiatedMtu = 23;
    private boolean scanning = false;
    private boolean connected = false;
    private boolean sending = false;
    private WritePacket currentPacket;
    private int totalImageBytes = 0;
    private int sentImageBytes = 0;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                handleScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            setStatus("扫描失败，错误码：" + errorCode);
            log("扫描失败：" + errorCode);
            Log.w(TAG, "scan failed: " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                logOnUi("已连接蓝牙，正在发现服务...");
                connected = false;
                Log.d(TAG, "connected, discovering services");
                gatt.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "disconnected, status=" + status);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        connected = false;
                        sending = false;
                        controlCharacteristic = null;
                        dataCharacteristic = null;
                        statusCharacteristic = null;
                        setStatus("已断开连接");
                        log("蓝牙连接已断开。");
                    }
                });
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            negotiatedMtu = mtu > 0 ? mtu : 23;
            logOnUi("当前 MTU：" + negotiatedMtu);
            Log.d(TAG, "mtu changed: " + negotiatedMtu + ", status=" + status);
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            Log.d(TAG, "services discovered status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logOnUi("发现服务失败：" + status);
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                Log.w(TAG, "target service not found");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("这不是 RLCD 开发板，请换一个设备。");
                        log("未找到 RLCD 服务 UUID。");
                    }
                });
                gatt.disconnect();
                return;
            }

            controlCharacteristic = service.getCharacteristic(CONTROL_UUID);
            dataCharacteristic = service.getCharacteristic(DATA_UUID);
            statusCharacteristic = service.getCharacteristic(STATUS_UUID);

            if (controlCharacteristic == null || dataCharacteristic == null || statusCharacteristic == null) {
                Log.w(TAG, "characteristics missing");
                logOnUi("开发板服务不完整，请确认固件已更新。");
                gatt.disconnect();
                return;
            }

            enableStatusNotifications(gatt);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, final int status) {
            Log.d(TAG, "descriptor write status=" + status);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    connected = true;
                    setStatus("已连接开发板，可以发送图片。");
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        log("状态通知已开启。");
                    } else {
                        log("状态通知开启失败，但仍可尝试发送，状态码：" + status);
                    }
                    if (bluetoothGatt != null) {
                        try {
                            boolean mtuRequested = bluetoothGatt.requestMtu(517);
                            log("正在协商更大的传输包：" + (mtuRequested ? "已发起" : "未发起，使用默认包长"));
                        } catch (RuntimeException e) {
                            log("MTU 协商异常，继续使用默认包长：" + e.getMessage());
                            Log.w(TAG, "requestMtu failed", e);
                        }
                    }
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "characteristic write status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                final int failedStatus = status;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        sending = false;
                        setStatus("写入失败，请重新连接后再试。");
                        log("BLE 写入失败：" + failedStatus);
                    }
                });
                return;
            }

            final WritePacket finished = currentPacket;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (finished != null && finished.imageBytes > 0) {
                        sentImageBytes += finished.imageBytes;
                        updateProgress();
                    }
                    writeNext();
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            handleStatus(characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleStatus(value);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        buildUi();
        requestRuntimePermissions();

        if (bluetoothAdapter == null) {
            setStatus("当前手机不支持蓝牙。");
        } else if (!bluetoothAdapter.isEnabled()) {
            setStatus("蓝牙未开启。");
            log("请先在系统设置中打开蓝牙。");
        } else {
            setStatus("准备就绪：选择图片，然后扫描开发板。");
        }
    }

    @Override
    protected void onDestroy() {
        stopScan();
        disconnectGatt();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri uri = data.getData();
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                selectedImage = convertImage(uri);
                setStatus("图片已准备好：400x300，15000 字节。");
                log("图片已转成屏幕可显示的黑白数据。");
            } catch (SecurityException ignored) {
                try {
                    selectedImage = convertImage(data.getData());
                    setStatus("图片已准备好：400x300，15000 字节。");
                    log("图片已转成屏幕可显示的黑白数据。");
                } catch (IOException e) {
                    setStatus("图片处理失败。");
                    log(e.getMessage());
                }
            } catch (IOException e) {
                setStatus("图片处理失败。");
                log(e.getMessage());
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasBlePermissions()) {
                setStatus("权限已授权，可以扫描开发板。");
                log("蓝牙权限已授权。");
            } else {
                setStatus("缺少蓝牙权限，无法扫描或连接。");
                log("请在系统设置中允许“附近设备/蓝牙”权限。");
            }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(12));
        root.setBackgroundColor(Color.rgb(246, 247, 249));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(14));
        header.setBackground(roundRect(Color.rgb(20, 31, 45), dp(12), 0, 0));

        TextView title = new TextView(this);
        title.setText("墨屏蓝牙传图");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("选择图片后，通过 BLE 发送到 ESP32-S3-RLCD-4.2");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.rgb(215, 224, 235));
        subtitle.setPadding(0, dp(6), 0, 0);
        header.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(Color.rgb(29, 57, 82));
        statusText.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusText.setBackground(roundRect(Color.rgb(232, 241, 252), dp(10), Color.rgb(188, 212, 238), 1));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(12), 0, dp(10));
        root.addView(statusText, statusParams);

        TextView previewTitle = sectionTitle("图片预览");
        root.addView(previewTitle);

        previewView = new ImageView(this);
        previewView.setBackground(roundRect(Color.WHITE, dp(10), Color.rgb(214, 219, 226), 1));
        previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewView.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(previewView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(212)));

        LinearLayout actionRow1 = new LinearLayout(this);
        actionRow1.setOrientation(LinearLayout.HORIZONTAL);
        actionRow1.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(10), 0, 0);
        root.addView(actionRow1, rowParams);

        Button pickButton = primaryButton("选择图片");
        pickButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickImage();
            }
        });
        actionRow1.addView(pickButton, weightedButtonParams(0, dp(4)));

        Button scanButton = secondaryButton("扫描开发板");
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startScan();
            }
        });
        actionRow1.addView(scanButton, weightedButtonParams(dp(4), 0));

        TextView deviceTitle = sectionTitle("开发板");
        LinearLayout.LayoutParams deviceTitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        deviceTitleParams.setMargins(0, dp(12), 0, dp(6));
        root.addView(deviceTitle, deviceTitleParams);

        deviceAdapter = new DeviceAdapter(this, devices);
        ListView deviceList = new ListView(this);
        deviceList.setDivider(null);
        deviceList.setAdapter(deviceAdapter);
        deviceList.setBackground(roundRect(Color.WHITE, dp(10), Color.rgb(225, 229, 235), 1));
        deviceList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                connect(devices.get(position).device);
            }
        });
        root.addView(deviceList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)));

        LinearLayout actionRow2 = new LinearLayout(this);
        actionRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Params.setMargins(0, dp(10), 0, 0);
        root.addView(actionRow2, row2Params);

        Button sendButton = primaryButton("发送到墨屏");
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendSelectedImage();
            }
        });
        actionRow2.addView(sendButton, weightedButtonParams(0, dp(4)));

        Button disconnectButton = secondaryButton("断开连接");
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                disconnectGatt();
            }
        });
        actionRow2.addView(disconnectButton, weightedButtonParams(dp(4), 0));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgressDrawable(progressDrawable());
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.setMargins(0, dp(12), 0, dp(8));
        root.addView(progressBar, progressParams);

        progressText = new TextView(this);
        progressText.setText("等待上传");
        progressText.setTextSize(13);
        progressText.setTypeface(Typeface.DEFAULT_BOLD);
        progressText.setTextColor(Color.rgb(29, 57, 82));
        progressText.setGravity(Gravity.CENTER);
        root.addView(progressText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextColor(Color.rgb(40, 47, 56));
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(12), dp(10), dp(12), dp(10));
        logText.setBackground(roundRect(Color.WHITE, dp(10), Color.rgb(225, 229, 235), 1));
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(logText);
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }

        ArrayList<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_IMAGE);
    }

    private void startScan() {
        if (bluetoothAdapter == null) {
            setStatus("当前手机不支持蓝牙。");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            setStatus("蓝牙未开启，请先打开蓝牙。");
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        if (!hasBlePermissions()) {
            requestRuntimePermissions();
            setStatus("请先授权蓝牙权限，然后再次扫描。");
            return;
        }

        stopScan();
        devices.clear();
        deviceAdapter.notifyDataSetChanged();
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("系统没有返回 BLE 扫描器，请重启蓝牙后再试。");
            return;
        }

        scanning = true;
        setStatus("正在扫描开发板...");
        log("开始扫描 15 秒，只显示 RLCD-BLE-IMG。若没有结果，请确认开发板已上电并靠近手机。");
        Log.d(TAG, "start scan");

        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();
        scanner.startScan(null, settings, scanCallback);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopScan();
                if (devices.isEmpty()) {
                    setStatus("没有扫描到开发板。");
                    log("没有发现 RLCD-BLE-IMG：请确认蓝牙权限、定位开关、开发板供电和距离。");
                } else {
                    setStatus("已找到开发板，点击列表连接。");
                }
            }
        }, 15000);
    }

    private void stopScan() {
        if (scanning && scanner != null && hasBlePermissions()) {
            scanner.stopScan(scanCallback);
        }
        if (scanning) {
            log("扫描已停止。");
        }
        scanning = false;
    }

    private void handleScanResult(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        BluetoothDevice device = result.getDevice();
        String name = null;
        if (record != null) {
            name = record.getDeviceName();
        }
        if (name == null && hasBlePermissions()) {
            name = device.getName();
        }

        boolean serviceMatch = false;
        if (record != null && record.getServiceUuids() != null) {
            for (ParcelUuid uuid : record.getServiceUuids()) {
                if (SERVICE_UUID.equals(uuid.getUuid())) {
                    serviceMatch = true;
                    break;
                }
            }
        }

        String address = safeAddress(device);
        Log.d(TAG, "scan result name=" + name + " address=" + address + " rssi=" + result.getRssi() + " serviceMatch=" + serviceMatch);
        boolean likelyBoard = serviceMatch || DEVICE_NAME.equals(name);
        if (!likelyBoard) {
            return;
        }

        for (DeviceEntry entry : devices) {
            if (entry.address.equals(address)) {
                entry.rssi = result.getRssi();
                if (name != null && name.length() > 0) {
                    entry.name = name;
                }
                entry.likelyBoard = true;
                deviceAdapter.notifyDataSetChanged();
                return;
            }
        }

        DeviceEntry entry = new DeviceEntry(device, name, address, result.getRssi(), likelyBoard);
        devices.add(0, entry);
        setStatus("找到开发板，点击列表连接。");
        deviceAdapter.notifyDataSetChanged();
    }

    private void connect(BluetoothDevice device) {
        if (!hasBlePermissions()) {
            requestRuntimePermissions();
            return;
        }
        stopScan();
        disconnectGatt();
        setStatus("正在连接...");
        log("正在连接：" + safeAddress(device));
        Log.d(TAG, "connect " + safeAddress(device));
        if (Build.VERSION.SDK_INT >= 23) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private void disconnectGatt() {
        if (bluetoothGatt != null && hasBlePermissions()) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
        }
        bluetoothGatt = null;
        connected = false;
        sending = false;
        setStatus("已断开连接");
    }

    private void enableStatusNotifications(BluetoothGatt gatt) {
        gatt.setCharacteristicNotification(statusCharacteristic, true);
        BluetoothGattDescriptor descriptor = statusCharacteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    connected = true;
                    setStatus("已连接开发板，可以发送图片。");
                    log("没有找到通知描述符，但写入通道可用。");
                }
            });
            return;
        }
        try {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(descriptor)) {
                markConnected("通知开启失败，但写入通道可用。");
            }
        } catch (RuntimeException e) {
            markConnected("通知开启异常，但写入通道可用：" + e.getMessage());
            Log.w(TAG, "descriptor write failed", e);
        }
    }

    private void markConnected(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connected = true;
                setStatus("已连接开发板，可以发送图片。");
                log(message);
            }
        });
    }

    private void sendSelectedImage() {
        if (!connected || bluetoothGatt == null || controlCharacteristic == null || dataCharacteristic == null) {
            setStatus("请先连接开发板。");
            return;
        }
        if (selectedImage == null || selectedImage.length != IMAGE_BYTES) {
            setStatus("请先选择图片。");
            return;
        }
        if (sending) {
            setStatus("正在上传，请稍等。");
            return;
        }

        int payloadSize = Math.max(18, Math.min(180, negotiatedMtu - 5));
        writeQueue.clear();
        sentImageBytes = 0;
        totalImageBytes = selectedImage.length;
        progressBar.setProgress(0);
        updateProgressText("准备上传", 0);

        byte[] start = new byte[9];
        start[0] = 'S';
        putU16LE(start, 1, WIDTH);
        putU16LE(start, 3, HEIGHT);
        putU32LE(start, 5, selectedImage.length);
        writeQueue.add(new WritePacket(controlCharacteristic, start, 0));

        int offset = 0;
        int seq = 0;
        while (offset < selectedImage.length) {
            int count = Math.min(payloadSize, selectedImage.length - offset);
            byte[] packet = new byte[count + 2];
            putU16LE(packet, 0, seq);
            System.arraycopy(selectedImage, offset, packet, 2, count);
            writeQueue.add(new WritePacket(dataCharacteristic, packet, count));
            offset += count;
            seq++;
        }

        sending = true;
        setStatus("正在上传图片...");
        log("开始发送：15000 字节，分包载荷 " + payloadSize + " 字节，共 " + (seq + 1) + " 个写入。");
        updateProgressText("上传中", 0);
        writeNext();
    }

    private void writeNext() {
        if (!sending || bluetoothGatt == null) {
            return;
        }
        currentPacket = writeQueue.poll();
        if (currentPacket == null) {
            sending = false;
            setStatus("上传完成，开发板正在保存并刷新。");
            log("图片数据已全部写入。");
            updateProgressText("上传完成，等待屏幕刷新", 1000);
            return;
        }

        currentPacket.characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ok = bluetoothGatt.writeCharacteristic(
                    currentPacket.characteristic,
                    currentPacket.value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == 0;
            } else {
                currentPacket.characteristic.setValue(currentPacket.value);
                ok = bluetoothGatt.writeCharacteristic(currentPacket.characteristic);
            }
        } catch (RuntimeException e) {
            sending = false;
            setStatus("BLE 写入异常，请重新连接后再试。");
            log("BLE 写入异常：" + e.getMessage());
            updateProgressText("上传失败", progressBar.getProgress());
            Log.e(TAG, "writeCharacteristic crashed", e);
            return;
        }

        if (!ok) {
            sending = false;
            setStatus("写入启动失败，请重新连接后再试。");
            log("writeCharacteristic 返回失败。");
            updateProgressText("上传失败", progressBar.getProgress());
        }
    }

    private byte[] convertImage(Uri uri) throws IOException {
        Bitmap source = decodeSampledBitmap(uri);
        if (source == null) {
            throw new IOException("无法读取所选图片。");
        }

        Bitmap canvasBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);
        canvas.drawColor(Color.WHITE);

        float scale = Math.max(WIDTH / (float) source.getWidth(), HEIGHT / (float) source.getHeight());
        float drawWidth = source.getWidth() * scale;
        float drawHeight = source.getHeight() * scale;
        float dx = (WIDTH - drawWidth) / 2f;
        float dy = (HEIGHT - drawHeight) / 2f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        canvas.drawBitmap(source, null, new RectF(dx, dy, dx + drawWidth, dy + drawHeight), paint);

        byte[] packed = new byte[IMAGE_BYTES];
        int[][] bayer = {
            {0, 8, 2, 10},
            {12, 4, 14, 6},
            {3, 11, 1, 9},
            {15, 7, 13, 5}
        };

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int color = canvasBitmap.getPixel(x, y);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                int gray = (r * 299 + g * 587 + b * 114) / 1000;
                int threshold = 92 + bayer[y & 3][x & 3] * 8;
                boolean white = gray >= threshold;
                if (white) {
                    packed[y * (WIDTH / 8) + (x / 8)] |= (byte) (1 << (x & 7));
                }
            }
        }

        previewView.setImageBitmap(renderPackedPreview(packed));
        if (source != canvasBitmap) {
            source.recycle();
        }
        canvasBitmap.recycle();
        return packed;
    }

    private Bitmap decodeSampledBitmap(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }

        int sample = 1;
        while ((bounds.outWidth / sample) > 1600 || (bounds.outHeight / sample) > 1200) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = sample;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private Bitmap renderPackedPreview(byte[] packed) {
        Bitmap preview = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        int bytesPerRow = WIDTH / 8;
        for (int y = 0; y < HEIGHT; y++) {
            int rowOffset = y * bytesPerRow;
            for (int x = 0; x < WIDTH; x++) {
                boolean white = (packed[rowOffset + (x / 8)] & (1 << (x & 7))) != 0;
                preview.setPixel(x, y, white ? Color.WHITE : Color.BLACK);
            }
        }
        return preview;
    }

    private void handleStatus(byte[] value) {
        if (value == null) {
            return;
        }
        final String message = new String(value, StandardCharsets.UTF_8);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                log("开发板：" + translateBoardStatus(message));
            }
        });
    }

    private String translateBoardStatus(String status) {
        if ("READY".equals(status)) return "已就绪";
        if ("CONNECTED".equals(status)) return "已连接";
        if ("DISCONNECTED".equals(status)) return "已断开";
        if ("DONE".equals(status)) return "接收完成";
        if ("SAVING".equals(status)) return "正在保存到 Flash";
        if ("SAVED".equals(status)) return "已保存到 Flash";
        if ("DRAWING".equals(status)) return "正在刷新屏幕";
        if ("DISPLAYED".equals(status)) return "已显示";
        if (status.startsWith("RX ")) return "接收进度 " + status.substring(3);
        if (status.startsWith("ERROR")) return "错误：" + status;
        return status;
    }

    private void updateProgress() {
        if (totalImageBytes <= 0) {
            progressBar.setProgress(0);
            return;
        }
        int progress = (int) Math.min(1000, (sentImageBytes * 1000L) / totalImageBytes);
        progressBar.setProgress(progress);
        setStatus(String.format(Locale.CHINA, "上传中：%d/%d 字节", sentImageBytes, totalImageBytes));
        updateProgressText("上传中", progress);
    }

    private void updateProgressText(String label, int progress) {
        if (progressText == null) {
            return;
        }
        int percent = Math.max(0, Math.min(100, progress / 10));
        progressText.setText(label + "  " + percent + "%");
    }

    private void setStatus(String status) {
        statusText.setText(status);
    }

    private void logOnUi(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                log(message);
            }
        });
    }

    private void log(String message) {
        logText.append(message + "\n");
    }

    private String safeAddress(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (SecurityException e) {
            return "未知地址";
        }
    }

    private void putU16LE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

    private void putU32LE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >> 8) & 0xff);
        target[offset + 2] = (byte) ((value >> 16) & 0xff);
        target[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(37, 46, 59));
        view.setPadding(dp(2), dp(4), 0, dp(6));
        return view;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(52));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        button.setBackground(buttonBg(Color.rgb(23, 96, 160), Color.rgb(16, 72, 122), 0));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(Color.rgb(29, 57, 82));
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(52));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        button.setBackground(buttonBg(Color.WHITE, Color.rgb(229, 236, 244), Color.rgb(194, 205, 218)));
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams(int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(54), 1);
        params.setMargins(left, 0, right, 0);
        return params;
    }

    private StateListDrawable buttonBg(int normalColor, int pressedColor, int strokeColor) {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_pressed}, roundRect(pressedColor, dp(12), strokeColor, strokeColor == 0 ? 0 : 1));
        drawable.addState(new int[]{android.R.attr.state_focused}, roundRect(pressedColor, dp(12), strokeColor, strokeColor == 0 ? 0 : 1));
        drawable.addState(new int[]{}, roundRect(normalColor, dp(12), strokeColor, strokeColor == 0 ? 0 : 1));
        return drawable;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private LayerDrawable progressDrawable() {
        GradientDrawable background = roundRect(Color.rgb(221, 229, 239), dp(8), 0, 0);
        GradientDrawable progress = roundRect(Color.rgb(23, 96, 160), dp(8), 0, 0);
        ClipDrawable clip = new ClipDrawable(progress, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable layer = new LayerDrawable(new android.graphics.drawable.Drawable[]{background, clip});
        layer.setId(0, android.R.id.background);
        layer.setId(1, android.R.id.progress);
        layer.setLayerHeight(0, dp(12));
        layer.setLayerHeight(1, dp(12));
        return layer;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class DeviceEntry {
        final BluetoothDevice device;
        final String address;
        String name;
        int rssi;
        boolean likelyBoard;

        DeviceEntry(BluetoothDevice device, String name, String address, int rssi, boolean likelyBoard) {
            this.device = device;
            this.name = name == null || name.length() == 0 ? "未命名 BLE 设备" : name;
            this.address = address;
            this.rssi = rssi;
            this.likelyBoard = likelyBoard;
        }
    }

    private class DeviceAdapter extends ArrayAdapter<DeviceEntry> {
        DeviceAdapter(Context context, ArrayList<DeviceEntry> entries) {
            super(context, 0, entries);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            DeviceEntry entry = getItem(position);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setBackgroundColor(Color.WHITE);

            TextView name = new TextView(getContext());
            name.setText((entry != null && entry.likelyBoard ? "开发板  " : "") + (entry == null ? "" : entry.name));
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(entry != null && entry.likelyBoard ? Color.rgb(12, 94, 58) : Color.rgb(38, 44, 52));
            row.addView(name);

            TextView detail = new TextView(getContext());
            detail.setText(entry == null ? "" : entry.address + "    信号 " + entry.rssi);
            detail.setTextSize(12);
            detail.setTextColor(Color.rgb(105, 116, 130));
            detail.setPadding(0, dp(3), 0, 0);
            row.addView(detail);

            return row;
        }
    }

    private static class WritePacket {
        final BluetoothGattCharacteristic characteristic;
        final byte[] value;
        final int imageBytes;

        WritePacket(BluetoothGattCharacteristic characteristic, byte[] value, int imageBytes) {
            this.characteristic = characteristic;
            this.value = value;
            this.imageBytes = imageBytes;
        }
    }
}
