package xyz.aicy.scrcpy.decoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoDecoder {
    private MediaCodec mCodec;
    private Worker mWorker;
    private AtomicBoolean mIsConfigured = new AtomicBoolean(false);
    private static final int SAMPLE_QUEUE_CAPACITY = 30;

    public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
        if (mWorker != null) {
            mWorker.decodeSample(data, offset, size, presentationTimeUs, flags);
        }
    }

    public void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1) {
        if (mWorker != null) {
            mWorker.configure(surface, width, height, csd0, csd1);
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

        private void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1) {
            int csd0Len = csd0 == null ? -1 : csd0.remaining();
            int csd1Len = csd1 == null ? -1 : csd1.remaining();
            if (surface == null || csd0 == null || csd1 == null || csd0Len <= 0 || csd1Len <= 0) {
                Log.w("Scrcpy", "Video configure skipped: surface=" + (surface != null)
                        + " csd0=" + csd0Len + " csd1=" + csd1Len);
                return;
            }
            if (mIsConfigured.get()) {
                mIsConfigured.set(false);
                if (mCodec != null) {
                    mCodec.stop();
                }
            }
            sampleQueue.clear();
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setByteBuffer("csd-0", csd0);
            format.setByteBuffer("csd-1", csd1);
            try {
                mCodec = MediaCodec.createDecoderByType("video/avc");
            } catch (IOException e) {
                throw new RuntimeException("Failed to create codec", e);
            }
            mCodec.configure(format, surface, null, 0);
            mCodec.start();
            Log.d("Scrcpy", "Video decoder configured: " + width + "x" + height);
            mIsConfigured.set(true);
        }


        public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
            if (!mIsConfigured.get() || !mIsRunning.get()) {
                return;
            }
            Sample sample = new Sample(data, offset, size, presentationTimeUs, flags);
            if (!sampleQueue.offer(sample)) {
                // Drop oldest frame to keep latency low
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
                        if (outputIndex >= 0) {
                            // setting true is telling system to render frame onto Surface
                            mCodec.releaseOutputBuffer(outputIndex, true);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                break;
                            }
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
