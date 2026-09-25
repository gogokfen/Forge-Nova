package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityCantChangeDayTime {
   public static boolean cantChangeDay(Game game, Boolean value) {
      if (value == null) {
         return false;
      } else {
         for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantChangeDayTime)) { // Forge Nova: indexed scan
            {
               if (stAb.checkConditions(StaticAbilityMode.CantChangeDayTime) && cantChangeDayCheck(stAb, value)) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   private static boolean cantChangeDayCheck(StaticAbility stAb, Boolean value) {
      if (stAb.hasParam("NewTime")) {
         switch (stAb.getParam("NewTime")) {
            case "Day":
               if (value) {
                  return false;
               }
            case "Night":
               if (!value) {
                  return false;
               }
         }
      }

      return true;
   }
}
