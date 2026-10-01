package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

public class StaticAbilityCountersRemain {
   public static boolean countersRemain(Card card, Zone zone) {
      if (zone != null && !zone.getZoneType().isHidden()) {
         if (!StaticAbilityModeRegistry.mayExist(StaticAbilityMode.CountersRemain)) {
            return false; // Forge Nova: no static ability of this mode exists anywhere
         }
         Game game = card.getGame();
         CardCollection allp = new CardCollection(game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES));
         allp.add(card);

         for(Card ca : allp) {
            for(StaticAbility stAb : ca.getStaticAbilities()) {
               if (stAb.checkConditions(StaticAbilityMode.CountersRemain) && applyCountersRemainAbility(stAb, card)) {
                  return true;
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static boolean applyCountersRemainAbility(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
