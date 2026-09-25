package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityCantBeSuspected {
   public static boolean cantBeSuspected(Card c) {
      Game game = c.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantBeSuspected)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantBeSuspected) && cantBeSuspectedCheck(stAb, c)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean cantBeSuspectedCheck(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
