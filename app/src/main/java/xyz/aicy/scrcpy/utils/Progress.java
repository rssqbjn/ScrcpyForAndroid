package xyz.aicy.scrcpy.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class Progress {

    private static final String TAG = "Progress";
    private static AlertDialog progressDialog;

    public static void showDialog(Activity context, String title) {
        showDialog(context, title, false);
    }

    public static void showDialog(Activity context, String title, boolean setup) {
        showDialog(context, title, "", setup);
    }

    public static void showDialog(Activity context, String title, String msg, boolean setup) {
        ThreadUtils.post(() -> {
            if (context == null || context.isFinishing()) {
                return;
            }
            if (progressDialog != null) {
                progressDialog.dismiss();
                progressDialog = null;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            if (!TextUtils.isEmpty(msg)) {
                builder.setTitle(title);
            }
            builder.setView(createContentView(context, title, msg, setup));
            builder.setCancelable(false);

            progressDialog = builder.create();
            progressDialog.show();
        });
    }

    public static boolean isShowing() {
        return progressDialog != null && progressDialog.isShowing();
    }

    public static void closeDialog() {
        ThreadUtils.post(() -> {
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
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 8));

        TextView messageView = new TextView(context);
        messageView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        messageView.setGravity(Gravity.START);

        String messageText = TextUtils.isEmpty(msg) ? title : msg;
        messageView.setText(messageText);
        container.addView(messageView);

        ProgressBar progressBar;
        if (setup) {
            progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setIndeterminate(false);
            progressBar.setMax(100);
        } else {
            progressBar = new ProgressBar(context);
            progressBar.setIndeterminate(true);
        }

        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        progressParams.topMargin = dp(context, 16);
        progressBar.setLayoutParams(progressParams);
        container.addView(progressBar);

        return container;
    }

    private static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
