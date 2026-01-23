package org.server.scrcpy;


import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
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

    private static Socket listenAndAccept(int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        Socket sock;
        try {
            sock = serverSocket.accept();
        } finally {
            serverSocket.close();
        }
        return sock;
    }

    public static DroidConnection open(String ip) throws IOException {
        Socket media = listenAndAccept(MEDIA_PORT);
        if (!media.getInetAddress().toString().equals(ip)) {
            Ln.w("media socket connect address != " + ip);
        }

        Socket control = listenAndAccept(CONTROL_PORT);
        if (!control.getInetAddress().toString().equals(ip)) {
            Ln.w("control socket connect address != " + ip);
        }

        if (media.getInetAddress().toString().isEmpty() || control.getInetAddress().toString().isEmpty()) {
            throw new IOException("Invalid socket address");
        }
        return new DroidConnection(media, control);
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
