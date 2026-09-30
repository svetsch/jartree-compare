package io.jartree.compare;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The full content of one side of a changed file, kept deflate-compressed: it is only needed when the user copies
 * or saves the whole file, so it should cost as little memory as possible until then.
 */
public final class PackedText {

    /** Longer texts are not kept; their diff is still available. */
    public static final int MAX_LENGTH = 8 * 1024 * 1024;

    private final byte[] data;

    private PackedText(byte[] data) {
        this.data = data;
    }

    /** Compresses a text; null for null or for a text longer than {@link #MAX_LENGTH}. */
    public static PackedText of(String text) {
        if (text == null || text.length() > MAX_LENGTH) {
            return null;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(bytes);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, bytes.length / 4));
            byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                out.write(buf, 0, deflater.deflate(buf));
            }
            return new PackedText(out.toByteArray());
        } finally {
            deflater.end();
        }
    }

    /** Reads the form written by {@link #encoded()}; null for null. */
    public static PackedText decode(String encoded) {
        return encoded == null ? null : new PackedText(Base64.getDecoder().decode(encoded));
    }

    /** Base64 of the compressed bytes, for JSON files. */
    public String encoded() {
        return Base64.getEncoder().encodeToString(data);
    }

    public String text() {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && !inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalStateException("truncated compressed text");
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (DataFormatException e) {
            throw new IllegalStateException("corrupt compressed text", e);
        } finally {
            inflater.end();
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PackedText p && Arrays.equals(data, p.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "PackedText[" + data.length + " bytes]";
    }
}
