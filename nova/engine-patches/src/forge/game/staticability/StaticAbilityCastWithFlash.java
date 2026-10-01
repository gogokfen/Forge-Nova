package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityCastWithFlash {
   public static boolean anyWithFlashNeedsInfo(SpellAbility sa, Card card, Player activator) {
      if (!StaticAbilityModeRegistry.mayExist(StaticAbilityMode.CastWithFlash)) {
         return false; // Forge Nova: no static ability of this mode exists anywhere
      }
      Game game = activator.getGame();
      // Forge Nova: the static ability zones' cards in order, then the card unless it is one of them; the index
      // lists the zone cards' CastWithFlash abilities in that order
      for(StaticAbility stAb : StaticAbilityIndex.forMode(game, StaticAbilityMode.CastWithFlash)) {
         if (stAb.checkConditions(StaticAbilityMode.CastWithFlash) && applyWithFlashNeedsInfo(stAb, sa, card, activator)) {
            return true;
         }
      }

      if (!StaticAbilityIndex.containsCard(game, card)) {
         for(StaticAbility stAb : card.getStaticAbilities()) {
            if (stAb.checkConditions(StaticAbilityMode.CastWithFlash) && applyWithFlashNeedsInfo(stAb, sa, card, activator)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean anyWithFlash(SpellAbility sa, Card card, Player activator) {
      if (!StaticAbilityModeRegistry.mayExist(StaticAbilityMode.CastWithFlash)) {
         return false; // Forge Nova: no static ability of this mode exists anywhere
      }
      Game game = activator.getGame();
      // Forge Nova: as in anyWithFlashNeedsInfo
      for(StaticAbility stAb : StaticAbilityIndex.forMode(game, StaticAbilityMode.CastWithFlash)) {
         if (stAb.checkConditions(StaticAbilityMode.CastWithFlash) && applyWithFlashAbility(stAb, sa, card, activator)) {
            return true;
         }
      }

      if (!StaticAbilityIndex.containsCard(game, card)) {
         for(StaticAbility stAb : card.getStaticAbilities()) {
            if (stAb.checkConditions(StaticAbilityMode.CastWithFlash) && applyWithFlashAbility(stAb, sa, card, activator)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean commonParts(StaticAbility stAb, SpellAbility sa, Card card, Player activator, boolean skipValidSA) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else if (!skipValidSA && !stAb.matchesValidParam("ValidSA", sa)) {
         return false;
      } else {
         return stAb.matchesValidParam("Caster", activator);
      }
   }

   public static boolean applyWithFlashNeedsInfo(StaticAbility stAb, SpellAbility sa, Card card, Player activator) {
      boolean info = false;
      String validSA = stAb.getParamOrDefault("ValidSA", "");
      if (validSA.contains("IsTargeting") || validSA.contains("XCost")) {
         info = true;
      }

      return !commonParts(stAb, sa, card, activator, info) ? false : info;
   }

   public static boolean applyWithFlashAbility(StaticAbility stAb, SpellAbility sa, Card card, Player activator) {
      return commonParts(stAb, sa, card, activator, false);
   }
}
