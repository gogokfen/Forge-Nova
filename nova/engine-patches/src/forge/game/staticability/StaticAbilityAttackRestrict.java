package forge.game.staticability;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityAttackRestrict {
   public static Integer globalAttackRestrict(Game game) {
      Integer max = null;

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.AttackRestrict)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.AttackRestrict) && !stAb.hasParam("ValidDefender")) {
               int stMax = AbilityUtils.calculateAmount(stAb.getHostCard(), stAb.getParamOrDefault("MaxAttackers", "1"), stAb);
               if (null == max || stMax < max) {
                  max = stMax;
               }
            }
         }
      }

      return max;
   }

   public static Integer attackRestrictNum(GameEntity defender) {
      Game game = defender.getGame();
      Integer num = null;

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.AttackRestrict)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.AttackRestrict) && stAb.hasParam("ValidDefender") && attackRestrict(stAb, defender)) {
               int stNum = AbilityUtils.calculateAmount(stAb.getHostCard(), stAb.getParamOrDefault("MaxAttackers", "1"), stAb);
               if (null == num || stNum < num) {
                  num = stNum;
               }
            }
         }
      }

      return num;
   }

   public static boolean attackRestrict(StaticAbility stAb, GameEntity defender) {
      return stAb.matchesValidParam("ValidDefender", defender);
   }
}
