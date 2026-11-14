package com.needlegroove.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QueueRules {
    static boolean passesFilter(long duration, long size, long minimumDuration, long minimumSize) {
        return duration >= minimumDuration && size >= minimumSize;
    }
    static boolean matchesSearch(String title, String artist, String album, String query) {
        return (title + " " + artist + " " + album).toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT));
    }
    static String fingerprint(String title, String artist, long duration, long size) {
        return title.trim().toLowerCase(Locale.ROOT) + "|" + artist.trim().toLowerCase(Locale.ROOT)
                + "|" + Math.round(duration / 1000d) + "|" + size;
    }
    record Line(long time, String text) {}
    static List<Line> lyrics(String content) {
        List<Line> lines = new ArrayList<>();
        Pattern timestamp = Pattern.compile("\\[(\\d{1,6}):([0-5]\\d)(?:\\.(\\d{1,3}))?]");
        long offset = 0;
        Matcher offsetMatch = Pattern.compile("\\[offset:([+-]?\\d{1,8})]").matcher(content);
        if (offsetMatch.find()) offset = Long.parseLong(offsetMatch.group(1));
        for (String line : content.split("\\R")) {
            Matcher match = timestamp.matcher(line);
            String text = timestamp.matcher(line).replaceAll("").strip();
            while (match.find()) {
                String fraction = match.group(3);
                long millis = fraction == null ? 0 : Integer.parseInt((fraction + "000").substring(0, 3));
                lines.add(new Line(Math.max(0, Long.parseLong(match.group(1)) * 60_000
                        + Long.parseLong(match.group(2)) * 1000 + millis + offset), text));
            }
        }
        lines.sort(Comparator.comparingLong(Line::time));
        return lines;
    }
    static int activeLyric(List<Line> lines, long position) {
        int active = -1;
        for (int i = 0; i < lines.size() && lines.get(i).time <= position; i++) active = i;
        return active;
    }
}
