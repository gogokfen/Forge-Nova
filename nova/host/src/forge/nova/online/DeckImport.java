package forge.nova.online;

import forge.StaticData;
import forge.card.CardDb;
import forge.card.CardRules;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckRecognizer;
import forge.deck.DeckSection;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a deck a friend pasted or uploaded: Forge .dck files, Arena/MTGO exports, Moxfield/Archidekt
 * text... through Forge's own deck recognizer (the one behind classic Forge's "Import deck").
 */
public final class DeckImport {
    private DeckImport() {
    }

    public static final int MAX_TEXT = 64 * 1024;
    private static final int MAX_LINES = 600;
    private static final int MAX_CARDS = 400;

    /**
     * @param deck       the deck (null if nothing usable was found)
     * @param unknown    lines that name no card Forge knows
     * @param candidates legal commanders in the deck, when the format needs one and none was marked
     */
    public record Result(Deck deck, List<String> unknown, List<String> candidates) {
    }

    /** The deck-building rules of a lobby format id. */
    public static DeckFormat formatOf(String format) {
        return switch (format == null ? "" : format) {
            case "commander" -> DeckFormat.Commander;
            case "brawl" -> DeckFormat.Brawl;
            case "oathbreaker" -> DeckFormat.Oathbreaker;
            case "tinyLeaders" -> DeckFormat.TinyLeaders;
            default -> DeckFormat.Constructed;
        };
    }

    public static boolean isCommanderFormat(String format) {
        return formatOf(format) != DeckFormat.Constructed;
    }

    public static Result parse(String text, String name, String format) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The deck list is empty.");
        }
        if (text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("That deck list is too long.");
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (lines.length > MAX_LINES) {
            throw new IllegalArgumentException("That deck list has too many lines.");
        }
        if (isForgeDck(lines)) {
            return finish(parseForgeDck(lines, name), format);
        }
        DeckRecognizer recognizer = new DeckRecognizer();
        try {
            recognizer.setArtPreference(StaticData.instance().getCardArtPreference());
        } catch (Throwable ignored) {
            // default art preference
        }
        List<DeckRecognizer.Token> tokens = recognizer.parseCardList(lines);

        Deck deck = new Deck(name == null || name.isBlank() ? "Imported deck" : name.trim());
        List<String> unknown = new ArrayList<>();
        int total = 0;
        for (DeckRecognizer.Token t : tokens) {
            DeckRecognizer.TokenType type = t.getType();
            if (type == DeckRecognizer.TokenType.DECK_NAME && (name == null || name.isBlank())) {
                deck.setName(t.getText());
            } else if (DeckRecognizer.TokenType.CARD_TOKEN_TYPES.contains(type) && t.getCard() != null) {
                DeckSection sec = t.getTokenSection();
                if (sec == null || (sec != DeckSection.Commander && sec != DeckSection.Sideboard)) {
                    sec = DeckSection.Main;
                }
                int q = Math.max(1, Math.min(99, t.getQuantity()));
                total += q;
                if (total > MAX_CARDS) {
                    throw new IllegalArgumentException("That deck has more than " + MAX_CARDS + " cards.");
                }
                deck.getOrCreate(sec).add(t.getCard(), q);
            } else if (type == DeckRecognizer.TokenType.UNKNOWN_CARD || type == DeckRecognizer.TokenType.UNSUPPORTED_CARD) {
                if (unknown.size() < 30) unknown.add(t.getText());
            }
        }
        return finish(new Result(deck, unknown, List.of()), format);
    }

    /** An empty deck counts as nothing recognized; a commander deck without a marked commander gets candidates. */
    private static Result finish(Result r, String format) {
        Deck deck = r.deck();
        if (deck == null || (deck.getMain().isEmpty() && !hasCommander(deck))) {
            return new Result(null, r.unknown(), List.of());
        }
        List<String> candidates = List.of();
        DeckFormat fmt = formatOf(format);
        if (fmt != DeckFormat.Constructed && !hasCommander(deck)) {
            candidates = findCommander(deck, fmt);
        }
        return new Result(deck, r.unknown(), candidates);
    }

    private static final Pattern SECTION = Pattern.compile("^\\[([^\\]]+)\\]$");
    private static final Pattern COUNT = Pattern.compile("^(\\d+)\\s*[xX]?\\s+(.+)$");

    /** Forge's own deck files: [metadata], [Main], [Commander]... with lines like "1 Sol Ring|C21|[263]". */
    private static boolean isForgeDck(String[] lines) {
        for (String raw : lines) {
            Matcher m = SECTION.matcher(raw.trim());
            if (m.matches()) {
                String s = m.group(1).trim().toLowerCase(Locale.ROOT);
                if (s.equals("metadata") || s.equals("main") || s.equals("commander") || s.equals("sideboard")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Result parseForgeDck(String[] lines, String name) {
        CardDb db = StaticData.instance().getCommonCards();
        Deck deck = new Deck(name == null || name.isBlank() ? "Imported deck" : name.trim());
        List<String> unknown = new ArrayList<>();
        String section = "main";
        int total = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//")) {
                continue;
            }
            Matcher h = SECTION.matcher(line);
            if (h.matches()) {
                section = h.group(1).trim().toLowerCase(Locale.ROOT);
                continue;
            }
            if (section.equals("metadata")) {
                if ((name == null || name.isBlank()) && line.regionMatches(true, 0, "Name=", 0, 5) && line.length() > 5) {
                    deck.setName(line.substring(5).trim());
                }
                continue;
            }
            DeckSection sec = switch (section) {
                case "main" -> DeckSection.Main;
                case "commander" -> DeckSection.Commander;
                case "sideboard" -> DeckSection.Sideboard;
                default -> null; // planes, schemes, avatars...: not part of a friend's game
            };
            if (sec == null) {
                continue;
            }
            int q = 1;
            String request = line;
            Matcher c = COUNT.matcher(line);
            if (c.matches()) {
                q = Math.max(1, Math.min(99, Integer.parseInt(c.group(1).length() > 3 ? "99" : c.group(1))));
                request = c.group(2).trim();
            }
            PaperCard pc = resolve(db, request);
            if (pc == null) {
                if (unknown.size() < 30) unknown.add(line);
                continue;
            }
            total += q;
            if (total > MAX_CARDS) {
                throw new IllegalArgumentException("That deck has more than " + MAX_CARDS + " cards.");
            }
            deck.getOrCreate(sec).add(pc, q);
        }
        return new Result(deck, unknown, List.of());
    }

    /** "Name|SET|[collector number]" or "Name|SET|art index" or just a name; the printing falls back to any. */
    private static PaperCard resolve(CardDb db, String request) {
        try {
            CardDb.CardRequest r = CardDb.CardRequest.fromString(request);
            if (r == null || r.cardName == null || r.cardName.isBlank()) {
                return null;
            }
            PaperCard pc = null;
            if (r.edition != null && !r.edition.isEmpty()) {
                if (r.collectorNumber != null && !r.collectorNumber.isEmpty() && !"N.A.".equals(r.collectorNumber)) {
                    pc = db.getCard(r.cardName, r.edition, r.collectorNumber);
                }
                if (pc == null && r.artIndex > 0) {
                    pc = db.getCard(r.cardName, r.edition, r.artIndex);
                }
                if (pc == null) {
                    pc = db.getCard(r.cardName, r.edition);
                }
            }
            return pc != null ? pc : db.getCard(r.cardName);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean hasCommander(Deck d) {
        CardPool c = d.get(DeckSection.Commander);
        return c != null && !c.isEmpty();
    }

    private static boolean canLead(PaperCard pc, DeckFormat fmt) {
        CardRules r = pc.getRules();
        try {
            if (fmt == DeckFormat.Oathbreaker) {
                return fmt.isLegalCommander(r) || r.canBeSignatureSpell();
            }
            return fmt.isLegalCommander(r) || r.canBePartnerCommander() || r.canBeBackground();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Commander exports often keep the commander in the sideboard (MTGO style) or simply list it
     * first. Moves an obvious commander into place; otherwise returns the candidates to choose from.
     */
    private static List<String> findCommander(Deck deck, DeckFormat fmt) {
        CardPool side = deck.get(DeckSection.Sideboard);
        if (side != null && !side.isEmpty() && side.countAll() <= 2) {
            boolean all = true;
            for (Map.Entry<PaperCard, Integer> e : side) {
                if (!canLead(e.getKey(), fmt)) all = false;
            }
            if (all) {
                CardPool cmd = deck.getOrCreate(DeckSection.Commander);
                for (Map.Entry<PaperCard, Integer> e : side) cmd.add(e.getKey(), e.getValue());
                deck.putSection(DeckSection.Sideboard, new CardPool());
                return List.of();
            }
        }
        Set<String> names = new LinkedHashSet<>();
        PaperCard only = null;
        for (DeckSection sec : new DeckSection[]{DeckSection.Main, DeckSection.Sideboard}) {
            CardPool pool = deck.get(sec);
            if (pool == null) continue;
            for (Map.Entry<PaperCard, Integer> e : pool) {
                if (canLead(e.getKey(), fmt) && names.add(e.getKey().getName())) {
                    only = e.getKey();
                }
            }
        }
        if (names.size() == 1 && only != null) {
            chooseCommanders(deck, List.of(only.getName()));
            return List.of();
        }
        return new ArrayList<>(names);
    }

    /** Cards of the deck that could lead it in the format (for a deck without a commander). */
    public static List<String> candidates(Deck deck, String format) {
        DeckFormat fmt = formatOf(format);
        Set<String> names = new LinkedHashSet<>();
        for (DeckSection sec : new DeckSection[]{DeckSection.Main, DeckSection.Sideboard}) {
            CardPool pool = deck.get(sec);
            if (pool == null) continue;
            for (Map.Entry<PaperCard, Integer> e : pool) {
                if (canLead(e.getKey(), fmt)) names.add(e.getKey().getName());
            }
        }
        return new ArrayList<>(names);
    }

    /** Moves the named cards (one copy each) from the main deck or sideboard into the command zone. */
    public static boolean chooseCommanders(Deck deck, List<String> names) {
        boolean moved = false;
        for (String n : names) {
            for (DeckSection sec : new DeckSection[]{DeckSection.Main, DeckSection.Sideboard}) {
                CardPool pool = deck.get(sec);
                PaperCard found = null;
                if (pool != null) {
                    for (Map.Entry<PaperCard, Integer> e : pool) {
                        if (e.getKey().getName().equalsIgnoreCase(n)) {
                            found = e.getKey();
                            break;
                        }
                    }
                }
                if (found != null) {
                    pool.remove(found, 1);
                    deck.getOrCreate(DeckSection.Commander).add(found, 1);
                    moved = true;
                    break;
                }
            }
        }
        return moved;
    }

    /** Forge's verdict on a deck in a format (null: fine). */
    public static String problem(Deck deck, String format) {
        DeckFormat fmt = formatOf(format);
        if (fmt != DeckFormat.Constructed && !hasCommander(deck)) {
            return "No commander chosen.";
        }
        try {
            return fmt.getDeckConformanceProblem(deck);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
