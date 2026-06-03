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
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int MAX_TEXT_BYTES = 4096;
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
    private TextView boardStateText;
    private TextView boardSignalText;
    private TextView boardDetailText;
    private TextView boardLastStatusText;
    private TextView connectBleText;
    private TextView connectServiceText;
    private TextView connectTransferText;
    private TextView uploadConnectionText;
    private TextView uploadImageText;
    private TextView uploadScreenText;
    private TextView connectScreenText;
    private TextView uploadOutputText;
    private TextView navConnect;
    private TextView navUpload;
    private Button connectButton;
    private LinearLayout connectPage;
    private LinearLayout uploadPage;
    private ImageView previewView;
    private ProgressBar progressBar;
    private EditText textInput;
    private TextView textStateText;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic controlCharacteristic;
    private BluetoothGattCharacteristic dataCharacteristic;
    private BluetoothGattCharacteristic statusCharacteristic;

    private byte[] selectedImage;
    private int negotiatedMtu = 23;
    private boolean scanning = false;
    private boolean connecting = false;
    private boolean connected = false;
    private boolean sending = false;
    private int lastBoardRssi = Integer.MIN_VALUE;
    private WritePacket currentPacket;
    private int totalImageBytes = 0;
    private int sentImageBytes = 0;
    private int currentPage = 0;
    private String currentTransferLabel = "图片";

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
            connecting = false;
            setStatus("扫描失败");
            updateBoardCard("扫描失败", null, "请重试");
            updateConnectButton();
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
                        connecting = false;
                        sending = false;
                        controlCharacteristic = null;
                        dataCharacteristic = null;
                        statusCharacteristic = null;
                        setStatus("未连接");
                        updateBoardCard("未连接", null, "点击连接开发板");
                        setLargeStatus(connectScreenText, "屏幕", "待上传");
                        updateConnectButton();
                        log("蓝牙连接已断开。");
                    }
                });
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            negotiatedMtu = mtu > 0 ? mtu : 23;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateTransferMetric();
                }
            });
            Log.d(TAG, "mtu changed: " + negotiatedMtu + ", status=" + status);
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            Log.d(TAG, "services discovered status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatusOnUi("连接失败");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        connecting = false;
                        updateBoardCard("连接失败", null, "请重试");
                        updateConnectButton();
                    }
                });
                log("发现服务失败：" + status);
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                Log.w(TAG, "target service not found");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        connecting = false;
                        setStatus("设备不匹配");
                        updateBoardCard("设备不匹配", null, "请确认固件");
                        updateConnectButton();
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
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        connecting = false;
                        updateBoardCard("固件需更新", null, "请重新烧录固件");
                        updateConnectButton();
                    }
                });
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
                    connecting = false;
                    setStatus("已连接");
                    updateBoardCard("已连接", null, "可以上传图片或文字");
                    updateTransferMetric();
                    updateConnectButton();
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
                        setMetric(uploadScreenText, "屏幕", "上传失败");
                        if (textStateText != null) {
                            textStateText.setText("上传失败");
                        }
                        updateProgressText("上传失败", progressBar == null ? 0 : progressBar.getProgress());
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
        root.setPadding(dp(12), topContentPadding(), dp(12), dp(8));
        root.setBackgroundColor(Color.rgb(247, 247, 244));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        connectPage = buildConnectPage();
        uploadPage = buildUploadPage();
        content.addView(connectPage);
        content.addView(uploadPage);
        content.addView(buildTextSection(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView footer = new TextView(this);
        footer.setText("Power by zstar");
        footer.setTextSize(11);
        footer.setTextColor(Color.rgb(132, 143, 156));
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.setMargins(0, 0, 0, 0);
        root.addView(footer, footerParams);

        setContentView(root);
    }

    private LinearLayout buildConnectPage() {
        LinearLayout page = pageShell();
        page.addView(pageTitle("连接"));

        page.addView(buildBoardCard());

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(10), 0, dp(10));
        page.addView(actionRow, rowParams);

        connectButton = primaryButton("连接开发板");
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startScan();
            }
        });
        actionRow.addView(connectButton, weightedButtonParams(0, dp(5)));

        Button disconnectButton = secondaryButton("断开");
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                disconnectGatt();
            }
        });
        actionRow.addView(disconnectButton, weightedButtonParams(dp(5), 0));

        return page;
    }

    private LinearLayout buildBoardCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(7), dp(10), dp(7));
        card.setBackground(roundRect(Color.WHITE, dp(16), Color.rgb(221, 225, 230), 1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(6), 0, 0);
        card.setLayoutParams(cardParams);

        TextView badge = new TextView(this);
        badge.setText("4.2");
        badge.setTextSize(16);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(roundRect(Color.rgb(23, 96, 160), dp(14), 0, 0));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        card.addView(badge, badgeParams);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        infoParams.setMargins(dp(12), 0, 0, 0);
        card.addView(infoCol, infoParams);

        TextView name = new TextView(this);
        name.setText("ESP32-S3-RLCD");
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Color.rgb(25, 31, 38));
        infoCol.addView(name);

        boardDetailText = new TextView(this);
        boardDetailText.setText(DEVICE_NAME);
        boardDetailText.setTextSize(11);
        boardDetailText.setTextColor(Color.rgb(96, 107, 120));
        boardDetailText.setPadding(0, dp(2), 0, 0);
        infoCol.addView(boardDetailText);

        return card;
    }

    private LinearLayout buildConnectDetailsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.setMargins(0, dp(12), 0, 0);
        panel.setLayoutParams(panelParams);

        panel.addView(sectionTitle("状态"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(6), 0, 0);
        panel.addView(row, rowParams);

        connectBleText = metricView("蓝牙", "就绪");
        row.addView(connectBleText, new LinearLayout.LayoutParams(0, dp(78), 1));

        connectServiceText = metricView("服务", "待连接");
        LinearLayout.LayoutParams middleParams = new LinearLayout.LayoutParams(0, dp(78), 1);
        middleParams.setMargins(dp(8), 0, 0, 0);
        row.addView(connectServiceText, middleParams);

        connectTransferText = metricView("传输", "--");
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, dp(78), 1);
        rightParams.setMargins(dp(8), 0, 0, 0);
        row.addView(connectTransferText, rightParams);

        return panel;
    }

    private LinearLayout buildUploadPage() {
        LinearLayout page = pageShell();
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pageParams.setMargins(0, dp(7), 0, 0);
        page.setLayoutParams(pageParams);
        page.addView(pageTitle("上传图片"));

        previewView = new AspectRatioImageView(this);
        previewView.setBackground(roundRect(Color.WHITE, dp(12), Color.rgb(223, 226, 231), 1));
        previewView.setScaleType(ImageView.ScaleType.FIT_XY);
        previewView.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        page.addView(previewView, previewParams);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(8), 0, dp(8));
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
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        progressParams.setMargins(0, dp(1), 0, dp(4));
        page.addView(progressBar, progressParams);

        progressText = new TextView(this);
        progressText.setText("等待上传");
        progressText.setTextSize(12);
        progressText.setTypeface(Typeface.DEFAULT_BOLD);
        progressText.setTextColor(Color.rgb(31, 43, 55));
        progressText.setGravity(Gravity.CENTER);
        page.addView(progressText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return page;
    }

    private LinearLayout buildTextSection() {
        LinearLayout page = pageShell();
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pageParams.setMargins(0, dp(7), 0, 0);
        page.setLayoutParams(pageParams);
        page.addView(pageTitle("上传文字"));

        textInput = new EditText(this);
        textInput.setTextSize(15);
        textInput.setTextColor(Color.rgb(25, 31, 38));
        textInput.setHint("输入要显示的文字");
        textInput.setHintTextColor(Color.rgb(132, 143, 156));
        textInput.setGravity(Gravity.TOP | Gravity.START);
        textInput.setMinLines(3);
        textInput.setMaxLines(6);
        textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        textInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        textInput.setBackground(roundRect(Color.WHITE, dp(12), Color.rgb(223, 226, 231), 1));
        page.addView(textInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(5), 0, 0);
        page.addView(actionRow, rowParams);

        Button sendTextButton = primaryButton("上传文字");
        sendTextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendSelectedText();
            }
        });
        actionRow.addView(sendTextButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        textStateText = new TextView(this);
        textStateText.setText("等待输入");
        textStateText.setTextSize(12);
        textStateText.setTextColor(Color.rgb(68, 78, 90));
        textStateText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textStateParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textStateParams.setMargins(0, dp(2), 0, 0);
        page.addView(textStateText, textStateParams);

        return page;
    }

    private LinearLayout buildUploadStatePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.setMargins(0, dp(22), 0, 0);
        panel.setLayoutParams(panelParams);

        panel.addView(sectionTitle("准备"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(6), 0, 0);
        panel.addView(row, rowParams);

        uploadConnectionText = metricView("连接", "未连接");
        row.addView(uploadConnectionText, new LinearLayout.LayoutParams(0, dp(78), 1));

        uploadImageText = metricView("图片", "未选择");
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(0, dp(78), 1);
        imageParams.setMargins(dp(8), 0, 0, 0);
        row.addView(uploadImageText, imageParams);

        uploadScreenText = metricView("屏幕", "待上传");
        LinearLayout.LayoutParams screenParams = new LinearLayout.LayoutParams(0, dp(78), 1);
        screenParams.setMargins(dp(8), 0, 0, 0);
        row.addView(uploadScreenText, screenParams);

        return panel;
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
        view.setTextSize(20);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(25, 31, 38));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(2), 0, 0, dp(1));
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

    private void updateBoardCard(String state, Integer rssi, String detail) {
        if (rssi != null) {
            lastBoardRssi = rssi;
        }
        setMetric(connectServiceText, "服务", state);
        setMetric(uploadConnectionText, "连接", connected ? "已连接" : "未连接");
        if (boardStateText != null) {
            boardStateText.setText(state);
            boardStateText.setTextColor(boardStateColor(state));
            boardStateText.setBackground(roundRect(boardStateBg(state), dp(12), 0, 0));
        }
        if (boardSignalText != null) {
            String signal = lastBoardRssi == Integer.MIN_VALUE ? "--" : lastBoardRssi + " dBm";
            boardSignalText.setText("信号\n" + signal);
            setMetric(connectBleText, "蓝牙", signal);
        }
        if (boardLastStatusText != null) {
            boardLastStatusText.setText("状态\n" + state);
        }
    }

    private void updateTransferMetric() {
        String value = connected ? negotiatedMtu + " MTU" : "--";
        setMetric(connectTransferText, "传输", value);
    }

    private int boardStateColor(String state) {
        if ("已连接".equals(state) || "已显示".equals(state)) {
            return Color.rgb(12, 94, 58);
        }
        if ("扫描中".equals(state) || "连接中".equals(state) || "等待刷新".equals(state)) {
            return Color.rgb(23, 96, 160);
        }
        if ("未找到".equals(state) || "连接失败".equals(state) || "上传失败".equals(state) || "设备不匹配".equals(state)) {
            return Color.rgb(156, 54, 44);
        }
        return Color.rgb(68, 78, 90);
    }

    private int boardStateBg(String state) {
        if ("已连接".equals(state) || "已显示".equals(state)) {
            return Color.rgb(225, 244, 235);
        }
        if ("扫描中".equals(state) || "连接中".equals(state) || "等待刷新".equals(state)) {
            return Color.rgb(230, 239, 250);
        }
        if ("未找到".equals(state) || "连接失败".equals(state) || "上传失败".equals(state) || "设备不匹配".equals(state)) {
            return Color.rgb(252, 235, 233);
        }
        return Color.rgb(241, 244, 248);
    }

    private void updateConnectButton() {
        if (connectButton == null) {
            return;
        }
        if (scanning || connecting) {
            connectButton.setText("正在连接");
            connectButton.setEnabled(false);
        } else {
            connectButton.setText(connected ? "已连接" : "连接开发板");
            connectButton.setEnabled(!connected);
        }
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
        setMetric(uploadImageText, "图片", "400 x 300");
        setMetric(uploadScreenText, "屏幕", "待上传");
        setLargeStatus(uploadOutputText, "输出", "400 x 300 黑白");
        setLargeStatus(connectScreenText, "屏幕", "图片已准备");
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
        if (connected) {
            setStatus("已连接");
            updateBoardCard("已连接", null, "可以上传图片或文字");
            updateConnectButton();
            return;
        }
        if (scanning || connecting) {
            setStatus("连接中");
            return;
        }
        if (bluetoothAdapter == null) {
            setStatus("蓝牙不可用");
            updateBoardCard("蓝牙不可用", null, "当前手机不支持蓝牙");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            setStatus("请打开蓝牙");
            updateBoardCard("未连接", null, "请打开手机蓝牙");
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        if (!hasBlePermissions()) {
            requestRuntimePermissions();
            setStatus("需要蓝牙权限");
            updateBoardCard("未连接", null, "请允许蓝牙权限");
            return;
        }

        stopScan();
        devices.clear();
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("扫描不可用");
            updateBoardCard("扫描失败", null, "请重启蓝牙后重试");
            return;
        }

        scanning = true;
        setStatus("扫描中");
        updateBoardCard("扫描中", null, "正在查找附近的开发板");
        setMetric(connectBleText, "蓝牙", "扫描中");
        setMetric(connectTransferText, "传输", "--");
        updateConnectButton();
        log("开始扫描 15 秒，只显示 RLCD-BLE-IMG。若没有结果，请确认开发板已上电并靠近手机。");
        Log.d(TAG, "start scan");

        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();
        scanner.startScan(null, settings, scanCallback);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!scanning) {
                    return;
                }
                stopScan();
                if (devices.isEmpty() && !connected && !connecting) {
                    setStatus("未找到开发板");
                    updateBoardCard("未找到", null, "靠近开发板后重试");
                    setMetric(connectBleText, "蓝牙", "就绪");
                    log("没有发现 RLCD-BLE-IMG：请确认蓝牙权限、定位开关、开发板供电和距离。");
                } else if (!connected && !connecting) {
                    setStatus("找到开发板");
                    updateBoardCard("已发现", null, "正在连接");
                }
                updateConnectButton();
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
                updateBoardCard(connected ? "已连接" : (connecting ? "连接中" : "已发现"), result.getRssi(), connected ? "可以上传图片或文字" : "正在连接");
                return;
            }
        }

        DeviceEntry entry = new DeviceEntry(device, name, address, result.getRssi(), likelyBoard);
        devices.add(0, entry);
        lastBoardRssi = result.getRssi();
        setStatus("找到开发板");
        updateBoardCard("已发现", result.getRssi(), "正在连接");
        if (!connected && !connecting) {
            connecting = true;
            updateConnectButton();
            stopScan();
            final BluetoothDevice foundDevice = device;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    connect(foundDevice);
                }
            });
        }
    }

    private void connect(BluetoothDevice device) {
        if (!hasBlePermissions()) {
            connecting = false;
            updateConnectButton();
            requestRuntimePermissions();
            return;
        }
        stopScan();
        disconnectGatt();
        connecting = true;
        setStatus("连接中");
        updateBoardCard("连接中", null, "正在连接开发板");
        setMetric(uploadConnectionText, "连接", "连接中");
        updateConnectButton();
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
        connecting = false;
        connected = false;
        sending = false;
        controlCharacteristic = null;
        dataCharacteristic = null;
        statusCharacteristic = null;
        setStatus("未连接");
        updateBoardCard("未连接", null, "点击连接开发板");
        setMetric(connectBleText, "蓝牙", "就绪");
        setMetric(connectTransferText, "传输", "--");
        setMetric(uploadConnectionText, "连接", "未连接");
        setLargeStatus(connectScreenText, "屏幕", "待上传");
        updateConnectButton();
    }

    private void enableStatusNotifications(BluetoothGatt gatt) {
        gatt.setCharacteristicNotification(statusCharacteristic, true);
        BluetoothGattDescriptor descriptor = statusCharacteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    connected = true;
                    connecting = false;
                    setStatus("已连接");
                    updateBoardCard("已连接", null, "可以上传图片");
                    updateTransferMetric();
                    updateConnectButton();
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
                    connecting = false;
                    setStatus("已连接");
                    updateBoardCard("已连接", null, "可以上传图片或文字");
                    updateTransferMetric();
                    updateConnectButton();
                    log(message);
                }
            });
    }

    private void sendSelectedImage() {
        if (!connected || bluetoothGatt == null || controlCharacteristic == null || dataCharacteristic == null) {
            showConnectToast();
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

        currentTransferLabel = "图片";
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
        setStatus("图片上传中");
        setMetric(uploadScreenText, "屏幕", "上传中");
        setLargeStatus(uploadOutputText, "输出", "上传中");
        setLargeStatus(connectScreenText, "屏幕", "上传中");
        log("开始发送：15000 字节，分包载荷 " + payloadSize + " 字节，共 " + (seq + 1) + " 个写入。");
        updateProgressText("上传中", 0);
        writeNext();
    }

    private void sendSelectedText() {
        if (!connected || bluetoothGatt == null || controlCharacteristic == null || dataCharacteristic == null) {
            showConnectToast();
            return;
        }
        if (textInput == null) {
            return;
        }
        String text = textInput.getText().toString().trim();
        if (text.length() == 0) {
            setStatus("先输入文字");
            if (textStateText != null) {
                textStateText.setText("请输入文字");
            }
            return;
        }
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        if (textBytes.length > MAX_TEXT_BYTES) {
            setStatus("文字太长");
            if (textStateText != null) {
                textStateText.setText("最多 " + MAX_TEXT_BYTES + " 字节");
            }
            return;
        }
        if (sending) {
            setStatus("上传中");
            return;
        }

        currentTransferLabel = "文字";
        int payloadSize = Math.max(18, Math.min(180, negotiatedMtu - 5));
        writeQueue.clear();
        sentImageBytes = 0;
        totalImageBytes = textBytes.length;
        if (progressBar != null) {
            progressBar.setProgress(0);
        }
        updateProgressText("准备上传", 0);

        byte[] start = new byte[5];
        start[0] = 'T';
        putU32LE(start, 1, textBytes.length);
        writeQueue.add(new WritePacket(controlCharacteristic, start, 0));

        int offset = 0;
        int seq = 0;
        while (offset < textBytes.length) {
            int count = Math.min(payloadSize, textBytes.length - offset);
            byte[] packet = new byte[count + 2];
            putU16LE(packet, 0, seq);
            System.arraycopy(textBytes, offset, packet, 2, count);
            writeQueue.add(new WritePacket(dataCharacteristic, packet, count));
            offset += count;
            seq++;
        }

        sending = true;
        setStatus("文字上传中");
        if (textStateText != null) {
            textStateText.setText("上传中");
        }
        log("开始发送文字：" + textBytes.length + " 字节，分包载荷 " + payloadSize + " 字节，共 " + (seq + 1) + " 个写入。");
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
            log(currentTransferLabel + "数据已全部写入。");
            setMetric(uploadScreenText, "屏幕", "刷新中");
            setLargeStatus(uploadOutputText, "输出", "刷新中");
            setLargeStatus(connectScreenText, "屏幕", "刷新中");
            if ("文字".equals(currentTransferLabel) && textStateText != null) {
                textStateText.setText("等待刷新");
            }
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
            setMetric(uploadScreenText, "屏幕", "上传失败");
            setLargeStatus(uploadOutputText, "输出", "上传失败");
            setLargeStatus(connectScreenText, "屏幕", "上传失败");
            if (textStateText != null) {
                textStateText.setText("上传失败");
            }
            updateProgressText("上传失败", progressBar.getProgress());
            Log.e(TAG, "writeCharacteristic crashed", e);
            return;
        }

        if (!ok) {
            sending = false;
            setStatus("上传失败");
            log("writeCharacteristic 返回失败。");
            setMetric(uploadScreenText, "屏幕", "上传失败");
            setLargeStatus(uploadOutputText, "输出", "上传失败");
            setLargeStatus(connectScreenText, "屏幕", "上传失败");
            if (textStateText != null) {
                textStateText.setText("上传失败");
            }
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
        String translated = translateBoardStatus(message);
        if ("READY".equals(message) || "CONNECTED".equals(message) || "SAVED".equals(message) || "DONE".equals(message)
            || "TEXT_SAVED".equals(message) || "TEXT_DONE".equals(message)) {
            updateBoardCard(connected ? "已连接" : translated, null, translated);
            if ("TEXT_DONE".equals(message) && textStateText != null) {
                textStateText.setText("接收完成");
            } else if ("TEXT_SAVED".equals(message) && textStateText != null) {
                textStateText.setText("已保存");
            }
        } else if ("SAVING".equals(message) || "DRAWING".equals(message)
            || "TEXT_SAVING".equals(message) || "TEXT_DRAWING".equals(message)) {
            setStatus("等待刷新");
            updateBoardCard("等待刷新", null, translated);
            setMetric(uploadScreenText, "屏幕", "刷新中");
            setLargeStatus(uploadOutputText, "输出", "刷新中");
            setLargeStatus(connectScreenText, "屏幕", "刷新中");
            if (message.startsWith("TEXT_") && textStateText != null) {
                textStateText.setText("刷新中");
            }
        } else if ("DISPLAYED".equals(message) || "TEXT_DISPLAYED".equals(message)) {
            setStatus("TEXT_DISPLAYED".equals(message) ? "文字已显示" : "图片已显示");
            updateBoardCard("已显示", null, "屏幕已刷新");
            setMetric(uploadScreenText, "屏幕", "已显示");
            setLargeStatus(uploadOutputText, "输出", "已显示");
            setLargeStatus(connectScreenText, "屏幕", "已显示");
            if ("TEXT_DISPLAYED".equals(message) && textStateText != null) {
                textStateText.setText("已显示");
            }
            updateProgressText("已显示", 1000);
        } else if ("DISCONNECTED".equals(message)) {
            updateBoardCard("未连接", null, "开发板已断开");
            setMetric(uploadConnectionText, "连接", "未连接");
            setLargeStatus(connectScreenText, "屏幕", "待上传");
        } else if (message.startsWith("ERROR")) {
            setStatus("上传失败");
            updateBoardCard("上传失败", null, translated);
            setMetric(uploadScreenText, "屏幕", "上传失败");
            setLargeStatus(uploadOutputText, "输出", "上传失败");
            setLargeStatus(connectScreenText, "屏幕", "上传失败");
            if (textStateText != null) {
                textStateText.setText("上传失败");
            }
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
        if ("TEXT_DONE".equals(status)) return "文字接收完成";
        if ("TEXT_SAVING".equals(status)) return "正在保存文字";
        if ("TEXT_SAVED".equals(status)) return "文字已保存";
        if ("TEXT_DRAWING".equals(status)) return "正在显示文字";
        if ("TEXT_DISPLAYED".equals(status)) return "文字已显示";
        if (status.startsWith("TEXT_START ")) return "开始接收文字";
        if (status.startsWith("RX ")) return "接收进度 " + status.substring(3);
        if (status.startsWith("ERROR")) return "错误：" + status;
        return status;
    }

    private void updateProgress() {
        if (totalImageBytes <= 0) {
            if (progressBar != null) {
                progressBar.setProgress(0);
            }
            return;
        }
        int progress = (int) Math.min(1000, (sentImageBytes * 1000L) / totalImageBytes);
        if (progressBar != null) {
            progressBar.setProgress(progress);
        }
        setStatus(currentTransferLabel + "上传中");
        int percent = Math.max(0, Math.min(100, progress / 10));
        setLargeStatus(uploadOutputText, "输出", "上传中 " + percent + "%");
        setLargeStatus(connectScreenText, "屏幕", "上传中 " + percent + "%");
        if ("文字".equals(currentTransferLabel) && textStateText != null) {
            textStateText.setText("上传中 " + percent + "%");
        }
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

    private void showConnectToast() {
        setStatus("请先连接板卡");
        Toast.makeText(this, "请先连接板卡", Toast.LENGTH_SHORT).show();
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
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(37, 46, 59));
        view.setPadding(dp(2), dp(2), 0, dp(3));
        return view;
    }

    private TextView metricView(String label, String value) {
        TextView view = new TextView(this);
        view.setText(label + "\n" + value);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(31, 43, 55));
        view.setGravity(Gravity.CENTER);
        view.setBackground(roundRect(Color.rgb(247, 248, 250), dp(12), Color.rgb(229, 232, 236), 1));
        return view;
    }

    private TextView largeStatusPanel(String label, String value) {
        TextView view = new TextView(this);
        view.setText(label + "\n" + value);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(31, 43, 55));
        view.setGravity(Gravity.CENTER);
        view.setBackground(roundRect(Color.rgb(250, 251, 249), dp(16), Color.rgb(218, 223, 229), 1));
        return view;
    }

    private void setMetric(TextView view, String label, String value) {
        if (view != null) {
            view.setText(label + "\n" + value);
        }
    }

    private void setLargeStatus(TextView view, String label, String value) {
        if (view != null) {
            view.setText(label + "\n" + value);
        }
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(44));
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
        button.setMinHeight(dp(44));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        button.setBackground(buttonBg(Color.WHITE, Color.rgb(229, 236, 244), Color.rgb(194, 205, 218)));
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams(int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
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
        layer.setLayerHeight(0, dp(8));
        layer.setLayerHeight(1, dp(8));
        return layer;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int topContentPadding() {
        int statusBarId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBarHeight = statusBarId > 0 ? getResources().getDimensionPixelSize(statusBarId) : dp(24);
        return statusBarHeight + dp(2);
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

    private static class AspectRatioImageView extends ImageView {
        AspectRatioImageView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = (width * 3) / 4;
            setMeasuredDimension(width, height);
        }
    }
}
