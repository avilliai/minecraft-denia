import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Turns the user-supplied 法杖 art (a tall non-square PNG, ~477x586) into a square, item-ready sprite for
 * 达妮娅's 形态一 专武「泡泡杖」: it is scaled (smooth, aspect-preserved) to fit inside a transparent SIZE x SIZE
 * canvas and centered, so {@code item/generated} renders it undistorted as a flat held staff (no more 3D
 * hammer-head model). Pure ASCII (this machine's javac defaults to GBK).
 * Usage: java PadWand <src.png> <dst.png> [size=64]
 */
public class PadWand {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int size = a.length > 2 ? Integer.parseInt(a[2]) : 64;
        int sw = src.getWidth(), sh = src.getHeight();
        double scale = Math.min((double) size / sw, (double) size / sh);
        int dw = Math.max(1, (int) Math.round(sw * scale));
        int dh = Math.max(1, (int) Math.round(sh * scale));
        Image scaled = src.getScaledInstance(dw, dh, Image.SCALE_SMOOTH);

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(scaled, (size - dw) / 2, (size - dh) / 2, null);
        g.dispose();

        // item/generated extrudes every semi-transparent edge into black needles, so harden the alpha.
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int p = out.getRGB(x, y);
                int alpha = (p >>> 24) & 0xFF;
                if (alpha < 96) out.setRGB(x, y, 0x00000000);
                else out.setRGB(x, y, 0xFF000000 | (p & 0x00FFFFFF));
            }
        }
        ImageIO.write(out, "PNG", new File(a[1]));
        System.out.println("PadWand: " + sw + "x" + sh + " -> " + size + "x" + size + " centered -> " + a[1]);
    }
}
