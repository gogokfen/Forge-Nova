package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityCantBeCopied {
   public static boolean cantBeCopied(Card c) {
      Game game = c.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantBeCopied)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantBeCopied) && cantBeCopiedCheck(stAb, c)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean cantBeCopiedCheck(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
