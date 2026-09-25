package forge.game.staticability;

import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityAdditionalActivations {
   public static int getLimit(Card card, SpellAbility sa, Player activator) {
      return getLimit(card, sa, activator, 1);
   }

   public static int getLimit(Card card, SpellAbility sa, Player activator, int def) {
      int result = def;
      int additional = 0;

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(card.getGame(), StaticAbilityMode.Activations)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.Activations) && isValid(stAb, card, sa, activator)) {
               if (stAb.hasParam("MinLimit")) {
                  int min = AbilityUtils.calculateAmount(stAb.getHostCard(), stAb.getParam("MinLimit"), stAb);
                  if (min == -1) {
                     return Integer.MAX_VALUE;
                  }

                  result = Math.max(result, min);
               }

               if (stAb.hasParam("Additional")) {
                  additional += AbilityUtils.calculateAmount(stAb.getHostCard(), stAb.getParam("Additional"), stAb);
               }
            }
         }
      }

      return result + additional;
   }

   public static boolean isValid(StaticAbility stAb, Card card, SpellAbility sa, Player activator) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else if (!stAb.matchesValidParam("ValidSA", sa)) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidPlayer", activator);
      }
   }
}
