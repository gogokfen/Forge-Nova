package forge.nova.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.card.IHasCardView;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.item.PaperCard;
import forge.nova.net.ClientLink;
import forge.nova.sync.CardJson;
import forge.nova.util.JsonOut;
import forge.util.ITranslatable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Translates Forge's blocking GUI questions into client-side modal dialogs.
 *
 * Every method blocks the calling thread (usually the game thread) until the browser
 * answers, mirroring the modal Swing dialogs of the desktop client.
 */
public final class Dialogs {
    private final ClientLink link;
    /** decides whether a card's face may be shown (hidden information) */
    private volatile Predicate<CardView> mayView = c -> true;
    private volatile Iterable<PlayerView> viewers = null;
    /** hook to flush board state before a dialog appears */
    private volatile Runnable beforeShow = () -> { };
    /** the match screen that bound the settings above */
    private volatile Object owner;

    public Dialogs(ClientLink link) {
        this.link = link;
    }

    public void bindGame(Object owner, Predicate<CardView> mayView, Iterable<PlayerView> viewers, Runnable beforeShow) {
        this.owner = owner;
        this.mayView = mayView == null ? c -> true : mayView;
        this.viewers = viewers;
        this.beforeShow = beforeShow == null ? () -> { } : beforeShow;
    }

    /** Resets the bindings, unless a newer match has bound its own meanwhile. */
    public void unbindGame(Object owner) {
        if (this.owner == owner) {
            bindGame(null, null, null, null);
        }
    }

    // ------------------------------------------------------------------ item rendering

    static String label(Object item, Function<Object, String> display) {
        if (item == null) {
            return "";
        }
        if (display != null) {
            try {
                String s = display.apply(item);
                if (s != null) {
                    return s;
                }
            } catch (Exception ignored) {
                // fall back to toString
            }
        }
        if (item instanceof ITranslatable t) {
            try {
                String s = t.getTranslatedName();
                if (s != null && !s.isEmpty()) {
                    return s;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return item.toString();
    }

    private void writeItem(JsonOut o, Object item, Function<Object, String> display) {
        o.beginObj();
        CardView cv = null;
        if (item instanceof CardView c) {
            cv = c;
        } else if (item instanceof CardView.CardStateView st) {
            cv = st.getCard();
        } else if (item instanceof IHasCardView h && !(item instanceof SpellAbilityView)) {
            cv = h.getCardView();
        } else if (item instanceof SpellAbilityView sav) {
            CardView host = sav.getHostCard();
            if (host != null) {
                o.put("host", host.getId());
            }
        }
        if (cv != null) {
            boolean canView = cv.getId() < 0 || mayView.test(cv);
            // a card's label is its name (CardView.toString): not for a card this player may not see
            o.put("label", canView ? label(item, display) : hiddenLabel(cv));
            CardJson.writeDialogCard(o, cv, viewers, canView);
        } else if (item instanceof PlayerView pv) {
            o.put("label", label(item, display));
            o.put("player", pv.getId());
        } else {
            o.put("label", label(item, display));
            if (item instanceof PaperCard pc) {
                CardJson.writePaperCard(o, pc);
            }
        }
        o.endObj();
    }

    private static String hiddenLabel(CardView cv) {
        return cv.isFaceDown() ? "Face-down card" : "Hidden card";
    }

    @SuppressWarnings("unchecked")
    private <T> void writeItems(JsonOut o, String key, Collection<T> items, Function<T, String> display) {
        o.beginArr(key);
        if (items != null) {
            for (T t : items) {
                writeItem(o, t, (Function<Object, String>) (Function<?, String>) display);
            }
        }
        o.endArr();
    }

    private static List<Integer> indices(JsonElement e) {
        List<Integer> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                try {
                    out.add(x.getAsInt());
                } catch (Exception ignored) {
                    // skip malformed entries
                }
            }
        }
        return out;
    }

    private JsonElement ask(String kind, java.util.function.Consumer<JsonOut> fields) {
        try {
            beforeShow.run();
        } catch (Exception ignored) {
            // best effort
        }
        return link.request(kind, fields);
    }

    // ------------------------------------------------------------------ dialogs

    /**
     * Pick between min and max items (min == -1: just display the items, "reveal").
     * Returns the chosen items in list order.
     */
    public <T> List<T> choose(String title, int min, int max, List<T> choices, List<T> selected,
                              Function<T, String> display, CardView refCard) {
        if (choices == null || choices.isEmpty()) {
            return new ArrayList<>();
        }
        final List<T> items = new ArrayList<>(choices);
        JsonElement reply = ask("choose", o -> {
            o.put("title", title == null ? "" : title);
            o.put("min", min).put("max", max);
            writeItems(o, "items", items, display);
            if (selected != null && !selected.isEmpty()) {
                o.beginArr("selected");
                for (T s : selected) {
                    int i = items.indexOf(s);
                    if (i >= 0) o.val(i);
                }
                o.endArr();
            }
            if (refCard != null) o.put("ref", refCard.getId());
        });
        List<T> result = new ArrayList<>();
        if (min < 0) {
            return result; // reveal only
        }
        for (int i : indices(reply)) {
            if (i >= 0 && i < items.size()) {
                result.add(items.get(i));
            }
        }
        // Keep the engine consistent even if the dialog was cancelled externally.
        if (result.size() < min) {
            for (T t : items) {
                if (result.size() >= min) break;
                if (!result.contains(t)) result.add(t);
            }
        }
        if (max > 0 && result.size() > max) {
            result = new ArrayList<>(result.subList(0, max));
        }
        return result;
    }

    /** Result of the two-list ordering dialog. */
    public record Ordered<T>(List<T> items, boolean remember) {
    }

    /**
     * Forge's DualListBox: move items from the source list into an ordered destination list.
     * Between remMin and remMax items must stay in the source list (remMax < 0: any amount).
     */
    public <T> Ordered<T> order(String title, String top, int remMin, int remMax, List<T> source,
                                List<T> dest, CardView refCard, boolean sideboarding, boolean showRemember) {
        final List<T> src = source == null ? new ArrayList<>() : new ArrayList<>(source);
        final List<T> dst = dest == null ? new ArrayList<>() : new ArrayList<>(dest);
        final List<T> all = new ArrayList<>(src);
        all.addAll(dst);
        JsonElement reply = ask("order", o -> {
            o.put("title", title == null ? "" : title);
            o.put("top", top == null ? "" : top);
            o.put("remMin", remMin).put("remMax", remMax);
            o.put("srcCount", src.size());
            writeItems(o, "items", all, null);
            o.flag("sideboard", sideboarding);
            o.flag("remember", showRemember);
            if (refCard != null) o.put("ref", refCard.getId());
        });
        List<T> ordered = new ArrayList<>();
        boolean remember = false;
        if (reply != null && reply.isJsonObject()) {
            JsonObject ro = reply.getAsJsonObject();
            for (int i : indices(ro.get("order"))) {
                if (i >= 0 && i < all.size() && !ordered.contains(all.get(i))) {
                    ordered.add(all.get(i));
                }
            }
            remember = ro.has("remember") && ro.get("remember").getAsBoolean();
        } else {
            // cancelled: keep the engine happy with a valid default
            ordered.addAll(dst);
            int mustMove = remMax < 0 ? 0 : Math.max(0, src.size() - remMax);
            for (int i = 0; i < mustMove && i < src.size(); i++) {
                ordered.add(src.get(i));
            }
        }
        return new Ordered<>(ordered, remember);
    }

    public int option(String message, String title, List<String> options, int defaultOption, CardView card) {
        final List<String> opts = options == null ? Collections.emptyList() : options;
        JsonElement reply = ask("option", o -> {
            o.put("title", title == null ? "" : title);
            o.put("message", message == null ? "" : message);
            o.beginArr("options");
            for (String s : opts) o.val(s);
            o.endArr();
            o.put("def", defaultOption);
            if (card != null) {
                CardJson.writeDialogCard(o, card, viewers, mayView.test(card));
            }
        });
        if (reply == null || reply.isJsonNull()) {
            return defaultOption >= 0 ? defaultOption : -1;
        }
        try {
            return reply.getAsInt();
        } catch (Exception e) {
            return defaultOption;
        }
    }

    public String input(String message, String title, String initial, List<String> options, boolean numeric) {
        JsonElement reply = ask("input", o -> {
            o.put("title", title == null ? "" : title);
            o.put("message", message == null ? "" : message);
            o.putOpt("initial", initial);
            if (options != null && !options.isEmpty()) {
                o.beginArr("options");
                for (String s : options) o.val(s);
                o.endArr();
            }
            o.flag("numeric", numeric);
        });
        if (reply == null || reply.isJsonNull()) {
            return null;
        }
        return reply.getAsString();
    }

    /**
     * A number from min to max (max Integer.MAX_VALUE: no limit), e.g. X of a spell: − / + buttons and a Max button.
     * {@code afford} >= 0: the most the player's mana can pay; {@code manaCost}: the cost X is part of ("{X}{R}").
     * Returns null when cancelled.
     */
    public Integer number(String message, int min, int max, int afford, String manaCost) {
        JsonElement reply = ask("number", o -> {
            o.put("title", message == null ? "" : message);
            o.put("min", min);
            if (max != Integer.MAX_VALUE) o.put("max", max);
            if (afford >= 0) o.put("afford", afford);
            o.putOpt("cost", manaCost);
        });
        if (reply == null || reply.isJsonNull()) {
            return null;
        }
        try {
            return Math.max(min, Math.min(max, reply.getAsInt()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A notice that needs no answer (an earned achievement, an error report): shown as a toast, so the
     * UI thread that raised it never waits for a click.
     */
    public void notify(String title, String message, boolean error) {
        String text = (title == null || title.isBlank() ? "" : title + ": ") + (message == null ? "" : message);
        if (text.length() > 400) {
            text = text.substring(0, 400) + "…";
        }
        link.send(new JsonOut(128 + text.length()).beginObj().put("t", "toast").put("msg", text)
                .putOpt("level", error ? "error" : null).endObj().toString());
    }

    public void message(String message, String title, boolean error) {
        message(message, title, error, null);
    }

    /** A message; {@code dice}: it reports a die roll, which the client shows rolling (unless that's switched off). */
    void message(String message, String title, boolean error, DiceRolls dice) {
        ask("message", o -> {
            o.put("title", title == null ? "" : title);
            o.put("message", message == null ? "" : message);
            o.flag("error", error);
            if (dice != null) {
                dice.write(o, "dice");
            }
        });
    }

    /** The scry / surveil window's answer: the cards for the top of the library and for the other place, each in order. */
    public record Arranged(List<CardView> top, List<CardView> other) {
    }

    /**
     * Scry or surveil: only the cards looked at. Each goes on top of the library or to {@code other} ("bottom": the
     * bottom of the library, "graveyard"); the top ones in the chosen order (first = top card), the bottom ones too
     * (first = the highest of them). Returns null when the window was closed without an answer.
     */
    public Arranged scry(String title, String other, List<CardView> cards) {
        JsonElement reply = ask("scry", o -> {
            o.put("title", title == null ? "" : title);
            o.put("other", other);
            o.beginArr("items");
            for (CardView c : cards) {
                boolean canView = mayView.test(c);
                o.beginObj();
                o.put("label", canView ? c.getName() : hiddenLabel(c));
                CardJson.writeDialogCard(o, c, viewers, canView);
                o.endObj();
            }
            o.endArr();
        });
        if (reply == null || !reply.isJsonObject()) {
            return null;
        }
        JsonObject ro = reply.getAsJsonObject();
        List<CardView> top = new ArrayList<>();
        List<CardView> rest = new ArrayList<>();
        for (int i : indices(ro.get("top"))) {
            if (i >= 0 && i < cards.size() && !top.contains(cards.get(i))) top.add(cards.get(i));
        }
        for (int i : indices(ro.get("other"))) {
            if (i >= 0 && i < cards.size() && !top.contains(cards.get(i)) && !rest.contains(cards.get(i))) rest.add(cards.get(i));
        }
        for (CardView c : cards) {
            if (!top.contains(c) && !rest.contains(c)) top.add(c); // a card the answer left out stays on top
        }
        return new Arranged(top, rest);
    }

    /** Combat damage assignment; returns damage per blocker (key null => defender for trample). */
    public java.util.Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage,
                                                               GameEntityView defender, boolean overrideOrder,
                                                               boolean maySkip, int[] lethal, boolean deathtouch) {
        JsonElement reply = ask("assignDamage", o -> {
            CardJson.writeDialogCard(o, attacker, viewers, mayView.test(attacker));
            o.put("damage", damage);
            o.flag("override", overrideOrder);
            o.flag("maySkip", maySkip);
            o.flag("deathtouch", deathtouch);
            o.beginArr("blockers");
            for (int i = 0; i < blockers.size(); i++) {
                CardView b = blockers.get(i);
                o.beginObj();
                o.put("label", b.toString());
                o.put("lethal", lethal[i]);
                CardJson.writeDialogCard(o, b, viewers, mayView.test(b));
                o.endObj();
            }
            o.endArr();
            if (defender != null) {
                o.beginObj("defender");
                o.put("label", defender.toString());
                if (defender instanceof PlayerView pv) {
                    o.put("player", pv.getId());
                } else if (defender instanceof CardView dc) {
                    CardJson.writeDialogCard(o, dc, viewers, mayView.test(dc));
                }
                o.endObj();
            }
        });
        java.util.Map<CardView, Integer> result = new java.util.LinkedHashMap<>();
        if (reply != null && reply.isJsonObject()) {
            JsonObject ro = reply.getAsJsonObject();
            if (ro.has("skip") && ro.get("skip").getAsBoolean()) {
                return null;
            }
            JsonArray arr = ro.has("blockers") ? ro.getAsJsonArray("blockers") : new JsonArray();
            for (int i = 0; i < blockers.size() && i < arr.size(); i++) {
                int v = arr.get(i).getAsInt();
                if (v > 0) result.put(blockers.get(i), v);
            }
            int toDef = ro.has("defender") ? ro.get("defender").getAsInt() : 0;
            if (toDef > 0) result.put(null, toDef);
            return result;
        }
        // cancelled: lethal damage in order, rest to the last blocker
        int left = damage;
        for (int i = 0; i < blockers.size() && left > 0; i++) {
            int v = i == blockers.size() - 1 ? left : Math.min(left, Math.max(1, lethal[i]));
            result.put(blockers.get(i), v);
            left -= v;
        }
        return result;
    }

    /** Distribute {@code amount} among the targets (e.g. "divide 5 damage"). */
    public java.util.Map<Object, Integer> assignAmount(CardView source, java.util.Map<Object, Integer> targets,
                                                       int amount, boolean atLeastOne, String amountLabel) {
        final List<Object> keys = new ArrayList<>(targets.keySet());
        JsonElement reply = ask("assignAmount", o -> {
            if (source != null) CardJson.writeDialogCard(o, source, viewers, mayView.test(source));
            o.put("amount", amount);
            o.flag("atLeastOne", atLeastOne);
            o.putOpt("unit", amountLabel);
            o.beginArr("targets");
            for (Object k : keys) {
                o.beginObj();
                o.put("label", label(k, null));
                Integer max = targets.get(k);
                o.put("max", max == null ? amount : max);
                if (k instanceof CardView cv) {
                    CardJson.writeDialogCard(o, cv, viewers, mayView.test(cv));
                } else if (k instanceof PlayerView pv) {
                    o.put("player", pv.getId());
                }
                o.endObj();
            }
            o.endArr();
        });
        java.util.Map<Object, Integer> result = new java.util.LinkedHashMap<>();
        if (reply != null && reply.isJsonArray()) {
            JsonArray arr = reply.getAsJsonArray();
            for (int i = 0; i < keys.size() && i < arr.size(); i++) {
                int v = arr.get(i).getAsInt();
                if (v > 0) result.put(keys.get(i), v);
            }
            return result;
        }
        // cancelled: spread evenly
        int left = amount;
        for (int i = 0; i < keys.size() && left > 0; i++) {
            int share = i == keys.size() - 1 ? left : Math.max(atLeastOne ? 1 : 0, amount / keys.size());
            share = Math.min(share, left);
            if (share > 0) result.put(keys.get(i), share);
            left -= share;
        }
        return result;
    }

    /** Scry/surveil-style arrangement: reorder cards; returns the new order (top first). */
    public List<CardView> arrange(String title, List<CardView> cards, List<CardView> manipulable,
                                  boolean toTop, boolean toBottom, boolean toAnywhere) {
        JsonElement reply = ask("arrange", o -> {
            o.put("title", title == null ? "" : title);
            o.flag("toTop", toTop).flag("toBottom", toBottom).flag("toAnywhere", toAnywhere);
            o.beginArr("items");
            for (CardView c : cards) {
                boolean canView = mayView.test(c);
                o.beginObj();
                o.put("label", canView ? c.toString() : hiddenLabel(c));
                o.flag("movable", manipulable.contains(c));
                CardJson.writeDialogCard(o, c, viewers, canView);
                o.endObj();
            }
            o.endArr();
        });
        List<CardView> out = new ArrayList<>();
        for (int i : indices(reply)) {
            if (i >= 0 && i < cards.size() && !out.contains(cards.get(i))) out.add(cards.get(i));
        }
        if (out.size() != cards.size()) {
            return new ArrayList<>(cards);
        }
        return out;
    }

    /** Between-games sideboarding: returns the new main deck, or null to keep it unchanged. */
    public List<PaperCard> sideboard(List<PaperCard> sideboard, List<PaperCard> main, String message) {
        final List<PaperCard> all = new ArrayList<>(sideboard);
        all.addAll(main);
        JsonElement reply = ask("sideboard", o -> {
            o.putOpt("message", message);
            o.put("sbCount", sideboard.size());
            o.beginArr("items");
            for (PaperCard pc : all) {
                o.beginObj();
                o.put("label", pc.getName());
                CardJson.writePaperCard(o, pc);
                o.endObj();
            }
            o.endArr();
        });
        if (reply == null || !reply.isJsonArray()) {
            return null;
        }
        List<PaperCard> newMain = new ArrayList<>();
        for (int i : indices(reply)) {
            if (i >= 0 && i < all.size()) newMain.add(all.get(i));
        }
        return newMain;
    }
}
