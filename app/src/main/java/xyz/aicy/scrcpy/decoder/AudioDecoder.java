package xyz.aicy.scrcpy.decoder;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioDecoder {

    public static final String MIMETYPE_AUDIO_AAC = "audio/mp4a-latm";

    private MediaCodec mCodec;
    private Worker mWorker;
    private AtomicBoolean mIsConfigured = new AtomicBoolean(false);
    private static final int SAMPLE_QUEUE_CAPACITY = 100;

    private AudioTrack audioTrack;
    private final int SAMPLE_RATE = 48000;

    private void initAudioTrack() {
        int bufferSizeInBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
        if (mWorker != null) {
            mWorker.decodeSample(data, offset, size, presentationTimeUs, flags);
        }
    }

    public void configure(byte[] data) {
        if (mWorker != null) {
            mWorker.configure(data);
        }
    }


    public void start() {
        if (mWorker == null) {
            mWorker = new Worker();
            mWorker.setRunning(true);
            mWorker.start();
        }
    }

    public void stop() {
        if (mWorker != null) {
            mWorker.setRunning(false);
            mWorker = null;
            mIsConfigured.set(false);
            if (mCodec != null) {
                mCodec.stop();
            }
            if (audioTrack != null) {
                audioTrack.stop();
            }
        }
    }

    private class Worker extends Thread {

        private AtomicBoolean mIsRunning = new AtomicBoolean(false);
        private final BlockingQueue<Sample> sampleQueue = new ArrayBlockingQueue<>(SAMPLE_QUEUE_CAPACITY);

        Worker() {
        }

        private void setRunning(boolean isRunning) {
            mIsRunning.set(isRunning);
        }

        private void configure(byte[] data) {
            if (data == null || data.length == 0) {
                Log.w("Scrcpy", "Audio configure skipped: empty CSD");
                return;
            }
            if (mIsConfigured.get()) {
                mIsConfigured.set(false);
                if (mCodec != null) {
                    mCodec.stop();
                }
                if (audioTrack != null) {
                    audioTrack.stop();
                }
            }
            sampleQueue.clear();
            MediaFormat format = MediaFormat.createAudioFormat(MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2);
            // 设置比特率
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            // adts 0
            // format.setInteger(MediaFormat.KEY_IS_ADTS, 1);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(data));

            try {
                mCodec = MediaCodec.createDecoderByType(MIMETYPE_AUDIO_AAC);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create codec", e);
            }
            mCodec.configure(format, null, null, 0);
            mCodec.start();
            mIsConfigured.set(true);
            Log.d("Scrcpy", "Audio decoder configured: csd=" + data.length);

            // 初始化音频播放器
            initAudioTrack();
            // audio track 启动
            audioTrack.play();
        }


        public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
            if (!mIsConfigured.get() || !mIsRunning.get()) {
                return;
            }
            Sample sample = new Sample(data, offset, size, presentationTimeUs, flags);
            if (!sampleQueue.offer(sample)) {
                // Drop oldest to keep audio in sync with video
                sampleQueue.poll();
                sampleQueue.offer(sample);
            }
        }

        @Override
        public void run() {
            try {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                Sample pendingSample = null;
                while (mIsRunning.get()) {
                    if (mIsConfigured.get()) {
                        if (pendingSample == null) {
                            pendingSample = sampleQueue.poll();
                        }
                        if (pendingSample != null) {
                            int inputIndex = mCodec.dequeueInputBuffer(0);
                            if (inputIndex >= 0) {
                                ByteBuffer buffer;
                                buffer = mCodec.getInputBuffer(inputIndex);
                                if (buffer != null) {
                                    buffer.put(pendingSample.data, pendingSample.offset, pendingSample.size);
                                    mCodec.queueInputBuffer(inputIndex, 0, pendingSample.size, pendingSample.presentationTimeUs, pendingSample.flags);
                                    pendingSample = null;
                                }
                            } else {
                                try {
                                    Thread.sleep(2);
                                } catch (InterruptedException ignore) {
                                }
                            }
                        }

                        int outputIndex = mCodec.dequeueOutputBuffer(info, 0);
                        // Log.e("Scrcpy", "Audio Decoder: " + outputIndex);
                        if (outputIndex >= 0) {
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                break;
                            }
                            // Log.e("Scrcpy", "Audio success get frame: " + outputIndex);

                            // 读取 pcm 数据，写入 audiotrack 播放
                            ByteBuffer outputBuffer = mCodec.getOutputBuffer(outputIndex);
                            if (outputBuffer != null && info.size > 0) {
                                outputBuffer.position(info.offset);
                                outputBuffer.limit(info.offset + info.size);
                                byte[] data = new byte[info.size];
                                outputBuffer.get(data);
                                audioTrack.write(data, 0, info.size);
                            }
                            // release (audio buffers must not be rendered)
                            mCodec.releaseOutputBuffer(outputIndex, false);
                        }
                    } else {
                        // just waiting to be configured, then decode and render
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignore) {
                        }
                    }
                }
            } catch (IllegalStateException e) {
            }

        }
    }

    private static class Sample {
        final byte[] data;
        final int offset;
        final int size;
        final long presentationTimeUs;
        final int flags;

        Sample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
            this.data = data;
            this.offset = offset;
            this.size = size;
            this.presentationTimeUs = presentationTimeUs;
            this.flags = flags;
        }
    }
}
