package io.jartree.scan;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits library file names such as {@code commons-lang3-3.12.0.jar} into an artifact name
 * ({@code commons-lang3}) and a version ({@code 3.12.0}).
 */
public final class NameParser {

    /** name, separator, version starting with a digit, e.g. guava-31.1-jre, foo_1.0, bar-2.0.0-SNAPSHOT. */
    private static final Pattern NAME_VERSION = Pattern.compile("^(.+?)[-_](v?\\d[0-9A-Za-z]*(?:[.\\-_+~][0-9A-Za-z]+)*)$");

    /** A bare version path segment, e.g. "1.2.3" or "2.0.0-SNAPSHOT". */
    private static final Pattern BARE_VERSION = Pattern.compile("^v?\\d+(?:[.\\-_+~][0-9A-Za-z]+)*$");

    private static final Set<String> EXTENSIONS = ConcurrentHashMap.newKeySet();

    static {
        EXTENSIONS.addAll(List.of("jar", "war", "ear", "rar", "sar", "aar", "zip", "har", "par", "ejb", "kar", "hpi", "jpi", "nbm"));
    }

    private NameParser() {
    }

    public record ParsedName(String name, String version, String extension) {
    }

    public static ParsedName parse(String fileName) {
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && isArchiveLikeExtension(fileName.substring(dot + 1))) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        Matcher m = NAME_VERSION.matcher(base);
        if (m.matches()) {
            return new ParsedName(m.group(1), m.group(2), ext);
        }
        if (BARE_VERSION.matcher(base).matches()) {
            return new ParsedName("", base, ext);
        }
        return new ParsedName(base, null, ext);
    }

    /** Removes version information from a single path segment so that versioned parent directories still match. */
    public static String normalizeSegment(String segment) {
        ParsedName p = parse(segment);
        if (p.version() == null) {
            return segment;
        }
        String name = p.name().isEmpty() ? "*" : p.name();
        return p.extension().isEmpty() ? name : name + "." + p.extension();
    }

    /**
     * Normalizes all segments of a {@code /} separated path. The {@code !} nested-archive marker is dropped so that an
     * exploded archive directory matches its packaged counterpart.
     */
    public static String normalizePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/", -1)) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(normalizeSegment(seg.endsWith("!") ? seg.substring(0, seg.length() - 1) : seg));
        }
        return sb.toString();
    }

    /** Registers additional archive extensions (lower case, without dot). */
    public static void registerExtensions(Collection<String> extensions) {
        for (String e : extensions) {
            EXTENSIONS.add(e.toLowerCase(Locale.ROOT));
        }
    }

    private static boolean isArchiveLikeExtension(String ext) {
        // only strip real file extensions, not version fragments like "Final" or "beta"
        return EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }
}
