package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityIgnoreLegendRule {
   public static boolean ignoreLegendRule(Card card) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.IgnoreLegendRule)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.IgnoreLegendRule) && applyIgnoreLegendRuleAbility(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean applyIgnoreLegendRuleAbility(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
