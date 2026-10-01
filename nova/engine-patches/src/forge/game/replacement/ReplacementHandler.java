package forge.game.replacement;

import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameEntityCounterTable;
import forge.game.GameLogEntryType;
import forge.game.IHasSVars;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.card.CardDamageTable;
import forge.game.card.CardState;
import forge.game.card.CounterType;
import forge.game.event.GameEventAddLog;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.Localizer;
import forge.util.TextUtil;
import forge.util.Visitor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

public class ReplacementHandler {
   private final Game game;
   private Set<ReplacementEffect> hasRun = Sets.newHashSet();
   private final List<Map<ReplacementEffect, List<Map<AbilityKey, Object>>>> replaceDamageList = new ArrayList();

   public ReplacementHandler(Game gameState) {
      this.game = gameState;
   }

   public List<ReplacementEffect> getReplacementList(ReplacementType event, Map<AbilityKey, Object> runParams, ReplacementLayer layer) {
      CardCollection preList = new CardCollection();
      Card affectedLKI = null;
      Card affectedCard = null;
      if (ReplacementType.Moved.equals(event) && ZoneType.Battlefield.equals(runParams.get(AbilityKey.Destination))) {
         ReplacementEffect causeRE = (ReplacementEffect)runParams.get(AbilityKey.ReplacementEffect);
         if (causeRE != null && !causeRE.getOtherChoices().isEmpty() && ReplacementType.Moved.equals(causeRE.getMode()) && layer.equals(causeRE.getLayer())) {
            return causeRE.getOtherChoices();
         }

         affectedCard = (Card)runParams.get(AbilityKey.Affected);
         affectedCard.getReplacementEffects();
         affectedLKI = CardCopyService.getLKICopy(affectedCard);
         affectedLKI.setLastKnownZone(affectedCard.getController().getZone(ZoneType.Battlefield));
         Map<Optional<Player>, Multiset<CounterType>> etbCounters = (Map)runParams.get(AbilityKey.CounterMap);
         affectedLKI.putEtbCounters(etbCounters);
         preList.add(affectedLKI);
         this.game.getAction().checkStaticAbilities(false, Sets.newHashSet(), preList);
         runParams.put(AbilityKey.Affected, affectedLKI);
      }

      List<ReplacementEffect> possibleReplacers = Lists.newArrayList();
      final forge.game.card.NovaCardLookup novaLastState = new forge.game.card.NovaCardLookup();
      final forge.util.Visitor<Card> novaVisitor = (crd) -> this.novaVisit(crd, preList, event, runParams, layer, possibleReplacers, novaLastState);
      if (event != ReplacementType.Moved && !forge.game.card.TraitEpoch.DISABLED) {
         // Forge Nova: visit only the cards that may matter for this event, in the same order (NovaReplacementIndex)
         NovaReplacementIndex.scan(this.game, event, novaVisitor);
         if (forge.game.card.TraitEpoch.VERIFY) {
            List<ReplacementEffect> fresh = Lists.newArrayList();
            this.game.forEachCardInGame((crd) -> this.novaVisit(crd, preList, event, runParams, layer, fresh, novaLastState), false);
            if (!forge.game.card.TraitEpoch.sameElements(possibleReplacers, fresh)) {
               forge.game.card.TraitEpoch.mismatch("replacementIndex:" + event + "/" + layer, runParams.get(AbilityKey.Affected), possibleReplacers, fresh);
            }
         }
      } else {
         this.game.forEachCardInGame(novaVisitor, affectedCard != null && affectedCard.isInZone(ZoneType.Sideboard));
      }
      if (affectedLKI != null) {
         for(ReplacementEffect re : affectedLKI.getReplacementEffects()) {
            re.setHostCard(affectedCard);
         }

         affectedCard.setStoredKeywords(affectedLKI.getStoredKeywords(), true);
         affectedCard.setStoredReplacements(affectedLKI.getStoredReplacements());
         if (affectedCard.getCastSA() != null && affectedCard.getCastSA().getKeyword() != null) {
            affectedCard.addKeywordForStaticAbility(affectedCard.getCastSA().getKeyword());
         }

         runParams.put(AbilityKey.Affected, affectedCard);
         runParams.put(AbilityKey.NewCard, CardCopyService.getLKICopy(affectedLKI));
         this.game.getAction().checkStaticAbilities(false);
      }

      return possibleReplacers;
   }

   /** Forge Nova: getReplacementList's per-card scan body, unchanged; false stops the scan. */
   private boolean novaVisit(Card crd, CardCollection preList, ReplacementType event, Map<AbilityKey, Object> runParams, ReplacementLayer layer, List<ReplacementEffect> possibleReplacers, forge.game.card.NovaCardLookup novaLastState) {
      // Forge Nova: preList.get(crd) of an empty list is crd itself
      Card c = preList.isEmpty() ? crd : (Card)preList.get(crd);
      Zone cardZone = this.game.getZoneOf(c);
      if (Spell.isPerformanceMode() && (event == ReplacementType.Tap || event == ReplacementType.Untap || event == ReplacementType.ProduceMana) && cardZone != null && cardZone.getZoneType() != ZoneType.Battlefield && cardZone.getZoneType() != ZoneType.Command) {
         return true;
      } else {
         boolean noLKIstate = c != crd || event != ReplacementType.Moved || c.isImmutable() || runParams.get(AbilityKey.LastStateBattlefield) == null;
         if (!noLKIstate) {
            // Forge Nova: same element as the linear CardCollectionView.get(c), found through a map
            Card lastState = novaLastState.get((CardCollectionView)runParams.get(AbilityKey.LastStateBattlefield), c);
            if (lastState != c) {
               c = lastState;
               cardZone = lastState.getLastKnownZone();
            } else if (cardZone != null && cardZone.is(ZoneType.Battlefield)) {
               return true;
            }
         }

         for(ReplacementEffect replacementEffect : c.getReplacementEffects()) {
            if (!replacementEffect.hasRun() && !this.hasRun.contains(replacementEffect) && (layer == null || replacementEffect.getLayer() == layer) && replacementEffect.modeCheck(event, runParams) && !possibleReplacers.contains(replacementEffect) && replacementEffect.zonesCheck(cardZone) && replacementEffect.requirementsCheck(this.game) && replacementEffect.canReplace(runParams)) {
               possibleReplacers.add(replacementEffect);
               if (layer == ReplacementLayer.CantHappen) {
                  return false;
               }
            }
         }

         return true;
      }
   }

   public boolean cantHappenCheck(ReplacementType event, Map<AbilityKey, Object> runParams) {
      return !this.getReplacementList(event, runParams, ReplacementLayer.CantHappen).isEmpty();
   }

   public ReplacementResult run(ReplacementType event, Map<AbilityKey, Object> runParams) {
      Object affected = runParams.get(AbilityKey.Affected);
      Player decider;
      if (affected instanceof Player) {
         decider = (Player)affected;
      } else {
         decider = ((Card)affected).getController();
      }

      for(ReplacementLayer layer : ReplacementLayer.values()) {
         ReplacementResult res = this.run(event, runParams, layer, decider);
         if (res != ReplacementResult.NotReplaced) {
            return res;
         }
      }

      return ReplacementResult.NotReplaced;
   }

   private ReplacementResult run(ReplacementType event, Map<AbilityKey, Object> runParams, ReplacementLayer layer, Player decider) {
      List<ReplacementEffect> possibleReplacers = this.getReplacementList(event, runParams, layer);
      if (possibleReplacers.isEmpty()) {
         return ReplacementResult.NotReplaced;
      } else {
         ReplacementEffect chosenRE;
         if (layer == ReplacementLayer.CantHappen) {
            chosenRE = (ReplacementEffect)possibleReplacers.get(0);
         } else {
            chosenRE = decider.getController().chooseSingleReplacementEffect(possibleReplacers);
         }

         possibleReplacers.remove(chosenRE);
         chosenRE.setHasRun(true);
         this.hasRun.add(chosenRE);
         chosenRE.setOtherChoices(possibleReplacers);
         ReplacementResult res = this.executeReplacement(runParams, chosenRE, decider);
         if (res == ReplacementResult.NotReplaced) {
            if (!possibleReplacers.isEmpty()) {
               res = this.run(event, runParams);
            }

            chosenRE.setHasRun(false);
            this.hasRun.remove(chosenRE);
            chosenRE.setOtherChoices((List)null);
            return res;
         } else {
            String message = chosenRE.getDescription();
            if (!StringUtils.isEmpty(message)) {
               this.game.fireEvent(new GameEventAddLog(GameLogEntryType.EFFECT_REPLACED, message));
            }

            if (res == ReplacementResult.Updated) {
               Map<AbilityKey, Object> params = AbilityKey.newMap(runParams);
               params.remove(AbilityKey.ReplacementResult);
               if (params.containsKey(AbilityKey.EffectOnly)) {
                  params.put(AbilityKey.EffectOnly, true);
               }

               ReplacementResult result = this.run(event, params);
               switch (result) {
                  case NotReplaced:
                  case Updated:
                     runParams.putAll(params);
                     runParams.put(AbilityKey.ReplacementResult, ReplacementResult.Updated);
                     break;
                  default:
                     res = result;
                     runParams.put(AbilityKey.ReplacementResult, result);
               }
            }

            chosenRE.setHasRun(false);
            this.hasRun.remove(chosenRE);
            chosenRE.setOtherChoices((List)null);
            return res;
         }
      }
   }

   private ReplacementResult executeReplacement(Map<AbilityKey, Object> runParams, ReplacementEffect replacementEffect, Player decider) {
      Card host = replacementEffect.getHostCard();
      if (host.hasAlternateState() || host.isFaceDown()) {
         host = this.game.getCardState(host);
      }

      SpellAbility effectSA = replacementEffect.ensureAbility();
      if (effectSA != null) {
         SpellAbility tailend = effectSA;

         do {
            replacementEffect.setReplacingObjects(runParams, tailend);
            tailend.setReplacingObject(AbilityKey.OriginalParams, runParams);
            tailend.setReplacingObjectsFrom(runParams, AbilityKey.InternalTriggerTable, AbilityKey.SimultaneousETB);
            tailend = tailend.getSubAbility();
         } while(tailend != null);

         effectSA.setLastStateBattlefield((CardCollectionView)Objects.requireNonNullElse(runParams.get(AbilityKey.LastStateBattlefield), this.game.getLastStateBattlefield()));
         effectSA.setLastStateGraveyard((CardCollectionView)Objects.requireNonNullElse(runParams.get(AbilityKey.LastStateGraveyard), this.game.getLastStateGraveyard()));
         if (replacementEffect.isIntrinsic()) {
            effectSA.setIntrinsic(true);
            effectSA.changeText();
         }

         effectSA.setReplacementEffect(replacementEffect);
      }

      if (replacementEffect.hasParam("Optional")) {
         Player optDecider = decider;
         if (replacementEffect.hasParam("OptionalDecider")) {
            optDecider = (Player)AbilityUtils.getDefinedPlayers(host, replacementEffect.getParam("OptionalDecider"), effectSA).get(0);
         }

         String name = ((Card)Objects.requireNonNullElse(host.getRenderForUI() ? host.getCardForUi() : null, host)).getTranslatedName();
         String effectDesc = TextUtil.fastReplace(replacementEffect.getDescription(), "CARDNAME", name);
         String question = runParams.containsKey(AbilityKey.Card) ? Localizer.getInstance().getMessage("lblApplyCardReplacementEffectToCardConfirm", new Object[]{name, runParams.get(AbilityKey.Card).toString(), effectDesc}) : Localizer.getInstance().getMessage("lblApplyReplacementEffectOfCardConfirm", new Object[]{name, effectDesc});
         GameEntity affected = (GameEntity)runParams.get(AbilityKey.Affected);
         boolean confirmed = optDecider.getController().confirmReplacementEffect(replacementEffect, effectSA, affected, question);
         if (!confirmed) {
            return ReplacementResult.NotReplaced;
         }
      }

      boolean isPrevent = "True".equals(replacementEffect.getParam("Prevent"));
      if (isPrevent || replacementEffect.hasParam("PreventionEffect")) {
         if (Boolean.TRUE.equals(runParams.get(AbilityKey.NoPreventDamage))) {
            if (replacementEffect.hasParam("AlwaysReplace")) {
               runParams.put(AbilityKey.PreventedAmount, runParams.get(AbilityKey.DamageAmount));
            } else {
               runParams.put(AbilityKey.PreventedAmount, 0);
            }

            return ReplacementResult.NotReplaced;
         }

         if (isPrevent) {
            return ReplacementResult.Prevented;
         }
      }

      if ("True".equals(replacementEffect.getParam("Skip"))) {
         return ReplacementResult.Skipped;
      } else {
         Player player = host.getController();
         if (effectSA != null) {
            ApiType apiType = effectSA.getApi();
            if (replacementEffect.getMode() == ReplacementType.DamageDone && apiType != ApiType.ReplaceDamage && apiType != ApiType.ReplaceSplitDamage && apiType != ApiType.ReplaceEffect) {
               runParams.put(AbilityKey.ReplacementResult, ReplacementResult.Replaced);
            } else {
               effectSA.setActivatingPlayer(host.getController());
               player.getController().playSpellAbilityNoStack(effectSA, true);
            }

            if (apiType == ApiType.ReplaceToken || apiType == ApiType.ReplaceEffect || apiType == ApiType.ReplaceMana) {
               runParams.put(AbilityKey.ReplacementResult, ReplacementResult.Updated);
            }
         }

         if (replacementEffect.hasParam("ReplacementResult")) {
            return ReplacementResult.valueOf(replacementEffect.getParam("ReplacementResult"));
         } else {
            return runParams.containsKey(AbilityKey.ReplacementResult) ? (ReplacementResult)runParams.get(AbilityKey.ReplacementResult) : ReplacementResult.Replaced;
         }
      }
   }

   private void getPossibleReplaceDamageList(PlayerCollection players, boolean isCombat, CardDamageTable damageMap, SpellAbility cause) {
      for(Map.Entry<GameEntity, Map<Card, Integer>> et : damageMap.columnMap().entrySet()) {
         GameEntity target = (GameEntity)et.getKey();
         int playerIndex = target instanceof Player ? players.indexOf((Player)target) : players.indexOf(((Card)target).getController());
         if (playerIndex != -1) {
            Map<ReplacementEffect, List<Map<AbilityKey, Object>>> replaceCandidateMap = (Map)this.replaceDamageList.get(playerIndex);

            for(Map.Entry<Card, Integer> e : et.getValue().entrySet()) {
               Card source = (Card)e.getKey();
               Integer damage = (Integer)e.getValue();
               if (damage > 0) {
                  boolean prevention = source.canDamagePrevented(isCombat) && (cause == null || !cause.hasParam("NoPrevention"));
                  Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(target);
                  repParams.put(AbilityKey.DamageSource, source);
                  repParams.put(AbilityKey.DamageAmount, damage);
                  repParams.put(AbilityKey.IsCombat, isCombat);
                  repParams.put(AbilityKey.NoPreventDamage, !prevention);
                  if (cause != null) {
                     repParams.put(AbilityKey.Cause, cause);
                  }

                  for(ReplacementEffect re : this.getReplacementList(ReplacementType.DamageDone, repParams, ReplacementLayer.Other)) {
                     if (!replaceCandidateMap.containsKey(re)) {
                        replaceCandidateMap.put(re, new ArrayList());
                     }

                     List<Map<AbilityKey, Object>> runParamList = (List)replaceCandidateMap.get(re);
                     runParamList.add(repParams);
                  }
               }
            }
         }
      }

   }

   private void runSingleReplaceDamageEffect(ReplacementEffect re, Map<AbilityKey, Object> runParams, Map<ReplacementEffect, List<Map<AbilityKey, Object>>> replaceCandidateMap, Map<ReplacementEffect, List<Map<AbilityKey, Object>>> executedDamageMap, Player decider, CardDamageTable damageMap, CardDamageTable preventMap) {
      List<Map<AbilityKey, Object>> executedParamList = (List)executedDamageMap.get(re);
      ApiType apiType = re.getOverridingAbility() != null ? re.getOverridingAbility().getApi() : null;
      Card source = (Card)runParams.get(AbilityKey.DamageSource);
      GameEntity target = (GameEntity)runParams.get(AbilityKey.Affected);
      int damage = (Integer)runParams.get(AbilityKey.DamageAmount);
      Map<String, String> mapParams = re.getMapParams();
      ReplacementResult res = this.executeReplacement(runParams, re, decider);
      GameEntity newTarget = (GameEntity)runParams.get(AbilityKey.Affected);
      int newDamage = (Integer)runParams.get(AbilityKey.DamageAmount);
      Map<AbilityKey, Object> oldParams = null;
      if (res != ReplacementResult.NotReplaced) {
         Iterator<Map.Entry<ReplacementEffect, List<Map<AbilityKey, Object>>>> itr = replaceCandidateMap.entrySet().iterator();

         while(itr.hasNext()) {
            Map.Entry<ReplacementEffect, List<Map<AbilityKey, Object>>> entry = (Map.Entry)itr.next();
            if (entry.getKey() != re && ((List)entry.getValue()).contains(runParams)) {
               ((List)entry.getValue()).remove(runParams);
               if (((List)entry.getValue()).isEmpty()) {
                  itr.remove();
               }
            }
         }

         if (res == ReplacementResult.Updated || apiType == ApiType.ReplaceSplitDamage) {
            Map<ReplacementEffect, List<Map<AbilityKey, Object>>> newReplaceCandidateMap = replaceCandidateMap;
            if (!target.equals(newTarget)) {
               PlayerCollection players = this.game.getPlayersInTurnOrder();
               int playerIndex = newTarget instanceof Player ? players.indexOf((Player)newTarget) : players.indexOf(((Card)newTarget).getController());
               newReplaceCandidateMap = (Map)this.replaceDamageList.get(playerIndex);
            }

            for(ReplacementEffect newRE : this.getReplacementList(ReplacementType.DamageDone, runParams, ReplacementLayer.Other)) {
               if (!executedDamageMap.containsKey(newRE) || !((List)executedDamageMap.get(newRE)).contains(runParams)) {
                  if (!newReplaceCandidateMap.containsKey(newRE)) {
                     newReplaceCandidateMap.put(newRE, new ArrayList());
                  }

                  List<Map<AbilityKey, Object>> runParamList = (List)newReplaceCandidateMap.get(newRE);
                  runParamList.add(runParams);
               }
            }
         }

         if (apiType == ApiType.ReplaceSplitDamage && res == ReplacementResult.Updated) {
            oldParams = AbilityKey.newMap(runParams);
            oldParams.put(AbilityKey.Affected, target);
            oldParams.put(AbilityKey.DamageAmount, damage - newDamage);

            for(ReplacementEffect newRE : this.getReplacementList(ReplacementType.DamageDone, oldParams, ReplacementLayer.Other)) {
               if (!replaceCandidateMap.containsKey(newRE)) {
                  replaceCandidateMap.put(newRE, new ArrayList());
               }

               List<Map<AbilityKey, Object>> runParamList = (List)replaceCandidateMap.get(newRE);
               runParamList.add(oldParams);
            }
         }
      }

      Map<ReplacementEffect, ReplacementResult> resultMap = (Map)runParams.get(AbilityKey.ReplacementResultMap);
      resultMap.put(re, res);
      switch (res) {
         case NotReplaced:
            break;
         case Updated:
            if (target.equals(newTarget)) {
               damageMap.put(source, target, newDamage - damage);
            } else if (apiType == ApiType.ReplaceSplitDamage) {
               damageMap.put(source, target, -newDamage);
            }

            if (!target.equals(newTarget)) {
               if (apiType != ApiType.ReplaceSplitDamage) {
                  damageMap.remove(source, target);
               }

               damageMap.put(source, newTarget, newDamage);
            }

            if (apiType == ApiType.ReplaceDamage) {
               preventMap.put(source, target, damage - newDamage);
               runParams.put(AbilityKey.PreventedAmount, damage - newDamage);
            }
            break;
         default:
            damageMap.remove(source, target);
            if (apiType == ApiType.ReplaceDamage || mapParams.containsKey("Prevent") && ((String)mapParams.get("Prevent")).equals("True") || mapParams.containsKey("PreventionEffect")) {
               preventMap.put(source, target, damage);
               runParams.put(AbilityKey.PreventedAmount, damage);
            }

            if (apiType == ApiType.ReplaceSplitDamage) {
               damageMap.put(source, newTarget, newDamage);
            }
      }

      executedParamList.add(runParams);
      if (apiType == ApiType.ReplaceSplitDamage) {
         executedParamList.add(oldParams);
      }

      if (res != ReplacementResult.NotReplaced) {
         String message = re.getDescription();
         if (!StringUtils.isEmpty(message)) {
            this.game.fireEvent(new GameEventAddLog(GameLogEntryType.EFFECT_REPLACED, message));
         }
      }

   }

   private void executeReplaceDamageBufferedSA(Map<ReplacementEffect, List<Map<AbilityKey, Object>>> executedDamageMap) {
      Iterator var2 = executedDamageMap.entrySet().iterator();

      while(true) {
         Map.Entry<ReplacementEffect, List<Map<AbilityKey, Object>>> entry;
         ReplacementEffect re;
         SpellAbility bufferedSA;
         while(true) {
            if (!var2.hasNext()) {
               return;
            }

            entry = (Map.Entry)var2.next();
            re = (ReplacementEffect)entry.getKey();
            if (re.getOverridingAbility() != null) {
               bufferedSA = re.getOverridingAbility();
               ApiType apiType = bufferedSA.getApi();
               if (apiType != ApiType.ReplaceDamage && apiType != ApiType.ReplaceSplitDamage && apiType != ApiType.ReplaceEffect) {
                  break;
               }

               bufferedSA = bufferedSA.getSubAbility();
               if (bufferedSA != null) {
                  break;
               }
            }
         }

         List<Map<AbilityKey, Object>> executedParamList = (List)entry.getValue();
         if (!executedParamList.isEmpty()) {
            Map<String, String> mapParams = re.getMapParams();
            boolean isPrevention = mapParams.containsKey("Prevent") && ((String)mapParams.get("Prevent")).equals("True") || mapParams.containsKey("PreventionEffect");
            boolean executePerSource = mapParams.containsKey("ExecuteMode") && ((String)mapParams.get("ExecuteMode")).equals("PerSource");
            boolean executePerTarget = mapParams.containsKey("ExecuteMode") && ((String)mapParams.get("ExecuteMode")).equals("PerTarget");

            while(!executedParamList.isEmpty()) {
               Map<AbilityKey, Object> runParams = AbilityKey.newMap();
               List<Card> damageSourceList = new ArrayList();
               List<GameEntity> affectedList = new ArrayList();
               int damageSum = 0;
               Iterator<Map<AbilityKey, Object>> itr = executedParamList.iterator();

               while(itr.hasNext()) {
                  Map<AbilityKey, Object> executedParams = (Map)itr.next();
                  Map<ReplacementEffect, ReplacementResult> resultMap = (Map)executedParams.get(AbilityKey.ReplacementResultMap);
                  ReplacementResult res = (ReplacementResult)resultMap.get(re);
                  if (res != ReplacementResult.NotReplaced || isPrevention && !Boolean.FALSE.equals(executedParams.get(AbilityKey.NoPreventDamage))) {
                     Card source = (Card)executedParams.get(AbilityKey.DamageSource);
                     if (!executePerSource || damageSourceList.isEmpty() || damageSourceList.contains(source)) {
                        GameEntity target = (GameEntity)executedParams.get(AbilityKey.Affected);
                        if (!executePerTarget || affectedList.isEmpty() || affectedList.contains(target)) {
                           itr.remove();
                           int damage = (Integer)executedParams.get(isPrevention ? AbilityKey.PreventedAmount : AbilityKey.DamageAmount);
                           if (!damageSourceList.contains(source)) {
                              damageSourceList.add(source);
                           }

                           if (!affectedList.contains(target)) {
                              affectedList.add(target);
                           }

                           damageSum += damage;
                        }
                     }
                  } else {
                     itr.remove();
                  }
               }

               if (damageSum > 0) {
                  runParams.put(AbilityKey.DamageSource, damageSourceList.size() > 1 ? damageSourceList : damageSourceList.get(0));
                  runParams.put(AbilityKey.Affected, affectedList.size() > 1 ? affectedList : affectedList.get(0));
                  runParams.put(AbilityKey.DamageAmount, damageSum);
                  re.setReplacingObjects(runParams, re.getOverridingAbility());
                  bufferedSA.setActivatingPlayer(re.getHostCard().getController());
                  AbilityUtils.resolve(bufferedSA);
               }
            }
         }
      }
   }

   public void runReplaceDamage(boolean isCombat, CardDamageTable damageMap, CardDamageTable preventMap, GameEntityCounterTable counterTable, SpellAbility cause) {
      PlayerCollection players = this.game.getPlayersInTurnOrder();

      for(int i = 0; i < players.size(); ++i) {
         this.replaceDamageList.add(new HashMap());
      }

      Map<ReplacementEffect, List<Map<AbilityKey, Object>>> executedDamageMap = new HashMap();
      this.getPossibleReplaceDamageList(players, isCombat, damageMap, cause);

      while(true) {
         Player decider = null;
         Map<ReplacementEffect, List<Map<AbilityKey, Object>>> replaceCandidateMap = null;

         for(int i = 0; i < players.size(); ++i) {
            if (!((Map)this.replaceDamageList.get(i)).isEmpty()) {
               decider = (Player)players.get(i);
               replaceCandidateMap = (Map)this.replaceDamageList.get(i);
               break;
            }
         }

         if (replaceCandidateMap == null) {
            this.replaceDamageList.clear();
            this.executeReplaceDamageBufferedSA(executedDamageMap);
            return;
         }

         List<ReplacementEffect> possibleReplacers = new ArrayList(replaceCandidateMap.keySet());
         ReplacementEffect chosenRE = decider.getController().chooseSingleReplacementEffect(possibleReplacers);
         List<Map<AbilityKey, Object>> runParamList = (List)replaceCandidateMap.get(chosenRE);
         if (!executedDamageMap.containsKey(chosenRE)) {
            executedDamageMap.put(chosenRE, new ArrayList());
         }

         chosenRE.setHasRun(true);
         SpellAbility effectSA = chosenRE.getOverridingAbility();
         ApiType apiType = null;
         SpellAbility bufferedSA = effectSA;
         boolean needRestoreSubSA = false;
         boolean needDivideShield = false;
         boolean needChooseSource = false;
         int shieldAmount = 0;
         if (effectSA != null) {
            apiType = effectSA.getApi();
            if (apiType == ApiType.ReplaceDamage || apiType == ApiType.ReplaceSplitDamage || apiType == ApiType.ReplaceEffect) {
               bufferedSA = effectSA.getSubAbility();
               if (bufferedSA != null) {
                  needRestoreSubSA = true;
                  effectSA.setSubAbility((AbilitySub)null);
               }
            }

            if (chosenRE.hasParam("PreventionEffect") && chosenRE.getParam("PreventionEffect").equals("NextN") || apiType == ApiType.ReplaceSplitDamage) {
               if (apiType == ApiType.ReplaceDamage) {
                  shieldAmount = AbilityUtils.calculateAmount(effectSA.getHostCard(), effectSA.getParamOrDefault("Amount", "1"), effectSA);
               } else if (apiType == ApiType.ReplaceSplitDamage) {
                  shieldAmount = AbilityUtils.calculateAmount(effectSA.getHostCard(), effectSA.getParamOrDefault("VarName", "1"), effectSA);
               }

               int damageAmount = 0;
               boolean hasMultipleSource = false;
               boolean hasMultipleTarget = false;
               Card firstSource = null;
               GameEntity firstTarget = null;

               for(Map<AbilityKey, Object> runParams : runParamList) {
                  if (apiType != ApiType.ReplaceDamage || !Boolean.TRUE.equals(runParams.get(AbilityKey.NoPreventDamage))) {
                     damageAmount += (Integer)runParams.get(AbilityKey.DamageAmount);
                     if (firstSource == null) {
                        firstSource = (Card)runParams.get(AbilityKey.DamageSource);
                     } else if (!firstSource.equals(runParams.get(AbilityKey.DamageSource))) {
                        hasMultipleSource = true;
                     }

                     if (firstTarget == null) {
                        firstTarget = (GameEntity)runParams.get(AbilityKey.Affected);
                     } else if (!firstTarget.equals(runParams.get(AbilityKey.Affected))) {
                        hasMultipleTarget = true;
                     }
                  }
               }

               if (damageAmount > shieldAmount && runParamList.size() > 1) {
                  if (hasMultipleSource) {
                     needChooseSource = true;
                  }

                  if (effectSA.hasParam("DivideShield") && hasMultipleTarget) {
                     needDivideShield = true;
                  }
               }
            }
         }

         Map<GameEntity, Integer> shieldMap = null;
         if (needDivideShield) {
            Map<GameEntity, Integer> affected = new HashMap();

            for(Map<AbilityKey, Object> runParams : runParamList) {
               GameEntity target = (GameEntity)runParams.get(AbilityKey.Affected);
               Integer damage = (Integer)runParams.get(AbilityKey.DamageAmount);
               affected.merge(target, damage, Integer::sum);
            }

            shieldMap = decider.getController().divideShield(chosenRE.getHostCard(), affected, shieldAmount);
         }

         if (needChooseSource) {
            CardCollection sourcesToChooseFrom = new CardCollection();

            for(Map<AbilityKey, Object> runParams : runParamList) {
               if (apiType != ApiType.ReplaceDamage || !Boolean.TRUE.equals(runParams.get(AbilityKey.NoPreventDamage))) {
                  sourcesToChooseFrom.add((Card)runParams.get(AbilityKey.DamageSource));
               }
            }

            Localizer var10000 = Localizer.getInstance();
            String choiceTitle = var10000.getMessage("lblChooseSource", new Object[0]) + " ";

            while(shieldAmount > 0 && !sourcesToChooseFrom.isEmpty()) {
               Card source = (Card)decider.getController().chooseSingleEntityForEffect(sourcesToChooseFrom, effectSA, choiceTitle, (Map)null);
               sourcesToChooseFrom.remove(source);
               Iterator<Map<AbilityKey, Object>> itr = runParamList.iterator();

               while(itr.hasNext()) {
                  Map<AbilityKey, Object> runParams = (Map)itr.next();
                  if (source.equals(runParams.get(AbilityKey.DamageSource))) {
                     itr.remove();
                     if (shieldMap != null) {
                        GameEntity target = (GameEntity)runParams.get(AbilityKey.Affected);
                        if (!shieldMap.containsKey(target) || (Integer)shieldMap.get(target) <= 0) {
                           continue;
                        }

                        Integer dividedShieldAmount = (Integer)shieldMap.get(target);
                        runParams.put(AbilityKey.DividedShieldAmount, dividedShieldAmount);
                        shieldAmount -= dividedShieldAmount;
                     } else {
                        shieldAmount -= (Integer)runParams.get(AbilityKey.DamageAmount);
                     }

                     if (!runParams.containsKey(AbilityKey.ReplacementResultMap)) {
                        Map<ReplacementEffect, ReplacementResult> resultMap = new HashMap();
                        runParams.put(AbilityKey.ReplacementResultMap, resultMap);
                     }

                     this.runSingleReplaceDamageEffect(chosenRE, runParams, replaceCandidateMap, executedDamageMap, decider, damageMap, preventMap);
                  }
               }
            }
         } else {
            for(Map<AbilityKey, Object> runParams : runParamList) {
               if (shieldMap != null) {
                  GameEntity target = (GameEntity)runParams.get(AbilityKey.Affected);
                  if (!shieldMap.containsKey(target) || (Integer)shieldMap.get(target) <= 0) {
                     continue;
                  }

                  Integer dividedShieldAmount = (Integer)shieldMap.get(target);
                  runParams.put(AbilityKey.DividedShieldAmount, dividedShieldAmount);
               }

               if (!runParams.containsKey(AbilityKey.ReplacementResultMap)) {
                  Map<ReplacementEffect, ReplacementResult> resultMap = new HashMap();
                  runParams.put(AbilityKey.ReplacementResultMap, resultMap);
               }

               this.runSingleReplaceDamageEffect(chosenRE, runParams, replaceCandidateMap, executedDamageMap, decider, damageMap, preventMap);
            }
         }

         if (needRestoreSubSA) {
            effectSA.setSubAbility((AbilitySub)bufferedSA);
         }

         chosenRE.setHasRun(false);
         replaceCandidateMap.remove(chosenRE);
      }
   }

   public static ReplacementEffect parseReplacement(String repParse, Card host, boolean intrinsic) {
      return parseReplacement(repParse, host, intrinsic, host);
   }

   public static ReplacementEffect parseReplacement(String repParse, Card host, boolean intrinsic, IHasSVars sVarHolder) {
      return parseReplacement(AbilityFactory.getMapParams(repParse), host, intrinsic, sVarHolder);
   }

   private static ReplacementEffect parseReplacement(Map<String, String> mapParams, Card host, boolean intrinsic, IHasSVars sVarHolder) {
      ReplacementType rt = ReplacementType.smartValueOf((String)mapParams.get("Event"));
      ReplacementEffect ret = rt.createReplacement(mapParams, host, intrinsic);
      String activeZones = (String)mapParams.get("ActiveZones");
      if (null != activeZones) {
         ret.setActiveZone(EnumSet.copyOf(ZoneType.listValueOf(activeZones)));
      }

      if (mapParams.containsKey("ReplaceWith") && sVarHolder != null) {
         ret.setOverridingAbility(AbilityFactory.getAbility(host, (String)mapParams.get("ReplaceWith"), sVarHolder));
      }

      if (sVarHolder instanceof CardState) {
         ret.setCardState((CardState)sVarHolder);
      } else if (sVarHolder instanceof CardTraitBase) {
         ret.setCardState(((CardTraitBase)sVarHolder).getCardState());
      }

      return ret;
   }

   public boolean wouldPhaseBeSkipped(Player player, PhaseType phase) {
      Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(player);
      repParams.put(AbilityKey.Phase, phase);
      List<ReplacementEffect> list = this.getReplacementList(ReplacementType.BeginPhase, repParams, ReplacementLayer.Control);
      return !list.isEmpty();
   }

   public boolean wouldExtraTurnBeSkipped(Player player) {
      Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(player);
      repParams.put(AbilityKey.ExtraTurn, true);
      List<ReplacementEffect> list = this.getReplacementList(ReplacementType.BeginTurn, repParams, ReplacementLayer.Other);
      return !list.isEmpty();
   }

   public int getTotalPreventionShieldAmount(final GameEntity o) {
      final List<ReplacementEffect> list = Lists.newArrayList();
      this.game.forEachCardInGame(new Visitor<Card>() {
         public boolean visit(Card c) {
            for(ReplacementEffect re : c.getReplacementEffects()) {
               if (re.getMode() == ReplacementType.DamageDone && re.getLayer() == ReplacementLayer.Other && re.hasParam("PreventionEffect") && re.zonesCheck(ReplacementHandler.this.game.getZoneOf(c)) && re.getOverridingAbility() != null && re.getOverridingAbility().getApi() == ApiType.ReplaceDamage && re.matchesValidParam("ValidTarget", o)) {
                  list.add(re);
               }
            }

            return true;
         }
      });
      int totalAmount = 0;

      for(ReplacementEffect re : list) {
         SpellAbility sa = re.getOverridingAbility();
         if (sa.hasParam("Amount")) {
            String varValue = sa.getParam("Amount");
            if (StringUtils.isNumeric(varValue)) {
               totalAmount += Integer.parseInt(varValue);
            } else {
               varValue = sa.getSVar(varValue);
               if (varValue.startsWith("Number$")) {
                  totalAmount += Integer.parseInt(varValue.substring(7));
               }
            }
         }
      }

      return totalAmount;
   }

   public final boolean isPreventCombatDamageThisTurn() {
      for(Card c : this.game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
         for(ReplacementEffect re : c.getReplacementEffects()) {
            if (re.getMode() == ReplacementType.DamageDone && re.getLayer() == ReplacementLayer.Other && "True".equals(re.getParam("Prevent")) && "True".equals(re.getParam("IsCombat")) && !re.hasParam("ValidSource") && !re.hasParam("ValidTarget") && re.zonesCheck(this.game.getZoneOf(c))) {
               return true;
            }
         }
      }

      return false;
   }

   public boolean isReplacing() {
      return !this.hasRun.isEmpty();
   }
}
