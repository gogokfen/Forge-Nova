package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class StaticAbilityGainLifeRadiation {
   public static boolean gainLifeRadiation(Player player) {
      Game game = player.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.GainLifeRadiation)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.GainLifeRadiation) && applyGainLifeRadiation(stAb, player)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyGainLifeRadiation(StaticAbility stAb, Player player) {
      return stAb.matchesValidParam("ValidPlayer", player);
   }
}
