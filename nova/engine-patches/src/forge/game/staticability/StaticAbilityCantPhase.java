package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityCantPhase {
   public static boolean cantPhaseIn(Card card) {
      return cantPhase(card, StaticAbilityMode.CantPhaseIn);
   }

   public static boolean cantPhaseOut(Card card) {
      return cantPhase(card, StaticAbilityMode.CantPhaseOut);
   }

   private static boolean cantPhase(Card card, StaticAbilityMode mode) {
      Game game = card.getGame();

      for(Card ca : game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
         for(StaticAbility stAb : ca.getStaticAbilities()) {
            if (stAb.checkConditions(mode) && applyCantPhase(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean applyCantPhase(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
