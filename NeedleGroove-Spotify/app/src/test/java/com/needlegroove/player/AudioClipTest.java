package com.needlegroove.player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioClipTest {
    @Test public void invalidRangesAreRejectedBeforeDecodingOrWriting() {
        for (double[] range : new double[][]{{-1, 2}, {2, 2}, {3, 2}, {0, 61}, {Double.NaN, 1}, {0, Double.POSITIVE_INFINITY}}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                AudioClip.export(null, null, output, range[0], range[1]);
                fail("Invalid range was accepted");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("valid clip")); }
            assertEquals(0, output.size());
        }
    }
}
