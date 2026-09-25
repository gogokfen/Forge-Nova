package forge.game.staticability;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;

public class StaticAbilityCantPreventDamage {
   public static boolean cantPreventDamage(Card source, boolean isCombat) {
      // Forge Nova: indexed static zones, then the source if it is not one of those cards
      for(StaticAbility stAb : StaticAbilityIndex.forMode(source.getGame(), StaticAbilityMode.CantPreventDamage)) {
         if (stAb.checkConditions(StaticAbilityMode.CantPreventDamage) && applyCantPreventDamage(stAb, source, isCombat)) {
            return true;
         }
      }

      if (!StaticAbilityIndex.containsCard(source.getGame(), source)) {
         for(StaticAbility stAb : source.getStaticAbilities()) {
            if (stAb.checkConditions(StaticAbilityMode.CantPreventDamage) && applyCantPreventDamage(stAb, source, isCombat)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCantPreventDamage(StaticAbility stAb, Card source, boolean isCombat) {
      if (stAb.hasParam("IsCombat") && stAb.getParam("IsCombat").equals("True") != isCombat) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidSource", source);
      }
   }
}
