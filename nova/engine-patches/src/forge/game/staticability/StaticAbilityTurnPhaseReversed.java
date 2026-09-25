package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class StaticAbilityTurnPhaseReversed {
   public static boolean isTurnReversed(Player player) {
      return anyTurnPhaseReversed(player, StaticAbilityMode.TurnReversed);
   }

   public static boolean isPhaseReversed(Player player) {
      return anyTurnPhaseReversed(player, StaticAbilityMode.PhaseReversed);
   }

   protected static boolean anyTurnPhaseReversed(Player player, StaticAbilityMode mode) {
      boolean result = false;
      Game game = player.getGame();

      for(Card ca : game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
         for(StaticAbility stAb : ca.getStaticAbilities()) {
            if (stAb.checkConditions(mode) && applyTurnPhaseReversed(stAb, player)) {
               result = !result;
            }
         }
      }

      return result;
   }

   protected static boolean applyTurnPhaseReversed(StaticAbility stAb, Player player) {
      return stAb.matchesValidParam("ValidPlayer", player);
   }
}
