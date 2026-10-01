package forge.nova.gui;

import com.google.common.collect.Multimap;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.AttackConstraints;
import forge.game.combat.AttackRequirement;
import forge.game.combat.AttackRestriction;
import forge.game.combat.AttackRestrictionType;
import forge.game.combat.Combat;
import forge.game.combat.GlobalAttackRestrictions;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.staticability.StaticAbilityMustAttack;
import forge.game.zone.ZoneType;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forge only says "Attack declaration invalid". This works out why, from the same attack constraints Forge checks
 * (CombatUtil.validateAttackers): limits on the number of attackers, creatures that can't attack alone (or only
 * alone), creatures that must attack (goaded, "attacks each combat if able", must attack a certain player), players
 * who must be attacked. It ends with an attack Forge would accept. Runs on the game thread, while the attack is still
 * declared.
 */
final class AttackExplainer {
    private AttackExplainer() {
    }

    /** The explanation, or null when there is no attack to explain. */
    static String explain(Game game) {
        Combat combat = game == null ? null : game.getCombat();
        if (combat == null) {
            return null;
        }
        AttackConstraints ac = combat.getAttackConstraints();
        Map<Card, GameEntity> declared = new LinkedHashMap<>(combat.getAttackersAndDefenders());
        Player attacker = combat.getAttackingPlayer();
        List<String> broken = new ArrayList<>();

        // ---- rules that forbid this attack outright
        GlobalAttackRestrictions global = ac.getGlobalRestrictions();
        Integer max = global.getMax();
        if (max != null && declared.size() > max) {
            broken.add((max == 0 ? "No creatures can attack this combat" : "No more than " + count(max, "creature") + " can attack each combat")
                    + sources(game, attacker, null) + ". You chose " + declared.size() + ".");
        }
        Map<GameEntity, Integer> perDefender = new LinkedHashMap<>();
        for (GameEntity d : declared.values()) {
            perDefender.merge(d, 1, Integer::sum);
        }
        for (Map.Entry<GameEntity, Integer> e : perDefender.entrySet()) {
            Integer dm = global.getDefenderMax().get(e.getKey());
            if (dm != null && e.getValue() > dm) {
                broken.add((dm == 0 ? name(e.getKey()) + " can't be attacked" : "No more than " + count(dm, "creature") + " can attack " + name(e.getKey()))
                        + sources(game, attacker, e.getKey()) + (dm == 0 ? "." : ". You sent " + e.getValue() + "."));
            }
        }
        for (Map.Entry<Card, GameEntity> e : declared.entrySet()) {
            Card c = e.getKey();
            AttackRestriction r = ac.getRestrictions().get(c);
            if (r == null) {
                continue;
            }
            if (!r.canAttack(e.getValue())) {
                broken.add(c.getName() + (r.getTypes().contains(AttackRestrictionType.NEVER) ? " can't attack." : " can't attack " + name(e.getValue()) + "."));
                continue;
            }
            for (AttackRestrictionType t : r.getViolation(declared)) {
                broken.add(c.getName() + switch (t) {
                    case ONLY_ALONE -> " can only attack alone.";
                    case NEED_GREATER_POWER -> " can't attack unless a creature with greater power also attacks.";
                    case NEED_BLACK_OR_GREEN -> " can't attack unless a black or green creature also attacks.";
                    case NOT_ALONE -> " can't attack alone.";
                    case NEED_TWO_OTHERS -> " can't attack unless at least two other creatures attack.";
                    case NEVER -> " can't attack.";
                });
            }
        }

        // ---- requirements: creatures and players that must be attacked with, if able
        List<String> missing = new ArrayList<>();
        if (broken.isEmpty()) {
            for (Map.Entry<Card, AttackRequirement> e : ac.getRequirements().entrySet()) {
                Card c = e.getKey();
                AttackRequirement req = e.getValue();
                if (!req.hasRequirement() || req.countViolations(declared.get(c), declared) <= 0) {
                    continue;
                }
                GameEntity target = declared.get(c);
                if (target == null) {
                    if (mustAttackItself(c, req)) {
                        missing.add(c.getName() + " must attack this combat if able" + mustAttackReason(game, c) + ".");
                    }
                } else {
                    GameEntity wanted = preferredDefender(req, target);
                    if (wanted != null && !wanted.equals(target)) {
                        missing.add(c.getName() + (c.isGoaded() ? " is goaded, so it must attack " + name(wanted) + " (a player who didn't goad it) if able."
                                : " must attack " + name(wanted) + " if able" + mustAttackReason(game, c) + "."));
                    }
                }
                // "if this creature attacks, those creatures attack too"
                if (target != null) {
                    for (Map.Entry<Card, Collection<StaticAbility>> also : req.getCausesToAttack().asMap().entrySet()) {
                        if (!declared.containsKey(also.getKey())) {
                            missing.add(also.getKey().getName() + " must attack too, because " + c.getName() + " attacks"
                                    + hosts(also.getValue()) + ".");
                        }
                    }
                }
            }
            Multimap<GameEntity, StaticAbility> playerReqs = StaticAbilityMustAttack.mustAttackSpecific(attacker, combat.getDefenders());
            Map<StaticAbility, Set<GameEntity>> byAbility = new LinkedHashMap<>();
            for (Map.Entry<GameEntity, StaticAbility> e : playerReqs.entries()) {
                byAbility.computeIfAbsent(e.getValue(), k -> new LinkedHashSet<>()).add(e.getKey());
            }
            for (Map.Entry<StaticAbility, Set<GameEntity>> e : byAbility.entrySet()) {
                boolean met = false;
                for (GameEntity d : e.getValue()) {
                    met |= declared.containsValue(d);
                }
                if (!met) {
                    List<String> names = new ArrayList<>();
                    for (GameEntity d : e.getValue()) {
                        names.add(name(d));
                    }
                    missing.add("At least one creature must attack " + String.join(" or ", names) + " if able ("
                            + e.getKey().getHostCard().getName() + ").");
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!broken.isEmpty()) {
            sb.append("\n\nRules it breaks:");
            for (String s : broken) sb.append("\n• ").append(s);
        }
        if (!missing.isEmpty()) {
            sb.append("\n\nWhat the attack still needs:");
            for (String s : missing) sb.append("\n• ").append(s);
        }
        if (broken.isEmpty() && missing.isEmpty()) {
            sb.append("\n\nSome creatures that must attack if able (goaded, \"attacks each combat if able\" and the like) aren't attacking, or are attacking the wrong player.");
        }
        try {
            Pair<Map<Card, GameEntity>, Integer> best = ac.getLegalAttackers();
            Map<Card, GameEntity> legal = best.getLeft();
            sb.append("\n\n");
            if (legal.isEmpty()) {
                sb.append("With your creatures as they are now, the only allowed choice is not to attack.");
            } else {
                sb.append("For example, this attack is allowed:");
                Map<GameEntity, List<String>> byDefender = new LinkedHashMap<>();
                for (Map.Entry<Card, GameEntity> e : legal.entrySet()) {
                    byDefender.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey().getName());
                }
                for (Map.Entry<GameEntity, List<String>> e : byDefender.entrySet()) {
                    sb.append("\n• ").append(String.join(", ", e.getValue())).append(" → ").append(name(e.getKey()));
                }
            }
        } catch (RuntimeException ignored) {
            // no example
        }
        return sb.toString().strip();
    }

    /** A requirement that counts even when the creature stays home (it isn't only "if X attacks, Y attacks too"). */
    private static boolean mustAttackItself(Card c, AttackRequirement req) {
        for (Pair<GameEntity, Integer> p : req.getSortedRequirements()) {
            if (p.getRight() > 0) {
                return true;
            }
        }
        return false;
    }

    /** The defender this creature has the most reasons to attack (null: no preference). */
    private static GameEntity preferredDefender(AttackRequirement req, GameEntity current) {
        List<Pair<GameEntity, Integer>> sorted = req.getSortedRequirements(); // ascending by count
        if (sorted.isEmpty()) {
            return null;
        }
        Pair<GameEntity, Integer> top = sorted.get(sorted.size() - 1);
        int currentCount = 0;
        for (Pair<GameEntity, Integer> p : sorted) {
            if (p.getLeft().equals(current)) currentCount = p.getRight();
        }
        return top.getRight() > currentCount ? top.getLeft() : null;
    }

    /** Why the creature must attack: goad, or the cards with "must attack" abilities that apply to it. */
    private static String mustAttackReason(Game game, Card c) {
        List<String> why = new ArrayList<>();
        if (c.isGoaded()) {
            List<String> who = new ArrayList<>();
            for (Player p : c.getGoaded()) who.add(p.getName());
            why.add("goaded by " + String.join(", ", who));
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (Card src : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (StaticAbility st : src.getStaticAbilities()) {
                try {
                    if (st.checkConditions(StaticAbilityMode.MustAttack) && st.matchesValidParam("ValidCreature", c)) {
                        hosts.add(src.getName());
                    }
                } catch (RuntimeException ignored) {
                    // skip this ability
                }
            }
        }
        if (!hosts.isEmpty()) {
            why.add(String.join(", ", hosts));
        }
        return why.isEmpty() ? "" : " (" + String.join("; ", why) + ")";
    }

    /** The cards limiting the number of attackers (all of them, or against one defender). */
    private static String sources(Game game, Player attacker, GameEntity defender) {
        Set<String> hosts = new LinkedHashSet<>();
        for (Card src : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (StaticAbility st : src.getStaticAbilities()) {
                try {
                    if (!st.checkConditions(StaticAbilityMode.AttackRestrict)) continue;
                    boolean forDefender = st.hasParam("ValidDefender");
                    if (defender == null ? !forDefender : forDefender && st.matchesValidParam("ValidDefender", defender)) {
                        hosts.add(src.getName());
                    }
                } catch (RuntimeException ignored) {
                    // skip this ability
                }
            }
        }
        return hosts.isEmpty() ? "" : " (" + String.join(", ", hosts) + ")";
    }

    private static String hosts(Collection<StaticAbility> abilities) {
        Set<String> names = new LinkedHashSet<>();
        for (StaticAbility st : abilities) {
            names.add(st.getHostCard().getName());
        }
        return names.isEmpty() ? "" : " (" + String.join(", ", names) + ")";
    }

    private static String name(GameEntity e) {
        if (e instanceof Card c) {
            return c.getName() + " (" + c.getController().getName() + ")";
        }
        return e == null ? "?" : e.getName();
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
