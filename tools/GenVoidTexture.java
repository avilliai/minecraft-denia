import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * Procedurally generates the 16x16 「虚质方块」(void_block) texture: dark-violet void matter,
 * a glowing concentric magenta "virtual matter" rune, a hot Fusion(fire)-orange core, energy
 * veins and a few sparkles. Deterministic (fixed seed) so re-runs are reproducible.
 *
 * Pure ASCII on purpose (this machine's javac defaults to GBK and chokes on CJK).
 * Usage: java GenVoidTexture <dst.png> [size=16]
 */
public class GenVoidTexture {
    public static void main(String[] a) throws Exception {
        File dst = new File(a[0]);
        int n = a.length > 1 ? Integer.parseInt(a[1]) : 16;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(0xDA1A);
        double c = (n - 1) / 2.0;

        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = x - c, dy = y - c;
                double md = Math.abs(dx) + Math.abs(dy);   // diamond (Manhattan) distance
                double t = (double) y / (n - 1);

                // 1) base: vertical violet gradient (top brighter) + faint noise
                int r = lerp(46, 16, t), g = lerp(22, 9, t), b = lerp(78, 34, t);
                int noise = rng.nextInt(14) - 7;
                r += noise; g += noise; b += noise;

                // 2) energy veins (thin diagonal lattice)
                if ((x + y) % 6 == 0 || (x - y + n) % 6 == 0) { r += 24; g += 8; b += 36; }

                // 3) concentric "virtual matter" rune rings, in violet->magenta
                double ring = n / 4.4;
                if (Math.abs(md - ring) < 0.8) { r = 198; g = 70; b = 255; }
                if (Math.abs(md - ring * 1.85) < 0.8) { r = 150; g = 48; b = 210; }

                // 4) hot Fusion core at the center
                if (md < 1.7) { r = 255; g = 196; b = 138; }
                else if (md < 2.7) { r = 255; g = 120; b = 74; }

                img.setRGB(x, y, 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
            }
        }

        // 5) sparkles — a handful of bright violet-white motes
        for (int i = 0; i < 7; i++) {
            int sx = rng.nextInt(n), sy = rng.nextInt(n);
            img.setRGB(sx, sy, 0xFFEAD2FF);
        }

        // 6) darker frame so the cube tiles with crisp edges, bright magenta corners
        for (int i = 0; i < n; i++) {
            darken(img, i, 0); darken(img, i, n - 1); darken(img, 0, i); darken(img, n - 1, i);
        }
        int corner = 0xFFD46BFF;
        img.setRGB(0, 0, corner); img.setRGB(n - 1, 0, corner);
        img.setRGB(0, n - 1, corner); img.setRGB(n - 1, n - 1, corner);

        ImageIO.write(img, "PNG", dst);
        System.out.println("GenVoidTexture: wrote " + n + "x" + n + " -> " + dst);
    }

    private static void darken(BufferedImage img, int x, int y) {
        int p = img.getRGB(x, y);
        int r = ((p >> 16) & 0xFF) * 6 / 10, g = ((p >> 8) & 0xFF) * 6 / 10, b = (p & 0xFF) * 6 / 10;
        img.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
    }

    private static int lerp(int from, int to, double t) { return (int) Math.round(from + (to - from) * t); }
    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
