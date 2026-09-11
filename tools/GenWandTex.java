import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * Generates the 16x16 texture atlas for 达妮娅's 形态一 signature weapon「泡泡杖」(bubble_wand) rendered
 * as a real 3D staff (see models/item/bubble_wand.json). The model maps:
 *   - the ORB cube faces to texture region [0,0]-[6,6]   (a glowing cyan bubble head)
 *   - the SHAFT side faces to region [8,0]-[10,16]       (a teal/silver banded handle)
 *   - the SHAFT cap faces to region [8,0]-[10,2]
 * Everything else is transparent. Pure ASCII (this machine's javac defaults to GBK).
 * Usage: java GenWandTex <dst.png>
 */
public class GenWandTex {
    public static void main(String[] a) throws Exception {
        int n = 16;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(0xB0BB1E);

        // ORB head: glowing cyan bubble in [0,0]-[6,6]
        double oc = 2.5;
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 6; x++) {
                double d = Math.sqrt((x - oc) * (x - oc) + (y - oc) * (y - oc));
                if (d > 3.0) continue;                       // round the orb, leave corners clear
                int r, g, b;
                if (d < 1.0)      { r = 220; g = 255; b = 255; } // bright highlight
                else if (d < 2.0) { r = 90;  g = 225; b = 255; } // cyan body
                else              { r = 60;  g = 170; b = 230; } // rim
                int nz = rng.nextInt(10) - 5;
                img.setRGB(x, y, 0xFF000000 | (cl(r + nz) << 16) | (cl(g + nz) << 8) | cl(b + nz));
            }
        }
        // a couple of pink sparkles on the orb
        img.setRGB(1, 1, 0xFFFF9AD6);
        img.setRGB(4, 2, 0xFFFFC2E6);

        // SHAFT handle strip in [8,0]-[10,16] (2 wide x 16 tall): teal/silver with banding
        for (int y = 0; y < 16; y++) {
            for (int x = 8; x < 10; x++) {
                int r, g, b;
                boolean band = (y % 4 == 0);                 // metal bands down the handle
                if (band)        { r = 210; g = 235; b = 240; } // bright silver band
                else if (x == 8) { r = 60;  g = 150; b = 165; } // teal, lit edge
                else             { r = 38;  g = 110; b = 125; } // teal, shaded edge
                int nz = rng.nextInt(8) - 4;
                img.setRGB(x, y, 0xFF000000 | (cl(r + nz) << 16) | (cl(g + nz) << 8) | cl(b + nz));
            }
        }
        ImageIO.write(img, "PNG", new File(a[0]));
        System.out.println("GenWandTex: wrote 16x16 -> " + a[0]);
    }

    private static int cl(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
