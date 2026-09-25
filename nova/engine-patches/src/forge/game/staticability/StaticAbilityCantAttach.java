package forge.game.staticability;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityCantAttach {
   public static StaticAbility cantAttach(GameEntity target, Card card, boolean checkSBA) {
      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(target.getGame(), StaticAbilityMode.CantAttach)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantAttach) && applyCantAttachAbility(stAb, card, target, checkSBA)) {
               return stAb;
            }
         }
      }

      return null;
   }

   public static boolean applyCantAttachAbility(StaticAbility stAb, Card card, GameEntity target, boolean checkSBA) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else if (!stAb.matchesValidParam("Target", target)) {
         return false;
      } else {
         if (stAb.hasParam("ValidCardToTarget")) {
            if (!(target instanceof Card)) {
               return false;
            }

            Card tcard = (Card)target;
            if (!stAb.matchesValid(card, stAb.getParam("ValidCardToTarget").split(","), tcard)) {
               return false;
            }
         }

         return !checkSBA && stAb.hasParam("ExceptionSBA") || !stAb.hasParam("Exceptions") || !stAb.matchesValidParam("Exceptions", card);
      }
   }
}
