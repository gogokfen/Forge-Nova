package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.zone.ZoneType;

public class StaticAbilitySurveilNum {
   public static int surveilNumMod(Player p) {
      Game game = p.getGame();
      int mod = 0;

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.SurveilNum)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.SurveilNum)) {
               mod += getSurveilMod(stAb, p);
            }
         }
      }

      return mod;
   }

   public static int getSurveilMod(StaticAbility stAb, Player p) {
      if (!stAb.matchesValidParam("ValidPlayer", p)) {
         return 0;
      } else {
         return stAb.hasParam("Optional") && !p.getController().confirmStaticApplication(stAb.getHostCard(), (PlayerActionConfirmMode)null, stAb.toString() + "?", (String)null) ? 0 : Integer.parseInt(stAb.getParam("Num"));
      }
   }
}
