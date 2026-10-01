package forge.nova.gui;

import forge.card.CardRules;
import forge.card.CardType;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.nova.sync.CardJson;
import forge.nova.util.JsonOut;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The library window's "Cards left": the player's own cards minus every one they can see somewhere else (hand,
 * battlefield, graveyard, exile, command zone, stack). What remains is their library, plus any of their cards hidden
 * elsewhere (face down in exile, in an opponent's hand), which they can't tell apart from the library either. Built
 * only from what the player may see, so it gives nothing away; their library's order isn't part of it.
 */
final class LibraryLeft {
    private LibraryLeft() {
    }

    private static final class Entry {
        final PaperCard card;
        int count;

        Entry(PaperCard card) {
            this.card = card;
        }
    }

    /** {t:'libraryLeft', pid, library, elsewhere, cards:[{k, cmc, tf, card:{...}}]}; null if not a local player. */
    static String json(Game game, PlayerView pv, Collection<PlayerView> locals, Predicate<CardView> mayView) {
        if (game == null || pv == null || !locals.contains(pv)) {
            return null;
        }
        Player p = game.getPlayer(pv);
        if (p == null) {
            return null;
        }
        Map<String, Entry> left = new LinkedHashMap<>();
        int library = 0, elsewhere = 0;
        for (Card c : game.getCardsInGame()) {
            if (c.getOwner() != p || c.isToken()) {
                continue;
            }
            Zone z = c.getZone();
            boolean inLibrary = z != null && z.is(ZoneType.Library);
            if (z != null && z.is(ZoneType.Sideboard)) {
                continue; // outside the game
            }
            if (!inLibrary && mayView.test(c.getView())) {
                continue; // the player can see it somewhere else
            }
            IPaperCard ipc = c.getPaperCard();
            if (!(ipc instanceof PaperCard pc)) {
                continue;
            }
            if (inLibrary) library++;
            else elsewhere++;
            left.computeIfAbsent(pc.getName(), k -> new Entry(pc)).count++;
        }
        JsonOut o = new JsonOut(8192).beginObj().put("t", "libraryLeft").put("pid", pv.getId());
        o.put("library", library).put("elsewhere", elsewhere);
        o.beginArr("cards");
        for (Entry e : left.values()) {
            o.beginObj().put("k", e.count);
            try {
                CardRules rules = e.card.getRules();
                CardType t = rules.getType();
                o.put("cmc", rules.getManaCost() == null ? 0 : rules.getManaCost().getCMC());
                int tf = 0;
                if (t.isCreature()) tf |= 1;
                if (t.isLand()) tf |= 2;
                if (t.isPlaneswalker()) tf |= 4;
                if (t.isArtifact()) tf |= 8;
                if (t.isEnchantment()) tf |= 16;
                if (t.isInstant()) tf |= 32;
                if (t.isSorcery()) tf |= 64;
                if (t.isBattle()) tf |= 128;
                if (t.isBasicLand()) tf |= 256;
                o.put("tf", tf);
            } catch (RuntimeException ignored) {
                // rules are best-effort
            }
            CardJson.writePaperCard(o, e.card);
            o.endObj();
        }
        o.endArr().endObj();
        return o.toString();
    }
}
