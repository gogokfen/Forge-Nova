package forge.ai;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.TraitEpoch;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Forge Nova engine patch: a player's battlefield abilities of one API, for AI checks that used to walk every
 * permanent's full ability list for every attacker/blocker pair (e.g. "could this creature gain first strike?").
 *
 * {@code for (Card c : player.getCardsIn(Battlefield)) for (SpellAbility sa : c.getAllSpellAbilities())} becomes
 * {@code for (i < scan.size()) for (SpellAbility sa : scan.abilities(i))}, which keeps only the abilities whose API
 * is the requested one (an ability's API is fixed when it is created); callers still apply all their other tests.
 *
 * Exactness: the card list and each card's list are what Forge's loop would see while
 * {@link TraitEpoch#global()} is unchanged (it changes with zone contents/order, phasing and any card's abilities
 * or states). A card's abilities are looked up the first time a loop reaches that card, so cards are visited in
 * the same order, and never earlier, than Forge visits them (getAllSpellAbilities can create a card's land/permanent
 * spell ability on first use, and ability ids come from a global counter). If the epoch changes during a loop, the
 * rest of that loop reads the cards live, as Forge does. With -Dnova.verifyCaches=true every cached list is
 * compared with a fresh one.
 */
final class NovaAbilityIndex {
    /** One player's battlefield as of one epoch; per-card lists are filled in as loops reach the cards. */
    static final class Scan {
        final long epoch;
        final ApiType api;
        final Card[] cards;
        final AtomicReferenceArray<List<SpellAbility>> lists;

        Scan(long epoch, ApiType api, Card[] cards) {
            this.epoch = epoch;
            this.api = api;
            this.cards = cards;
            this.lists = new AtomicReferenceArray<>(cards.length);
        }

        int size() {
            return cards.length;
        }

        Card card(int i) {
            return cards[i];
        }

        /** the requested-API abilities of card i, as c.getAllSpellAbilities() would list them right now */
        List<SpellAbility> abilities(int i) {
            if (TraitEpoch.global() != epoch) {
                return filter(cards[i], api); // something changed mid-loop: read it live, don't keep it
            }
            List<SpellAbility> l = lists.get(i);
            if (l == null) {
                l = filter(cards[i], api);
                if (TraitEpoch.global() == epoch) {
                    lists.compareAndSet(i, null, l);
                }
            } else if (TraitEpoch.VERIFY) {
                List<SpellAbility> fresh = filter(cards[i], api);
                if (TraitEpoch.global() == epoch && !TraitEpoch.sameElements(l, fresh)) {
                    TraitEpoch.mismatch("battlefieldPumps", cards[i], l, fresh);
                }
            }
            return l;
        }
    }

    private static final class Holder {
        final Game game;
        final long epoch;
        final ConcurrentHashMap<Player, Scan> pumps = new ConcurrentHashMap<>();

        Holder(Game game, long epoch) {
            this.game = game;
            this.epoch = epoch;
        }
    }

    private static volatile Holder holder;

    private NovaAbilityIndex() {
    }

    /** the Pump abilities of the player's battlefield cards, in Forge's scan order */
    static Scan battlefieldPumps(Player player) {
        Game game = player.getGame();
        long ep = TraitEpoch.global();
        if (TraitEpoch.DISABLED) {
            return new Scan(ep - 1, ApiType.Pump, cardsOf(player)); // never current: every list is read live
        }
        Holder h = holder;
        if (h == null || h.game != game || h.epoch != ep) {
            h = new Holder(game, ep);
            holder = h;
        }
        Scan s = h.pumps.get(player);
        if (s == null) {
            s = new Scan(ep, ApiType.Pump, cardsOf(player));
            if (TraitEpoch.global() == ep) {
                Scan prev = h.pumps.putIfAbsent(player, s);
                if (prev != null) {
                    s = prev;
                }
            }
        } else if (TraitEpoch.VERIFY) {
            Card[] fresh = cardsOf(player);
            if (TraitEpoch.global() == ep && !TraitEpoch.sameElements(java.util.Arrays.asList(s.cards), java.util.Arrays.asList(fresh))) {
                TraitEpoch.mismatch("battlefieldCards", player, java.util.Arrays.asList(s.cards), java.util.Arrays.asList(fresh));
            }
        }
        return s;
    }

    private static Card[] cardsOf(Player p) {
        List<Card> l = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            l.add(c);
        }
        return l.toArray(new Card[0]);
    }

    private static List<SpellAbility> filter(Card c, ApiType api) {
        List<SpellAbility> l = null;
        for (SpellAbility sa : c.getAllSpellAbilities()) {
            if (sa.getApi() == api) {
                if (l == null) {
                    l = new ArrayList<>(2);
                }
                l.add(sa);
            }
        }
        return l == null ? Collections.emptyList() : Collections.unmodifiableList(l);
    }
}
