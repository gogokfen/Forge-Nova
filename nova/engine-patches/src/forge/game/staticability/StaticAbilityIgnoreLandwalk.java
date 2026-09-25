package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.keyword.KeywordInterface;
import forge.game.zone.ZoneType;

public class StaticAbilityIgnoreLandwalk {
   public static boolean ignoreLandWalk(Card attacker, Card blocker, KeywordInterface k) {
      Game game = attacker.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.IgnoreLandwalk)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.IgnoreLandwalk) && ignoreLandWalkAbility(stAb, attacker, blocker, k)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean ignoreLandWalkAbility(StaticAbility stAb, Card attacker, Card blocker, KeywordInterface k) {
      if (!stAb.matchesValidParam("ValidAttacker", attacker)) {
         return false;
      } else if (!stAb.matchesValidParam("ValidBlocker", blocker)) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidKeyword", k.getOriginal());
      }
   }
}
