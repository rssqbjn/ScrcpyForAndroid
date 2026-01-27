package org.server.scrcpy;


import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public final class DroidConnection implements Closeable {

    public static final int MEDIA_PORT = 7007;
    public static final int CONTROL_PORT = 7008;

    private Socket mediaSocket;
    private Socket controlSocket;
    private OutputStream mediaOutputStream;
    private DataInputStream controlInputStream;

    private DroidConnection(Socket mediaSocket, Socket controlSocket) throws IOException {
        this.mediaSocket = mediaSocket;
        this.controlSocket = controlSocket;
        this.mediaOutputStream = mediaSocket.getOutputStream();
        this.controlInputStream = new DataInputStream(controlSocket.getInputStream());
    }

    /**
     * 创建一个可重用地址的 ServerSocket
     */
    private static ServerSocket createServerSocket(int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        return serverSocket;
    }

    public static DroidConnection open(String ip) throws IOException {
        ServerSocket mediaServer = null;
        ServerSocket controlServer = null;
        int retryCount = 5;  // 增加重试次数
        IOException lastException = null;
        
        while (retryCount > 0) {
            try {
                mediaServer = createServerSocket(MEDIA_PORT);
                controlServer = createServerSocket(CONTROL_PORT);
                break;  // 成功创建，跳出循环
            } catch (IOException e) {
                lastException = e;
                Ln.w("Failed to bind ports, retrying... (" + retryCount + " attempts left): " + e.getMessage());
                // 关闭可能已经创建的 socket
                if (mediaServer != null) {
                    try { mediaServer.close(); } catch (IOException ignore) {}
                    mediaServer = null;
                }
                if (controlServer != null) {
                    try { controlServer.close(); } catch (IOException ignore) {}
                    controlServer = null;
                }
                
                // 尝试杀死占用端口的进程（在服务端执行，但不杀死自己）
                if (retryCount == 5) {  // 只在第一次重试时尝试
                    try {
                        int myPid = android.os.Process.myPid();
                        String killCmd = "for pid in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$'); do " +
                                "if [ \"$pid\" != \"" + myPid + "\" ]; then " +
                                "  if cat /proc/$pid/cmdline 2>/dev/null | tr '\\0' ' ' | grep -q 'org.server.scrcpy'; then " +
                                "    kill -9 $pid 2>/dev/null; " +
                                "  fi; " +
                                "fi; " +
                                "done";
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", killCmd}).waitFor();
                    } catch (Exception ignore) {}
                }
                
                try {
                    Thread.sleep(1000);  // 增加等待时间
                } catch (InterruptedException ignore) {}
                retryCount--;
            }
        }
        
        if (mediaServer == null || controlServer == null) {
            throw new IOException("Failed to bind ports after retries", lastException);
        }
        
        try {
            writeReadyMarker();
            Socket media = mediaServer.accept();
            if (!media.getInetAddress().toString().equals(ip)) {
                Ln.w("media socket connect address != " + ip);
            }

            Socket control = controlServer.accept();
            if (!control.getInetAddress().toString().equals(ip)) {
                Ln.w("control socket connect address != " + ip);
            }

            if (media.getInetAddress().toString().isEmpty() || control.getInetAddress().toString().isEmpty()) {
                throw new IOException("Invalid socket address");
            }
            return new DroidConnection(media, control);
        } finally {
            try {
                mediaServer.close();
            } catch (IOException ignore) {
            }
            try {
                controlServer.close();
            } catch (IOException ignore) {
            }
        }
    }

    private static void writeReadyMarker() {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "echo ready >/data/local/tmp/scrcpy.ready"});
        } catch (IOException e) {
            Ln.w("Failed to write ready marker: " + e.getMessage());
        }
    }

    public void close() throws IOException {
        if (controlSocket != null) {
            try {
                controlSocket.shutdownInput();
                controlSocket.shutdownOutput();
            } finally {
                controlSocket.close();
            }
        }
        if (mediaSocket != null) {
            try {
                mediaSocket.shutdownInput();
                mediaSocket.shutdownOutput();
            } finally {
                mediaSocket.close();
            }
        }
    }

    public OutputStream getOutputStream() {
        return mediaOutputStream;
    }


    /**
     * TODO 需要根据原版 scrcpy 进行改造消息传送，目前仅支持 触控消息
     *
     * @return
     * @throws IOException
     */
    public int[] NewreceiveControlEvent() throws IOException {

        byte[] buf = new byte[20];
        // 使用 readFully 确保读取完整的 20 字节，避免 TCP 分片导致数据不完整
        controlInputStream.readFully(buf, 0, 20);

        final int[] array = new int[buf.length / 4];
        for (int i = 0; i < array.length; i++)
            array[i] = (((int) (buf[i * 4]) << 24) & 0xFF000000) |
                    (((int) (buf[i * 4 + 1]) << 16) & 0xFF0000) |
                    (((int) (buf[i * 4 + 2]) << 8) & 0xFF00) |
                    ((int) (buf[i * 4 + 3]) & 0xFF);
        return array;


    }

}
