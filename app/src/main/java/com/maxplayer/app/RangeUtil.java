package com.maxplayer.app;

/** Разбор заголовка Range для отдачи файлов частями (перемотка трека). Без зависимостей от Android. */
final class RangeUtil {
    private RangeUtil() {}

    /** Диапазон лежит за пределами файла: ответ 416. */
    static final long[] UNSATISFIABLE = new long[] { -1, -1 };

    /**
     * @return null, если нужно отдать файл целиком (заголовка нет или он непонятен);
     *         UNSATISFIABLE, если диапазон невыполним; иначе {начало, конец}, оба включительно.
     */
    static long[] parse(String header, long size) {
        if (header == null || size <= 0) return null;
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, 6)) return null;
        String spec = h.substring(6).trim();
        if (spec.isEmpty() || spec.indexOf(',') >= 0) return null;
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String a = spec.substring(0, dash).trim();
        String b = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (a.isEmpty()) {
                if (b.isEmpty()) return null;
                long n = Long.parseLong(b);
                if (n <= 0) return UNSATISFIABLE;
                start = Math.max(0, size - n);
                end = size - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? size - 1 : Long.parseLong(b);
                if (start < 0) return null;
                if (start >= size) return UNSATISFIABLE;
                if (end < start) return null;
                if (end >= size) end = size - 1;
            }
            return new long[] { start, end };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
