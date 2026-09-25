package forge.game.staticability;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.mana.ManaConversionMatrix;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityManaConvert {
   public static boolean manaConvert(ManaConversionMatrix matrix, Player p, Card card, SpellAbility sa) {
      Game game = p.getGame();
      boolean changed = false;

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.ManaConvert)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.ManaConvert) && checkManaConvert(stAb, p, card, sa)) {
               AbilityUtils.applyManaColorConversion(matrix, stAb.getParam("ManaConversion"));
               changed = true;
            }
         }
      }

      return changed;
   }

   public static boolean checkManaConvert(StaticAbility stAb, Player p, Card card, SpellAbility sa) {
      if (!stAb.matchesValidParam("ValidPlayer", p)) {
         return false;
      } else if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else if (!stAb.matchesValidParam("ValidSA", sa)) {
         return false;
      } else {
         if (stAb.hasParam("Optional")) {
            stAb.getHostCard().clearRemembered();
            if (!p.getController().confirmStaticApplication(card, (PlayerActionConfirmMode)null, "Do you want to spend mana as though it were mana of any type to pay the cost?", (String)null)) {
               return false;
            }

            stAb.getHostCard().addRemembered(sa.getHostCard());
         }

         return true;
      }
   }
}
