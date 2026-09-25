package forge.nova.sync;

import forge.card.CardStateName;
import forge.card.ColorSet;
import forge.card.mana.ManaCost;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.player.PlayerView;
import forge.gui.card.CardDetailUtil;
import forge.item.PaperCard;
import forge.nova.util.JsonOut;

import com.google.common.collect.Multiset;

/**
 * Serialises card views into the compact JSON the client renders from.
 */
public final class CardJson {
    private CardJson() {
    }

    public static String zoneCode(forge.game.zone.ZoneType z) {
        return z == null ? "" : z.name();
    }

    public static String colors(ColorSet cs) {
        if (cs == null || cs.isColorless()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(5);
        if (cs.hasWhite()) sb.append('W');
        if (cs.hasBlue()) sb.append('U');
        if (cs.hasBlack()) sb.append('B');
        if (cs.hasRed()) sb.append('R');
        if (cs.hasGreen()) sb.append('G');
        return sb.toString();
    }

    public static String cost(ManaCost mc) {
        if (mc == null || mc.isNoCost()) {
            return "";
        }
        return mc.getSimpleString();
    }

    private static String safe(java.util.function.Supplier<String> s) {
        try {
            String v = s.get();
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** Face characteristics (name, cost, type, text, P/T, image) of one card state. */
    public static void writeFace(JsonOut o, CardView.CardStateView st, Iterable<PlayerView> viewers, boolean canView) {
        o.putOpt("n", safe(st::getName));
        String img = safe(() -> viewers == null ? st.getImageKey() : st.getImageKey(viewers));
        o.putOpt("img", img);
        o.putOpt("mc", cost(st.getManaCost()));
        o.putNz("cmc", st.getManaCost() == null ? 0 : st.getManaCost().getCMC()); // hand auto-sort
        o.putOpt("col", colors(st.getColors()));
        o.putOpt("ty", safe(() -> CardDetailUtil.formatCardType(st, canView)));
        o.putOpt("tx", safe(st::getOracleText));
        if (st.isCreature() || st.hasPrintedPT()) {
            o.put("pow", st.getPower()).put("tou", st.getToughness());
        }
        if (st.isPlaneswalker()) {
            o.putOpt("loy", safe(st::getLoyalty));
        }
        if (st.isBattle()) {
            o.putOpt("def", safe(st::getDefense));
        }
        int types = 0;
        if (st.isCreature()) types |= 1;
        if (st.isLand()) types |= 2;
        if (st.isPlaneswalker()) types |= 4;
        if (st.isArtifact()) types |= 8;
        if (st.isEnchantment()) types |= 16;
        if (st.isInstant()) types |= 32;
        if (st.isSorcery()) types |= 64;
        if (st.isBattle()) types |= 128;
        if (st.isBasicLand()) types |= 256;
        if (st.isPlane() || st.isPhenomenon()) types |= 512;
        o.putNz("tf", types);
        o.putOpt("set", safe(st::getSetCode));
    }

    /**
     * Full board representation of a card as seen by the local player(s).
     *
     * @param canView whether the local player may see this card's face
     * @param mayFlip whether the alternate face may be shown
     */
    public static void write(JsonOut o, CardView cv, Iterable<PlayerView> viewers, boolean canView, boolean mayFlip) {
        o.beginObj();
        o.put("id", cv.getId());
        PlayerView owner = cv.getOwner();
        PlayerView ctrl = cv.getController();
        if (owner != null) o.put("o", owner.getId());
        if (ctrl != null) o.put("c", ctrl.getId());
        o.putOpt("z", zoneCode(cv.getZone()));
        o.flag("tap", cv.isTapped());
        o.flag("fd", cv.isFaceDown());
        o.flag("tok", cv.isToken());
        o.flag("ph", cv.isPhasedOut());

        if (!canView) {
            o.put("hid", true);
            // Face-down permanents still show their public characteristics (e.g. a 2/2 morph).
            if (cv.isFaceDown() && cv.getZone() == forge.game.zone.ZoneType.Battlefield) {
                CardView.CardStateView st = cv.getCurrentState();
                if (st != null && st.isCreature()) {
                    o.put("pow", st.getPower()).put("tou", st.getToughness()).put("tf", 1);
                }
                o.putOpt("img", safe(() -> cv.getCurrentState().getImageKey(viewers)));
            }
            writeBoardState(o, cv);
            o.endObj();
            return;
        }

        CardView.CardStateView st = cv.getCurrentState();
        if (st != null) {
            writeFace(o, st, viewers, true);
        }
        o.flag("cmd", cv.isCommander());
        writeBoardState(o, cv);

        if (mayFlip) {
            CardView.CardStateView alt = cv.getAlternateState();
            if (alt != null && alt != st) {
                o.beginObj("alt");
                writeFace(o, alt, viewers, true);
                o.endObj();
            }
        }
        // Split/room cards: show both halves' names in the detail view
        if (cv.isSplitCard() && cv.hasLeftSplitState() && cv.hasRightSplitState()) {
            o.put("split", true);
        }
        o.endObj();
    }

    /** State that changes during play and is public information. */
    private static void writeBoardState(JsonOut o, CardView cv) {
        o.flag("sick", cv.isSick());
        o.flag("atk", cv.isAttacking());
        o.flag("blk", cv.isBlocking());
        o.putNz("dmg", cv.getDamage());
        GameEntityView att = cv.getEntityAttachedTo();
        if (att instanceof CardView ac) {
            o.put("at", ac.getId());
        } else if (att instanceof PlayerView ap) {
            o.put("atp", ap.getId());
        }
        Multiset<CounterType> counters = cv.getCounters();
        if (counters != null && !counters.isEmpty()) {
            o.beginObj("cnt");
            for (Multiset.Entry<CounterType> e : counters.entrySet()) {
                o.put(e.getElement().getName(), e.getCount());
            }
            o.endObj();
        }
        CardView exiledWith = cv.getExiledWith();
        if (exiledWith != null) {
            o.put("exw", exiledWith.getId());
        }
        String overlay = cv.getOverlayText();
        o.putOpt("ovl", overlay);
        String chosenType = cv.getChosenType();
        o.putOpt("chT", chosenType);
        java.util.List<String> chosenColors = cv.getChosenColors();
        if (chosenColors != null && !chosenColors.isEmpty()) {
            o.put("chC", String.join(",", chosenColors));
        }
        PlayerView chosenPlayer = cv.getChosenPlayer();
        if (chosenPlayer != null) {
            o.put("chP", chosenPlayer.getId());
        }
    }

    /** A card reference used inside dialogs, where the card might not be part of the synced board. */
    public static void writeDialogCard(JsonOut o, CardView cv, Iterable<PlayerView> viewers, boolean canView) {
        o.beginObj("card");
        o.put("id", cv.getId());
        if (canView && cv.getCurrentState() != null) {
            writeFace(o, cv.getCurrentState(), viewers, true);
        } else {
            o.put("hid", true);
            o.putOpt("n", canView ? cv.getName() : "");
        }
        o.putOpt("z", zoneCode(cv.getZone()));
        PlayerView ctrl = cv.getController();
        if (ctrl != null) o.put("c", ctrl.getId());
        o.flag("tap", cv.isTapped());
        o.endObj();
    }

    public static void writePaperCard(JsonOut o, PaperCard pc) {
        o.beginObj("card");
        o.put("id", -1);
        o.put("n", pc.getName());
        o.putOpt("img", safe(() -> pc.getImageKey(false)));
        try {
            o.putOpt("mc", cost(pc.getRules().getManaCost()));
            o.putOpt("col", colors(pc.getRules().getColor()));
            o.putOpt("ty", pc.getRules().getType().toString());
            o.putOpt("tx", pc.getRules().getOracleText());
            if (pc.getRules().getType().isCreature()) {
                o.put("pow", pc.getRules().getIntPower()).put("tou", pc.getRules().getIntToughness());
            }
        } catch (Exception ignored) {
            // rules information is best-effort
        }
        o.endObj();
    }

    public static boolean isOriginalState(CardView cv) {
        CardView.CardStateView st = cv.getCurrentState();
        return st == null || st.getState() == CardStateName.Original;
    }
}
