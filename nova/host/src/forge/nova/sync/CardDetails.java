package forge.nova.sync;

import com.google.common.collect.Table;
import forge.game.Game;
import forge.game.GameView;
import forge.game.StaticEffect;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.keyword.KeywordInterface;
import forge.game.keyword.KeywordsChange;
import forge.gui.card.CardDetailUtil;
import forge.nova.util.JsonOut;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the detail panel shows about a hovered card, asked for on demand ({"t":"cardText"}): classic Forge's card
 * detail text (the card's current abilities, including keywords and abilities other cards gave it, plus
 * attachments, counters, choices...) and the effects of other cards on it, each with its source.
 *
 * Read while the game thread may be changing the card: callers retry on a concurrent modification.
 */
public final class CardDetails {
    private CardDetails() {
    }

    /**
     * @param tracked the card's view as the GUI tracks it (used when the game doesn't know the card)
     * @param alt     the card's other face (the detail panel's Flip button)
     * @param mayView whether this client may see a card's face
     * @param mayFlip whether this client may see a card's other face
     */
    public static String json(GameView gv, CardView tracked, int id, boolean alt, Predicate<CardView> mayView, Predicate<CardView> mayFlip) {
        JsonOut o = new JsonOut(1024).beginObj().put("t", "cardText").put("id", id).put("alt", alt);
        Game game = gv == null ? null : gv.getGame();
        // the card itself: a view kept elsewhere can be an older copy (a card that changed zones is a new object)
        Card c = game == null ? null : game.findById(id);
        CardView cv = c != null ? c.getView() : tracked;
        if (gv == null || cv == null || !mayView.test(cv)) {
            return o.endObj().toString();
        }
        CardView.CardStateView st = alt && mayFlip.test(cv) && cv.getAlternateState() != null ? cv.getAlternateState() : cv.getCurrentState();
        if (st != null) {
            o.putOpt("text", CardDetailUtil.composeCardText(st, gv, true));
        }
        if (c != null && !alt) {
            writeEffects(o, game, c, mayView);
        }
        return o.endObj().toString();
    }

    /** Continuous effects of other cards on this one, then changes without a lasting source (pump spells...). */
    private static void writeEffects(JsonOut o, Game game, Card c, Predicate<CardView> mayView) {
        boolean opened = false;
        for (StaticEffect se : toList(game.getStaticEffects().getEffects())) {
            Card src = se.getSource();
            CardCollectionView affected = se.getAffectedCards();
            if (src == null || src.getId() == c.getId() || affected == null || !affected.contains(c)) {
                continue;
            }
            boolean visible = mayView.test(src.getView());
            String name = visible ? src.getName() : "A hidden card";
            String text = visible ? describe(se.getParams(), name) : "";
            if (!opened) {
                o.beginArr("fx");
                opened = true;
            }
            o.beginObj().put("n", name).put("src", visible ? src.getId() : -1).putOpt("d", text).endObj();
        }
        if (opened) {
            o.endArr();
        }

        // one-shot changes are stored without their source (the static id column is 0)
        int dp = 0, dt = 0;
        for (Table.Cell<Long, Long, Pair<Integer, Integer>> cell : new ArrayList<>(c.getPTBoostTable().cellSet())) {
            Pair<Integer, Integer> v = cell.getValue();
            if (v != null && cell.getColumnKey() != null && cell.getColumnKey() == 0L) {
                dp += v.getLeft() == null ? 0 : v.getLeft();
                dt += v.getRight() == null ? 0 : v.getRight();
            }
        }
        Set<String> kws = new LinkedHashSet<>();
        for (Table.Cell<Long, Long, KeywordsChange> cell : new ArrayList<>(c.getChangedCardKeywords().cellSet())) {
            KeywordsChange kc = cell.getValue();
            if (kc == null || cell.getColumnKey() == null || cell.getColumnKey() != 0L) {
                continue;
            }
            for (KeywordInterface k : kc.getKeywords()) {
                String t = k.getTitle();
                if (t == null || t.isBlank()) t = k.getOriginal();
                if (t != null && !t.isBlank() && !t.startsWith("HIDDEN")) kws.add(t);
            }
        }
        if (dp != 0 || dt != 0) {
            o.put("pump", signed(dp) + "/" + signed(dt));
        }
        if (!kws.isEmpty()) {
            o.put("gained", String.join(", ", kws));
        }
    }

    private static <T> List<T> toList(Iterable<T> it) {
        List<T> out = new ArrayList<>();
        if (it != null) {
            for (T t : it) out.add(t);
        }
        return out;
    }

    /** The static ability's own text, or a summary of what it changes when the script has none. */
    static String describe(Map<String, String> params, String sourceName) {
        if (params == null) {
            return "";
        }
        String d = params.get("Description");
        if (d != null && !d.isBlank()) {
            return d.replace("CARDNAME", sourceName).replace("NICKNAME", sourceName).trim();
        }
        List<String> parts = new ArrayList<>();
        String p = params.get("AddPower"), t = params.get("AddToughness");
        if (isInt(p) || isInt(t)) {
            parts.add(signed(isInt(p) ? Integer.parseInt(p) : 0) + "/" + signed(isInt(t) ? Integer.parseInt(t) : 0));
        }
        if (params.containsKey("SetPower") || params.containsKey("SetToughness")) {
            parts.add("base power and toughness " + params.getOrDefault("SetPower", "?") + "/" + params.getOrDefault("SetToughness", "?"));
        }
        String kw = params.get("AddKeyword");
        if (kw != null && !kw.isBlank()) {
            parts.add("gains " + kw.replace(" & ", ", "));
        }
        String type = params.get("AddType");
        if (type != null && !type.isBlank()) {
            parts.add("is also " + type.replace(" & ", " "));
        }
        if (params.containsKey("RemoveAllAbilities")) {
            parts.add("loses all abilities");
        }
        if (params.containsKey("AddAbility") || params.containsKey("AddTrigger") || params.containsKey("AddStaticAbility")) {
            parts.add("gains an ability");
        }
        return String.join("; ", parts);
    }

    private static boolean isInt(String s) {
        return s != null && s.matches("[+-]?\\d+");
    }

    private static String signed(int v) {
        return v >= 0 ? "+" + v : String.valueOf(v);
    }
}
