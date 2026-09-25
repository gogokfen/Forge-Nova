package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityIgnoreZeroLoyalty {
   public static boolean ignorePlaneswalkerZeroLoyaltyRule(Card card) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.IgnorePlaneswalkerZeroLoyaltyRule)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.IgnorePlaneswalkerZeroLoyaltyRule) && applyIgnorePlaneswalkerZeroLoyaltyRuleAbility(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean applyIgnorePlaneswalkerZeroLoyaltyRuleAbility(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
