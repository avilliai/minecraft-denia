import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generates the 16x16 「寻路信标」(path_beacon) item icon: a glowing cyan diamond crystal with a hot
 * white core and four light rays, on a transparent ground — reads as a beacon/marker.
 * Pure ASCII (this machine's javac defaults to GBK). Usage: java GenBeaconTex <dst.png>
 */
public class GenBeaconTex {
    public static void main(String[] a) throws Exception {
        int n = 16;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        double c = (n - 1) / 2.0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = x - c, dy = y - c;
                double md = Math.abs(dx) + Math.abs(dy);   // diamond (Manhattan) distance
                int argb = 0;                               // transparent default
                if (md < 1.6)      argb = 0xFFFFFFFF;       // hot white core
                else if (md < 3.2) argb = 0xFF8AF0FF;       // bright cyan
                else if (md < 5.0) argb = 0xFF36C8F0;       // cyan body
                else if (md < 6.2) argb = 0xC01E8FC8;       // soft rim
                // four light rays along the axes
                if ((x == (int) Math.round(c) || y == (int) Math.round(c)) && md < 7.5 && md >= 5.0) {
                    argb = 0xB0BFF6FF;
                }
                if (argb != 0) img.setRGB(x, y, argb);
            }
        }
        // a couple of sparkle motes
        img.setRGB(3, 4, 0xFFEAFBFF);
        img.setRGB(12, 11, 0xFFEAFBFF);
        ImageIO.write(img, "PNG", new File(a[0]));
        System.out.println("GenBeaconTex: wrote 16x16 -> " + a[0]);
    }
}
