package com.needlegroove.player;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class AudioClip {
    static void export(Context context, Uri source, OutputStream output, double start, double end) throws IOException {
        if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start || end - start > 60) {
            throw new IOException("Choose a valid clip between 0 and 60 seconds long.");
        }
        long startUs = Math.round(start * 1_000_000), endUs = Math.round(end * 1_000_000);
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, source, null);
            MediaFormat input = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { extractor.selectTrack(i); input = format; break; }
            }
            if (input == null) throw new IOException("No supported audio track found.");
            if (input.containsKey(MediaFormat.KEY_DURATION) && endUs > input.getLong(MediaFormat.KEY_DURATION)) throw new IOException("Clip end is beyond the song duration.");
            codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            codec.configure(input, null, null, 0);
            codec.start();
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean inputDone = false, outputDone = false;
            long timeout = android.os.SystemClock.elapsedRealtime() + 60_000;
            while (!outputDone) {
                if (android.os.SystemClock.elapsedRealtime() > timeout) throw new IOException("Clip decoding timed out.");
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        if (buffer == null) throw new IOException("Decoder input buffer is unavailable.");
                        int size = extractor.readSampleData(buffer, 0);
                        long time = extractor.getSampleTime();
                        if (size < 0 || time > endUs + 1_000_000) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(index, 0, size, time, 0);
                            extractor.advance();
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = codec.getOutputFormat();
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) throw new IOException("This decoder does not output 16-bit PCM. Try another audio format.");
                } else if (index >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer == null) throw new IOException("Decoder output buffer is unavailable.");
                    int frameSize = channels * 2;
                    long frames = info.size / frameSize;
                    long first = Math.max(0, (long) Math.ceil((startUs - info.presentationTimeUs) * rate / 1_000_000d));
                    long last = Math.min(frames, (long) Math.ceil((endUs - info.presentationTimeUs) * rate / 1_000_000d));
                    if (last > first) {
                        int count = (int) ((last - first) * frameSize);
                        if (pcm.size() + count > 64_000_000) throw new IOException("The decoded clip is too large.");
                        byte[] bytes = new byte[count];
                        buffer.position(info.offset + (int) first * frameSize);
                        buffer.get(bytes);
                        pcm.write(bytes);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || info.presentationTimeUs >= endUs;
                    codec.releaseOutputBuffer(index, false);
                }
            }
            if (pcm.size() == 0) throw new IOException("No audio was decoded in that time range.");
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + pcm.size()).put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
                    .putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * 2)
                    .putShort((short) (channels * 2)).putShort((short) 16).put(new byte[]{'d', 'a', 't', 'a'}).putInt(pcm.size());
            output.write(header.array());
            pcm.writeTo(output);
        } catch (RuntimeException error) {
            throw new IOException("This file could not be decoded for clipping.", error);
        } finally {
            if (codec != null) codec.release();
            extractor.release();
        }
    }
}
