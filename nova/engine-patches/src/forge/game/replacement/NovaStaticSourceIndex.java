package forge.game.replacement;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.TraitEpoch;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge Nova engine patch: the cards GameEntity.staticDamagePrevention has to look at.
 *
 * The AI predicts combat damage by asking every attacker/blocker pair, for every damage amount, whether damage
 * would be prevented; each question walked {@code game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)} (a
 * freshly built, de-duplicated copy of every battlefield, graveyard, exile, command and stack card) and asked each
 * card for its replacement effects. That was ~60% of a four-player game with many creatures. Visiting a card has an
 * effect only if one of its replacement effects is a DamageDone effect (every other effect fails the mode test,
 * which comes first and is a plain comparison), or if getReplacementEffects() may create effects on first use
 * (shield/stun/finality counters, Adventure/Omen), the same rule as {@link NovaReplacementIndex}.
 * {@link #damageDone} lists exactly those cards, in the order of that collection.
 *
 * Rebuilt when {@link TraitEpoch#global()} changes (zone contents/order, phasing, any zoned card's abilities,
 * states or types), when {@link TraitEpoch#replacementExtras()} changes, when the players change, or for another
 * game.
 */
public final class NovaStaticSourceIndex {
    private static final Card[] NONE = new Card[0];

    private static final class Index {
        final Game game;
        final long epoch;
        final long extras;
        final Player[] players;
        final Card[] damageDone;

        Index(Game game, long epoch, long extras, Player[] players, Card[] damageDone) {
            this.game = game;
            this.epoch = epoch;
            this.extras = extras;
            this.players = players;
            this.damageDone = damageDone;
        }
    }

    private static volatile Index last;

    private NovaStaticSourceIndex() {
    }

    /**
     * The cards of {@code game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)}, in that order, whose
     * getReplacementEffects() may contain a DamageDone effect or may create effects. The array must not be changed.
     */
    public static Card[] damageDone(Game game) {
        long ep = TraitEpoch.global();
        long ex = TraitEpoch.replacementExtras();
        Index ix = last;
        if (ix == null || ix.game != game || ix.epoch != ep || ix.extras != ex || !samePlayers(ix.players, game)) {
            ix = build(game, ep, ex);
            if (TraitEpoch.global() == ep && TraitEpoch.replacementExtras() == ex) {
                last = ix;
            }
        }
        return ix.damageDone;
    }

    private static final class Zones {
        final Game game;
        final long epoch;
        final Player[] players;
        final Card[] battlefield;
        final Card[] command;
        /** per name set (identity): the cards of battlefield with one of those names, in order */
        final java.util.concurrent.ConcurrentHashMap<java.util.Set<String>, Card[]> named = new java.util.concurrent.ConcurrentHashMap<>();
        /** the battles of battlefield, in order (null until computed) */
        volatile Card[] battles;

        Zones(Game game, long epoch, Player[] players, Card[] battlefield, Card[] command) {
            this.game = game;
            this.epoch = epoch;
            this.players = players;
            this.battlefield = battlefield;
            this.command = command;
        }
    }

    private static volatile Zones lastZones;

    /**
     * The cards of {@code game.getCardsIn(zone)} (Battlefield or Command), in that order: the same de-duplicated
     * list, built once per {@link TraitEpoch#global()} (zone contents/order and phasing bump it). Must not be changed.
     */
    /**
     * The cards of {@code cardsIn(game, zone)} whose getName() is in {@code names} (a constant set), in that order.
     * Names change only with a trait epoch bump (state changes, CardState.setName, changed names), which bumps the
     * global epoch for zoned cards.
     */
    public static Card[] namedCards(Game game, ZoneType zone, java.util.Set<String> names) {
        if (zone != ZoneType.Battlefield) {
            throw new IllegalArgumentException(String.valueOf(zone));
        }
        long ep = TraitEpoch.global();
        Card[] all = cardsIn(game, zone);
        Zones z = lastZones;
        if (z != null && z.battlefield == all) {
            Card[] r = z.named.get(names);
            if (r != null) {
                if (TraitEpoch.VERIFY) {
                    List<Card> fresh = new ArrayList<>();
                    for (Card c : game.getCardsIn(zone)) {
                        if (names.contains(c.getName())) {
                            fresh.add(c);
                        }
                    }
                    if (!TraitEpoch.sameElements(java.util.Arrays.asList(r), fresh) && TraitEpoch.global() == ep) {
                        TraitEpoch.mismatch("namedCards", game, java.util.Arrays.asList(r), fresh);
                    }
                }
                return r;
            }
        }
        List<Card> l = new ArrayList<>();
        for (Card c : all) {
            if (names.contains(c.getName())) {
                l.add(c);
            }
        }
        Card[] r = l.isEmpty() ? NONE : l.toArray(NONE);
        if (z != null && z.battlefield == all && TraitEpoch.global() == ep && z.epoch == ep) {
            z.named.put(names, r);
        }
        return r;
    }

    /**
     * The battles of {@code cardsIn(game, Battlefield)} ({@code c.isBattle()}), in that order: the cards of
     * {@code CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.BATTLES)}. Types change only
     * with a trait epoch bump.
     */
    public static Card[] battles(Game game) {
        long ep = TraitEpoch.global();
        Card[] all = cardsIn(game, ZoneType.Battlefield);
        Zones z = lastZones;
        if (z != null && z.battlefield == all) {
            Card[] r = z.battles;
            if (r != null) {
                if (TraitEpoch.VERIFY) {
                    List<Card> fresh = new ArrayList<>();
                    for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
                        if (c.isBattle()) {
                            fresh.add(c);
                        }
                    }
                    if (!TraitEpoch.sameElements(java.util.Arrays.asList(r), fresh) && TraitEpoch.global() == ep) {
                        TraitEpoch.mismatch("battles", game, java.util.Arrays.asList(r), fresh);
                    }
                }
                return r;
            }
        }
        List<Card> l = new ArrayList<>();
        for (Card c : all) {
            if (c.isBattle()) {
                l.add(c);
            }
        }
        Card[] r = l.isEmpty() ? NONE : l.toArray(NONE);
        if (z != null && z.battlefield == all && TraitEpoch.global() == ep && z.epoch == ep) {
            z.battles = r;
        }
        return r;
    }

    public static Card[] cardsIn(Game game, ZoneType zone) {
        long ep = TraitEpoch.global();
        Zones z = lastZones;
        if (z == null || z.game != game || z.epoch != ep || !samePlayers(z.players, game)) {
            List<Player> pl = new ArrayList<>();
            for (Player p : game.getPlayers()) {
                pl.add(p);
            }
            z = new Zones(game, ep, pl.toArray(new Player[0]), toArray(game.getCardsIn(ZoneType.Battlefield)), toArray(game.getCardsIn(ZoneType.Command)));
            if (TraitEpoch.global() == ep) {
                lastZones = z;
            }
        }
        if (zone == ZoneType.Battlefield) {
            return z.battlefield;
        }
        if (zone == ZoneType.Command) {
            return z.command;
        }
        throw new IllegalArgumentException(String.valueOf(zone));
    }

    private static Card[] toArray(Iterable<Card> cards) {
        List<Card> l = new ArrayList<>();
        for (Card c : cards) {
            l.add(c);
        }
        return l.isEmpty() ? NONE : l.toArray(NONE);
    }

    private static boolean samePlayers(Player[] players, Game game) {
        int i = 0;
        for (Player p : game.getPlayers()) {
            if (i >= players.length || players[i] != p) {
                return false;
            }
            i++;
        }
        return i == players.length;
    }

    private static Index build(Game game, long ep, long ex) {
        List<Player> pl = new ArrayList<>();
        for (Player p : game.getPlayers()) {
            pl.add(p);
        }
        List<Card> relevant = new ArrayList<>();
        for (Card c : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            if (c.hasCounterReplacementEffects() || c.getCurrentState().novaHasAdventureOrOmen()) {
                relevant.add(c); // visiting it may create replacement effects
                continue;
            }
            for (ReplacementEffect re : c.getCurrentState().getReplacementEffects(false)) {
                if (re.getMode() == ReplacementType.DamageDone) {
                    relevant.add(c);
                    break;
                }
            }
        }
        return new Index(game, ep, ex, pl.toArray(new Player[0]), relevant.isEmpty() ? NONE : relevant.toArray(NONE));
    }
}
