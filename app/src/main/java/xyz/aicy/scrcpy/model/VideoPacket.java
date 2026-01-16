package xyz.aicy.scrcpy.model;

import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Created by Alexandr Golovach on 27.06.16.
 * https://www.github.com/alexmprog/VideoCodec
 */

public class VideoPacket extends MediaPacket {

    public Flag flag;
    public long presentationTimeStamp;
    public byte[] data;

    public VideoPacket() {
    }

    public VideoPacket(Type type, Flag flag, long presentationTimeStamp, byte[] data) {
        this.type = type;
        this.flag = flag;
        this.presentationTimeStamp = presentationTimeStamp;
        this.data = data;
    }

    // create packet from byte array
    public static VideoPacket fromArray(byte[] values) {
        VideoPacket videoPacket = new VideoPacket();

        // should be a type value - 1 byte
        byte typeValue = values[0];
        // should be a flag value - 1 byte
        byte flagValue = values[1];

        videoPacket.type = Type.getType(typeValue);
        videoPacket.flag = Flag.getFlag(flagValue);

        // should be 8 bytes for timestamp
        byte[] timeStamp = new byte[8];
        System.arraycopy(values, 2, timeStamp, 0, 8);
        videoPacket.presentationTimeStamp = ByteUtils.bytesToLong(timeStamp);

        // all other bytes is data
        int dataLength = values.length - 10;
        byte[] data = new byte[dataLength];
        System.arraycopy(values, 10, data, 0, dataLength);
        videoPacket.data = data;

        return videoPacket;
    }

    public static VideoPacket readHead(byte[] values) {
        VideoPacket videoPacket = new VideoPacket();

        // should be a type value - 1 byte
        byte typeValue = values[0];
        // should be a flag value - 1 byte
        byte flagValue = values[1];

        videoPacket.type = Type.getType(typeValue);
        videoPacket.flag = VideoPacket.Flag.getFlag(flagValue);

        // should be 8 bytes for timestamp
        byte[] timeStamp = new byte[8];
        System.arraycopy(values, 2, timeStamp, 0, 8);
        videoPacket.presentationTimeStamp = ByteUtils.bytesToLong(timeStamp);

        videoPacket.data = null;

        return videoPacket;
    }

    public static int getHeadLen(){
        return 10;
    }

    // create byte array
    public static byte[] toArray(Type type, Flag flag, long presentationTimeStamp, byte[] data) {

        // should be 4 bytes for packet size
        byte[] bytes = ByteUtils.intToBytes(10 + data.length);

        int packetSize = 14 + data.length; // 4 - inner packet size 1 - type + 1 - flag + 8 - timeStamp + data.length
        byte[] values = new byte[packetSize];

        System.arraycopy(bytes, 0, values, 0, 4);

        // set type value
        values[4] = type.getType();
        // set flag value
        values[5] = flag.getFlag();
        // set timeStamp
        byte[] longToBytes = ByteUtils.longToBytes(presentationTimeStamp);
        System.arraycopy(longToBytes, 0, values, 6, longToBytes.length);

        // set data array
        System.arraycopy(data, 0, values, 14, data.length);
        return values;
    }

    // should call on inner packet
    public static boolean isVideoPacket(byte[] values) {
        return values[0] == Type.VIDEO.getType();
    }

    public static StreamSettings getStreamSettings(byte[] buffer) {
        if (buffer == null || buffer.length < 8) {
            Log.w("Scrcpy", "Video CSD buffer too small: " + (buffer == null ? -1 : buffer.length));
            return null;
        }

        if (ByteBuffer.wrap(buffer).getInt() != 0x00000001) {
            Log.w("Scrcpy", "Video CSD missing SPS start code");
        }

        int ppsIndex = -1;
        for (int i = 4; i <= buffer.length - 4; i++) {
            if (buffer[i] == 0x00 && buffer[i + 1] == 0x00 && buffer[i + 2] == 0x00 && buffer[i + 3] == 0x01) {
                ppsIndex = i;
                break;
            }
        }
        if (ppsIndex <= 0) {
            Log.w("Scrcpy", "Video CSD missing PPS start code, len=" + buffer.length);
            return null;
        }

        byte[] sps = new byte[ppsIndex];
        System.arraycopy(buffer, 0, sps, 0, sps.length);
        byte[] pps = new byte[buffer.length - ppsIndex];
        System.arraycopy(buffer, ppsIndex, pps, 0, pps.length);
        Log.d("Scrcpy", "Video CSD parsed: sps=" + sps.length + " pps=" + pps.length);

        // sps buffer
        ByteBuffer spsBuffer = ByteBuffer.wrap(sps, 0, sps.length);

        // pps buffer
        ByteBuffer ppsBuffer = ByteBuffer.wrap(pps, 0, pps.length);

        StreamSettings streamSettings = new StreamSettings();
        streamSettings.sps = spsBuffer;
        streamSettings.pps = ppsBuffer;

        return streamSettings;
    }

    public byte[] toByteArray() {
        return toArray(type, flag, presentationTimeStamp, data);
    }

    public enum Flag {

        FRAME((byte) 0), KEY_FRAME((byte) 1), CONFIG((byte) 2), END((byte) 4);

        private byte type;

        Flag(byte type) {
            this.type = type;
        }

        public static Flag getFlag(byte value) {
            for (Flag type : Flag.values()) {
                if (type.getFlag() == value) {
                    return type;
                }
            }

            return null;
        }

        public byte getFlag() {
            return type;
        }
    }

    public static class StreamSettings {
        public ByteBuffer pps;
        public ByteBuffer sps;
    }
}
