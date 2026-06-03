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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
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
    private TextView progressText;
    private TextView imageStateText;
    private TextView navConnect;
    private TextView navUpload;
    private FrameLayout pageContainer;
    private LinearLayout connectPage;
    private LinearLayout uploadPage;
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
    private int currentPage = 0;

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
            setStatus("扫描失败");
            log("扫描失败：" + errorCode);
            Log.w(TAG, "scan failed: " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                log("已连接蓝牙，正在发现服务...");
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
                        setStatus("未连接");
                        log("蓝牙连接已断开。");
                    }
                });
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            negotiatedMtu = mtu > 0 ? mtu : 23;
            Log.d(TAG, "mtu changed: " + negotiatedMtu + ", status=" + status);
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            Log.d(TAG, "services discovered status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatusOnUi("连接失败");
                log("发现服务失败：" + status);
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                Log.w(TAG, "target service not found");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("设备不匹配");
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
                setStatusOnUi("固件需更新");
                log("开发板服务不完整，请确认固件已更新。");
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
                    setStatus("已连接");
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
                        setStatus("上传失败");
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
            setStatus("蓝牙不可用");
        } else if (!bluetoothAdapter.isEnabled()) {
            setStatus("请打开蓝牙");
            log("请先在系统设置中打开蓝牙。");
        } else {
            setStatus("准备就绪");
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
                markImageReady();
            } catch (SecurityException ignored) {
                try {
                    selectedImage = convertImage(data.getData());
                    markImageReady();
                } catch (IOException e) {
                    setStatus("图片处理失败");
                    log(e.getMessage());
                }
            } catch (IOException e) {
                setStatus("图片处理失败");
                log(e.getMessage());
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasBlePermissions()) {
                setStatus("可以扫描");
                log("蓝牙权限已授权。");
            } else {
                setStatus("需要蓝牙权限");
                log("请在系统设置中允许“附近设备/蓝牙”权限。");
            }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(12));
        root.setBackgroundColor(Color.rgb(247, 247, 244));

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setTextColor(Color.rgb(31, 43, 55));
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(14), dp(11), dp(14), dp(11));
        statusText.setBackground(roundRect(Color.WHITE, dp(12), Color.rgb(218, 223, 229), 1));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, 0, 0, dp(12));
        root.addView(statusText, statusParams);

        pageContainer = new FrameLayout(this);
        root.addView(pageContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        connectPage = buildConnectPage();
        uploadPage = buildUploadPage();
        pageContainer.addView(connectPage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageContainer.addView(uploadPage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        nav.setBackground(roundRect(Color.WHITE, dp(16), Color.rgb(221, 225, 230), 1));
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        navParams.setMargins(0, dp(10), 0, 0);
        root.addView(nav, navParams);

        navConnect = navItem("连接");
        navConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showPage(0);
            }
        });
        nav.addView(navConnect, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        navUpload = navItem("上传");
        navUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showPage(1);
            }
        });
        LinearLayout.LayoutParams uploadNavParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        uploadNavParams.setMargins(dp(6), 0, 0, 0);
        nav.addView(navUpload, uploadNavParams);

        showPage(0);
        setContentView(root);
    }

    private LinearLayout buildConnectPage() {
        LinearLayout page = pageShell();
        page.addView(pageTitle("连接"));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(12), 0, dp(14));
        page.addView(actionRow, rowParams);

        Button scanButton = primaryButton("扫描");
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startScan();
            }
        });
        actionRow.addView(scanButton, weightedButtonParams(0, dp(5)));

        Button disconnectButton = secondaryButton("断开");
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                disconnectGatt();
            }
        });
        actionRow.addView(disconnectButton, weightedButtonParams(dp(5), 0));

        TextView deviceTitle = sectionTitle("开发板");
        page.addView(deviceTitle);

        deviceAdapter = new DeviceAdapter(this, devices);
        ListView deviceList = new ListView(this);
        deviceList.setDivider(null);
        deviceList.setAdapter(deviceAdapter);
        deviceList.setBackground(roundRect(Color.WHITE, dp(12), Color.rgb(223, 226, 231), 1));
        deviceList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                connect(devices.get(position).device);
            }
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132));
        listParams.setMargins(0, dp(6), 0, 0);
        page.addView(deviceList, listParams);

        return page;
    }

    private LinearLayout buildUploadPage() {
        LinearLayout page = pageShell();
        page.addView(pageTitle("上传"));

        imageStateText = sectionTitle("未选择图片");
        LinearLayout.LayoutParams imageStateParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        imageStateParams.setMargins(0, dp(8), 0, dp(6));
        page.addView(imageStateText, imageStateParams);

        previewView = new ImageView(this);
        previewView.setBackground(roundRect(Color.WHITE, dp(12), Color.rgb(223, 226, 231), 1));
        previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewView.setPadding(dp(8), dp(8), dp(8), dp(8));
        page.addView(previewView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(14), 0, dp(16));
        page.addView(actionRow, rowParams);

        Button pickButton = primaryButton("选择图片");
        pickButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickImage();
            }
        });
        actionRow.addView(pickButton, weightedButtonParams(0, dp(5)));

        Button sendButton = secondaryButton("上传");
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendSelectedImage();
            }
        });
        actionRow.addView(sendButton, weightedButtonParams(dp(5), 0));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgressDrawable(progressDrawable());
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.setMargins(0, dp(2), 0, dp(8));
        page.addView(progressBar, progressParams);

        progressText = new TextView(this);
        progressText.setText("等待上传");
        progressText.setTextSize(13);
        progressText.setTypeface(Typeface.DEFAULT_BOLD);
        progressText.setTextColor(Color.rgb(31, 43, 55));
        progressText.setGravity(Gravity.CENTER);
        page.addView(progressText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return page;
    }

    private LinearLayout pageShell() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(2), dp(2), dp(2), 0);
        return page;
    }

    private TextView pageTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(26);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(25, 31, 38));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(2), dp(6), 0, dp(4));
        return view;
    }

    private TextView navItem(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setPadding(0, 0, 0, dp(1));
        return view;
    }

    private void showPage(int page) {
        currentPage = page;
        if (connectPage != null) {
            connectPage.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        }
        if (uploadPage != null) {
            uploadPage.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        }
        updateNavState();
    }

    private void updateNavState() {
        styleNav(navConnect, currentPage == 0);
        styleNav(navUpload, currentPage == 1);
    }

    private void styleNav(TextView view, boolean selected) {
        if (view == null) {
            return;
        }
        view.setTextColor(selected ? Color.WHITE : Color.rgb(68, 78, 90));
        view.setBackground(roundRect(selected ? Color.rgb(23, 96, 160) : Color.TRANSPARENT, dp(12), 0, 0));
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

    private void markImageReady() {
        setStatus("图片已选");
        if (imageStateText != null) {
            imageStateText.setText("图片已选");
        }
        if (progressBar != null) {
            progressBar.setProgress(0);
        }
        updateProgressText("等待上传", 0);
        log("图片已转成屏幕可显示的黑白数据。");
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
            setStatus("蓝牙不可用");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            setStatus("请打开蓝牙");
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        if (!hasBlePermissions()) {
            requestRuntimePermissions();
            setStatus("需要蓝牙权限");
            return;
        }

        stopScan();
        devices.clear();
        deviceAdapter.notifyDataSetChanged();
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("扫描不可用");
            return;
        }

        scanning = true;
        setStatus("扫描中");
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
                    setStatus("未找到开发板");
                    log("没有发现 RLCD-BLE-IMG：请确认蓝牙权限、定位开关、开发板供电和距离。");
                } else {
                    setStatus("找到开发板");
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
        setStatus("找到开发板");
        deviceAdapter.notifyDataSetChanged();
    }

    private void connect(BluetoothDevice device) {
        if (!hasBlePermissions()) {
            requestRuntimePermissions();
            return;
        }
        stopScan();
        disconnectGatt();
        setStatus("连接中");
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
        setStatus("未连接");
    }

    private void enableStatusNotifications(BluetoothGatt gatt) {
        gatt.setCharacteristicNotification(statusCharacteristic, true);
        BluetoothGattDescriptor descriptor = statusCharacteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    connected = true;
                    setStatus("已连接");
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
                setStatus("已连接");
                log(message);
            }
        });
    }

    private void sendSelectedImage() {
        if (!connected || bluetoothGatt == null || controlCharacteristic == null || dataCharacteristic == null) {
            setStatus("先连接开发板");
            return;
        }
        if (selectedImage == null || selectedImage.length != IMAGE_BYTES) {
            setStatus("先选择图片");
            return;
        }
        if (sending) {
            setStatus("上传中");
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
        setStatus("上传中");
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
            setStatus("等待刷新");
            log("图片数据已全部写入。");
            updateProgressText("等待刷新", 1000);
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
            setStatus("上传失败");
            log("BLE 写入异常：" + e.getMessage());
            updateProgressText("上传失败", progressBar.getProgress());
            Log.e(TAG, "writeCharacteristic crashed", e);
            return;
        }

        if (!ok) {
            sending = false;
            setStatus("上传失败");
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
                updateBoardStatus(message);
            }
        });
    }

    private void updateBoardStatus(String message) {
        log("开发板：" + translateBoardStatus(message));
        if ("SAVING".equals(message) || "DRAWING".equals(message)) {
            setStatus("等待刷新");
        } else if ("DISPLAYED".equals(message)) {
            setStatus("已显示");
            updateProgressText("已显示", 1000);
        } else if (message.startsWith("ERROR")) {
            setStatus("上传失败");
            updateProgressText("上传失败", progressBar == null ? 0 : progressBar.getProgress());
        }
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
        setStatus("上传中");
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
        if (statusText != null) {
            statusText.setText(status);
        }
    }

    private void setStatusOnUi(final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setStatus(status);
            }
        });
    }

    private void log(String message) {
        if (message != null) {
            Log.d(TAG, message);
        }
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
            name.setText(entry == null ? "" : "开发板");
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(entry != null && entry.likelyBoard ? Color.rgb(12, 94, 58) : Color.rgb(38, 44, 52));
            row.addView(name);

            TextView detail = new TextView(getContext());
            detail.setText(entry == null ? "" : "信号 " + entry.rssi);
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
