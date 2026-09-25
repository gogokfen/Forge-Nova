package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityPlotZone {
   public static boolean plotZone(Card card) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.PlotZone)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.PlotZone) && applyPlotZoneAbility(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean applyPlotZoneAbility(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
