package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityCantBeBeamedUp {
   public static boolean cantBeBeamedUp(Card card) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantBeBeamedUp)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantBeBeamedUp) && applyCantBeBeamedUpAbility(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCantBeBeamedUpAbility(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
