package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityNoCleanupDamage {
   public static boolean damageNotRemoved(Card card) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.NoCleanupDamage)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.NoCleanupDamage) && damageNotRemovedApplies(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean damageNotRemovedApplies(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
