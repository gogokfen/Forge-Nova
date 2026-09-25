package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class StaticAbilityCantPutCounter {
   public static boolean anyCantPutCounter(Card card, CounterType type) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantPutCounter)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantPutCounter) && applyCantPutCounter(stAb, card, type)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean anyCantPutCounter(Player player, CounterType type) {
      Game game = player.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantPutCounter)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantPutCounter) && applyCantPutCounter(stAb, player, type)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCantPutCounter(StaticAbility stAb, Card card, CounterType type) {
      if (stAb.hasParam("CounterType")) {
         CounterType t = CounterType.getType(stAb.getParam("CounterType"));
         if (t != null && !type.equals(t)) {
            return false;
         }
      }

      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else {
         return !stAb.hasParam("ValidPlayer");
      }
   }

   public static boolean applyCantPutCounter(StaticAbility stAb, Player player, CounterType type) {
      if (stAb.hasParam("CounterType")) {
         CounterType t = CounterType.getType(stAb.getParam("CounterType"));
         if (t != null && !type.equals(t)) {
            return false;
         }
      }

      if (!stAb.matchesValidParam("ValidPlayer", player)) {
         return false;
      } else {
         return !stAb.hasParam("ValidCard");
      }
   }
}
