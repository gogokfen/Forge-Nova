package forge.game.staticability;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class StaticAbilityUntapOtherPlayer {
   public static boolean untap(Card card, Player player) {
      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(card.getGame(), StaticAbilityMode.UntapOtherPlayer)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.UntapOtherPlayer) && applyUntapAbility(stAb, card, player)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyUntapAbility(StaticAbility stAb, Card card, Player player) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidPlayer", player);
      }
   }
}
