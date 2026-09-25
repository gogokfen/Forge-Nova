package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityCantExile {
   public static boolean cantExile(Card card, SpellAbility cause, boolean effect) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantExile)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantExile) && applyCantExileAbility(stAb, card, cause, effect)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCantExileAbility(StaticAbility stAb, Card card, SpellAbility cause, boolean effect) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else if (stAb.hasParam("ForCost") && "True".equalsIgnoreCase(stAb.getParam("ForCost")) == effect) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidCause", cause);
      }
   }
}
