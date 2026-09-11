import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * Generates the TWO textures for the reworked 「虚质方块」(void_block): a SEMI-TRANSPARENT outer shell
 * and an opaque GLOWING inner core. The block model nests a small core cube inside a translucent shell
 * cube so the cube reads as ghostly virtual matter with a hot magenta/orange heart (reference image 4).
 *
 * Pure ASCII on purpose (this machine's javac defaults to GBK and chokes on CJK).
 * Usage: java GenVoidBlockTex <shell.png> <core.png> [size=16]
 */
public class GenVoidBlockTex {
    public static void main(String[] a) throws Exception {
        File shellDst = new File(a[0]);
        File coreDst = new File(a[1]);
        int n = a.length > 2 ? Integer.parseInt(a[2]) : 16;
        ImageIO.write(shell(n), "PNG", shellDst);
        ImageIO.write(core(n), "PNG", coreDst);
        System.out.println("GenVoidBlockTex: wrote shell -> " + shellDst + ", core -> " + coreDst);
    }

    /**
     * Translucent shell in 达妮娅's 二形态 palette — deep blue / black / purple — with a blue energy lattice.
     * Alpha stays low in the middle so the glowing core shows through.
     */
    private static BufferedImage shell(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(0xDA1A);
        double c = (n - 1) / 2.0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = x - c, dy = y - c;
                double md = Math.abs(dx) + Math.abs(dy);
                double t = (double) y / (n - 1);
                // top: deep blue-violet; bottom: near-black blue (黑/深蓝)
                int r = lerp(64, 24, t), g = lerp(54, 20, t), b = lerp(150, 70, t);
                int noise = rng.nextInt(14) - 7;
                r += noise; g += noise; b += noise;
                int alpha = 96;                                  // ghostly: see the core through it
                // blue energy veins (thin diagonal lattice)
                if ((x + y) % 6 == 0 || (x - y + n) % 6 == 0) { r += 18; g += 40; b += 70; alpha = 150; }
                // faint rune ring (electric blue/purple)
                double ring = n / 3.0;
                if (Math.abs(md - ring) < 0.9) { r = 110; g = 130; b = 255; alpha = 175; }
                img.setRGB(x, y, (clamp(alpha) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
            }
        }
        // crisp, more-opaque frame so the cube edges read clearly even when translucent
        for (int i = 0; i < n; i++) { frame(img, i, 0); frame(img, i, n - 1); frame(img, 0, i); frame(img, n - 1, i); }
        int corner = (0xE0 << 24) | 0x6B8BFF;
        img.setRGB(0, 0, corner); img.setRGB(n - 1, 0, corner);
        img.setRGB(0, n - 1, corner); img.setRGB(n - 1, n - 1, corner);
        return img;
    }

    /** Opaque, glowing heart in the blue-mixed palette: blue-white center → cyan/blue → purple → deep blue edge. */
    private static BufferedImage core(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(0xC0FFEE);
        double c = (n - 1) / 2.0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = x - c, dy = y - c;
                double rd = Math.sqrt(dx * dx + dy * dy);
                double md = Math.abs(dx) + Math.abs(dy);
                int r, g, b;
                if (md < 1.8)       { r = 200; g = 240; b = 255; }  // blue-white hot heart
                else if (md < 3.2)  { r = 90;  g = 190; b = 255; }  // bright cyan-blue
                else if (rd < n * 0.34) { r = 70;  g = 110; b = 255; } // electric blue
                else if (rd < n * 0.5)  { r = 130; g = 70;  b = 230; } // blue-violet
                else                { r = 60;  g = 36;  b = 150; }  // deep blue edge
                int noise = rng.nextInt(12) - 6;
                img.setRGB(x, y, 0xFF000000 | (clamp(r + noise) << 16) | (clamp(g + noise) << 8) | clamp(b + noise));
            }
        }
        // a few bright sparkles
        for (int i = 0; i < 5; i++) img.setRGB(rng.nextInt(n), rng.nextInt(n), 0xFFE6F4FF);
        return img;
    }

    private static void frame(BufferedImage img, int x, int y) {
        int p = img.getRGB(x, y);
        int r = ((p >> 16) & 0xFF) * 6 / 10, g = ((p >> 8) & 0xFF) * 6 / 10, b = (p & 0xFF) * 6 / 10;
        img.setRGB(x, y, (0xC0 << 24) | (r << 16) | (g << 8) | b);
    }

    private static int lerp(int from, int to, double t) { return (int) Math.round(from + (to - from) * t); }
    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
