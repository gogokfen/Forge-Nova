package forge.game.trigger;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import forge.game.CardTraitBase;
import forge.game.CardTraitPredicates;
import forge.game.Game;
import forge.game.IHasSVars;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardState;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityDisableTriggers;
import forge.game.staticability.StaticAbilityPanharmonicon;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.FileSection;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class TriggerHandler {
   private final Set<TriggerType> suppressedModes = EnumSet.noneOf(TriggerType.class);
   private boolean allSuppressed = false;
   private final List<Trigger> activeTriggers = new ArrayList();
   private final List<Trigger> delayedTriggers = new ArrayList();
   private final List<Trigger> thisTurnDelayedTriggers = new ArrayList();
   private final ListMultimap<Player, Trigger> playerDefinedDelayedTriggers = ArrayListMultimap.create();
   private final List<TriggerWaiting> waitingTriggers = new ArrayList();
   private final Game game;

   public TriggerHandler(Game gameState) {
      this.game = gameState;
   }

   public final void registerDelayedTrigger(Trigger trig) {
      this.delayedTriggers.add(trig);
   }

   public final void clearDelayedTrigger() {
      this.delayedTriggers.clear();
   }

   public final void registerThisTurnDelayedTrigger(Trigger trig) {
      this.thisTurnDelayedTriggers.add(trig);
      this.delayedTriggers.add(trig);
   }

   public final void clearThisTurnDelayedTrigger() {
      this.delayedTriggers.removeAll(this.thisTurnDelayedTriggers);
      this.thisTurnDelayedTriggers.clear();
   }

   public final void clearDelayedTrigger(Card card) {
      for(Trigger trigger : new ArrayList<Trigger>(this.delayedTriggers)) {
         if (trigger.getHostCard().equals(card)) {
            this.delayedTriggers.remove(trigger);
         }
      }

   }

   public final void registerPlayerDefinedDelayedTrigger(Player player, Trigger trig) {
      this.playerDefinedDelayedTriggers.put(player, trig);
   }

   public final void clearPlayerDefinedDelayedTrigger() {
      this.playerDefinedDelayedTriggers.clear();
   }

   public final void handlePlayerDefinedDelTriggers(Player player) {
      List<Trigger> playerTriggers = this.playerDefinedDelayedTriggers.removeAll(player);
      Stream var10000 = playerTriggers.stream().filter(CardTraitPredicates.hasParam("ThisTurn"));
      List var10001 = this.thisTurnDelayedTriggers;
      Objects.requireNonNull(var10001);
      var10000.forEach(var10001::add);
      this.delayedTriggers.addAll(playerTriggers);
   }

   public final void suppressMode(TriggerType mode) {
      this.suppressedModes.add(mode);
   }

   public final void setSuppressAllTriggers(boolean suppress) {
      this.allSuppressed = suppress;
   }

   public final void clearSuppression(TriggerType mode) {
      this.suppressedModes.remove(mode);
   }

   public boolean isTriggerSuppressed(TriggerType mode) {
      return this.allSuppressed || this.suppressedModes.contains(mode);
   }

   public static Trigger parseTrigger(String trigParse, Card host, boolean intrinsic) {
      return parseTrigger(trigParse, host, intrinsic, host.getCurrentState());
   }

   public static Trigger parseTrigger(String trigParse, Card host, boolean intrinsic, IHasSVars sVarHolder) {
      try {
         Map<String, String> mapParams = parseParams(trigParse);
         return parseTrigger(mapParams, host, intrinsic, sVarHolder);
      } catch (Exception e) {
         String msg = "TriggerHandler:parseTrigger failed to parse";
         Breadcrumb bread = new Breadcrumb(msg);
         bread.setData("Card", host.getName());
         bread.setData("Trigger", trigParse);
         Sentry.addBreadcrumb(bread);
         throw new RuntimeException("Error in Trigger for Card: " + host.getName(), e);
      }
   }

   public static Trigger parseTrigger(Map<String, String> mapParams, Card host, boolean intrinsic, IHasSVars sVarHolder) {
      try {
         TriggerType type = TriggerType.smartValueOf((String)mapParams.get("Mode"));
         Trigger result = type.createTrigger(mapParams, host, intrinsic);
         if (sVarHolder != null) {
            result.ensureAbility(sVarHolder);
            if (sVarHolder instanceof CardState) {
               result.setCardState((CardState)sVarHolder);
            } else if (sVarHolder instanceof CardTraitBase) {
               result.setCardState(((CardTraitBase)sVarHolder).getCardState());
            }
         }

         return result;
      } catch (Exception e) {
         String msg = "TriggerHandler:parseTrigger failed to parse";
         Breadcrumb bread = new Breadcrumb(msg);
         bread.setData("Card", host.getName());
         bread.setData("Params", mapParams.toString());
         Sentry.addBreadcrumb(bread);
         throw new RuntimeException("Error in Trigger for Card: " + host.getName(), e);
      }
   }

   private static Map<String, String> parseParams(String trigParse) {
      if (trigParse.length() == 0) {
         throw new RuntimeException("TriggerFactory : registerTrigger -- trigParse too short");
      } else {
         return FileSection.parseToMap(trigParse, FileSection.DOLLAR_SIGN_KV_SEPARATOR);
      }
   }

   public void collectTriggerForWaiting() {
      for(TriggerWaiting wt : this.waitingTriggers) {
         if (wt.getTriggers() == null) {
            wt.setTriggers(this.getActiveTrigger(wt.getMode(), wt.getParams()));
         }
      }

   }

   public final void resetActiveTriggers() {
      this.resetActiveTriggers(true, (CardCollectionView)null);
   }

   public final void resetActiveTriggers(boolean collect, CardCollectionView lastStateBattlefield) {
      if (collect) {
         this.collectTriggerForWaiting();
      }

      this.activeTriggers.clear();
      // Forge Nova: while this pass runs, activeTriggers holds exactly the triggers it added, so isTriggerActive's
      // final "same id already active?" loop over activeTriggers is a lookup in their ids (quadratic before)
      final Set<Integer> novaIds = forge.game.card.TraitEpoch.DISABLED ? null : new java.util.HashSet<Integer>();
      this.game.forEachCardInGame((c) -> {
         for(Trigger t : c.getTriggers()) {
            if ((!c.isInPlay() || lastStateBattlefield == null || lastStateBattlefield.contains(c) || !t.looksBackInTime()) && (novaIds == null ? this.isTriggerActive(t) : this.novaIsTriggerActive(t, novaIds))) {
               this.activeTriggers.add(t);
               if (novaIds != null) {
                  novaIds.add(t.getId());
               }
            }
         }

         return true;
      });
   }

   /** Forge Nova: isTriggerActive for a pass whose added trigger ids are activeIds (same tests, same order). */
   private boolean novaIsTriggerActive(Trigger regtrig, Set<Integer> activeIds) {
      boolean result;
      if (!regtrig.phasesCheck(this.game)) {
         result = false;
      } else if (regtrig.isSuppressed()) {
         result = false;
      } else if (TriggerType.Always.equals(regtrig.getMode()) && this.game.getStack().hasStateTrigger(regtrig.getId())) {
         result = false;
      } else if (regtrig.getSpawningAbility() == null && !regtrig.zonesCheck(this.game.getZoneOf(regtrig.getHostCard()))) {
         result = false;
      } else {
         result = !activeIds.contains(regtrig.getId());
      }
      if (forge.game.card.TraitEpoch.VERIFY) {
         boolean fresh = this.isTriggerActive(regtrig);
         if (fresh != result) {
            forge.game.card.TraitEpoch.mismatch("isTriggerActive", regtrig, java.util.Collections.singletonList(result), java.util.Collections.singletonList(fresh));
         }
      }
      return result;
   }

   public final void clearActiveTriggers(Card c, Zone zoneFrom) {
      List<Trigger> toBeRemoved = Lists.newArrayList();

      for(Trigger t : this.activeTriggers) {
         if (c.getId() == t.getHostCard().getId() && (!c.getTriggers().contains(t) || !t.zonesCheck(zoneFrom))) {
            toBeRemoved.add(t);
         }
      }

      this.activeTriggers.removeAll(toBeRemoved);
   }

   public final void registerActiveTrigger(Card c, boolean onlyExtrinsic) {
      for(Trigger t : c.getTriggers()) {
         if (!onlyExtrinsic || c.isCloned() || !t.isIntrinsic() || TriggerType.Always.equals(t.getMode())) {
            this.registerOneTrigger(t);
         }
      }

   }

   public final void registerActiveLTBTrigger(Card c) {
      for(Trigger t : c.getTriggers()) {
         if (t.looksBackInTime()) {
            this.registerOneTrigger(t);
         }
      }

   }

   public final boolean registerOneTrigger(Trigger t) {
      if (this.isTriggerActive(t)) {
         this.activeTriggers.add(t);
         return true;
      } else {
         return false;
      }
   }

   public final void runTrigger(TriggerType mode, Map<AbilityKey, Object> runParams, boolean holdTrigger) {
      if (!this.isTriggerSuppressed(mode)) {
         boolean canWait = this.waitingTriggers.size() < 9999;
         if (mode == TriggerType.Always) {
            this.runStateTrigger(runParams);
         } else if (canWait && (this.game.getStack().isFrozen() || holdTrigger) && mode != TriggerType.TapsForMana && mode != TriggerType.ManaAdded) {
            this.waitingTriggers.add(new TriggerWaiting(mode, runParams));
         } else {
            this.runWaitingTrigger(new TriggerWaiting(mode, runParams));
         }

      }
   }

   private void runStateTrigger(Map<AbilityKey, Object> runParams) {
      for(Trigger t : Lists.newArrayList(this.activeTriggers)) {
         if (this.canRunTrigger(t, TriggerType.Always, runParams)) {
            this.runSingleTrigger(t, runParams);
         }
      }

   }

   public final boolean runWaitingTriggers() {
      if (this.waitingTriggers.isEmpty()) {
         return false;
      } else {
         List<TriggerWaiting> waiting = new ArrayList(this.waitingTriggers);
         this.waitingTriggers.clear();
         boolean haveWaiting = false;

         for(TriggerWaiting wt : waiting) {
            haveWaiting |= this.runWaitingTrigger(wt);
         }

         return haveWaiting;
      }
   }

   private boolean runWaitingTrigger(TriggerWaiting wt) {
      Player playerAP = this.game.getPhaseHandler().getPlayerTurn();
      if (playerAP == null) {
         return false;
      } else {
         TriggerType mode = wt.getMode();
         Map<AbilityKey, Object> runParams = wt.getParams();
         List<Trigger> delayedTriggersWorkingCopy = new ArrayList(this.delayedTriggers);
         boolean checkStatics = false;

         for(Trigger t : Lists.newArrayList(this.activeTriggers)) {
            if (t.isStatic() && this.canRunTrigger(t, mode, runParams)) {
               int trigAmt = 1 + StaticAbilityPanharmonicon.handlePanharmonicon(this.game, t, runParams);

               for(int i = 0; i < trigAmt; ++i) {
                  this.runSingleTrigger(t, runParams);
               }

               checkStatics = true;
            }
         }

         if (runParams.containsKey(AbilityKey.Destination)) {
            if (runParams.get(AbilityKey.Destination) instanceof String) {
               String type = (String)runParams.get(AbilityKey.Destination);
               checkStatics |= type.equals("Battlefield");
            } else {
               ZoneType zone = (ZoneType)runParams.get(AbilityKey.Destination);
               if (zone != null) {
                  checkStatics |= zone.equals(ZoneType.Battlefield);
               }
            }
         }

         boolean wasCollected = wt.getTriggers() != null;

         for(Trigger t : wasCollected ? wt.getTriggers() : this.activeTriggers) {
            if (!t.isStatic() && (wasCollected || this.canRunTrigger(t, mode, runParams)) && (!wasCollected || t.checkActivationLimit())) {
               int trigAmt = 1 + StaticAbilityPanharmonicon.handlePanharmonicon(this.game, t, runParams);

               for(int i = 0; i < trigAmt; ++i) {
                  this.runSingleTrigger(t, runParams, wt.getController(t));
               }

               checkStatics = true;
            }
         }

         for(Trigger deltrig : delayedTriggersWorkingCopy) {
            if (this.isTriggerActive(deltrig) && this.canRunTrigger(deltrig, mode, runParams)) {
               this.delayedTriggers.remove(deltrig);
               this.runSingleTrigger(deltrig, runParams);
            }
         }

         return checkStatics;
      }
   }

   public void clearWaitingTriggers() {
      this.waitingTriggers.clear();
   }

   private boolean isTriggerActive(Trigger regtrig) {
      if (!regtrig.phasesCheck(this.game)) {
         return false;
      } else if (regtrig.isSuppressed()) {
         return false;
      } else if (TriggerType.Always.equals(regtrig.getMode()) && this.game.getStack().hasStateTrigger(regtrig.getId())) {
         return false;
      } else if (regtrig.getSpawningAbility() == null && !regtrig.zonesCheck(this.game.getZoneOf(regtrig.getHostCard()))) {
         return false;
      } else {
         for(Trigger t : this.activeTriggers) {
            if (regtrig.getId() == t.getId()) {
               return false;
            }
         }

         return true;
      }
   }

   private boolean canRunTrigger(Trigger regtrig, TriggerType mode, Map<AbilityKey, Object> runParams) {
      if (regtrig.getMode() != mode) {
         return false;
      } else if (regtrig.isSuppressed()) {
         return false;
      } else if (!regtrig.checkActivationLimit()) {
         return false;
      } else if (!regtrig.requirementsCheck(this.game)) {
         return false;
      } else if (!regtrig.meetsRequirementsOnTriggeredObjects(this.game, runParams)) {
         return false;
      } else if (!regtrig.performTest(runParams)) {
         return false;
      } else if (TriggerType.Always.equals(regtrig.getMode()) && this.game.getStack().hasStateTrigger(regtrig.getId())) {
         return false;
      } else {
         return regtrig.isStatic() || !StaticAbilityDisableTriggers.disabled(this.game, regtrig, runParams);
      }
   }

   private void runSingleTrigger(Trigger regtrig, Map<AbilityKey, Object> runParams) {
      this.runSingleTrigger(regtrig, runParams, (Player)null);
   }

   private void runSingleTrigger(Trigger regtrig, Map<AbilityKey, Object> runParams, Player controller) {
      if (controller == null) {
         controller = regtrig.getHostCard().getController();
      }

      if (runParams.get(AbilityKey.MergedCards) != null) {
         Card original = (Card)runParams.get(AbilityKey.Card);
         CardCollection mergedCards = (CardCollection)runParams.get(AbilityKey.MergedCards);
         mergedCards.set(mergedCards.indexOf(original), original);
         Map<AbilityKey, Object> newParams = AbilityKey.newMap(runParams);
         if ("Battlefield".equals(regtrig.getParam("Origin"))) {
            newParams.put(AbilityKey.Card, mergedCards);
            this.runSingleTriggerInternal(regtrig, newParams, controller);
         } else {
            for(Card c : mergedCards) {
               newParams.put(AbilityKey.Card, c);
               this.runSingleTriggerInternal(regtrig, newParams, controller);
            }
         }
      } else {
         this.runSingleTriggerInternal(regtrig, runParams, controller);
      }

   }

   private void runSingleTriggerInternal(Trigger regtrig, Map<AbilityKey, Object> runParams, Player controller) {
      this.adjustUndoStack(regtrig, runParams);
      Card host = regtrig.getHostCard();
      SpellAbility sa = regtrig.getOverridingAbility();
      if (sa == null) {
         if (!regtrig.hasParam("Execute")) {
            sa = new SpellAbility.EmptySa(host);
         } else {
            String name = regtrig.getParam("Execute");
            if (!host.getCurrentState().hasSVar(name)) {
               System.err.println("Warning: tried to run a trigger for card " + String.valueOf(host) + " referencing a SVar " + name + " not present on the current state " + String.valueOf(host.getCurrentState()) + ". Aborting trigger execution to prevent a crash.");
               return;
            }

            sa = AbilityFactory.getAbility(host, name);
            regtrig.setOverridingAbility(sa);
         }

         sa.setActivatingPlayer(controller);
         if (regtrig.isIntrinsic()) {
            sa.setIntrinsic(true);
            sa.changeText();
         }
      } else {
         if (regtrig.getSpawningAbility() != null) {
            controller = regtrig.getSpawningAbility().getActivatingPlayer();
         }

         sa = sa.copy(host, controller, false, true);
      }

      sa.setTrigger(regtrig);
      regtrig.setTriggeringObjects(sa, runParams);
      if (regtrig.hasParam("TriggerController")) {
         Player p = (Player)AbilityUtils.getDefinedPlayers(host, regtrig.getParam("TriggerController"), sa).get(0);
         sa.setActivatingPlayer(p);
      }

      if (sa.getActivatingPlayer().isInGame()) {
         sa.setStackDescription(sa.toString());
         Player decider = null;
         boolean isMandatory = false;
         if (regtrig.hasParam("OptionalDecider")) {
            sa.setOptionalTrigger(true);
            decider = (Player)AbilityUtils.getDefinedPlayers(host, regtrig.getParam("OptionalDecider"), sa).get(0);
         } else if (!(sa instanceof AbilitySub) && sa.hasParam("Cost") && (sa.getPayCosts() == null || !sa.getPayCosts().isMandatory()) && !sa.getParam("Cost").equals("0")) {
            sa.setOptionalTrigger(true);
            decider = sa.getActivatingPlayer();
         } else {
            isMandatory = true;
         }

         WrappedAbility wrapperAbility = new WrappedAbility(regtrig, sa, decider);
         if (regtrig.isStatic()) {
            if (wrapperAbility.getActivatingPlayer().getController().playTrigger(host, wrapperAbility, isMandatory)) {
               Map<AbilityKey, Object> staticParams = AbilityKey.mapFromCard(host);
               staticParams.put(AbilityKey.SpellAbility, sa);
               this.game.getTriggerHandler().runTrigger(TriggerType.AbilityResolves, staticParams, false);
            }
         } else {
            this.game.getStack().addSimultaneousStackEntry(wrapperAbility);
            this.game.getTriggerHandler().runTrigger(TriggerType.AbilityTriggered, TriggerAbilityTriggered.getRunParams(regtrig, wrapperAbility, runParams), false);
         }

         regtrig.triggerRun();
         boolean removeBoon = host.isBoon();
         if (regtrig.hasParam("BoonAmount")) {
            int x = AbilityUtils.calculateAmount(host, regtrig.getParam("BoonAmount"), wrapperAbility);
            int y = host.getAbilityActivatedThisGame(regtrig.getOverridingAbility());
            if (y < x) {
               removeBoon = false;
            }
         }

         if (regtrig.hasParam("OneOff") && host.isImmutable() || removeBoon) {
            host.getController().getZone(ZoneType.Command).remove(host);
         }

      }
   }

   private void adjustUndoStack(Trigger regtrig, Map<AbilityKey, Object> runParams) {
      if (regtrig.getMode() != TriggerType.TapsForMana && regtrig.getMode() != TriggerType.ManaAdded) {
         if (regtrig.getMode() != TriggerType.AbilityCast && !(regtrig instanceof TriggerAbilityResolves)) {
            if (regtrig.getMode() == TriggerType.Taps || regtrig.getMode() == TriggerType.Untaps) {
               Card c = (Card)runParams.get(AbilityKey.Card);

               for(SpellAbility sa : this.game.getStack().filterUndoStackByHost(c)) {
                  sa.setUndoable(false);
               }
            } else if (regtrig.getMode() == TriggerType.TapAll) {
               for(Card c : (Iterable<Card>)runParams.get(AbilityKey.Cards)) {
                  for(SpellAbility sa : this.game.getStack().filterUndoStackByHost(c)) {
                     sa.setUndoable(false);
                  }
               }
            } else if (regtrig.getMode() == TriggerType.UntapAll) {
               Map<Player, CardCollection> map = (Map)runParams.get(AbilityKey.Map);

               for(Card c : Iterables.concat(map.values())) {
                  for(SpellAbility sa : this.game.getStack().filterUndoStackByHost(c)) {
                     sa.setUndoable(false);
                  }
               }
            }
         } else {
            SpellAbility abMana = (SpellAbility)runParams.get(AbilityKey.SpellAbility);
            if (null != abMana && null != abMana.getManaPart()) {
               abMana.setUndoable(false);
            }
         }
      } else {
         SpellAbility abMana = (SpellAbility)runParams.get(AbilityKey.AbilityMana);
         if (null != abMana && null != abMana.getManaPart()) {
            abMana.setUndoable(false);
         }
      }

   }

   public List<Trigger> getActiveTrigger(TriggerType mode, Map<AbilityKey, Object> runParams) {
      List<Trigger> trigger = Lists.newArrayList();

      for(Trigger t : this.activeTriggers) {
         if (this.canRunTrigger(t, mode, runParams)) {
            trigger.add(t);
         }
      }

      return trigger;
   }

   public void onPlayerLost(Player p) {
      this.delayedTriggers.removeIf((t) -> p.equals(t.getSpawningAbility().getActivatingPlayer()));
      this.runWaitingTriggers();
   }
}
