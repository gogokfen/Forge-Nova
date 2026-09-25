package forge.game.staticability;

import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityActivateAbilityAsIfHaste {
   public static boolean canActivate(Card card) {
      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(card.getGame(), StaticAbilityMode.ActivateAbilityAsIfHaste)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.ActivateAbilityAsIfHaste) && applyCanActivateAbility(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean applyCanActivateAbility(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
