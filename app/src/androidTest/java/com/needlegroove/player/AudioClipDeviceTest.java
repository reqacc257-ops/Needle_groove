package com.needlegroove.player;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AudioClipDeviceTest {
    @Test public void nativeDecoderExportsRequestedPcmDurationAndAudibleSamples() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int rate = 44100, samples = rate * 12;
        ByteBuffer wave = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        wave.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + samples * 2)
                .put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(samples * 2);
        for (int i = 0; i < samples; i++) wave.putShort((short) (Math.sin(i * 2 * Math.PI * 440 / rate) * 8192));
        File source = File.createTempFile("clip-device-test-", ".wav", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(source)) { output.write(wave.array()); }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            AudioClip.export(context, Uri.fromFile(source), output, 1.25, 3.75);
            byte[] clip = output.toByteArray();
            assertEquals("RIFF", new String(clip, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
            ByteBuffer decoded = ByteBuffer.wrap(clip).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(rate, decoded.getInt(24));
            assertEquals(1, decoded.getShort(22));
            int bytes = decoded.getInt(40);
            assertEquals(clip.length - 44, bytes);
            assertEquals(2.5, bytes / (rate * 2d), .02);
            int peak = 0;
            for (int i = 44; i + 1 < clip.length; i += 2) peak = Math.max(peak, Math.abs(decoded.getShort(i)));
            assertTrue("Clip was silent", peak > 1000);
        } finally {
            assertTrue("Test fixture could not be removed", source.delete());
        }
    }
}
