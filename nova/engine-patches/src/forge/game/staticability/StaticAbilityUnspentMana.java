package forge.game.staticability;

import com.google.common.collect.Sets;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import java.util.Collection;
import java.util.Set;

public class StaticAbilityUnspentMana {
   public static Collection<Byte> getManaToKeep(Player player) {
      Game game = player.getGame();
      Set<Byte> result = Sets.newHashSet();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.UnspentMana)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.UnspentMana)) {
               applyUnspentManaAbility(stAb, player, result);
            }
         }
      }

      return result;
   }

   public static boolean hasManaBurn(Player player) {
      Game game = player.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.ManaBurn)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.ManaBurn)) {
               if (!stAb.matchesValidParam("ValidPlayer", player)) {
                  return false;
               }

               return true;
            }
         }
      }

      return false;
   }

   private static void applyUnspentManaAbility(StaticAbility stAb, Player player, Set<Byte> result) {
      if (stAb.matchesValidParam("ValidPlayer", player)) {
         if (!stAb.hasParam("ManaType")) {
            for(byte b : ManaAtom.MANATYPES) {
               result.add(b);
            }
         } else {
            result.add(MagicColor.fromName(stAb.getParam("ManaType")));
         }

      }
   }
}
