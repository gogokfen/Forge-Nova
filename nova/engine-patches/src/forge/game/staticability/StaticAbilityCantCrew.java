package forge.game.staticability;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;

public class StaticAbilityCantCrew {
   public static boolean cantCrew(Card card) {
      if (!StaticAbilityModeRegistry.mayExist(StaticAbilityMode.CantCrew)) {
         return false; // Forge Nova: no static ability of this mode exists anywhere
      }
      CardCollection list = new CardCollection(card.getGame().getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES));
      list.add(card);

      for(Card ca : list) {
         for(StaticAbility stAb : ca.getStaticAbilities()) {
            if (stAb.checkConditions(StaticAbilityMode.CantCrew) && applyCantCrew(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCantCrew(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
