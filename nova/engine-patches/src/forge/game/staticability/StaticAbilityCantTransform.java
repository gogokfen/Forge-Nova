package forge.game.staticability;

import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityCantTransform {
   public static boolean cantTransform(Card card, CardTraitBase cause) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantTransform)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantTransform) && applyCantTransformAbility(stAb, card, cause)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCantTransformAbility(StaticAbility stAb, Card card, CardTraitBase cause) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else {
         return !stAb.hasParam("ExceptCause") || !stAb.matchesValidParam("ExceptCause", cause);
      }
   }
}
