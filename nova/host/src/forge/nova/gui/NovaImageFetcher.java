package forge.nova.gui;

import forge.ImageKeys;
import forge.item.PaperCard;
import forge.util.ImageUtil;
import forge.localinstance.properties.ForgeConstants;
import forge.util.BuildInfo;
import forge.util.ImageFetcher;
import forge.util.ScryfallRateLimiter;
import forge.util.TextUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Downloads missing card/token images using Forge's own URL resolution (Scryfall + set lookups),
 * saving them in the same cache folder classic Forge uses, so both clients share one cache.
 */
public final class NovaImageFetcher extends ImageFetcher {
    /**
     * ImageKeys keeps its caches in plain HashMaps; every access from the host goes through
     * this lock (lookups are fast, downloads happen outside of it).
     */
    public static final Object IMAGE_LOCK = new Object();

    private final NovaEdt edt;
    /** set synchronously (on the UI thread) when ImageFetcher decided to start a download */
    private CompletableFuture<Boolean> lastCreatedTask;
    private final Map<String, CompletableFuture<Boolean>> inflightByKey = new ConcurrentHashMap<>();

    public NovaImageFetcher(NovaEdt edt) {
        this.edt = edt;
    }

    /**
     * Returns the cached image file for a key, downloading it first if necessary.
     * Blocks the calling (worker) thread for at most {@code timeoutSeconds}.
     */
    public File getOrFetch(String key, int timeoutSeconds) {
        File f = lookup(key);
        if (f != null) {
            return f;
        }
        CompletableFuture<Boolean> done = inflightByKey.computeIfAbsent(key, k -> {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            edt.later(() -> {
                try {
                    CompletableFuture<Boolean> task;
                    synchronized (IMAGE_LOCK) {
                        lastCreatedTask = null;
                        fetchImage(k, () -> { });
                        task = lastCreatedTask;
                        lastCreatedTask = null;
                    }
                    if (task != null) {
                        task.whenComplete((ok, ex) -> result.complete(ok != null && ok));
                    } else {
                        // Nothing was queued (no known URL, custom card, earlier failure...).
                        result.complete(false);
                    }
                } catch (Throwable t) {
                    result.complete(false);
                }
            });
            return result;
        });
        try {
            done.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // timeout or failure: fall through and report whatever exists on disk
        } finally {
            if (done.isDone()) {
                inflightByKey.remove(key, done);
            }
        }
        synchronized (IMAGE_LOCK) {
            ImageKeys.clearMissingCards();
        }
        return lookup(key);
    }

    /** Thread-safe ImageKeys lookup; null when the image is not on disk. */
    public static File lookup(String key) {
        File f;
        synchronized (IMAGE_LOCK) {
            String fileKey = toFileKey(key);
            f = fileKey == null ? null : ImageKeys.getImageFile(fileKey);
        }
        return f != null && f.isFile() ? f : null;
    }

    /**
     * Card keys ("c:Name|SET|art", optionally with $alt / $Xspec suffixes) name a printing; ImageKeys
     * expects the relative file path of that printing. Same conversion as forge.ImageCache.
     */
    static String toFileKey(String imageKey) {
        if (imageKey == null || !imageKey.startsWith("c:")) {
            return imageKey;
        }
        boolean alt = imageKey.endsWith("$alt");
        String spec = "";
        if (imageKey.endsWith("$wspec")) spec = "white";
        else if (imageKey.endsWith("$uspec")) spec = "blue";
        else if (imageKey.endsWith("$bspec")) spec = "black";
        else if (imageKey.endsWith("$rspec")) spec = "red";
        else if (imageKey.endsWith("$gspec")) spec = "green";
        String k = imageKey;
        if (alt) {
            k = k.substring(0, k.length() - "$alt".length());
        } else if (!spec.isEmpty()) {
            k = k.substring(0, k.length() - "$wspec".length());
        }
        PaperCard pc = ImageUtil.getPaperCardFromImageKey(k);
        if (pc == null) {
            return null;
        }
        String fileKey = alt ? pc.getCardAltImageKey() : !spec.isEmpty() ? ImageUtil.getImageKey(pc, spec, true) : pc.getCardImageKey();
        return fileKey == null || fileKey.isBlank() ? null : fileKey;
    }

    @Override
    protected Runnable getDownloadTask(String[] downloadUrls, String destPath, Runnable notifyObservers) {
        final CompletableFuture<Boolean> task = new CompletableFuture<>();
        lastCreatedTask = task;
        return () -> {
            boolean ok = false;
            for (String url : downloadUrls) {
                try {
                    if (doFetch(url, destPath)) {
                        ok = true;
                        break;
                    }
                } catch (Exception e) {
                    System.err.println("[Nova] Image download failed (" + url + "): " + e.getMessage());
                }
            }
            if (ok) {
                edt.later(notifyObservers);
            }
            task.complete(ok);
        };
    }

    /** Mirrors forge.util.SwingImageFetcher's naming rules without depending on AWT/Swing threads. */
    private static boolean doFetch(String urlToDownload, String destPath) throws IOException {
        if (urlToDownload.startsWith("https://downloads.cardforge.org") || urlToDownload.startsWith("PLANECHASEBG:")) {
            return false;
        }
        if (ScryfallRateLimiter.shouldSkip(urlToDownload)) {
            return false;
        }
        boolean isScryfallUrl = urlToDownload.startsWith("https://api.scryfall.com/cards/") || urlToDownload.startsWith("https://cards.scryfall.io/");
        String newDestPath = !urlToDownload.contains(".fullborder.jpg") && !isScryfallUrl
                ? destPath
                : TextUtil.fastReplace(destPath, ".full.jpg", ".fullborder.jpg");
        if (!newDestPath.contains(".full") && !newDestPath.contains(".artcrop") && isScryfallUrl
                && !destPath.startsWith(ForgeConstants.CACHE_TOKEN_PICS_DIR)) {
            newDestPath = newDestPath.replace(".jpg", ".fullborder.jpg");
        }

        URL url = new URL(urlToDownload);
        System.out.println("[Nova] Fetching " + url);
        ScryfallRateLimiter.acquire(urlToDownload);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
        int code = conn.getResponseCode();
        if (code != 200) {
            ScryfallRateLimiter.noteIfRateLimited(code, urlToDownload, conn.getHeaderField("Retry-After"));
            conn.disconnect();
            return false;
        }
        byte[] bytes;
        try (InputStream is = conn.getInputStream()) {
            bytes = is.readAllBytes();
        }
        String type = conn.getContentType();
        File dest = new File(newDestPath);
        File tmp = new File(newDestPath + ".tmp");
        tmp.getParentFile().mkdirs();
        if (type != null && type.startsWith("image/jpeg")) {
            Files.write(tmp.toPath(), bytes); // already JPEG: store losslessly
        } else {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null || !ImageIO.write(img, "jpg", tmp)) {
                return false;
            }
        }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
