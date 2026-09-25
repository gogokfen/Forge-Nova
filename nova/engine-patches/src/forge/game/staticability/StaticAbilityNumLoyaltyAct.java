package forge.game.staticability;

import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityNumLoyaltyAct {
   public static boolean limitIncrease(Card card) {
      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(card.getGame(), StaticAbilityMode.NumLoyaltyAct)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.NumLoyaltyAct) && applyLimitIncrease(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyLimitIncrease(StaticAbility stAb, Card card) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else {
         return stAb.hasParam("Twice");
      }
   }

   public static int additionalActivations(Card card, SpellAbility sa) {
      int addl = 0;

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(card.getGame(), StaticAbilityMode.NumLoyaltyAct)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.NumLoyaltyAct) && stAb.matchesValidParam("ValidCard", card) && stAb.hasParam("Additional") && (!stAb.hasParam("OnlySourceAbs") || stAb.getHostCard().getEffectSourceAbility().getRootAbility().getOriginalAbility().equals(sa))) {
               addl += AbilityUtils.calculateAmount(card, stAb.getParam("Additional"), stAb);
            }
         }
      }

      return addl;
   }
}
