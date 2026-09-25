package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityCantDiscard {
   public static boolean cantDiscard(Player player, SpellAbility cause, boolean effect) {
      Game game = player.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantDiscard)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantDiscard) && applyCantDiscardAbility(stAb, player, cause, effect)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCantDiscardAbility(StaticAbility stAb, Player player, SpellAbility cause, boolean effect) {
      if (!stAb.matchesValidParam("ValidPlayer", player)) {
         return false;
      } else if (stAb.hasParam("ForCost") && "True".equalsIgnoreCase(stAb.getParam("ForCost")) == effect) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidCause", cause);
      }
   }
}
