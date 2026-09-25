package forge.game.staticability;

import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityTapPowerValue {
   public static boolean withToughness(Card card, CardTraitBase ctb) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.TapPowerValue)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.TapPowerValue) && withToughness(stAb, card, ctb)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean withToughness(StaticAbility stAb, Card card, CardTraitBase ctb) {
      if (!stAb.getParam("Value").equals("Toughness")) {
         return false;
      } else if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidSA", ctb);
      }
   }

   public static int getMod(Card card, CardTraitBase ctb) {
      int i = 0;
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.TapPowerValue)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.TapPowerValue) && stAb.matchesValidParam("ValidCard", card) && stAb.matchesValidParam("ValidSA", ctb)) {
               i += Integer.parseInt(stAb.getParam("Value"));
            }
         }
      }

      return i;
   }
}
