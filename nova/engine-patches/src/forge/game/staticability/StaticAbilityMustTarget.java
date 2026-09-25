package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;

public class StaticAbilityMustTarget {
   public static boolean filterMustTargetCards(Player targetingPlayer, List<Card> targets, SpellAbility spellAbility) {
      if (targetingPlayer != spellAbility.getHostCard().getController()) {
         return false;
      } else {
         List<Pair<String, ZoneType>> restrictions = getAllRestrictions(spellAbility);
         return applyMustTargetCardAbility(restrictions, targets, spellAbility);
      }
   }

   public static boolean meetsMustTargetRestriction(SpellAbility spellAbility) {
      if (spellAbility.isCopied()) {
         return true;
      } else {
         Game game = spellAbility.getHostCard().getGame();
         List<Pair<String, ZoneType>> restrictions = getAllRestrictions(spellAbility);
         if (restrictions.isEmpty()) {
            return true;
         } else {
            SpellAbility currentAbility = spellAbility;
            boolean usesTargeting = false;

            do {
               if (currentAbility.usesTargeting() && !currentAbility.hasParam("TargetingPlayer")) {
                  usesTargeting = true;
                  TargetRestrictions tgt = currentAbility.getTargetRestrictions();
                  List<ZoneType> zone = tgt.getZone();
                  List<Card> validCards = CardLists.getValidCards(game.getCardsIn((Iterable)zone), (String[])tgt.getValidTgts(), currentAbility.getActivatingPlayer(), currentAbility.getHostCard(), currentAbility);
                  List<Card> choices = CardLists.getTargetableCards(validCards, currentAbility);
                  isRestrictionsMet(restrictions, choices, currentAbility);
               }

               currentAbility = currentAbility.getSubAbility();
            } while(currentAbility != null);

            return !usesTargeting || restrictions.isEmpty();
         }
      }
   }

   private static List<Pair<String, ZoneType>> getAllRestrictions(SpellAbility spellAbility) {
      Game game = spellAbility.getHostCard().getGame();
      List<Pair<String, ZoneType>> restrictions = new ArrayList();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.MustTarget)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.MustTarget) && stAb.matchesValidParam("ValidSA", spellAbility)) {
               Pair<String, ZoneType> newRestriction = Pair.of(stAb.getParam("ValidTarget"), ZoneType.smartValueOf(stAb.getParam("ValidZone")));
               if (!restrictions.contains(newRestriction)) {
                  restrictions.add(newRestriction);
               }
            }
         }
      }

      return restrictions;
   }

   private static boolean isRestrictionsMet(List<Pair<String, ZoneType>> restrictions, List<Card> targets, SpellAbility spellAbility) {
      for(int i = restrictions.size() - 1; i >= 0; --i) {
         Pair<String, ZoneType> restriction = (Pair)restrictions.get(i);
         boolean found = false;

         for(Card card : spellAbility.getTargets().getTargetCards()) {
            if (card.getType().hasStringType((String)restriction.getLeft()) && card.isInZone((ZoneType)restriction.getRight())) {
               found = true;
               break;
            }
         }

         if (found) {
            restrictions.remove(i);
         } else {
            found = false;

            for(Card card : targets) {
               if (card.getType().hasStringType((String)restriction.getLeft()) && card.isInZone((ZoneType)restriction.getRight())) {
                  found = true;
                  break;
               }
            }

            if (!found) {
               restrictions.remove(i);
            }
         }
      }

      return restrictions.isEmpty();
   }

   private static boolean applyMustTargetCardAbility(List<Pair<String, ZoneType>> restrictions, List<Card> targets, SpellAbility spellAbility) {
      if (isRestrictionsMet(restrictions, targets, spellAbility)) {
         return false;
      } else {
         int maxTargets = spellAbility.getMaxTargets();
         int targeted = spellAbility.getTargets().size();
         if (restrictions.size() > maxTargets - targeted) {
            targets.clear();
            return true;
         } else {
            boolean filtered = false;

            for(int i = targets.size() - 1; i >= 0; --i) {
               Card card = (Card)targets.get(i);
               boolean satisfied = false;

               for(Pair<String, ZoneType> restriction : restrictions) {
                  if (card.getType().hasStringType((String)restriction.getLeft()) && card.isInZone((ZoneType)restriction.getRight())) {
                     satisfied = true;
                     break;
                  }
               }

               if (!satisfied) {
                  targets.remove(i);
                  filtered = true;
               }
            }

            return filtered;
         }
      }
   }
}
