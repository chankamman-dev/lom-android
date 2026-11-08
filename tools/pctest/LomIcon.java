import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

// Launcher icons for 活侠传 from the game's own calligraphy logo (Steam library art, art/logo.png):
// the 「活」 character in ink on rice paper, inside a faint ink-wash ring.
//   LomIcon <logo.png> <res dir> [preview.png]
// Writes mipmap-*/app_icon.png and app_icon_round.png (legacy), ic_launcher_foreground/background.png
// and mipmap-anydpi-v26/app_icon(.round).xml (adaptive).
public class LomIcon {
    static final Color PAPER_IN = new Color(0xF3ECDF), PAPER_OUT = new Color(0xD8C9B1);

    // The first character of the logo, with the grey wash behind it faded out so only ink remains.
    static BufferedImage glyph(BufferedImage logo) {
        int x0 = 10, x1 = 284, y0 = 44, y1 = 294;
        BufferedImage g = new BufferedImage(x1 - x0, y1 - y0, BufferedImage.TYPE_INT_ARGB);
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                int p = logo.getRGB(x, y), a = p >>> 24;
                int r = p >> 16 & 255, gg = p >> 8 & 255, b = p & 255;
                // Lightness as seen on white paper: the wash is dark grey at low opacity.
                int lum = 255 - a * (255 - (r * 3 + gg * 6 + b) / 10) / 255;
                // Ink is dark; the wash behind it is lighter grey. Fade out between lum 95 and 140.
                double k = lum <= 95 ? 1 : lum >= 140 ? 0 : (140 - lum) / 45.0;
                int na = (int) Math.round(a * k);
                g.setRGB(x - x0, y - y0, na << 24 | (p & 0xFFFFFF));
            }
        return g;
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    // Paper with a soft vignette and an ink-wash ring (enso), full bleed, size x size.
    static BufferedImage background(int size) { return background(size, 1.0); }

    static BufferedImage background(int size, double k) {
        BufferedImage d = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = d.createGraphics();
        hints(g);
        float c = size / 2f;
        g.setPaint(new RadialGradientPaint(c, c * 0.9f, size * 0.75f, new float[] { 0f, 1f }, new Color[] { PAPER_IN, PAPER_OUT }));
        g.fillRect(0, 0, size, size);
        // Ring: many thin translucent strokes with slight wobble, like a brush circle.
        java.util.Random rnd = new java.util.Random(7);
        double R = size * 0.335 * k;
        for (int i = 0; i < 90; i++) {
            double r = R + (rnd.nextGaussian() * size * 0.012);
            double start = 100 + rnd.nextGaussian() * 6, extent = 300 + rnd.nextGaussian() * 10;
            float w = (float) (size * (0.004 + rnd.nextDouble() * 0.01));
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(70, 62, 56, 5 + rnd.nextInt(9)));
            g.draw(new Arc2D.Double(c - r, c - r, 2 * r, 2 * r, start, extent, Arc2D.OPEN));
        }
        // A small red seal, lower right, as on the game's title screen.
        double s = size * 0.06, sx = c + R * 0.68, sy = c + R * 0.62;
        g.setColor(new Color(0xA8, 0x2C, 0x24, 225));
        g.fill(new RoundRectangle2D.Double(sx, sy, s, s * 1.3, s * 0.12, s * 0.12));
        g.dispose();
        return d;
    }

    // The glyph centred, its larger side `fill` of the canvas.
    static BufferedImage foreground(BufferedImage glyph, int size, double fill) {
        BufferedImage d = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = d.createGraphics();
        hints(g);
        double s = size * fill / Math.max(glyph.getWidth(), glyph.getHeight());
        double w = glyph.getWidth() * s, h = glyph.getHeight() * s;
        g.drawImage(down(glyph, (int) Math.round(w), (int) Math.round(h)), (int) Math.round((size - w) / 2 - size * 0.01), (int) Math.round((size - h) / 2), null);
        g.dispose();
        return d;
    }

    static BufferedImage down(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            BufferedImage n = new BufferedImage(cur.getWidth() / 2, cur.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = n.createGraphics();
            hints(g);
            g.drawImage(cur, 0, 0, n.getWidth(), n.getHeight(), null);
            g.dispose();
            cur = n;
        }
        BufferedImage d = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = d.createGraphics();
        hints(g);
        g.drawImage(cur, 0, 0, w, h, null);
        g.dispose();
        return d;
    }

    static BufferedImage compose(BufferedImage glyph, int size, Shape clip) {
        int big = size * 4;
        BufferedImage full = background(big);
        Graphics2D g = full.createGraphics();
        hints(g);
        g.drawImage(foreground(glyph, big, 0.56), 0, 0, null);
        g.dispose();
        BufferedImage small = down(full, size, size);
        if (clip == null) return small;
        BufferedImage d = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D cg = d.createGraphics();
        hints(cg);
        cg.setClip(clip);
        cg.drawImage(small, 0, 0, null);
        cg.dispose();
        return d;
    }

    public static void main(String[] a) throws Exception {
        BufferedImage glyph = glyph(ImageIO.read(new File(a[0])));
        File res = new File(a[1]);
        String[] dens = { "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi" };
        double[] mul = { 1, 1.5, 2, 3, 4 };
        for (int i = 0; i < dens.length; i++) {
            File dir = new File(res, "mipmap-" + dens[i]);
            dir.mkdirs();
            int legacy = (int) Math.round(48 * mul[i]);
            double m = legacy / 24.0, side = legacy - 2 * m;
            ImageIO.write(compose(glyph, legacy, new RoundRectangle2D.Double(m, m, side, side, side * 0.36, side * 0.36)), "png", new File(dir, "app_icon.png"));
            ImageIO.write(compose(glyph, legacy, new Ellipse2D.Double(m, m, side, side)), "png", new File(dir, "app_icon_round.png"));
            // Adaptive: 108 dp layers, about the middle 72 dp is shown. Background: paper and ring at
            // 108 dp scale (the ring sits inside the visible window); foreground: the glyph.
            int ad = (int) Math.round(108 * mul[i]);
            BufferedImage bg = down(background(ad * 4, 72 / 108.0), ad, ad);
            ImageIO.write(bg, "png", new File(dir, "ic_launcher_background.png"));
            ImageIO.write(down(foreground(glyph, ad * 4, 0.56 * 72 / 108.0 * 1.0), ad, ad), "png", new File(dir, "ic_launcher_foreground.png"));
        }
        File any = new File(res, "mipmap-anydpi-v26");
        any.mkdirs();
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                + "    <background android:drawable=\"@mipmap/ic_launcher_background\" />\n"
                + "    <foreground android:drawable=\"@mipmap/ic_launcher_foreground\" />\n</adaptive-icon>\n";
        java.nio.file.Files.writeString(new File(any, "app_icon.xml").toPath(), xml);
        java.nio.file.Files.writeString(new File(any, "app_icon_round.xml").toPath(), xml);
        if (a.length > 2) ImageIO.write(compose(glyph, 512, new RoundRectangle2D.Double(0, 0, 512, 512, 150, 150)), "png", new File(a[2]));
    }
}
