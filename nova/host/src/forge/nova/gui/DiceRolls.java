package forge.nova.gui;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.spellability.SpellAbility;
import forge.nova.util.JsonOut;
import forge.util.Localizer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forge tells every player about a die roll with a plain message ("Bob rolled 4, 17"). This reads such a message back
 * into the dice that were rolled, so the client can show them rolling: how many sides (from the RollDice ability that
 * is resolving), the natural rolls, the ignored ones; or the planar die's face.
 */
final class DiceRolls {
    /** The standard dice; an unknown die is shown as the smallest of these that can show the rolls. */
    private static final int[] STANDARD = {4, 6, 8, 10, 12, 20, 100};

    private final String who;
    private final int sides;
    private final List<Integer> rolls;
    private final List<Integer> ignored;
    private final List<Integer> results;
    private final String planar;

    private DiceRolls(String who, int sides, List<Integer> rolls, List<Integer> ignored, List<Integer> results, String planar) {
        this.who = who;
        this.sides = sides;
        this.rolls = rolls;
        this.ignored = ignored;
        this.results = results;
        this.planar = planar;
    }

    /** The dice behind a roll message, or null when the message isn't about a roll. Runs on the game thread. */
    static DiceRolls parse(String message, Game game) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        String[] lines = message.replace("\r", "").split("\n");
        String first = lines[0].trim();
        Localizer loc = Localizer.getInstance();
        Matcher m = template(loc.getMessage("lblPlanarDiceResult", "\u0000", "\u0001")).matcher(first);
        if (m.matches()) {
            String face = m.group(1).trim();
            return new DiceRolls("", 6, List.of(), List.of(), List.of(), face);
        }
        int sides = -1;
        m = template(loc.getMessage("lblAttractionRollResult", "\u0000", "\u0001")).matcher(first);
        if (m.matches()) {
            sides = 6;
        } else {
            m = template(loc.getMessage("lblPlayerRolledResult", "\u0000", "\u0001")).matcher(first);
            if (!m.matches()) {
                return null;
            }
        }
        List<Integer> results = numbers(m.group(2));
        if (results.isEmpty()) {
            return null;
        }
        List<Integer> ignored = new ArrayList<>();
        List<Integer> natural = null;
        Pattern ign = template(loc.getMessage("lblIgnoredRolls", "\u0001"));
        Pattern nat = template(loc.getMessage("lblNaturalRolls", "\u0001"));
        for (int i = 1; i < lines.length; i++) {
            String ln = lines[i].trim();
            Matcher mi = ign.matcher(ln);
            if (mi.matches()) {
                ignored.addAll(numbers(mi.group(1)));
                continue;
            }
            Matcher mn = nat.matcher(ln);
            if (mn.matches()) {
                natural = numbers(mn.group(1));
            }
        }
        List<Integer> rolls = natural != null && natural.size() == results.size() ? natural : results;
        if (sides < 0) {
            sides = sidesOfResolvingRoll(game);
        }
        int highest = 1;
        for (int r : rolls) highest = Math.max(highest, r);
        for (int r : ignored) highest = Math.max(highest, r);
        if (sides < 2 || sides < highest) {
            sides = highest;
            for (int s : STANDARD) {
                if (s >= highest) {
                    sides = s;
                    break;
                }
            }
        }
        return new DiceRolls(m.group(1).trim(), sides, rolls, ignored, results, null);
    }

    /** {field: {who, sides, rolls, ignored, results} | {planar: face}} */
    void write(JsonOut o, String field) {
        o.beginObj(field);
        if (planar != null) {
            o.put("planar", planar);
        } else {
            o.put("who", who).put("sides", sides);
            o.beginArr("rolls");
            for (int r : rolls) o.val(r);
            o.endArr();
            o.beginArr("ignored");
            for (int r : ignored) o.val(r);
            o.endArr();
            o.beginArr("results");
            for (int r : results) o.val(r);
            o.endArr();
        }
        o.endObj();
    }

    /** A localized message with {0}/{1} filled in as \u0000/\u0001: the same text as a pattern, those as groups. */
    private static Pattern template(String filled) {
        StringBuilder re = new StringBuilder();
        int start = 0;
        for (int i = 0; i < filled.length(); i++) {
            char ch = filled.charAt(i);
            if (ch == '\u0000' || ch == '\u0001') {
                re.append(Pattern.quote(filled.substring(start, i))).append(ch == '\u0000' ? "(.*?)" : "(.+)");
                start = i + 1;
            }
        }
        re.append(Pattern.quote(filled.substring(start)));
        return Pattern.compile(re.toString(), Pattern.DOTALL);
    }

    private static List<Integer> numbers(String s) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("-?\\d+").matcher(s == null ? "" : s);
        while (m.find() && out.size() < 64) {
            try {
                out.add(Integer.parseInt(m.group()));
            } catch (NumberFormatException ignored) {
                // not a die face
            }
        }
        return out;
    }

    /** The "Sides" of the RollDice ability being resolved (the top of the stack, its sub-abilities), else -1. */
    private static int sidesOfResolvingRoll(Game game) {
        try {
            SpellAbility sa = game == null || game.getStack().isEmpty() ? null : game.getStack().peekAbility();
            SpellAbility roll = findRoll(sa, 0);
            if (roll != null) {
                return AbilityUtils.calculateAmount(roll.getHostCard(), roll.getParamOrDefault("Sides", "6"), roll);
            }
        } catch (RuntimeException ignored) {
            // unknown die
        }
        return -1;
    }

    private static SpellAbility findRoll(SpellAbility sa, int depth) {
        if (sa == null || depth > 8) {
            return null;
        }
        if (sa.getApi() == ApiType.RollDice) {
            return sa;
        }
        SpellAbility found = findRoll(sa.getSubAbility(), depth + 1);
        if (found != null) {
            return found;
        }
        for (SpellAbility extra : sa.getAdditionalAbilities().values()) {
            found = findRoll(extra, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
