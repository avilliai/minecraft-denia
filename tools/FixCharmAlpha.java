import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Cleans a held-item sprite so {@code item/generated} stops extruding it into
 * dark "needles". Two causes, two fixes:
 *   1) semi-transparent (anti-aliased) edge texels -> binarise alpha to 0/255;
 *   2) excessive resolution (e.g. 256x256) -> the per-texel side faces become a
 *      fine comb that reads as needles, so downscale to a sane item resolution
 *      where the extruded sides are chunky and clean, like a vanilla item.
 *
 * Pure ASCII on purpose (this machine's javac defaults to GBK and chokes on CJK).
 * Usage: java FixCharmAlpha <src> <dst> <size|0=keep> [threshold=128]
 */
public class FixCharmAlpha {
    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        File dst = new File(a[1]);
        int size = a.length > 2 ? Integer.parseInt(a[2]) : 0;
        int threshold = a.length > 3 ? Integer.parseInt(a[3]) : 128;

        BufferedImage in = ImageIO.read(src);
        int sw = in.getWidth(), sh = in.getHeight();

        BufferedImage work = in;
        if (size > 0 && (sw != size || sh != size)) {
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(in, 0, 0, size, size, null);
            g.dispose();
            work = scaled;
        }

        int w = work.getWidth(), h = work.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        long opaque = 0, clear = 0, partial = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = work.getRGB(x, y);
                int alpha = (p >>> 24) & 0xFF;
                if (alpha > 0 && alpha < 255) partial++;
                if (alpha >= threshold) {
                    out.setRGB(x, y, 0xFF000000 | (p & 0x00FFFFFF));
                    opaque++;
                } else {
                    out.setRGB(x, y, 0x00000000);
                    clear++;
                }
            }
        }
        ImageIO.write(out, "PNG", dst);
        System.out.println("FixCharmAlpha: " + sw + "x" + sh + " -> " + w + "x" + h
            + " threshold=" + threshold
            + " partialAlphaBeforeBinarise=" + partial
            + " opaque=" + opaque + " transparent=" + clear);
    }
}
