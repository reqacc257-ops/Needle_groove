package com.needlegroove.player;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class QueueRulesTest {
    @Test public void filtersIncludeBoundaryAndRequireBothThresholds() {
        assertTrue(QueueRules.passesFilter(10_000, 1_000_000, 10_000, 1_000_000));
        assertFalse(QueueRules.passesFilter(9_999, 1_000_000, 10_000, 1_000_000));
        assertFalse(QueueRules.passesFilter(10_000, 999_999, 10_000, 1_000_000));
    }
    @Test public void searchFindsArtistAndAlbumWithoutCaseOrOuterWhitespace() {
        assertTrue(QueueRules.matchesSearch("Song", "AURORA", "Night", "  aurora "));
        assertTrue(QueueRules.matchesSearch("Song", "Artist", "Night", "night"));
        assertFalse(QueueRules.matchesSearch("Song", "Artist", "Night", "jazz"));
    }
    @Test public void timedLyricsHandleMultipleTagsFractionsOffsetAndOrdering() {
        List<QueueRules.Line> lines = QueueRules.lyrics("[offset:-100]\n[00:12.50]Later\n[00:01.2][00:10.123]Again");
        assertEquals(3, lines.size());
        assertEquals(1100, lines.get(0).time());
        assertEquals(10023, lines.get(1).time());
        assertEquals(12400, lines.get(2).time());
        assertEquals("Again", lines.get(1).text());
        assertEquals(-1, QueueRules.activeLyric(lines, 1000));
        assertEquals(1, QueueRules.activeLyric(lines, 10023));
        assertEquals(2, QueueRules.activeLyric(lines, 20_000));
    }
    @Test public void plainLyricsDoNotPretendToBeTimed() {
        assertTrue(QueueRules.lyrics("A line\nAnother line").isEmpty());
    }
    @Test public void portableFingerprintIgnoresCaseButDistinguishesAudioFiles() {
        assertEquals(QueueRules.fingerprint(" Song ", "Artist", 100_100, 5000),
                QueueRules.fingerprint("song", "ARTIST", 100_000, 5000));
        assertNotEquals(QueueRules.fingerprint("Song", "Artist", 100_000, 5000),
                QueueRules.fingerprint("Song", "Artist", 100_000, 6000));
    }
}
