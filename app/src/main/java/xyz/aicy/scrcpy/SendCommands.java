package xyz.aicy.scrcpy;


import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import xyz.aicy.scrcpy.utils.ThreadUtils;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

public class SendCommands {

    private Context context;
    private int status;


    public SendCommands() {

    }

    public int SendAdbCommands(Context context, final String ip, int port, int forwardport, String localip, int bitrate, int size) {
        return this.SendAdbCommands(context, null, ip, port, forwardport, localip, bitrate, size, true);
    }

    public int SendAdbCommands(Context context, final String ip, int port, int forwardport, String localip, int bitrate, int size,
                               boolean audioEnabled) {
        return this.SendAdbCommands(context, null, ip, port, forwardport, localip, bitrate, size, audioEnabled);
    }

    public int SendAdbCommands(Context context, final byte[] fileBase64, final String ip, int port, int forwardport, String localip, int bitrate,
                               int size) {
        return this.SendAdbCommands(context, fileBase64, ip, port, forwardport, localip, bitrate, size, true);
    }

    public int SendAdbCommands(Context context, final byte[] fileBase64, final String ip, int port, int forwardport, String localip, int bitrate,
                               int size, boolean audioEnabled) {
        this.context = context;
        status = 1;
        ThreadUtils.execute(() -> {
            try {
                // 新版的复制方式
                newAdbServerStart(context, ip, localip, port, forwardport, bitrate, size, audioEnabled);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        int count = 0;
        while (status == 1 && count < 100) {
            Log.e("ADB", "Connecting...");
            try {
                Thread.sleep(100);
                count++;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (count >= 50) {
            status = 2;
            return status;
        }
        if (status == 0) {
            count = 0;
            //  检测程序是否已经启动，如果启动了，该文件会被删除
            while (status == 0 && count < 10) {
                String adbTextCmd = App.adbCmd("-s", ip + ":" + port, "shell", "ls", "-alh", "/data/local/tmp/scrcpy-server.jar");
                if (TextUtils.isEmpty(adbTextCmd)) {
                    break;
                } else {
                    try {
                        Thread.sleep(100);
                        count++;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return status;
    }


    private void newAdbServerStart(Context context, String ip, String localip, int port, int serverport, int bitrate, int size, boolean audioEnabled) {
        String targetDevice = ip + ":" + port;
        String connectRet = App.adbCmd("connect", targetDevice);
        Log.i("Scrcpy", "adb connect " + targetDevice + " result: " + connectRet);

        Log.i("Scrcpy", "adb devices: " + App.adbCmd("devices"));
        
        // 复制server端到可执行目录
        File localJarFile = new File(context.getExternalFilesDir("scrcpy"), "scrcpy-server.jar");
        Log.i("Scrcpy", "Local jar file: " + localJarFile.getAbsolutePath() + ", exists: " + localJarFile.exists() + ", size: " + localJarFile.length());
        
        String pushRet = App.adbCmd("-s", targetDevice, "push", localJarFile.getAbsolutePath(), "/data/local/tmp/scrcpy-server.jar");
        Log.i("Scrcpy", "pushRet: " + pushRet);

        // 检查 push 是否成功
        if (pushRet == null || !pushRet.contains("file pushed")) {
            Log.e("Scrcpy", "Push failed: " + pushRet);
            status = 2;
            return;
        }

        // 清理旧的标记与日志，避免误判
        App.adbCmd("-s", targetDevice, "shell", "rm", "-f",
                "/data/local/tmp/scrcpy.ready",
                "/data/local/tmp/scrcpy.started",
                "/data/local/tmp/scrcpy.shell",
                "/data/local/tmp/scrcpy.log");

        String adbTextCmd = App.adbCmd("-s", targetDevice, "shell", "ls", "-alh", "/data/local/tmp/scrcpy-server.jar");
        Log.i("Scrcpy", "ls result: " + adbTextCmd);
        // 检查文件是否存在：如果返回为空或包含 "No such file" 则表示文件不存在
        if (TextUtils.isEmpty(adbTextCmd) || adbTextCmd.contains("No such file")) {
            Log.e("Scrcpy", "Server file not found on remote device");
            status = 2;
            return;
        }
        // 开启本地端口 forward 转发
        Log.i("Scrcpy", "开启本地端口转发");
        String forwardRet1 = App.adbCmd("-s", targetDevice, "forward", "tcp:" + serverport, "tcp:" + 7007);
        String forwardRet2 = App.adbCmd("-s", targetDevice, "forward", "tcp:" + (serverport + 1), "tcp:" + 7008);
        Log.i("Scrcpy", "forward result: " + forwardRet1 + ", " + forwardRet2);

        // 执行启动命令
        Log.i("Scrcpy", "Starting server with command");
        String ipArg = "/" + localip;
        String startCmd = "echo shell_start >/data/local/tmp/scrcpy.shell; "
                + "export CLASSPATH=/data/local/tmp/scrcpy-server.jar; "
                + "/system/bin/app_process / org.server.scrcpy.Server "
                + ipArg + " " + size + " " + bitrate + " false " + audioEnabled
                + " >/data/local/tmp/scrcpy.log 2>&1 &";
        App.adbCmd("-s", targetDevice, "shell", "sh", "-c", startCmd);

        // 等待 server ready 标记
        int readyCount = 0;
        while (readyCount < 100) {
            String readyRet = App.adbCmd("-s", targetDevice, "shell", "ls", "/data/local/tmp/scrcpy.ready");
            if (!TextUtils.isEmpty(readyRet) && readyRet.contains("scrcpy.ready")) {
                status = 0;
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            readyCount++;
        }
        Log.e("Scrcpy", "Server ready marker timeout");
        String startedRet = App.adbCmd("-s", targetDevice, "shell", "sh", "-c",
                "if [ -f /data/local/tmp/scrcpy.started ]; then echo started_exists; else echo started_missing; fi");
        Log.e("Scrcpy", "Server started marker: " + startedRet);
        String shellRet = App.adbCmd("-s", targetDevice, "shell", "sh", "-c",
                "if [ -f /data/local/tmp/scrcpy.shell ]; then echo shell_exists; else echo shell_missing; fi");
        Log.e("Scrcpy", "Shell marker: " + shellRet);
        String logRet = App.adbCmd("-s", targetDevice, "shell", "sh", "-c",
                "if [ -f /data/local/tmp/scrcpy.log ]; then echo log_exists; else echo log_missing; fi");
        Log.e("Scrcpy", "Server log file: " + logRet);
        String serverLog = App.adbCmd("-s", targetDevice, "shell", "sh", "-c",
                "if [ -f /data/local/tmp/scrcpy.log ]; then cat /data/local/tmp/scrcpy.log; fi");
        if (!TextUtils.isEmpty(serverLog)) {
            Log.e("Scrcpy", "Server log:\n" + serverLog);
        } else {
            Log.e("Scrcpy", "Server log is empty or missing");
        }
        status = 2;
    }

}
