package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class StaticAbilityDevotion {
   public static int getDevotionMod(Player player) {
      int i = 0;
      Game game = player.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.Devotion)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.Devotion) && stAb.matchesValidParam("ValidPlayer", player)) {
               int t = Integer.parseInt(stAb.getParamOrDefault("Value", "1"));
               i += t;
            }
         }
      }

      return i;
   }
}
