package forge.nova;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Avatars and card sleeves sliced from the Forge skin sprite sheets, using the same
 * grid rules as forge.toolbox.FSkin so avatar/sleeve indices match classic Forge.
 */
public final class SkinAssets {
    private final List<int[]> avatarCells = new ArrayList<>(); // {sheet, x, y}
    private final List<int[]> sleeveCells = new ArrayList<>();
    private final List<BufferedImage> avatarSheets = new ArrayList<>();
    private final List<BufferedImage> sleeveSheets = new ArrayList<>();
    private final Map<String, byte[]> pngCache = new ConcurrentHashMap<>();

    public SkinAssets(File skinDir) {
        load(new File(skinDir, "sprite_avatars.png"), avatarSheets, avatarCells, 100, 100, true);
        load(new File(skinDir, "sprite_sleeves.png"), sleeveSheets, sleeveCells, 360, 500, false);
        load(new File(skinDir, "sprite_sleeves2.png"), sleeveSheets, sleeveCells, 360, 500, false);
    }

    private static void load(File f, List<BufferedImage> sheets, List<int[]> cells, int w, int h, boolean skipFirst) {
        if (!f.isFile()) {
            return;
        }
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) return;
            int sheet = sheets.size();
            sheets.add(img);
            for (int y = 0; y + h <= img.getHeight(); y += h) {
                for (int x = 0; x + w <= img.getWidth(); x += w) {
                    if (skipFirst && x == 0 && y == 0) continue;
                    int alpha = (img.getRGB(x + w / 2, y + h / 2) >>> 24) & 0xff;
                    if (alpha != 0) {
                        cells.add(new int[]{sheet, x, y, w, h});
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Nova] cannot read skin sprite " + f + ": " + e);
        }
    }

    public int avatarCount() {
        return avatarCells.size();
    }

    public int sleeveCount() {
        return sleeveCells.size();
    }

    public byte[] avatarPng(int index) {
        return cell("a" + index, avatarSheets, avatarCells, index);
    }

    public byte[] sleevePng(int index) {
        return cell("s" + index, sleeveSheets, sleeveCells, index);
    }

    private byte[] cell(String cacheKey, List<BufferedImage> sheets, List<int[]> cells, int index) {
        if (cells.isEmpty()) {
            return null;
        }
        int i = Math.floorMod(index, cells.size());
        return pngCache.computeIfAbsent(cacheKey.charAt(0) + String.valueOf(i), k -> {
            int[] c = cells.get(i);
            try {
                BufferedImage sub = sheets.get(c[0]).getSubimage(c[1], c[2], c[3], c[4]);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(sub, "png", bos);
                return bos.toByteArray();
            } catch (Exception e) {
                return null;
            }
        });
    }
}
