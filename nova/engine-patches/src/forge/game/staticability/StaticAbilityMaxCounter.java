package forge.game.staticability;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.zone.ZoneType;

public class StaticAbilityMaxCounter {
   public static Integer maxCounter(Card c, CounterType type) {
      Game game = c.getGame();
      Integer result = null;

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.MaxCounter)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.MaxCounter) && applyMaxCounter(stAb, c, type)) {
               int value = AbilityUtils.calculateAmount(stAb.getHostCard(), stAb.getParam("MaxNum"), stAb);
               if (result == null || result > value) {
                  result = value;
               }
            }
         }
      }

      return result;
   }

   protected static boolean applyMaxCounter(StaticAbility stAb, Card c, CounterType type) {
      if (stAb.hasParam("CounterType")) {
         CounterType t = CounterType.getType(stAb.getParam("CounterType"));
         if (t != null && !type.equals(t)) {
            return false;
         }
      }

      return stAb.matchesValidParam("ValidCard", c);
   }
}
