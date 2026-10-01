package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityCantGainLosePayLife {
   public static boolean anyCantGainLife(Player player) {
      if (!StaticAbilityModeRegistry.mayExist(StaticAbilityMode.CantGainLife, StaticAbilityMode.CantChangeLife)) {
         return false; // Forge Nova: no static ability of these modes exists anywhere
      }
      Game game = player.getGame();

      for(Card ca : game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
         for(StaticAbility stAb : ca.getStaticAbilities()) {
            if ((stAb.checkMode(StaticAbilityMode.CantGainLife) || stAb.checkMode(StaticAbilityMode.CantChangeLife)) && stAb.checkConditions() && applyCommonAbility(stAb, player)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean anyCantLoseLife(Player player) {
      if (!StaticAbilityModeRegistry.mayExist(StaticAbilityMode.CantLoseLife, StaticAbilityMode.CantChangeLife)) {
         return false; // Forge Nova: no static ability of these modes exists anywhere
      }
      Game game = player.getGame();

      for(Card ca : game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
         for(StaticAbility stAb : ca.getStaticAbilities()) {
            if ((stAb.checkMode(StaticAbilityMode.CantLoseLife) || stAb.checkMode(StaticAbilityMode.CantChangeLife)) && stAb.checkConditions() && applyCommonAbility(stAb, player)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean anyCantPayLife(Player player, boolean effect, SpellAbility cause) {
      if (!StaticAbilityModeRegistry.mayExist(StaticAbilityMode.CantPayLife, StaticAbilityMode.CantLoseLife, StaticAbilityMode.CantChangeLife)) {
         return false; // Forge Nova: no static ability of these modes exists anywhere
      }
      Game game = player.getGame();

      for(Card ca : game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
         for(StaticAbility stAb : ca.getStaticAbilities()) {
            if ((stAb.checkMode(StaticAbilityMode.CantPayLife) || stAb.checkMode(StaticAbilityMode.CantLoseLife) || stAb.checkMode(StaticAbilityMode.CantChangeLife)) && stAb.checkConditions() && (!stAb.hasParam("ForCost") || "True".equalsIgnoreCase(stAb.getParam("ForCost")) != effect) && stAb.matchesValidParam("ValidCause", cause) && applyCommonAbility(stAb, player)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCommonAbility(StaticAbility stAb, Player player) {
      return stAb.matchesValidParam("ValidPlayer", player);
   }
}
