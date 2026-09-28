package io.jartree.compare;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

/** Text detection, normalization of build noise, and unified diffs. */
public final class TextSupport {

    /** Manifest attributes that change on every build without reflecting a code change. */
    public static final Set<String> NOISY_MANIFEST_ATTRIBUTES = Set.of(
            "bnd-lastmodified", "build-time", "build-timestamp", "build-date", "built-by", "build-jdk",
            "build-jdk-spec", "created-by", "implementation-build-date", "build-host", "build-os", "tool",
            "originally-created-by", "sha-256-digest", "sha1-digest", "md5-digest");

    private static final Pattern SIGNATURE_FILE = Pattern.compile("META-INF/[^/]+\\.(SF|RSA|DSA|EC)", Pattern.CASE_INSENSITIVE);

    private TextSupport() {
    }

    public record DiffText(String text, int added, int removed) {
        public static final DiffText NONE = new DiffText("", 0, 0);

        public boolean isEmpty() {
            return added == 0 && removed == 0;
        }
    }

    public static boolean isSignatureFile(String path) {
        return SIGNATURE_FILE.matcher(path).matches();
    }

    public static boolean isText(byte[] data) {
        int n = Math.min(data.length, 8192);
        for (int i = 0; i < n; i++) {
            if (data[i] == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, 0, n == data.length ? n : utf8Boundary(data, n)));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static int utf8Boundary(byte[] data, int n) {
        // do not cut a multi-byte sequence in half when only checking a prefix
        int i = n;
        while (i > 0 && (data[i] & 0xC0) == 0x80) {
            i--;
        }
        return i;
    }

    public static String decode(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Returns a normalized form used to decide whether a change is only build noise: generated comments in
     * {@code .properties} files and volatile manifest attributes are removed.
     */
    public static String normalizeForNoise(String path, String text) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.equals("meta-inf/manifest.mf")) {
            StringBuilder sb = new StringBuilder();
            for (String line : unfoldManifest(text)) {
                int colon = line.indexOf(':');
                String key = colon < 0 ? line : line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if (!NOISY_MANIFEST_ATTRIBUTES.contains(key) && !line.isBlank()) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }
        if (lower.endsWith(".properties")) {
            StringBuilder sb = new StringBuilder();
            for (String line : text.split("\\R")) {
                String t = line.stripLeading();
                if (!t.startsWith("#") && !t.startsWith("!") && !t.isBlank()) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }
        return text;
    }

    private static List<String> unfoldManifest(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.startsWith(" ") && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + line.substring(1));
            } else {
                lines.add(line);
            }
        }
        return lines;
    }

    public static DiffText unifiedDiff(String oldName, String newName, String oldText, String newText, int context,
                                       int maxLines) {
        return diffs(oldName, newName, oldText, newText, context, maxLines, false).diff();
    }

    /**
     * A unified diff of two texts, and the same diff with the whole file as context.
     *
     * @param diff  the diff with {@code context} lines around each change, cut after {@code maxLines}
     * @param whole every line of both texts, never cut; {@link DiffText#NONE} when they are equal
     */
    public record Diffs(DiffText diff, DiffText whole) {
    }

    public static Diffs unifiedDiffs(String oldName, String newName, String oldText, String newText, int context,
                                     int maxLines) {
        return diffs(oldName, newName, oldText, newText, context, maxLines, true);
    }

    private static Diffs diffs(String oldName, String newName, String oldText, String newText, int context,
                               int maxLines, boolean withWhole) {
        List<String> a = oldText == null ? List.of() : lines(oldText);
        List<String> b = newText == null ? List.of() : lines(newText);
        Patch<String> patch = DiffUtils.diff(a, b);
        if (patch.getDeltas().isEmpty()) {
            return new Diffs(DiffText.NONE, DiffText.NONE);
        }
        String from = oldText == null ? "/dev/null" : oldName;
        String to = newText == null ? "/dev/null" : newName;
        return new Diffs(render(from, to, a, patch, context, maxLines),
                withWhole ? render(from, to, a, patch, a.size() + b.size(), 0) : DiffText.NONE);
    }

    private static DiffText render(String oldName, String newName, List<String> a, Patch<String> patch, int context,
                                   int maxLines) {
        int added = 0;
        int removed = 0;
        for (var delta : patch.getDeltas()) {
            added += delta.getTarget().size();
            removed += delta.getSource().size();
        }
        List<String> out = UnifiedDiffUtils.generateUnifiedDiff(oldName, newName, a, patch, context);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : out) {
            if (maxLines > 0 && count++ >= maxLines) {
                sb.append("... diff truncated (").append(out.size() - maxLines).append(" more lines)\n");
                break;
            }
            sb.append(line).append('\n');
        }
        return new DiffText(sb.toString(), added, removed);
    }

    /** Gzip-compresses a text, to keep large texts that are rarely looked at small in memory. */
    public static byte[] compress(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64, text.length() / 6));
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static String decompress(byte[] data) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> lines(String text) {
        List<String> list = new ArrayList<>(List.of(text.split("\\R", -1)));
        if (!list.isEmpty() && list.get(list.size() - 1).isEmpty()) {
            list.remove(list.size() - 1);
        }
        return list;
    }
}
