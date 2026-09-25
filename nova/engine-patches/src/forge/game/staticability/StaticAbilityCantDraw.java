package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class StaticAbilityCantDraw {
   public static boolean canDrawThisAmount(Player player, int startAmount) {
      if (startAmount <= 0) {
         return true;
      } else {
         return startAmount <= canDrawAmount(player, startAmount);
      }
   }

   public static int canDrawAmount(Player player, int startAmount) {
      int amount = startAmount;
      if (startAmount <= 0) {
         return 0;
      } else {
         Game game = player.getGame();

         for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantDraw)) { // Forge Nova: indexed scan
            {
               if (stAb.checkConditions(StaticAbilityMode.CantDraw)) {
                  amount = applyCantDrawAmountAbility(stAb, player, amount);
               }
            }
         }

         return amount;
      }
   }

   public static int applyCantDrawAmountAbility(StaticAbility stAb, Player player, int amount) {
      if (!stAb.matchesValidParam("ValidPlayer", player)) {
         return amount;
      } else {
         int limit = Integer.parseInt(stAb.getParamOrDefault("DrawLimit", "0"));
         int drawn = player.getNumDrawnThisTurn();
         return Math.min(Math.max(limit - drawn, 0), amount);
      }
   }
}
