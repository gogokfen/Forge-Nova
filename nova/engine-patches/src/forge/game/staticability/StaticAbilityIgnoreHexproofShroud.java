package forge.game.staticability;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityIgnoreHexproofShroud {
   public static boolean ignore(GameEntity entity, SpellAbility spellAbility, StaticAbility keyword) {
      // Forge Nova: for a hexproof (shroud) keyword only IgnoreHexproof (IgnoreShroud) statics can match
      if (keyword.isKeyword(Keyword.HEXPROOF) && !StaticAbilityModeRegistry.mayExist(StaticAbilityMode.IgnoreHexproof)
            || keyword.isKeyword(Keyword.SHROUD) && !StaticAbilityModeRegistry.mayExist(StaticAbilityMode.IgnoreShroud)) {
         return false;
      }
      Game game = entity.getGame();

      for(Card ca : game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
         for(StaticAbility stAb : ca.getStaticAbilities()) {
            if ((!keyword.isKeyword(Keyword.HEXPROOF) || stAb.checkConditions(StaticAbilityMode.IgnoreHexproof)) && (!keyword.isKeyword(Keyword.SHROUD) || stAb.checkConditions(StaticAbilityMode.IgnoreShroud)) && commonAbility(stAb, entity, spellAbility)) {
               return true;
            }
         }
      }

      return false;
   }

   protected static boolean commonAbility(StaticAbility stAb, GameEntity entity, SpellAbility spellAbility) {
      Player activator = spellAbility.getActivatingPlayer();
      if (!stAb.matchesValidParam("Activator", activator)) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidEntity", entity);
      }
   }
}
