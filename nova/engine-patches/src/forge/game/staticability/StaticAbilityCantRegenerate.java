package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityCantRegenerate {
   public static boolean cantRegenerate(Card card) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantRegenerate)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantRegenerate) && applyCantRegenerateAbility(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCantRegenerateAbility(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
