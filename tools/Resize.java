import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

public class Resize {
    public static void main(String[] a) throws Exception {
        var img = ImageIO.read(Paths.get(a[0]).toFile());
        int w = Integer.parseInt(a[2]);
        int h = Math.round(img.getHeight() * (w / (float) img.getWidth()));
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        ImageIO.write(out, "png", Paths.get(a[1]).toFile());
        System.out.println(a[1] + " " + w + "x" + h + " from " + img.getWidth() + "x" + img.getHeight());
    }
}
