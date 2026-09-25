package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityAdapt {
   public static boolean anyWithAdapt(SpellAbility sa, Card card) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CanAdapt)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CanAdapt) && applyWithAdapt(stAb, sa, card)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyWithAdapt(StaticAbility stAb, SpellAbility sa, Card card) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidSA", sa);
      }
   }
}
