package forge.game.staticability;

import com.google.common.collect.Table;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CardZoneTable;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import java.util.Map;
import java.util.function.Predicate;
import org.apache.commons.lang3.ArrayUtils;

public class StaticAbilityDisableTriggers {
   public static boolean disabled(Game game, Trigger regtrig, Map<AbilityKey, Object> runParams) {
      if (!StaticAbilityModeRegistry.mayExist(StaticAbilityMode.DisableTriggers)) {
         return false; // Forge Nova: no static ability of this mode exists anywhere
      }
      CardCollectionView cardList = null;
      if (regtrig.looksBackInTime()) {
         if (runParams.containsKey(AbilityKey.LastStateBattlefield)) {
            cardList = (CardCollectionView)runParams.get(AbilityKey.LastStateBattlefield);
         }

         if (cardList == null) {
            cardList = game.getLastStateBattlefield();
         }
      } else {
         cardList = game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES);
      }

      for(Card ca : cardList) {
         for(StaticAbility stAb : ca.getStaticAbilities()) {
            if (stAb.checkConditions(StaticAbilityMode.DisableTriggers) && isDisabled(stAb, regtrig, runParams)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean isDisabled(StaticAbility stAb, Trigger regtrig, Map<AbilityKey, Object> runParams) {
      TriggerType trigMode = regtrig.getMode();
      if (stAb.hasParam("ValidCard") && regtrig.getSpawningAbility() != null) {
         return false;
      } else if (!stAb.matchesValidParam("ValidCard", regtrig.getHostCard())) {
         return false;
      } else if (!stAb.matchesValidParam("ValidTrigger", regtrig.getOverridingAbility())) {
         return false;
      } else if (stAb.hasParam("ValidMode") && !ArrayUtils.contains(stAb.getParam("ValidMode").split(","), trigMode.toString())) {
         return false;
      } else {
         if (trigMode.equals(TriggerType.ChangesZone)) {
            Card moved = (Card)runParams.get(AbilityKey.Card);
            if ("Battlefield".equals(regtrig.getParam("Origin"))) {
               moved = (Card)runParams.get(AbilityKey.CardLKI);
            }

            if (!stAb.matchesValidParam("ValidCause", moved)) {
               return false;
            }

            if (!stAb.matchesValidParam("Destination", runParams.get(AbilityKey.Destination))) {
               return false;
            }

            if (!stAb.matchesValidParam("Origin", runParams.get(AbilityKey.Origin))) {
               return false;
            }

            if ("Graveyard".equals(runParams.get(AbilityKey.Destination)) && "Battlefield".equals(runParams.get(AbilityKey.Origin)) && "Card.Self".equals(regtrig.getParam("ValidCard")) && (!regtrig.hasParam("Origin") || "Any".equals(regtrig.getParam("Origin")))) {
               return false;
            }
         } else if (trigMode.equals(TriggerType.ChangesZoneAll)) {
            String origin = stAb.getParam("Origin");
            String destination = stAb.getParam("Destination");
            CardZoneTable table = (CardZoneTable)runParams.get(AbilityKey.CardsFiltered);
            if (table == null) {
               table = (CardZoneTable)runParams.get(AbilityKey.Cards);
            }

            CardZoneTable filtered = new CardZoneTable(table.getLastStateBattlefield(), table.getLastStateGraveyard());
            boolean possiblyDisabled = false;

            for(Table.Cell<ZoneType, ZoneType, CardCollection> cell : table.cellSet()) {
               CardCollection changers = (CardCollection)cell.getValue();
               if ((origin == null || cell.getRowKey() == ZoneType.valueOf(origin)) && (destination == null || cell.getColumnKey() == ZoneType.valueOf(destination))) {
                  Predicate<Card> validCause = CardPredicates.restriction((String[])stAb.getParam("ValidCause").split(","), stAb.getHostCard().getController(), stAb.getHostCard(), stAb);
                  changers = CardLists.filter(changers, validCause.negate());
                  if (changers.size() < ((CardCollection)cell.getValue()).size()) {
                     possiblyDisabled = true;
                  }
               }

               filtered.put((ZoneType)cell.getRowKey(), (ZoneType)cell.getColumnKey(), changers);
            }

            if (!possiblyDisabled) {
               return false;
            }

            Map<AbilityKey, Object> runParamsFiltered = AbilityKey.newMap(runParams);
            runParamsFiltered.put(AbilityKey.Cards, filtered);
            if (regtrig.performTest(runParamsFiltered)) {
               runParams.put(AbilityKey.CardsFiltered, filtered);
               return false;
            }
         }

         return true;
      }
   }
}
