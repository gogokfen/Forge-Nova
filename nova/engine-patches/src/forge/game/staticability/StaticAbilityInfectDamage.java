package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class StaticAbilityInfectDamage {
   public static boolean isInfectDamage(Player target) {
      Game game = target.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.InfectDamage)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.InfectDamage) && applyInfectDamageAbility(stAb, target)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyInfectDamageAbility(StaticAbility stAb, Player target) {
      return stAb.matchesValidParam("ValidTarget", target);
   }
}
