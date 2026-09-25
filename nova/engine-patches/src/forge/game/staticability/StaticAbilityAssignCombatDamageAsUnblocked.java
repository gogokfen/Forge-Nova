package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityAssignCombatDamageAsUnblocked {
   public static boolean assignCombatDamageAsUnblocked(Card card) {
      return assignCombatDamageAsUnblocked(card, true);
   }

   public static boolean assignCombatDamageAsUnblocked(Card card, boolean optional) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.AssignCombatDamageAsUnblocked)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.AssignCombatDamageAsUnblocked) && stAb.hasParam("Optional") == optional && applyAssignCombatDamageAsUnblocked(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean applyAssignCombatDamageAsUnblocked(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
