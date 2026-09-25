package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityWitherDamage {
   public static boolean isWitherDamage(Card source) {
      Game game = source.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.WitherDamage)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.WitherDamage) && applyWitherDamageAbility(stAb, source)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyWitherDamageAbility(StaticAbility stAb, Card source) {
      return stAb.matchesValidParam("ValidCard", source);
   }
}
