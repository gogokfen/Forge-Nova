package forge.game.staticability;

import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityCantGainControl {
   public static boolean cantGainControl(Card card) {
      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(card.getGame(), StaticAbilityMode.CantGainControl)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantGainControl) && applyCantGainControlAbility(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCantGainControlAbility(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
