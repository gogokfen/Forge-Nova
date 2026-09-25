package forge.game.staticability;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class StaticAbilityBlockRestrict {
   public static int blockRestrictNum(Player defender) {
      Game game = defender.getGame();
      int num = Integer.MAX_VALUE;

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.BlockRestrict)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.BlockRestrict) && blockRestrict(stAb, defender)) {
               int stNum = AbilityUtils.calculateAmount(stAb.getHostCard(), stAb.getParamOrDefault("MaxBlockers", "1"), stAb);
               if (stNum < num) {
                  num = stNum;
               }
            }
         }
      }

      return num;
   }

   public static boolean blockRestrict(StaticAbility stAb, Player defender) {
      return stAb.matchesValidParam("ValidDefender", defender);
   }
}
