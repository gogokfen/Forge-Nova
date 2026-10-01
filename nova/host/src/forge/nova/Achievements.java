package forge.nova;

import forge.game.GameType;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.localinstance.achievements.Achievement;
import forge.localinstance.achievements.AchievementCollection;
import forge.localinstance.achievements.AltWinAchievements;
import forge.localinstance.achievements.CardActivationAchievements;
import forge.localinstance.achievements.ChallengeAchievements;
import forge.localinstance.achievements.PlaneswalkerAchievements;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.nova.util.JsonOut;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classic Forge's Achievements screen for the browser: every collection Forge keeps (Constructed, Draft, Sealed,
 * Quest, Planar Conquest, Puzzle, Adventure, alternate win conditions, planeswalker ultimates, card activations,
 * challenges), each achievement with its trophy (common / uncommon / rare / mythic / special), what it takes and the
 * best result so far. Trophy pictures are the ones Forge downloads into its cache (pics/achievements); a missing one
 * is fetched from Forge's download server on first view, like classic Forge's "download achievement images".
 */
final class Achievements {
    private final Map<String, Boolean> failed = new ConcurrentHashMap<>();
    /** Forge's download server didn't answer: no downloads until then */
    private volatile long serverDownUntil;
    private volatile Map<String, String> imageUrls;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private static List<AchievementCollection> collections() {
        List<AchievementCollection> out = new ArrayList<>();
        for (GameType t : new GameType[]{GameType.Constructed, GameType.Draft, GameType.Sealed, GameType.Quest,
                GameType.PlanarConquest, GameType.Puzzle, GameType.Adventure}) {
            try {
                AchievementCollection c = FModel.getAchievements(t);
                if (c != null && !out.contains(c)) out.add(c);
            } catch (RuntimeException ignored) {
                // a collection this Forge doesn't have
            }
        }
        out.add(AltWinAchievements.instance);
        out.add(PlaneswalkerAchievements.instance);
        out.add(CardActivationAchievements.instance);
        out.add(ChallengeAchievements.instance);
        return out;
    }

    /** {sets:[{name, items:[{key, name, sub, shared, special, tier, tiers:[{t, d, on}], card}]}]} */
    String json() {
        JsonOut o = new JsonOut(64 * 1024).beginObj().beginArr("sets");
        for (AchievementCollection c : collections()) {
            o.beginObj().put("name", c.toString());
            o.beginArr("items");
            for (Achievement a : c) {
                o.beginObj().put("key", a.getKey()).put("name", a.getDisplayName());
                try {
                    o.putOpt("sub", a.getSubTitle(true));
                } catch (RuntimeException ignored) {
                    // no subtitle
                }
                o.putOpt("shared", a.getSharedDesc());
                boolean special = a.isSpecial();
                o.flag("special", special);
                o.putOpt("tier", special ? (a.isActive() ? "special" : null)
                        : a.earnedMythic() ? "mythic" : a.earnedRare() ? "rare" : a.earnedUncommon() ? "uncommon" : a.earnedCommon() ? "common" : null);
                o.beginArr("tiers");
                if (special) {
                    tier(o, "special", a.getMythicDesc(), a.isActive());
                } else {
                    tier(o, "mythic", a.getMythicDesc(), a.earnedMythic());
                    tier(o, "rare", a.getRareDesc(), a.earnedRare());
                    tier(o, "uncommon", a.getUncommonDesc(), a.earnedUncommon());
                    tier(o, "common", a.getCommonDesc(), a.earnedCommon());
                }
                o.endArr();
                try {
                    IPaperCard ipc = a.getPaperCard();
                    if (ipc instanceof PaperCard pc) {
                        o.putOpt("card", pc.getImageKey(false));
                    }
                } catch (RuntimeException ignored) {
                    // no card art
                }
                o.endObj();
            }
            o.endArr().endObj();
        }
        o.endArr().endObj();
        return o.toString();
    }

    private static void tier(JsonOut o, String t, String desc, boolean on) {
        if (desc == null || desc.isBlank()) {
            return;
        }
        o.beginObj().put("t", t).put("d", desc).put("on", on).endObj();
    }

    /**
     * The trophy picture of an achievement (Forge's cache, downloaded on first use); null when there is none. Only
     * names on Forge's own list of achievement pictures are ever downloaded.
     */
    File image(String key) {
        if (key == null || key.isBlank() || key.length() > 120 || key.contains("..") || key.contains("/") || key.contains("\\")) {
            return null;
        }
        File dir = new File(ForgeConstants.CACHE_ACHIEVEMENTS_DIR);
        File f = new File(dir, key + ".png");
        if (f.isFile()) {
            return f;
        }
        String url = urls().get(key);
        if (url == null || failed.containsKey(key) || System.currentTimeMillis() < serverDownUntil) {
            return null;
        }
        try {
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Forge Nova").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                res.body().close();
                failed.put(key, true);
                return null;
            }
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            File tmp = new File(dir, key + ".png.part");
            try (InputStream in = res.body()) {
                Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return f;
        } catch (IOException e) {
            // the server can't be reached (offline, or it is down): don't try again for every trophy
            serverDownUntil = System.currentTimeMillis() + 10 * 60_000L;
            return null;
        } catch (RuntimeException e) {
            failed.put(key, true);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Forge's list of achievement pictures (res/lists/achievement-images.txt): file name without ".png" -> URL. */
    private Map<String, String> urls() {
        Map<String, String> m = imageUrls;
        if (m != null) {
            return m;
        }
        m = new HashMap<>();
        try {
            for (String line : Files.readAllLines(new File(ForgeConstants.IMAGE_LIST_ACHIEVEMENTS_FILE).toPath(), StandardCharsets.UTF_8)) {
                String url = line.trim();
                if (!url.startsWith("https://") || !url.endsWith(".png")) {
                    continue;
                }
                String file = url.substring(url.lastIndexOf('/') + 1, url.length() - 4);
                m.put(URLDecoder.decode(file, StandardCharsets.UTF_8), url);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[Nova] achievement picture list unavailable: " + e);
        }
        imageUrls = m;
        return m;
    }
}
