package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class StaticAbilityCantVenture {
   public static boolean cantVenture(Player player) {
      Game game = player.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantVenture)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantVenture) && applyCantVentureAbility(stAb, player)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCantVentureAbility(StaticAbility stAb, Player player) {
      return stAb.matchesValidParam("ValidPlayer", player);
   }
}
