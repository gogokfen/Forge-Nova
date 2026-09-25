package forge.game.staticability;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;

public class StaticAbilityAssignNoCombatDamage {
   public static boolean assignNoCombatDamage(Card card) {
      // Forge Nova: indexed static zones, then the card itself if it is not one of those cards
      for(StaticAbility stAb : StaticAbilityIndex.forMode(card.getGame(), StaticAbilityMode.AssignNoCombatDamage)) {
         if (stAb.checkConditions(StaticAbilityMode.AssignNoCombatDamage) && applyAssignNoCombatDamage(stAb, card)) {
            return true;
         }
      }

      if (!StaticAbilityIndex.containsCard(card.getGame(), card)) {
         for(StaticAbility stAb : card.getStaticAbilities()) {
            if (stAb.checkConditions(StaticAbilityMode.AssignNoCombatDamage) && applyAssignNoCombatDamage(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyAssignNoCombatDamage(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
