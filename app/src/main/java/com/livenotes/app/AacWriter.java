package com.livenotes.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Encodes 16-bit mono PCM to AAC-LC inside an .m4a file. Not thread-safe: call from one thread. */
final class AacWriter {
    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final int rate;
    private final File file;
    private int track = -1;
    private boolean muxing = false;
    private boolean closed = false;
    private long samples = 0;

    AacWriter(File file, int sampleRate) throws IOException {
        this.file = file;
        this.rate = sampleRate;
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 48000);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    long durationMs() {
        return samples * 1000L / rate;
    }

    void write(byte[] pcm, int len) {
        if (closed) return;
        int off = 0;
        int guard = 0;
        while (off < len && guard++ < 200) {
            int idx = codec.dequeueInputBuffer(10000);
            if (idx >= 0) {
                ByteBuffer in = codec.getInputBuffer(idx);
                if (in == null) continue;
                in.clear();
                int n = Math.min(in.remaining(), len - off);
                n -= n % 2;
                in.put(pcm, off, n);
                long pts = samples * 1_000_000L / rate;
                samples += n / 2;
                off += n;
                codec.queueInputBuffer(idx, 0, n, pts, 0);
            }
            drain(false);
        }
    }

    /** Finishes the file. Returns false (and deletes it) when nothing was recorded. */
    boolean close() {
        if (closed) return muxing;
        closed = true;
        try {
            int idx = -1;
            for (int i = 0; i < 50 && idx < 0; i++) idx = codec.dequeueInputBuffer(10000);
            if (idx >= 0) {
                long pts = samples * 1_000_000L / rate;
                codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drain(true);
            }
        } catch (Exception ignored) {
        }
        try { codec.stop(); } catch (Exception ignored) { }
        try { codec.release(); } catch (Exception ignored) { }
        boolean ok = muxing;
        try { if (muxing) muxer.stop(); } catch (Exception e) { ok = false; }
        try { muxer.release(); } catch (Exception ignored) { }
        if (!ok || samples == 0) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return false;
        }
        return true;
    }

    private void drain(boolean endOfStream) {
        int idle = 0;
        while (true) {
            int o = codec.dequeueOutputBuffer(info, endOfStream ? 10000 : 0);
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++idle > 100) return;
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxing) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxing = true;
                }
            } else if (o >= 0) {
                ByteBuffer out = codec.getOutputBuffer(o);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (out != null && info.size > 0 && muxing) {
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    muxer.writeSampleData(track, out, info);
                }
                codec.releaseOutputBuffer(o, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }
}
