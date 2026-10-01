package forge.game.staticability;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObjectPredicates;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardDamageTable;
import forge.game.card.CardZoneTable;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.ArrayUtils;

public class StaticAbilityPanharmonicon {
   public static int handlePanharmonicon(Game game, Trigger t, Map<AbilityKey, Object> runParams) {
      int n = 0;
      if (t.isStatic() && t.getMode() != TriggerType.TapsForMana && t.getMode() != TriggerType.ManaAdded) {
         return n;
      } else if (t.getSpawningAbility() != null) {
         return n;
      } else if (!StaticAbilityModeRegistry.mayExist(StaticAbilityMode.Panharmonicon)) {
         return n; // Forge Nova: no static ability of this mode exists anywhere
      } else {
         CardCollectionView cardList = null;
         if (t.looksBackInTime()) {
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
               if (stAb.checkConditions(StaticAbilityMode.Panharmonicon)) {
                  if (t.hasParam("GameActivationLimit") && t.getActivationsThisGame() + n + 1 >= Integer.parseInt(t.getParam("GameActivationLimit")) || t.hasParam("ActivationLimit") && t.getActivationsThisTurn() + n + 1 >= Integer.parseInt(t.getParam("ActivationLimit"))) {
                     break;
                  }

                  if (applyPanharmoniconAbility(stAb, t, runParams)) {
                     ++n;
                  }
               }
            }
         }

         return n;
      }
   }

   public static boolean applyPanharmoniconAbility(StaticAbility stAb, Trigger trigger, Map<AbilityKey, Object> runParams) {
      Card host = stAb.getHostCard();
      TriggerType trigMode = trigger.getMode();
      if (!stAb.matchesValidParam("ValidCard", trigger.getHostCard())) {
         return false;
      } else if (stAb.hasParam("ValidMode") && !ArrayUtils.contains(stAb.getParam("ValidMode").split(","), trigMode.toString())) {
         return false;
      } else {
         List<ZoneType> validZones = ZoneType.listValueOf(stAb.getParamOrDefault("ValidZone", "Battlefield"));
         if (!validZones.contains(trigger.getHostCard().getZone().getZoneType())) {
            return false;
         } else {
            if (trigMode.equals(TriggerType.ChangesZone)) {
               Card moved = (Card)runParams.get(AbilityKey.Card);
               if ("Battlefield".equals(trigger.getParam("Origin"))) {
                  moved = (Card)runParams.get(AbilityKey.CardLKI);
               }

               if (!stAb.matchesValidParam("ValidCause", moved)) {
                  return false;
               }

               if (!stAb.matchesValidParam("Origin", runParams.get(AbilityKey.Origin))) {
                  return false;
               }

               if (!stAb.matchesValidParam("Destination", runParams.get(AbilityKey.Destination))) {
                  return false;
               }
            } else if (trigMode.equals(TriggerType.ChangesZoneAll)) {
               String origin = stAb.getParam("Origin");
               String destination = stAb.getParam("Destination");
               CardZoneTable table = (CardZoneTable)runParams.get(AbilityKey.CardsFiltered);
               if (table == null) {
                  table = (CardZoneTable)runParams.get(AbilityKey.Cards);
               }

               List<ZoneType> trigOrigin = null;
               List<ZoneType> trigDestination = null;
               if (trigger.hasParam("Destination") && !trigger.getParam("Destination").equals("Any")) {
                  trigDestination = ZoneType.listValueOf(trigger.getParam("Destination"));
               }

               if (trigger.hasParam("Origin") && !trigger.getParam("Origin").equals("Any")) {
                  trigOrigin = ZoneType.listValueOf(trigger.getParam("Origin"));
               }

               CardCollection causesForTrigger = table.filterCards(trigOrigin, trigDestination, trigger.getParam("ValidCards"), trigger.getHostCard(), trigger);
               CardCollection causesForStatic = table.filterCards(origin == null ? null : List.of(ZoneType.smartValueOf(origin)), destination == null ? null : ZoneType.listValueOf(destination), stAb.getParam("ValidCause"), host, stAb);
               if (Collections.disjoint(causesForTrigger, causesForStatic)) {
                  return false;
               }
            } else if (trigMode.equals(TriggerType.Attacks)) {
               if (!stAb.matchesValidParam("ValidCause", runParams.get(AbilityKey.Attacker))) {
                  return false;
               }
            } else if (!trigMode.equals(TriggerType.AttackersDeclared) && !trigMode.equals(TriggerType.AttackersDeclaredOneTarget)) {
               if (!trigMode.equals(TriggerType.SpellCastOrCopy) && !trigMode.equals(TriggerType.SpellCast) && !trigMode.equals(TriggerType.SpellCopy)) {
                  if (trigMode.equals(TriggerType.BecomesTarget)) {
                     if (!stAb.matchesValidParam("ValidTarget", runParams.get(AbilityKey.Target))) {
                        return false;
                     }
                  } else if (trigMode.equals(TriggerType.BecomesTargetOnce)) {
                     if (!stAb.matchesValidParam("ValidTarget", runParams.get(AbilityKey.Targets))) {
                        return false;
                     }
                  } else if (!trigMode.equals(TriggerType.DamageDone) && !trigMode.equals(TriggerType.DamageDoneOnce) && !trigMode.equals(TriggerType.DamageAll) && !trigMode.equals(TriggerType.DamageDealtOnce)) {
                     if (trigMode.equals(TriggerType.TurnFaceUp)) {
                        if (!stAb.matchesValidParam("ValidTurned", runParams.get(AbilityKey.Card))) {
                           return false;
                        }
                     } else if (trigMode.equals(TriggerType.LifeGained) && !stAb.matchesValidParam("ValidPlayer", runParams.get(AbilityKey.Player))) {
                        return false;
                     }
                  } else {
                     if (stAb.hasParam("CombatDamage") && stAb.getParam("CombatDamage").equalsIgnoreCase("True") != (Boolean)runParams.get(AbilityKey.IsCombatDamage)) {
                        return false;
                     }

                     if (trigMode.equals(TriggerType.DamageDone)) {
                        if (!stAb.matchesValidParam("ValidSource", runParams.get(AbilityKey.DamageSource))) {
                           return false;
                        }

                        if (!stAb.matchesValidParam("ValidTarget", runParams.get(AbilityKey.DamageTarget))) {
                           return false;
                        }
                     }

                     if (trigMode.equals(TriggerType.DamageDoneOnce)) {
                        if (!stAb.matchesValidParam("ValidTarget", runParams.get(AbilityKey.DamageTarget))) {
                           return false;
                        }

                        Map<Card, Integer> dmgMap = (Map)runParams.get(AbilityKey.DamageMap);
                        if (dmgMap.keySet().stream().noneMatch(GameObjectPredicates.matchesValidParam(stAb, "ValidSource").and(GameObjectPredicates.matchesValidParam(trigger, "ValidSource")))) {
                           return false;
                        }
                     }

                     if (trigMode.equals(TriggerType.DamageDealtOnce)) {
                        if (!stAb.matchesValidParam("ValidSource", runParams.get(AbilityKey.DamageSource))) {
                           return false;
                        }

                        Map<GameEntity, Integer> dmgMap = (Map)runParams.get(AbilityKey.DamageMap);
                        if (dmgMap.keySet().stream().noneMatch(GameObjectPredicates.matchesValidParam(stAb, "ValidTarget").and(GameObjectPredicates.matchesValidParam(trigger, "ValidTarget")))) {
                           return false;
                        }
                     }

                     if (trigMode.equals(TriggerType.DamageAll)) {
                        CardDamageTable table = (CardDamageTable)runParams.get(AbilityKey.DamageMap);
                        table = table.filteredMap(trigger.getParam("ValidSource"), trigger.getParam("ValidTarget"), trigger.getHostCard(), trigger);
                        table = table.filteredMap(stAb.getParam("ValidSource"), stAb.getParam("ValidTarget"), host, stAb);
                        if (table.isEmpty()) {
                           return false;
                        }
                     }
                  }
               } else {
                  SpellAbility sa = (SpellAbility)runParams.get(AbilityKey.SpellAbility);
                  if (!stAb.matchesValidParam("ValidCause", sa.getHostCard())) {
                     return false;
                  }

                  if (!stAb.matchesValidParam("ValidActivator", sa.getActivatingPlayer())) {
                     return false;
                  }
               }
            } else if (!stAb.matchesValidParam("ValidCause", runParams.get(AbilityKey.Attackers))) {
               return false;
            }

            return true;
         }
      }
   }
}
