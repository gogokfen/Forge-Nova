package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityCombatDamageToughness {
   public static boolean combatDamageToughness(Card card) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CombatDamageToughness)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CombatDamageToughness) && applyCombatDamageToughnessAbility(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCombatDamageToughnessAbility(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
