package xyz.aicy.scrcpy;

import android.annotation.SuppressLint;
import androidx.annotation.NonNull;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;
import xyz.aicy.scrcpy.utils.PreUtils;
import xyz.aicy.scrcpy.utils.Progress;
import xyz.aicy.scrcpy.utils.ThreadUtils;
import xyz.aicy.scrcpy.utils.Util;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;


public class MainActivity extends Activity implements Scrcpy.ServiceCallbacks, SensorEventListener {

    // 是否直接连接远程
    public final static String START_REMOTE = "start_remote_headless";

    private boolean headlessMode = false;  // 是否为无头模式，不显示操作选项等
    private int screenWidth;
    private int screenHeight;
    private boolean landscape = false;
    private boolean first_time = true;
    private boolean result_of_Rotation = false;
    private boolean serviceBound = false;
    // 如果 pause 切换到后台，断开后，自动重连
    // 该状态禁止保存恢复
    private boolean resumeScrcpy = false;
    SensorManager sensorManager;
    private SendCommands sendCommands;
    private int videoBitrate;
    private int delayControl;
    private Context context;
    private String serverAdr = null;
    private SurfaceView surfaceView;
    private Surface surface;
    private Scrcpy scrcpy;
    private long timestamp = 0;

    // private byte[] fileBase64;
    private LinearLayout linearLayout;

    private int errorCount = 0;  // 连接失败错误的计数，错误超过一定次数重启服务

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            scrcpy = ((Scrcpy.MyServiceBinder) iBinder).getService();
            scrcpy.setServiceCallbacks(MainActivity.this);
            serviceBound = true;
            if (first_time) {
                if (!Progress.isShowing()) {
                    Progress.showDialog(MainActivity.this, getString(R.string.please_wait));
                }
                boolean audioEnabled = PreUtils.get(context, Constant.CONTROL_AUDIO, true);
                scrcpy.start(surface, Scrcpy.LOCAL_IP + ":" + Scrcpy.LOCAL_FORWART_PORT,
                        screenHeight, screenWidth, delayControl, audioEnabled);
                ThreadUtils.workPost(() -> {
                    int count = 50;
                    while (count > 0 && !scrcpy.check_socket_connection()) {
                        count--;
                        SystemClock.sleep(100);
                    }
                    int finalCount = count;
                    ThreadUtils.post(() -> {
                        Progress.closeDialog();
                        if (finalCount == 0) {
                            if (serviceBound) {
                                showMainView();
                            }
                            Toast.makeText(context, "Connection Timed out 2", Toast.LENGTH_SHORT).show();
                        } else {
                            first_time = false;
                            // 连接成功后，再把按钮显示出来
                            set_display_nd_touch();
                            connectSuccessExt();
                        }
                    });
                });
            } else {
                scrcpy.setParms(surface, screenWidth, screenHeight);
                set_display_nd_touch();
                connectSuccessExt();
            }
            // set_display_nd_touch();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceBound = false;
        }
    };

    private void showMainView() {
        showMainView(false);
    }

    // userDisconnect ：是否为用户手动断开连接
    private void showMainView(boolean userDisconnect) {
        if (scrcpy != null) {
            scrcpy.StopService();
        }
        try {
            // 可能会导致重复解绑，所以捕获异常
            unbindService(serviceConnection);
        } catch (Exception e) {
            Log.e("Scrcpy", "Failed to unbind service", e);
        }
        if (surface != null) {
            surface = null;
        }
        if (surfaceView != null) {
            surfaceView = null;
        }
        serviceBound = false;
        scrcpy_main();

        if (scrcpy != null) {
            scrcpy = null;
        }
        if (userDisconnect && !resumeScrcpy) {
            PreUtils.put(context, Constant.CONTROL_AUTO_RECONNECT, false);
        }
        // 退出连接，需要处理额外的事件
        connectExitExt(userDisconnect);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.context = this;
        if (savedInstanceState != null) {
            first_time = savedInstanceState.getBoolean("first_time");
            landscape = savedInstanceState.getBoolean("landscape");
            headlessMode = savedInstanceState.getBoolean("headlessMode");
            resumeScrcpy = savedInstanceState.getBoolean("resumeScrcpy");
            screenHeight = savedInstanceState.getInt("screenHeight");
            screenWidth = savedInstanceState.getInt("screenWidth");
        } else {
            // 冷启动（APP被杀死后重新打开），清除自动重连标志
            // 这样可以防止用户清理后台后重新打开APP时自动重连
            PreUtils.put(this, Constant.CONTROL_AUTO_RECONNECT, false);
        }
        // 读取屏幕是横屏、还是竖屏
        landscape = getApplication().getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_PORTRAIT;
        if (first_time) {
            scrcpy_main();
        } else {
            Log.e("Scrcpy: ", "from onCreate");
            start_screen_copy_magic();
        }
        sensorManager = (SensorManager) this.getSystemService(SENSOR_SERVICE);
        Sensor proximity;
        proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);

        if (savedInstanceState != null) {
            Log.i("Scrcpy", "outState: " + savedInstanceState.getBoolean("from_save_instance"));
        }
        // 从销毁状态恢复
        if (savedInstanceState == null || !savedInstanceState.getBoolean("from_save_instance", false)) {
            // 初次进入 app
            if (getIntent() != null && getIntent().getExtras() != null) {
                headlessMode = getIntent().getExtras().getBoolean(START_REMOTE, headlessMode);
            }
        }
        if (headlessMode && first_time) {
            getAttributes();
            connectScrcpyServer(PreUtils.get(this, Constant.CONTROL_REMOTE_ADDR, ""));
        }
        if (headlessMode) {
            View scrollView = findViewById(R.id.main_scroll_view);
            if (scrollView != null) {
                scrollView.setVisibility(View.INVISIBLE);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.i("Scrcpy", "enter onSaveInstanceState");
        outState.putBoolean("from_save_instance", true);
        outState.putBoolean("first_time", first_time);
        outState.putBoolean("landscape", landscape);
        outState.putBoolean("headlessMode", headlessMode);  // 第二次进入时，intent会被重置，需要保存状态
        // 小窗模式、半屏模式切换避免恢复横竖屏，会导致黑屏（因为scrcpy恢复的resume只允许一次连接）
        if (!isChangingConfigurations()) {
            outState.putBoolean("resumeScrcpy", resumeScrcpy);
        }
        outState.putInt("screenHeight", screenHeight);
        outState.putInt("screenWidth", screenWidth);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    public void scrcpy_main() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.getWindow().setStatusBarColor(getColor(R.color.status_bar));
        } else {
            this.getWindow().setStatusBarColor(getResources().getColor(R.color.status_bar));
        }
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        landscape = false;  // 将模式重新置为 竖屏，模式不正确将导致连接黑屏
        setContentView(R.layout.activity_main);
        final Button startButton = findViewById(R.id.button_start);
        // final Button floatButton = findViewById(R.id.button_start_float);

        sendCommands = new SendCommands();

        startButton.setOnClickListener(v -> {
            // local_ip = wifiIpAddress();
            getAttributes();
            connectScrcpyServer(serverAdr);
        });

//        floatButton.setOnClickListener(v -> {
//            getAttributes();
//            showDisplayWindow();
//        });
        get_saved_preferences();

        EditText editText = findViewById(R.id.editText_server_host);

        findViewById(R.id.history_list).setOnClickListener(v -> {
            Log.i("Scrcpy", "focus true");
            editText.clearFocus();
            showListPopulWindow(editText);
        });

        // 无头模式，实际上要隐藏掉所有控件，否则会被显示出 ip 地址
        if (headlessMode) {
            View scrollView = findViewById(R.id.main_scroll_view);
            if (scrollView != null) {
                scrollView.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void showListPopulWindow(EditText mEditText) {
        String[] list = getHistoryList();//要填充的数据
        if (list.length == 0) {  // 如果list为空，则使用本机填充一个
            list = new String[]{"127.0.0.1"};
        }
        final ListPopupWindow listPopupWindow;
        listPopupWindow = new ListPopupWindow(this);
        listPopupWindow.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, list));//用android内置布局，或设计自己的样式
        listPopupWindow.setAnchorView(mEditText);//以哪个控件为基准，在该处以mEditText为基准
        listPopupWindow.setModal(true);
        listPopupWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        String[] finalList = list;
        listPopupWindow.setOnItemClickListener((adapterView, view, i, l) -> {
            mEditText.setText(finalList[i]);
            listPopupWindow.dismiss();
        });
        listPopupWindow.show();
    }

    public void get_saved_preferences() {
        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        final Switch aSwitch0 = findViewById(R.id.switch0);
        final Switch aSwitch1 = findViewById(R.id.switch1);
        final Switch switchScreenOff = findViewById(R.id.switch_screen_off);
        final Switch switchAudioForward = findViewById(R.id.switch_audio_forward);
        String historySpServerAdr = PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, "");
        if (TextUtils.isEmpty(historySpServerAdr)) {
            String[] historyList = getHistoryList();
            if (historyList.length > 0) {
                editTextServerHost.setText(historyList[0]);
            }
        } else {
            editTextServerHost.setText(historySpServerAdr);
        }
        aSwitch0.setChecked(PreUtils.get(context, Constant.CONTROL_NO, false));
        aSwitch1.setChecked(PreUtils.get(context, Constant.CONTROL_NAV, false));
        switchScreenOff.setChecked(PreUtils.get(context, Constant.CONTROL_SCREEN_OFF, false));
        switchAudioForward.setChecked(PreUtils.get(context, Constant.CONTROL_AUDIO, true));
        setSpinner(R.array.options_resolution_values, R.id.spinner_video_resolution, Constant.PREFERENCE_SPINNER_RESOLUTION);
        setSpinner(R.array.options_bitrate_keys, R.id.spinner_video_bitrate, Constant.PREFERENCE_SPINNER_BITRATE);
        setSpinner(R.array.options_delay_keys, R.id.delay_control_spinner, Constant.PREFERENCE_SPINNER_DELAY);
        if (aSwitch0.isChecked()) {
            aSwitch1.setClickable(false);
            aSwitch1.setTextColor(Color.GRAY);
            // aSwitch1.setVisibility(View.GONE);
        }

        aSwitch0.setOnClickListener(v -> {
            if (aSwitch0.isChecked()) {
                aSwitch1.setClickable(false);
                aSwitch1.setTextColor(Color.GRAY);
                // aSwitch1.setVisibility(View.GONE);
            } else {
                aSwitch1.setClickable(true);
                aSwitch1.setTextColor(Color.WHITE);
                // aSwitch1.setVisibility(View.VISIBLE);
            }
        });

        // Handle screen off switch change
        switchScreenOff.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d("Scrcpy", "switch_screen_off changed: " + isChecked);
            boolean hasConnection = scrcpy != null && scrcpy.check_socket_connection();
            Log.d("Scrcpy", "switch_screen_off hasConnection: " + hasConnection);
            if (isChecked && hasConnection) {
                // When switch is turned on, send screen off command
                scrcpy.sendScreenOffCommand();
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    public void set_display_nd_touch() {
        DisplayMetrics metrics = new DisplayMetrics();
        if (ViewConfiguration.get(context).hasPermanentMenuKey()) {
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
        } else {
            final Display display = getWindowManager().getDefaultDisplay();
            display.getRealMetrics(metrics);
        }
//        float this_dev_height = metrics.heightPixels;
//        float this_dev_width = metrics.widthPixels;

        float this_dev_height = linearLayout.getHeight();
        float this_dev_width = linearLayout.getWidth();
        if (PreUtils.get(context, Constant.CONTROL_NAV, false) &&
                !PreUtils.get(context, Constant.CONTROL_NO, false)) {
            if (landscape) {
                this_dev_width = this_dev_width - 96;
            } else {                                                 //100 is the height of nav bar but need multiples of 8.
                this_dev_height = this_dev_height - 96;
            }
        }
        int[] rem_res = scrcpy.get_remote_device_resolution();
        int remote_device_height = rem_res[1];
        int remote_device_width = rem_res[0];
        float remote_device_aspect_ratio = (float) remote_device_height / remote_device_width;

        if (!landscape) {                                                            //Portrait
            float this_device_aspect_ratio = this_dev_height / this_dev_width;
//            Log.d("fuck", "set_display_nd_touch: "+this_device_aspect_ratio);
            if (remote_device_aspect_ratio > this_device_aspect_ratio) {
                //TODO
                float wantWidth = this_dev_height / remote_device_aspect_ratio;
                int padding = (int) (this_dev_width - wantWidth) / 2;
                linearLayout.setPadding(padding, 0, padding, 0);
            } else if (remote_device_aspect_ratio < this_device_aspect_ratio) {
                linearLayout.setPadding(0, (int) (((this_device_aspect_ratio - remote_device_aspect_ratio) * this_dev_width)), 0, 0);
            }

        } else {                                                                        //Landscape
            float this_device_aspect_ratio = this_dev_width / this_dev_height;
//            Log.d("fuck", "set_display_nd_touch_land: "+this_device_aspect_ratio);
            if (remote_device_aspect_ratio > this_device_aspect_ratio) {
                float wantHeight = this_dev_width / remote_device_aspect_ratio;
                int padding = (int) (this_dev_height - wantHeight) / 2;
                linearLayout.setPadding(0, padding, 0, padding);
            } else if (remote_device_aspect_ratio < this_device_aspect_ratio) {
                linearLayout.setPadding(((int) (((this_device_aspect_ratio - remote_device_aspect_ratio) * this_dev_height)) / 2), 0, ((int) (((this_device_aspect_ratio - remote_device_aspect_ratio) * this_dev_height)) / 2), 0);
            }

        }
        if (!PreUtils.get(context, Constant.CONTROL_NO, false)) {
            // Log.i("Screen", "setOnTouchListener: " + surfaceView.getWidth() + "x" + surfaceView.getHeight());
            surfaceView.setOnTouchListener((view, event) -> scrcpy.touchevent(event, landscape, surfaceView.getWidth(), surfaceView.getHeight()));
        }

        if (PreUtils.get(context, Constant.CONTROL_NAV, false) &&
                !PreUtils.get(context, Constant.CONTROL_NO, false)) {
            final View backButton = findViewById(R.id.back_button);
            final View homeButton = findViewById(R.id.home_button);
            final View appswitchButton = findViewById(R.id.appswitch_button);

            if (backButton != null) {
                backButton.setOnClickListener(v -> scrcpy.sendKeyevent(KeyEvent.KEYCODE_BACK));
            }
            if (homeButton != null) {
                // Home 键支持长按（长按会触发语音助手等）
                setupLongPressButton(homeButton, KeyEvent.KEYCODE_HOME);
            }
            if (appswitchButton != null) {
                appswitchButton.setOnClickListener(v -> scrcpy.sendKeyevent(KeyEvent.KEYCODE_APP_SWITCH));
            }
            
            // 电源按钮 - 控制远程设备电源，支持长按（长按弹出关机菜单）
            final View powerButton = findViewById(R.id.power_button);
            if (powerButton != null) {
                setupPowerButton(powerButton);
            }
        }
    }

    /**
     * 为电源按钮设置特殊处理
     * 在息屏控制模式下，短按使用 display power toggle 而不是真实的 POWER 键，
     * 这样可以避免唤醒并解锁设备
     * 长按仍然使用真实的 POWER 键以支持弹出关机菜单等功能
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupPowerButton(View button) {
        final boolean[] isLongPress = {false};
        
        // 长按：发送真实的 POWER 键（用于弹出关机菜单等）
        button.setOnLongClickListener(v -> {
            isLongPress[0] = true;
            Log.d("Scrcpy", "Power button long press DOWN");
            scrcpy.sendKeyeventWithAction(KeyEvent.KEYCODE_POWER, 1, 0);  // 发送按下事件
            return true;
        });
        
        button.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (isLongPress[0]) {
                    // 长按后松开，发送抬起事件
                    Log.d("Scrcpy", "Power button long press UP");
                    scrcpy.sendKeyeventWithAction(KeyEvent.KEYCODE_POWER, 2, 0);  // 发送抬起事件
                    isLongPress[0] = false;
                    return true;
                }
            } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (isLongPress[0]) {
                    // 取消时也发送抬起事件
                    scrcpy.sendKeyeventWithAction(KeyEvent.KEYCODE_POWER, 2, 0);
                    isLongPress[0] = false;
                }
            }
            return false;  // 返回false让其他事件继续处理（如click）
        });
        
        // 短按：根据息屏控制模式决定发送的命令
        button.setOnClickListener(v -> {
            if (!isLongPress[0]) {
                boolean screenOffMode = PreUtils.get(context, Constant.CONTROL_SCREEN_OFF, false);
                if (screenOffMode) {
                    // 息屏控制模式：使用 display power toggle，不会解锁设备
                    Log.d("Scrcpy", "Power button click in screen-off mode: sending display power toggle");
                    scrcpy.sendDisplayPowerToggleCommand();
                } else {
                    // 普通模式：发送真实的 POWER 键
                    Log.d("Scrcpy", "Power button click: sending KEYCODE_POWER");
                    scrcpy.sendKeyevent(KeyEvent.KEYCODE_POWER);
                }
            }
        });
    }

    /**
     * 为虚拟按钮设置长按支持
     * 短按：发送单击事件
     * 长按：发送按下事件，松开时发送抬起事件
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupLongPressButton(View button, int keyCode) {
        final boolean[] isLongPress = {false};
        
        button.setOnLongClickListener(v -> {
            isLongPress[0] = true;
            Log.d("Scrcpy", "Virtual button long press DOWN: " + KeyEvent.keyCodeToString(keyCode));
            scrcpy.sendKeyeventWithAction(keyCode, 1, 0);  // 发送按下事件
            return true;
        });
        
        button.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (isLongPress[0]) {
                    // 长按后松开，发送抬起事件
                    Log.d("Scrcpy", "Virtual button long press UP: " + KeyEvent.keyCodeToString(keyCode));
                    scrcpy.sendKeyeventWithAction(keyCode, 2, 0);  // 发送抬起事件
                    isLongPress[0] = false;
                    return true;
                }
            } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (isLongPress[0]) {
                    // 取消时也发送抬起事件
                    scrcpy.sendKeyeventWithAction(keyCode, 2, 0);
                    isLongPress[0] = false;
                }
            }
            return false;  // 返回false让其他事件继续处理（如click）
        });
        
        button.setOnClickListener(v -> {
            if (!isLongPress[0]) {
                // 短按，发送单击事件
                scrcpy.sendKeyevent(keyCode);
            }
        });
    }

    private void setSpinner(final int textArrayOptionResId, final int textViewResId, final String preferenceId) {

        final Spinner spinner = findViewById(textViewResId);
        ArrayAdapter<CharSequence> arrayAdapter = ArrayAdapter.createFromResource(this, textArrayOptionResId, android.R.layout.simple_spinner_item);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(arrayAdapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                PreUtils.put(context, preferenceId, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                PreUtils.put(context, preferenceId, 0);
            }
        });
        int selection = PreUtils.get(context, preferenceId, 0);
        if (selection < arrayAdapter.getCount()) {
            spinner.setSelection(selection);
        } else {
            spinner.setSelection(0);
        }
    }

    private void getAttributes() {

        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        serverAdr = editTextServerHost.getText().toString();
        if (!TextUtils.isEmpty(serverAdr)) {
            serverAdr = serverAdr.trim();
        }
        if (!TextUtils.isEmpty(serverAdr)) {
            PreUtils.put(context, Constant.CONTROL_REMOTE_ADDR, serverAdr);
        }
        final Spinner videoResolutionSpinner = findViewById(R.id.spinner_video_resolution);
        final Spinner videoBitrateSpinner = findViewById(R.id.spinner_video_bitrate);
        final Spinner delayControlSpinner = findViewById(R.id.delay_control_spinner);
        final Switch a_Switch0 = findViewById(R.id.switch0);
        boolean no_control = a_Switch0.isChecked();
        final Switch a_Switch1 = findViewById(R.id.switch1);
        boolean nav = a_Switch1.isChecked();
        final Switch switchScreenOff = findViewById(R.id.switch_screen_off);
        boolean screenOff = switchScreenOff.isChecked();
        final Switch switchAudioForward = findViewById(R.id.switch_audio_forward);
        boolean audioForward = switchAudioForward.isChecked();
        PreUtils.put(context, Constant.CONTROL_NO, no_control);
        PreUtils.put(context, Constant.CONTROL_NAV, nav);
        PreUtils.put(context, Constant.CONTROL_SCREEN_OFF, screenOff);
        PreUtils.put(context, Constant.CONTROL_AUDIO, audioForward);

        final String[] videoResolutions = getResources().getStringArray(R.array.options_resolution_values)[videoResolutionSpinner.getSelectedItemPosition()].split("x");
        screenHeight = Integer.parseInt(videoResolutions[0]);
        screenWidth = Integer.parseInt(videoResolutions[1]);
        videoBitrate = getResources().getIntArray(R.array.options_bitrate_values)[videoBitrateSpinner.getSelectedItemPosition()];
        delayControl = getResources().getIntArray(R.array.options_delay_values)[delayControlSpinner.getSelectedItemPosition()];
    }

    private String[] getHistoryList() {
        String historyList = PreUtils.get(context, Constant.HISTORY_LIST_KEY, "");
        if (TextUtils.isEmpty(historyList)) {
            return new String[]{};
        }
        try {
            JSONArray historyJson = new JSONArray(historyList);
            String[] retList = new String[historyJson.length()];
            for (int i = 0; i < historyJson.length(); i++) {
                retList[i] = historyJson.get(i).toString();
            }
            return retList;
        } catch (Exception e) {
            Log.e("Scrcpy", "Failed to parse history list", e);
        }
        return new String[]{};
    }

    /**
     * 保存设备历史连接记录
     */
    private boolean saveHistory(String device) {
        if (headlessMode) {
            // 无头模式不保存记录
            return false;
        }
        JSONArray historyJson = new JSONArray();
        String[] historyList = getHistoryList();
        if (historyList.length == 0) {
            historyJson.put(device);
        } else {
            try {
                historyJson.put(0, device);
            } catch (JSONException e) {
                Log.e("Scrcpy", "Failed to update history list", e);
            }
            // 最多记录 30 个
            int count = Math.min(historyList.length, 30);
            for (int i = 0; i < count; i++) {
                if (!historyList[i].equals(device)) {
                    historyJson.put(historyList[i]);
                }
            }
        }
        try {
            return PreUtils.put(context, Constant.HISTORY_LIST_KEY, historyJson.toString());
        } catch (Exception e) {
            Log.e("Scrcpy", "Failed to save history list", e);
        }
        return false;
    }

    @SuppressLint("SuspiciousNameCombination")
    private void swapDimensions() {
        int temp = screenHeight;
        screenHeight = screenWidth;
        screenWidth = temp;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void start_screen_copy_magic() {
        setContentView(R.layout.surface);
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        surfaceView = findViewById(R.id.decoder_surface);
        SurfaceHolder holder = surfaceView.getHolder();
        surface = holder.getSurface();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                if (serviceBound && scrcpy != null) {
                    scrcpy.setParms(surface, screenWidth, screenHeight);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surface = holder.getSurface();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surface = null;
            }
        });
        final LinearLayout nav_bar = findViewById(R.id.nav_button_bar);
        if (PreUtils.get(context, Constant.CONTROL_NAV, false) &&
                !PreUtils.get(context, Constant.CONTROL_NO, false)) {
            nav_bar.setVisibility(LinearLayout.VISIBLE);
        } else {
            nav_bar.setVisibility(LinearLayout.GONE);
        }
        linearLayout = findViewById(R.id.container1);
        start_Scrcpy_service();
    }

    private void start_Scrcpy_service() {
        Intent intent = new Intent(this, Scrcpy.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    public void loadNewRotation() {
        if (first_time) {
            first_time = false;
        }
        try {
            // 可能会导致重复解绑，所以捕获异常
            unbindService(serviceConnection);
        } catch (Exception e) {
            Log.e("Scrcpy", "Failed to unbind service during rotation", e);
        }
        serviceBound = false;
        result_of_Rotation = true;
        landscape = !landscape;
        swapDimensions();
        if (landscape) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    @Override
    public void errorDisconnect() {
        // 必须退出
        // 退出重连
        Dialog.displayDialog(this, getString(R.string.disconnect),
                getString(R.string.disconnect_ask), () -> {
                    if (serviceBound) {
                        showMainView();
                        first_time = true;
                    } else {
                        MainActivity.this.finish();
                    }
                }, false);
    }


    @Override
    protected void onPause() {
        super.onPause();
        if (serviceBound) {
            scrcpy.pause();
            // 切后台保持连接，仅暂停解码与渲染
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (serviceBound && scrcpy != null) {
            scrcpy.setBackgroundMode(false);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 只有真正退出时才断开连接
        if (isFinishing() && serviceBound) {
            showMainView(true);
            first_time = true;
            errorCount = 0;  // 主动断开连接，将错误计数重置为 0
        } else if (serviceBound && scrcpy != null) {
            scrcpy.setBackgroundMode(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!first_time && !result_of_Rotation) {
            final View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            if (serviceBound) {
                // 黑屏无需修复， 因为只是自带的配置问题
                linearLayout = findViewById(R.id.container1);
                scrcpy.resume();
            }
        }
        boolean shouldReconnect = resumeScrcpy || PreUtils.get(context, Constant.CONTROL_AUTO_RECONNECT, false);
        if (shouldReconnect && !result_of_Rotation) {
            resumeScrcpy = false;
            PreUtils.put(context, Constant.CONTROL_AUTO_RECONNECT, false);
            connectScrcpyServer(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""));
        }
        resumeScrcpy = false;  // 两处都要resumeScrcpy设置为false
        result_of_Rotation = false;
    }

    @Override
    public void onBackPressed() {
        if (timestamp == 0) {
            if (serviceBound) {
                timestamp = SystemClock.uptimeMillis();
                Toast.makeText(context, "Press again to exit", Toast.LENGTH_SHORT).show();
            } else {
                finish();
            }
        } else {
            long now = SystemClock.uptimeMillis();
            if (now < timestamp + 1000) {
                timestamp = 0;
                if (serviceBound) {
                    showMainView(true);
                    first_time = true;
                    errorCount = 0;  // 主动断开连接，将错误计数重置为 0
                } else {
                    finish();
                }
            }
            timestamp = 0;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            if (sensorEvent.values[0] == 0) {
                if (serviceBound) {
                    // 该事件会使远程手机 按下电源键，触发方式：按住距离传感器，然后点击屏幕即可锁屏
                    // 发送横竖屏会导致抬起事件无效
                    // scrcpy.sendKeyevent(28);
                }
            } else {
                if (serviceBound) {
                    // 发送横竖屏会导致抬起事件无效
                    // scrcpy.sendKeyevent(29);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    /**
     * 捕获物理按键事件并转发到远程设备
     * 支持捕获音量键和电源键
     * 同时按下音量+和音量-可以触发远程设备电源键
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        
        // 只有在连接状态下才处理音量键转发
        if (serviceBound && scrcpy != null && scrcpy.check_socket_connection() 
                && !PreUtils.get(context, Constant.CONTROL_NO, false)) {  // 观看模式下不转发按键
            
            // 判断是否是我们要捕获的按键
            boolean shouldCapture = false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_VOLUME_MUTE:
                case KeyEvent.KEYCODE_POWER:
                case KeyEvent.KEYCODE_CAMERA:
                case KeyEvent.KEYCODE_MENU:
                    shouldCapture = true;
                    break;
            }
            
            if (shouldCapture) {
                // 支持长按：分别发送按下、重复、抬起事件
                if (action == KeyEvent.ACTION_DOWN) {
                    int repeat = event.getRepeatCount();
                    if (repeat == 0) {
                        // 首次按下
                        Log.d("Scrcpy", "Key DOWN: " + KeyEvent.keyCodeToString(keyCode));
                        scrcpy.sendKeyeventWithAction(keyCode, 1, 0);  // action=1 表示按下
                    } else {
                        // 长按重复
                        Log.d("Scrcpy", "Key REPEAT: " + KeyEvent.keyCodeToString(keyCode) + " count=" + repeat);
                        scrcpy.sendKeyeventWithAction(keyCode, 3, repeat);  // action=3 表示重复
                    }
                } else if (action == KeyEvent.ACTION_UP) {
                    // 抬起
                    Log.d("Scrcpy", "Key UP: " + KeyEvent.keyCodeToString(keyCode));
                    scrcpy.sendKeyeventWithAction(keyCode, 2, 0);  // action=2 表示抬起
                }
                // 返回true表示已消费该事件，不会在本机上执行
                return true;
            }
        }
        
        // 其他按键按默认方式处理
        return super.dispatchKeyEvent(event);
    }

    private void connectScrcpyServer(String serverAdr) {
        if (!TextUtils.isEmpty(serverAdr)) {
            boolean historySaved = saveHistory(serverAdr);  // 保存到历史记录
            if (!historySaved) {
                Log.w("Scrcpy", "Failed to save history for " + serverAdr);
            }
            String[] serverInfo = Util.getServerHostAndPort(serverAdr);
            String serverHost = serverInfo[0];
            int serverPort = Integer.parseInt(serverInfo[1]);
            int localForwardPort = Scrcpy.LOCAL_FORWART_PORT;

            Progress.showDialog(MainActivity.this, getString(R.string.please_wait));
            ThreadUtils.workPost(() -> {
                AssetManager assetManager = getAssets();
                Log.d("Scrcpy", "File scrcpy-server.jar try write");
                InputStream input_Stream = null;
                FileOutputStream outputStream = null;
                try {
                    input_Stream = assetManager.open("scrcpy-server.jar");
                    byte[] buffer = new byte[input_Stream.available()];
                    input_Stream.read(buffer);
                    
                    File scrcpyDir = context.getExternalFilesDir("scrcpy");
                    if (scrcpyDir == null) {
                        Log.e("Scrcpy", "Failed to get external files directory");
                        ThreadUtils.post(Progress::closeDialog);
                        Toast.makeText(context, "Failed to access storage", Toast.LENGTH_SHORT).show();
                        connectExitExt();
                        return;
                    }
                    
                    if (!scrcpyDir.exists()) {
                        if (!scrcpyDir.mkdirs()) {
                            Log.e("Scrcpy", "Failed to create directory: " + scrcpyDir.getAbsolutePath());
                            ThreadUtils.post(Progress::closeDialog);
                            Toast.makeText(context, "Failed to create directory", Toast.LENGTH_SHORT).show();
                            connectExitExt();
                            return;
                        }
                    }
                    
                    File targetFile = new File(scrcpyDir, "scrcpy-server.jar");
                    Log.d("Scrcpy", "Writing to: " + targetFile.getAbsolutePath());
                    
                    outputStream = new FileOutputStream(targetFile);
                    outputStream.write(buffer);
                    outputStream.flush();
                    
                    Log.d("Scrcpy", "File scrcpy-server.jar write successful, size: " + buffer.length + " bytes");
                    // fileBase64 = Base64.encode(buffer, 2);
                } catch (IOException e) {
                    Log.e("Scrcpy", "File scrcpy-server.jar write failed: " + e.getMessage(), e);
                    ThreadUtils.post(Progress::closeDialog);
                    Toast.makeText(context, "Failed to extract server file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    connectExitExt();
                    return;
                } finally {
                    try {
                        if (outputStream != null) {
                            outputStream.close();
                        }
                        if (input_Stream != null) {
                            input_Stream.close();
                        }
                    } catch (IOException e) {
                        Log.e("Scrcpy", "Error closing streams: " + e.getMessage());
                    }
                }
                boolean audioEnabled = PreUtils.get(context, Constant.CONTROL_AUDIO, true);
                if (sendCommands.SendAdbCommands(context, serverHost,
                        serverPort,
                        localForwardPort,
                        Scrcpy.LOCAL_IP,
                        videoBitrate, Math.max(screenHeight, screenWidth), audioEnabled) == 0) {
                    ThreadUtils.post(() -> {
                        if (!MainActivity.this.isFinishing()) {
                            // 进入主线程
                            Log.e("Scrcpy: ", "from startButton");
                            start_screen_copy_magic();
                        }
                    });
                } else {
                    ThreadUtils.post(Progress::closeDialog);
                    Toast.makeText(context, "Network OR ADB connection failed", Toast.LENGTH_SHORT).show();
                    connectExitExt();
                }
            });
        } else {
            Toast.makeText(context, "Server Address Empty", Toast.LENGTH_SHORT).show();
            connectExitExt();
        }
    }

    /**
     * 连接成功了，而且成功的显示了画面出来
     */
    protected void connectSuccessExt() {
        errorCount = 0;
        boolean screenOff = PreUtils.get(context, Constant.CONTROL_SCREEN_OFF, false);
        if (screenOff && scrcpy != null && scrcpy.check_socket_connection()) {
            scrcpy.sendScreenOffCommand();
        }
    }

    protected void connectExitExt() {
        this.connectExitExt(false);
    }

    /**
     * 连接失败的额外处理
     */
    protected void connectExitExt(boolean userDisconnect) {
        if (!userDisconnect) {  // userDisconnect : 用户主动断开连接
            errorCount += 1;
            // errorCount = 0;
            Log.i("Scrcpy", "连接错误次数: " + errorCount);
            // 错误 3 次，则重启 adb 服务
            App.startAdbServer();
        }
        // 如果是无头模式，自行弹出重连选项
        if (headlessMode && !resumeScrcpy && !result_of_Rotation) {
            // 非用户主动断开、非页面切换、非横竖屏切换，才会自动弹出断连提示
            if (!userDisconnect) {
                Dialog.displayDialog(this, getString(R.string.connect_faild),
                        getString(R.string.connect_faild_ask), () -> {
                            // 重试连接
                            connectScrcpyServer(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""));
                        }, () -> {

                            // 取消重试
                            finishAndRemoveTask();
                        });
            } else {
                finishAndRemoveTask();
            }
        }
    }

}
