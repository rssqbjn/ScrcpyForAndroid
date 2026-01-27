package xyz.aicy.scrcpy.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import xyz.aicy.scrcpy.R;

public final class Progress {

    private static final String TAG = "Progress";
    private static AlertDialog progressDialog;
    private static Runnable cancelCallback;

    public static void showDialog(Activity context, String title) {
        showDialog(context, title, false);
    }

    public static void showDialog(Activity context, String title, boolean setup) {
        showDialog(context, title, "", setup);
    }

    public static void showDialog(Activity context, String title, String msg, boolean setup) {
        showDialog(context, title, msg, setup, null);
    }

    /**
     * 显示进度对话框，支持取消回调
     * @param context Activity 上下文
     * @param title 标题
     * @param msg 消息
     * @param setup 是否显示进度条
     * @param onCancel 取消回调，如果为 null 则不允许取消
     */
    public static void showDialog(Activity context, String title, String msg, boolean setup, Runnable onCancel) {
        ThreadUtils.post(() -> {
            if (context == null || context.isFinishing()) {
                return;
            }
            if (progressDialog != null) {
                progressDialog.dismiss();
                progressDialog = null;
            }
            cancelCallback = onCancel;
            
            AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.ProgressDialogTheme);
            builder.setView(createContentView(context, title, msg, setup));
            builder.setCancelable(onCancel != null);
            if (onCancel != null) {
                builder.setOnCancelListener(dialog -> {
                    if (cancelCallback != null) {
                        cancelCallback.run();
                        cancelCallback = null;
                    }
                });
            }

            progressDialog = builder.create();
            
            // 设置对话框窗口属性
            Window window = progressDialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                // 设置窗口动画
                window.setWindowAnimations(android.R.style.Animation_Dialog);
            }
            
            // 先显示对话框
            progressDialog.show();
            
            // Android 12+ 支持原生背景模糊，必须在 show() 之后设置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window != null) {
                try {
                    window.setBackgroundBlurRadius(40);
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                    WindowManager.LayoutParams params = window.getAttributes();
                    params.setBlurBehindRadius(80);
                    window.setAttributes(params);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to set blur effect: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 尝试取消对话框，如果设置了取消回调则会触发
     * @return 如果成功取消返回 true
     */
    public static boolean cancel() {
        if (progressDialog != null && progressDialog.isShowing() && cancelCallback != null) {
            progressDialog.cancel();
            return true;
        }
        return false;
    }

    public static boolean isShowing() {
        return progressDialog != null && progressDialog.isShowing();
    }

    public static void closeDialog() {
        ThreadUtils.post(() -> {
            // 先清除回调，避免关闭对话框时触发取消事件
            cancelCallback = null;
            if (progressDialog != null) {
                if (progressDialog.isShowing()) {
                    try {
                        progressDialog.dismiss();
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG, "Failed to dismiss progress dialog.", e);
                    }
                }
                progressDialog = null;
            }
        });
    }

    private static LinearLayout createContentView(
            Context context,
            String title,
            String msg,
            boolean setup
    ) {
        // 主容器 - 居中布局
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(dp(context, 32), dp(context, 32), dp(context, 32), dp(context, 28));

        // 创建圆形进度条容器
        LinearLayout progressContainer = new LinearLayout(context);
        progressContainer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams progressContainerParams = new LinearLayout.LayoutParams(
                dp(context, 64), dp(context, 64)
        );
        progressContainer.setLayoutParams(progressContainerParams);

        ProgressBar progressBar;
        if (setup) {
            // 水平进度条模式
            progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setIndeterminate(false);
            progressBar.setMax(100);
            LinearLayout.LayoutParams horizontalProgressParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(context, 4)
            );
            progressBar.setLayoutParams(horizontalProgressParams);
            container.addView(progressBar);
        } else {
            // 圆形进度条
            progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleLarge);
            progressBar.setIndeterminate(true);
            LinearLayout.LayoutParams circleProgressParams = new LinearLayout.LayoutParams(
                    dp(context, 48), dp(context, 48)
            );
            progressBar.setLayoutParams(circleProgressParams);
            
            // 设置进度条颜色 - 白色
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(
                        Color.WHITE));
            }
            
            progressContainer.addView(progressBar);
            container.addView(progressContainer);
        }

        // 标题文字
        TextView titleView = new TextView(context);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(context, 20);
        titleView.setLayoutParams(titleParams);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTextColor(Color.WHITE); // 白色文字
        
        titleView.setText(title);
        container.addView(titleView);

        // 副标题/消息文字（如果有）
        if (!TextUtils.isEmpty(msg) && !msg.equals(title)) {
            TextView msgView = new TextView(context);
            LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            msgParams.topMargin = dp(context, 8);
            msgView.setLayoutParams(msgParams);
            msgView.setGravity(Gravity.CENTER);
            msgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            msgView.setTextColor(Color.parseColor("#B0FFFFFF")); // 浅白色
            msgView.setText(msg);
            container.addView(msgView);
        }

        return container;
    }

    private static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
