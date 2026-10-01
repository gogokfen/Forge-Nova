package forge.nova.moxfield;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Moxfield's web API (the one moxfield.com itself calls). There is no login, so it sees public and
 * unlisted decks; a private deck answers "not found".
 */
final class MoxfieldClient {
    private static final String API = "https://api2.moxfield.com";
    /** the largest page Moxfield's search hands out */
    private static final int PAGE = 100;
    /** a sanity limit: 2,000 decks */
    private static final int MAX_PAGES = 20;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** A deck in a user's list; {@code updated} changes whenever the deck is edited. */
    record Listed(String id, String name, String format, String updated, String visibility) {
    }

    /** Moxfield answered with an error status. */
    static final class StatusException extends IOException {
        final int status;

        StatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        try {
            return e == null || e.isJsonNull() ? fallback : e.getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Accept-Encoding", "gzip")
                .header("Referer", "https://www.moxfield.com/")
                .header("Origin", "https://www.moxfield.com")
                .GET()
                .build();
    }

    private static InputStream body(HttpResponse<InputStream> res) throws IOException {
        String enc = res.headers().firstValue("Content-Encoding").orElse("");
        return "gzip".equalsIgnoreCase(enc.trim()) ? new GZIPInputStream(res.body()) : res.body();
    }

    /** How long a 429 answer asks us to wait (capped), else {@code fallback} ms. */
    private static long retryAfter(HttpResponse<?> res, long fallback) {
        try {
            long s = Long.parseLong(res.headers().firstValue("Retry-After").orElse("").trim());
            return Math.min(20_000, Math.max(500, s * 1000));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * GET returning JSON. Rate limits (429) and server hiccups are retried with a back-off.
     *
     * @throws StatusException for other error answers (404: a missing or private deck)
     */
    JsonElement get(String path) throws IOException, InterruptedException {
        IOException failure = null;
        long wait = 0;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (wait > 0) {
                Thread.sleep(wait);
            }
            HttpResponse<InputStream> res;
            try {
                res = http.send(request(path), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                failure = new IOException("Can't reach Moxfield. Check your internet connection.", e);
                wait = 1000L << attempt;
                continue;
            }
            final int code = res.statusCode();
            try (InputStream in = body(res)) {
                if (code == 200) {
                    try {
                        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    } catch (RuntimeException e) {
                        throw new IOException("Moxfield sent something that isn't a deck list.", e);
                    }
                }
                in.readAllBytes();
            }
            if (code == 429 || code >= 500) {
                failure = new StatusException(code, code == 429
                        ? "Moxfield is getting too many requests from this computer. Try again in a minute."
                        : "Moxfield is having trouble right now (HTTP " + code + "). Try again later.");
                wait = code == 429 ? retryAfter(res, 2000L << attempt) : 1000L << attempt;
                continue;
            }
            if (code == 403 && res.headers().firstValue("cf-mitigated").isPresent()) {
                throw new StatusException(code, "Moxfield's firewall is blocking requests from this computer right now. Try again later.");
            }
            throw new StatusException(code, "Moxfield answered HTTP " + code + ".");
        }
        throw failure;
    }

    /**
     * Every deck the user lists on Moxfield. {@code showIllegal} matters: without it the search leaves out the
     * decks Moxfield marks as not legal (a Commander deck that isn't exactly 100 cards, an off-color card...),
     * which is why only some decks used to come through. Moxfield's own profile page asks for them too.
     */
    List<Listed> listDecks(String user) throws IOException, InterruptedException {
        Map<String, Listed> decks = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonElement el = get("/v2/decks/search?pageNumber=" + page + "&pageSize=" + PAGE
                    + "&sortType=created&sortDirection=ascending&showIllegal=true&authorUserNames=" + enc(user));
            JsonObject o = el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
            JsonArray data = o.has("data") && o.get("data").isJsonArray() ? o.getAsJsonArray("data") : new JsonArray();
            boolean foreign = false;
            for (JsonElement e : data) {
                if (!e.isJsonObject()) continue;
                JsonObject d = e.getAsJsonObject();
                if (!authoredBy(d, user)) {
                    foreign = true;
                    continue;
                }
                String id = str(d, "publicId");
                if (id != null) {
                    decks.putIfAbsent(id, new Listed(id, str(d, "name"), str(d, "format"), str(d, "lastUpdatedAtUtc"), str(d, "visibility")));
                }
            }
            // For a name that isn't a Moxfield user, the search ignores the author and lists everyone's decks:
            // stop right there. Otherwise page by the totals Moxfield reports, not by the page size.
            int pages = intOr(o, "totalPages", -1);
            int total = intOr(o, "totalResults", -1);
            if (foreign || data.isEmpty() || (pages > 0 && page >= pages) || (pages <= 0 && total >= 0 && decks.size() >= total)) {
                break;
            }
        }
        return new ArrayList<>(decks.values());
    }

    /** Whether a listed deck belongs to the user (its creator or one of its authors). */
    private static boolean authoredBy(JsonObject deck, String user) {
        JsonElement by = deck.get("createdByUser");
        if (by != null && by.isJsonObject() && user.equalsIgnoreCase(str(by.getAsJsonObject(), "userName"))) return true;
        JsonElement authors = deck.get("authors");
        if (authors != null && authors.isJsonArray()) {
            for (JsonElement a : authors.getAsJsonArray()) {
                if (a.isJsonObject() && user.equalsIgnoreCase(str(a.getAsJsonObject(), "userName"))) return true;
            }
        }
        return false;
    }

    /** Whether a Moxfield user of that name exists. */
    boolean userExists(String user) throws IOException, InterruptedException {
        try {
            get("/v1/users/" + enc(user));
            return true;
        } catch (StatusException e) {
            if (e.status == 404) return false;
            throw e;
        }
    }

    /** A deck with all its boards, or null when it doesn't exist or is private. */
    JsonObject deck(String publicId) throws IOException, InterruptedException {
        try {
            JsonElement el = get("/v3/decks/all/" + enc(publicId));
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (StatusException e) {
            if (e.status == 404 || e.status == 401 || (e.status == 403 && !e.getMessage().contains("firewall"))) {
                return null;
            }
            throw e;
        }
    }
}
