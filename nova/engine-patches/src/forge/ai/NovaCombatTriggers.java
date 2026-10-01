package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.TraitEpoch;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollection;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Forge Nova engine patch: the triggers and static abilities ComputerUtilCombat's predictions look at.
 *
 * Forge's combat predictions (predictPowerBonusOfAttacker and friends, called for every attacker/blocker pair
 * in every simulated block and attack) collect the triggers of every card on the battlefield and in the
 * command zones into a fresh collection and ask {@link ComputerUtilCombat#combatTriggerWillTrigger} about each
 * one. That method rejects every trigger whose mode is not one of {@link #MODES} before anything else
 * (without side effects), so only those triggers matter. Likewise they scan every card's static abilities for
 * Continuous ones. Both lists are kept here, built exactly as Forge builds them (same cards, same order, same
 * FCollection/CardCollection de-duplication, then filtered by mode), and rebuilt whenever
 * {@link TraitEpoch#global()} changes (zone contents/order, phasing, any card's traits) or a player leaves.
 * With -Dnova.verifyCaches=true every use is compared with a fresh build.
 */
public final class NovaCombatTriggers {
    /** the modes combatTriggerWillTrigger does not reject right away */
    static final Set<TriggerType> MODES = EnumSet.of(TriggerType.Attacks, TriggerType.AttackerUnblocked, TriggerType.Blocks,
            TriggerType.AttackerBlocked, TriggerType.AttackerBlockedByCreature, TriggerType.DamageDone);

    /** Continuous static abilities and their host cards (parallel lists), in scan order. */
    public static final class Statics {
        final List<StaticAbility> statics = new ArrayList<>();
        final List<Card> hosts = new ArrayList<>();

        public int size() {
            return statics.size();
        }

        public StaticAbility stAb(int i) {
            return statics.get(i);
        }

        public Card host(int i) {
            return hosts.get(i);
        }
    }

    private static final class Lists {
        final Game game;
        final long epoch;
        final int players;
        /** combat-mode triggers of the battlefield cards */
        final FCollection<Trigger> battlefield;
        /** combat-mode triggers of the battlefield cards, then of the command zone cards */
        final FCollection<Trigger> battlefieldAndCommand;
        /** Continuous statics of CardCollection.combine(battlefield, command) */
        final Statics continuousBattlefieldAndCommand;
        /** Continuous statics of the battlefield cards */
        final Statics continuousBattlefield;

        Lists(Game game, long epoch, int players, FCollection<Trigger> battlefield, FCollection<Trigger> battlefieldAndCommand,
              Statics continuousBattlefieldAndCommand, Statics continuousBattlefield) {
            this.game = game;
            this.epoch = epoch;
            this.players = players;
            this.battlefield = battlefield;
            this.battlefieldAndCommand = battlefieldAndCommand;
            this.continuousBattlefieldAndCommand = continuousBattlefieldAndCommand;
            this.continuousBattlefield = continuousBattlefield;
        }
    }

    private static volatile Lists last;

    private NovaCombatTriggers() {
    }

    private static Lists lists(Game game) {
        long ep = TraitEpoch.global();
        int players = game.getPlayers().size();
        Lists l = last;
        if (l == null || l.game != game || l.epoch != ep || l.players != players || TraitEpoch.DISABLED) {
            l = build(game, ep, players);
            last = l;
        } else if (TraitEpoch.VERIFY) {
            Lists fresh = build(game, ep, players);
            if (TraitEpoch.global() == ep) {
                check("combatTriggers:battlefield", game, l.battlefield, fresh.battlefield);
                check("combatTriggers:battlefield+command", game, l.battlefieldAndCommand, fresh.battlefieldAndCommand);
                check("combatStatics:battlefield+command", game, l.continuousBattlefieldAndCommand.statics, fresh.continuousBattlefieldAndCommand.statics);
                check("combatStatics:battlefield+command/hosts", game, l.continuousBattlefieldAndCommand.hosts, fresh.continuousBattlefieldAndCommand.hosts);
                check("combatStatics:battlefield", game, l.continuousBattlefield.statics, fresh.continuousBattlefield.statics);
                check("combatStatics:battlefield/hosts", game, l.continuousBattlefield.hosts, fresh.continuousBattlefield.hosts);
            }
        }
        return l;
    }

    private static void check(String what, Game game, List<?> cached, List<?> fresh) {
        if (!TraitEpoch.sameElements(cached, fresh)) {
            TraitEpoch.mismatch(what, game, cached, fresh);
        }
    }

    private static Lists build(Game game, long ep, int players) {
        // exactly the collections Forge builds, then only the modes that matter
        FCollection<Trigger> all = new FCollection<Trigger>();
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            all.addAll(card.getTriggers());
        }
        FCollection<Trigger> bf = filter(all);
        for (Card card : game.getCardsIn(ZoneType.Command)) {
            all.addAll(card.getTriggers());
        }
        Statics bfCmd = new Statics();
        for (Card card : CardCollection.combine(game.getCardsIn(ZoneType.Battlefield), game.getCardsIn(ZoneType.Command))) {
            addContinuous(bfCmd, card);
        }
        Statics bfOnly = new Statics();
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            addContinuous(bfOnly, card);
        }
        return new Lists(game, ep, players, bf, filter(all), bfCmd, bfOnly);
    }

    private static void addContinuous(Statics s, Card card) {
        for (StaticAbility stAb : card.getStaticAbilities()) {
            if (stAb.checkMode(StaticAbilityMode.Continuous)) {
                s.statics.add(stAb);
                s.hosts.add(card);
            }
        }
    }

    private static FCollection<Trigger> filter(FCollection<Trigger> all) {
        FCollection<Trigger> r = new FCollection<Trigger>();
        for (Trigger t : all) {
            if (MODES.contains(t.getMode())) {
                r.add(t);
            }
        }
        return r;
    }

    /** Forge's "triggers of all battlefield cards, then of all command zone cards", minus other modes. Read-only. */
    static FCollection<Trigger> battlefieldAndCommand(Game game) {
        return lists(game).battlefieldAndCommand;
    }

    /** Forge's "triggers of all battlefield cards", minus other modes. Read-only. */
    static FCollection<Trigger> battlefield(Game game) {
        return lists(game).battlefield;
    }

    /** Continuous static abilities of CardCollection.combine(battlefield, command) in order. Read-only. */
    static Statics continuousBattlefieldAndCommand(Game game) {
        return lists(game).continuousBattlefieldAndCommand;
    }

    /** Continuous static abilities of the battlefield cards in order. Read-only. */
    static Statics continuousBattlefield(Game game) {
        return lists(game).continuousBattlefield;
    }

    /**
     * {@code triggers.addAll(extra.getTriggers())} restricted to the modes that matter: extra's relevant
     * triggers that are not in the collection yet are appended (to a copy; the argument is not modified).
     */
    static FCollection<Trigger> plus(FCollection<Trigger> triggers, Card extra) {
        FCollection<Trigger> result = null;
        for (Trigger t : extra.getTriggers()) {
            if (MODES.contains(t.getMode()) && !(result == null ? triggers : result).contains(t)) {
                if (result == null) {
                    result = new FCollection<Trigger>(triggers);
                }
                result.add(t);
            }
        }
        return result == null ? triggers : result;
    }
}
