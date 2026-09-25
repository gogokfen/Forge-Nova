package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class StaticAbilityCantBecomeMonarch {
   public static boolean anyCantBecomeMonarch(Player player) {
      Game game = player.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantBecomeMonarch)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantBecomeMonarch) && applyCantBecomeMonarchAbility(stAb, player)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean applyCantBecomeMonarchAbility(StaticAbility stAb, Player player) {
      return stAb.matchesValidParam("ValidPlayer", player);
   }
}
