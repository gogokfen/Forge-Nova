package forge.nova.online;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Board-sized copies of card images for friends playing online: the board draws cards at 256×357,
 * so sending that size instead of the full 488×680 scan saves more than half of the host's upload
 * (the full image is only fetched for the card someone looks at). Kept on disk (nova/cache/thumbs),
 * so each card is only scaled once.
 */
public final class Thumbnails {
    public static final int W = 256;
    public static final int H = 357;

    private final File dir;

    public Thumbnails(File dir) {
        this.dir = dir;
    }

    /** JPEG bytes of the scaled image, or null if the source can't be read. */
    public byte[] get(File src) {
        try {
            String id = sha1(src.getAbsolutePath() + "|" + src.lastModified() + "|" + src.length() + "|" + W);
            File out = new File(new File(dir, id.substring(0, 2)), id + ".jpg");
            if (out.isFile()) {
                return Files.readAllBytes(out.toPath());
            }
            BufferedImage img = ImageIO.read(src);
            if (img == null) {
                return null;
            }
            BufferedImage dst = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            try {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, W, H);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(img, 0, 0, W, H, null);
            } finally {
                g.dispose();
            }
            byte[] jpeg = encode(dst);
            out.getParentFile().mkdirs();
            File tmp = new File(out.getPath() + "." + Thread.currentThread().getId() + ".tmp");
            Files.write(tmp.toPath(), jpeg);
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return jpeg;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] encode(BufferedImage img) throws Exception {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            w.setOutput(ios);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.86f);
            w.write(null, new IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
        return bos.toByteArray();
    }

    private static String sha1(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
