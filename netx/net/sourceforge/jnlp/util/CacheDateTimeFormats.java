package net.sourceforge.jnlp.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * Locale-aware cache timestamp formatting with millisecond precision and local timezone.
 */
public final class CacheDateTimeFormats {

    private static final DateTimeFormatter CACHE_TIMESTAMP = DateTimeFormatter
            .ofPattern("EEEE, MMMM d, yyyy 'at' HH:mm:ss.SSS z", Locale.getDefault())
            .withZone(ZoneId.systemDefault());

    private CacheDateTimeFormats() {
    }

    public static String formatEpochMillis(long epochMillis) {
        if (epochMillis <= 0) {
            return Long.toString(epochMillis);
        }
        return CACHE_TIMESTAMP.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String formatDate(Date date) {
        if (date == null) {
            return "";
        }
        return formatEpochMillis(date.getTime());
    }
}
