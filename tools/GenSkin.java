import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * One-off generator for a placeholder 64x64 player skin + a 64x64 mod icon.
 * Run with JDK 17+. Not part of the mod build.
 */
public class GenSkin {
    static int SKIN = 0xFFE8B79B;   // skin tone
    static int HAIR = 0xFF5A3A24;   // brown hair
    static int SHIRT = 0xFFE86A9A;  // pink shirt
    static int PANTS = 0xFF3E4A6B;  // dark blue pants
    static int EYE = 0xFF2A2A2A;    // eyes
    static int BLUSH = 0xFFE89AA8;  // cheeks

    static void fill(BufferedImage img, int x, int y, int w, int h, int argb) {
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++)
                if (x + i < img.getWidth() && y + j < img.getHeight())
                    img.setRGB(x + i, y + j, argb);
    }

    static BufferedImage skin() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        // start fully transparent so overlay/hat UV regions stay transparent
        fill(img, 0, 0, 64, 64, 0x00000000);

        // --- Head (base layer) ---
        fill(img, 8, 0, 16, 8, HAIR);    // top + bottom of head -> hair
        fill(img, 0, 8, 32, 8, HAIR);    // right/front/left/back side strip -> hair
        fill(img, 8, 8, 8, 8, SKIN);     // front face -> skin
        // eyes + blush on the front face
        fill(img, 10, 12, 1, 1, EYE);
        fill(img, 13, 12, 1, 1, EYE);
        fill(img, 9, 13, 1, 1, BLUSH);
        fill(img, 14, 13, 1, 1, BLUSH);
        // a hair fringe over the top of the face
        fill(img, 8, 8, 8, 1, HAIR);

        // --- Body (base layer) ---
        fill(img, 16, 16, 24, 16, SHIRT);

        // --- Right leg (base) ---
        fill(img, 0, 16, 16, 16, PANTS);
        // --- Left leg (base) ---
        fill(img, 16, 48, 16, 16, PANTS);

        // --- Right arm (base) ---
        fill(img, 40, 16, 16, 16, SKIN);
        // --- Left arm (base) ---
        fill(img, 32, 48, 16, 16, SKIN);
        // short shirt sleeves on the arm tops
        fill(img, 44, 16, 4, 4, SHIRT);
        fill(img, 36, 48, 4, 4, SHIRT);

        return img;
    }

    static BufferedImage icon() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(img, 0, 0, 64, 64, 0xFF1E1424);
        // simple heart
        int[][] heart = {
            {0,1,1,0,0,1,1,0},
            {1,1,1,1,1,1,1,1},
            {1,1,1,1,1,1,1,1},
            {0,1,1,1,1,1,1,0},
            {0,0,1,1,1,1,0,0},
            {0,0,0,1,1,0,0,0},
        };
        for (int r = 0; r < heart.length; r++)
            for (int c = 0; c < heart[r].length; c++)
                if (heart[r][c] == 1)
                    fill(img, 16 + c * 4, 18 + r * 4, 4, 4, 0xFFE86A9A);
        return img;
    }

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : ".";
        File skinFile = new File(base, "src/main/resources/assets/mcgf/textures/entity/girlfriend.png");
        File iconFile = new File(base, "src/main/resources/assets/mcgf/icon.png");
        skinFile.getParentFile().mkdirs();
        iconFile.getParentFile().mkdirs();
        ImageIO.write(skin(), "png", skinFile);
        ImageIO.write(icon(), "png", iconFile);
        System.out.println("wrote " + skinFile.getPath());
        System.out.println("wrote " + iconFile.getPath());
    }
}
