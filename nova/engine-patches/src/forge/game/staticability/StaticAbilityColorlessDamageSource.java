package forge.game.staticability;

import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.zone.ZoneType;

public class StaticAbilityColorlessDamageSource {
   public static boolean colorlessDamageSource(CardState state) {
      Card card = state.getCard();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(card.getGame(), StaticAbilityMode.ColorlessDamageSource)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.ColorlessDamageSource) && applyColorlessDamageSource(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyColorlessDamageSource(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
