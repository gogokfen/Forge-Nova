package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityMustBlock {
   public static boolean blocksEachCombatIfAble(Card creature) {
      Game game = creature.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.MustBlock)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.MustBlock) && applyBlocksEachCombatIfAble(stAb, creature)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyBlocksEachCombatIfAble(StaticAbility stAb, Card creature) {
      return stAb.matchesValidParam("ValidCreature", creature);
   }
}
