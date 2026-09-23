import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Draws the application icon (two archives and an arrow) and writes the PNG files used by the window and the
 * Windows .ico used by jpackage. Run it after changing the design:
 *
 * <pre>java tools/IconGenerator.java</pre>
 */
public final class IconGenerator {

    private static final Color BACKGROUND_TOP = new Color(0x3B4A66);
    private static final Color BACKGROUND_BOTTOM = new Color(0x1E2632);
    private static final Color OLD_JAR = new Color(0x9AA4B5);
    private static final Color OLD_JAR_TOP = new Color(0xC3CAD6);
    private static final Color NEW_JAR = new Color(0x5B8CFF);
    private static final Color NEW_JAR_TOP = new Color(0x9DB8FF);
    private static final Color ARROW = new Color(0xF2F4F8);
    private static final Color ADDED = new Color(0x3FBF62);
    private static final Color REMOVED = new Color(0xE2574C);

    public static void main(String[] args) throws IOException {
        Path resources = Path.of("src/main/resources/io/jartree/gui");
        Path packaging = Path.of("packaging");
        Files.createDirectories(resources);
        Files.createDirectories(packaging);

        for (int size : new int[] {32, 64, 128, 256}) {
            ImageIO.write(draw(size), "png", resources.resolve("icon-" + size + ".png").toFile());
        }
        writeIco(packaging.resolve("jartree-compare.ico"), List.of(16, 24, 32, 48, 64, 128, 256));
        System.out.println("icons written to " + resources + " and " + packaging);
    }

    /** The icon: two archives, the new one marked with an added and a removed line. */
    static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double s = size / 256.0;

        g.setPaint(new GradientPaint(0, 0, BACKGROUND_TOP, 0, size, BACKGROUND_BOTTOM));
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, 56 * s, 56 * s));

        jar(g, 40 * s, 74 * s, 74 * s, 116 * s, OLD_JAR, OLD_JAR_TOP);
        jar(g, 142 * s, 62 * s, 74 * s, 128 * s, NEW_JAR, NEW_JAR_TOP);

        // arrow from the old to the new archive
        GeneralPath arrow = new GeneralPath();
        arrow.moveTo(120 * s, 134 * s);
        arrow.lineTo(138 * s, 134 * s);
        arrow.lineTo(138 * s, 124 * s);
        arrow.lineTo(156 * s, 142 * s);
        arrow.lineTo(138 * s, 160 * s);
        arrow.lineTo(138 * s, 150 * s);
        arrow.lineTo(120 * s, 150 * s);
        arrow.closePath();
        g.setColor(ARROW);
        g.fill(arrow);

        // a line added and a line removed inside the new archive
        if (size >= 48) {
            g.setColor(ADDED);
            g.fill(new RoundRectangle2D.Double(156 * s, 104 * s, 46 * s, 12 * s, 6 * s, 6 * s));
            g.setColor(REMOVED);
            g.fill(new RoundRectangle2D.Double(156 * s, 128 * s, 32 * s, 12 * s, 6 * s, 6 * s));
        }
        g.dispose();
        return image;
    }

    private static void jar(Graphics2D g, double x, double y, double width, double height, Color body, Color top) {
        double lid = height * 0.22;
        g.setColor(body);
        g.fill(new RoundRectangle2D.Double(x, y + lid / 2, width, height - lid / 2, width * 0.18, width * 0.18));
        g.setColor(top);
        g.fill(new Ellipse2D.Double(x, y, width, lid));
    }

    /** Windows icon container holding one PNG per size (supported since Windows Vista). */
    private static void writeIco(Path file, List<Integer> sizes) throws IOException {
        List<byte[]> images = sizes.stream().map(size -> {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageIO.write(draw(size), "png", bytes);
                return bytes.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList();

        try (OutputStream out = Files.newOutputStream(file);
             DataOutputStream data = new DataOutputStream(out)) {
            writeShort(data, 0);
            writeShort(data, 1);
            writeShort(data, sizes.size());
            int offset = 6 + 16 * sizes.size();
            for (int i = 0; i < sizes.size(); i++) {
                int size = sizes.get(i);
                data.writeByte(size >= 256 ? 0 : size);
                data.writeByte(size >= 256 ? 0 : size);
                data.writeByte(0);
                data.writeByte(0);
                writeShort(data, 1);
                writeShort(data, 32);
                writeInt(data, images.get(i).length);
                writeInt(data, offset);
                offset += images.get(i).length;
            }
            for (byte[] image : images) {
                data.write(image);
            }
        }
    }

    private static void writeShort(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >> 8) & 0xFF);
    }

    private static void writeInt(DataOutputStream out, int value) throws IOException {
        writeShort(out, value & 0xFFFF);
        writeShort(out, (value >>> 16) & 0xFFFF);
    }
}
