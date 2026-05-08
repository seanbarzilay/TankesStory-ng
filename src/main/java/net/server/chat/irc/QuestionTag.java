package net.server.chat.irc;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QuestionTag {

    private static final Pattern TAG = Pattern.compile("\\[#(\\d+)]");

    private QuestionTag() {}

    public static String marker(int id) {
        return "[#" + id + "]";
    }

    public static OptionalInt parseFirst(String text) {
        if (text == null || text.isEmpty()) return OptionalInt.empty();
        Matcher m = TAG.matcher(text);
        if (!m.find()) return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    public static String strip(String text) {
        if (text == null) return "";
        Matcher m = TAG.matcher(text);
        if (!m.find()) return text;
        int start = m.start();
        int end = m.end();
        if (end < text.length() && text.charAt(end) == ' ') {
            end++;
        } else if (start > 0 && text.charAt(start - 1) == ' ') {
            start--;
        }
        return text.substring(0, start) + text.substring(end);
    }
}
