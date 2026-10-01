package forge.game;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.UnmodifiableIterator;
import forge.GameCommand;
import forge.StaticData;
import forge.card.CardStateName;
import forge.card.ColorSet;
import forge.card.GamePieceType;
import forge.card.CardType.Supertype;
import forge.deck.DeckSection;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.card.CardDamageTable;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CardZoneTable;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.event.GameEventAddLog;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardDestroyed;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.event.GameEventCardTapped;
import forge.game.event.GameEventFlipCoin;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventScry;
import forge.game.extrahands.BackupPlanService;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.mulligan.MulliganService;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.player.PlayerView;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementResult;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellPermanent;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityContinuous;
import forge.game.staticability.StaticAbilityCountersRemain;
import forge.game.staticability.StaticAbilityLayer;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.TriggerType;
import forge.game.zone.PlayerZone;
import forge.game.zone.PlayerZoneBattlefield;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.Aggregates;
import forge.util.Expressions;
import forge.util.IterableUtil;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.util.TextUtil;
import forge.util.ThreadUtil;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jgrapht.alg.cycle.SzwarcfiterLauerSimpleCycles;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

public class GameAction {
   private final Game game;
   private boolean holdCheckingStaticAbilities = false;
   private static final Comparator<StaticAbility> effectOrder = Comparator.comparing(StaticAbility::isCharacteristicDefining).reversed().thenComparing(StaticAbility::getTimestamp);

   public GameAction(Game game0) {
      this.game = game0;
   }

   public Card changeZone(Zone zoneFrom, Zone zoneTo, Card c, Integer position, SpellAbility cause) {
      return this.changeZone(zoneFrom, zoneTo, c, position, cause, (Map)null);
   }

   private Card changeZone(Zone zoneFrom, Zone zoneTo, Card c, Integer position, SpellAbility cause, Map<AbilityKey, Object> params) {
      if (c.isCopiedSpell() && zoneTo.is(ZoneType.Battlefield) && c.isPermanent() && cause != null && cause.isSpell() && c.equals(cause.getHostCard())) {
         c.setGamePieceType(GamePieceType.TOKEN);
      }

      if (!c.isCopiedSpell() && (!c.isImmutable() || !zoneTo.is(ZoneType.Exile))) {
         if (zoneFrom == null && !c.isToken()) {
            zoneTo.add(c, position, CardCopyService.getLKICopy(c));
            this.checkStaticAbilities();
            this.game.getTriggerHandler().registerActiveTrigger(c, true);
            this.game.fireEvent(new GameEventCardChangeZone(c, zoneFrom, zoneTo));
            return c;
         } else {
            boolean toBattlefield = zoneTo.is(ZoneType.Battlefield) || zoneTo.is(ZoneType.Merged);
            boolean fromBattlefield = zoneFrom != null && zoneFrom.is(ZoneType.Battlefield);
            boolean fromGraveyard = zoneFrom != null && zoneFrom.is(ZoneType.Graveyard);
            boolean wasFacedown = c.isFaceDown();
            if (c.isSpell() || !c.isToken() || fromBattlefield || zoneFrom == null || zoneFrom.is(ZoneType.Stack) || cause != null && (cause instanceof SpellPermanent || cause.isCastFaceDown()) && cause.isCastFromPlayEffect()) {
               if (!toBattlefield || !c.isInstant() && !c.isSorcery()) {
                  CardCollectionView lastBattlefield = this.getLastState(AbilityKey.LastStateBattlefield, cause, params, false);
                  CardCollectionView lastGraveyard = this.getLastState(AbilityKey.LastStateGraveyard, cause, params, false);
                  if ((c.getGamePieceType() == GamePieceType.ATTRACTION || c.getGamePieceType() == GamePieceType.CONTRAPTION) && !toBattlefield && !zoneTo.getZoneType().isPartOfCommandZone() && !zoneTo.is(ZoneType.Exile)) {
                     return this.moveToJunkyard(c, cause, params);
                  } else {
                     if (c.isSplitCard() && toBattlefield && c.getCastSA() == null) {
                        c.updateRooms();
                     }

                     boolean suppress = !c.isToken() && zoneFrom.equals(zoneTo);
                     Card copied = null;
                     Card lastKnownInfo = null;
                     if (params != null && params.containsKey(AbilityKey.CardLKI)) {
                        lastKnownInfo = (Card)params.get(AbilityKey.CardLKI);
                     } else if (toBattlefield && cause != null && cause.isReplacementAbility()) {
                        ReplacementEffect re = cause.getReplacementEffect();
                        if (ReplacementType.Moved.equals(re.getMode()) && cause.getReplacingObject(AbilityKey.CardLKI).equals(c)) {
                           lastKnownInfo = (Card)cause.getReplacingObject(AbilityKey.CardLKI);
                        }
                     }

                     if (toBattlefield || suppress && zoneTo.getZoneType().isHidden()) {
                        copied = c;
                        if (lastKnownInfo == null) {
                           lastKnownInfo = CardCopyService.getLKICopy(c);
                        }

                        if (!StaticAbilityCountersRemain.countersRemain(lastKnownInfo, zoneTo)) {
                           c.clearCounters();
                        }
                     } else {
                        if (fromBattlefield) {
                           int idx = lastBattlefield.indexOf(c);
                           if (idx != -1) {
                              lastKnownInfo = (Card)lastBattlefield.get(idx);
                           }
                        }

                        if (fromGraveyard) {
                           int idx = lastGraveyard.indexOf(c);
                           if (idx != -1) {
                              lastKnownInfo = (Card)lastGraveyard.get(idx);
                           }
                        }

                        if (lastKnownInfo == null) {
                           lastKnownInfo = CardCopyService.getLKICopy(c);
                        }

                        if (fromBattlefield && !zoneTo.is(ZoneType.Stack) && !zoneTo.is(ZoneType.Flashback)) {
                           this.game.addChangeZoneLKIInfo(lastKnownInfo);
                        }

                        copied = (new CardCopyService(c)).copyCard(false);
                        copied.setGameTimestamp(c.getGameTimestamp());
                        if (zoneTo.is(ZoneType.Stack)) {
                           copied.setExiledWith(c.getExiledWith());
                           copied.setExiledBy(c.getExiledBy());
                           copied.setDrawnThisTurn(c.getDrawnThisTurn());
                           if (c.isRealToken()) {
                              copied.setCopiedPermanent(c.getCopiedPermanent());
                           }

                           if (cause != null && cause.isSpell() && c.equals(cause.getHostCard())) {
                              copied.setCastFrom(zoneFrom);
                              copied.setCastSA(cause);
                              copied.setSplitStateToPlayAbility(cause);
                              copied.setController(cause.getActivatingPlayer(), 0L);
                              KeywordInterface kw = cause.getKeyword();
                              if (kw != null) {
                                 copied.addKeywordForStaticAbility(kw);
                                 if (!cause.isIntrinsic()) {
                                    kw.setHostCard(copied);
                                    copied.addChangedCardKeywordsInternal(List.of(kw), (Collection)null, false, copied.getGameTimestamp(), kw.getStatic(), false);
                                 }
                              }
                           }
                        } else if (copied.getCurrentStateName() != CardStateName.PreparedSpell) {
                           copied.setState(CardStateName.Original, false);
                           copied.setBackSide(false);
                        }

                        if (StaticAbilityCountersRemain.countersRemain(lastKnownInfo, zoneTo)) {
                           copied.setCounters(HashMultiset.create(lastKnownInfo.getCounters()));
                        }

                        if (c.hasIntensity()) {
                           copied.setIntensity(c.getIntensity(false));
                        }

                        if (c.isSpecialized()) {
                           copied.setState(c.getCurrentStateName(), false);
                        }

                        if (c.hasPerpetual()) {
                           copied.setPerpetual(c);
                        }
                     }

                     copied.updateStateForView();
                     Card staticEff = this.setupStaticEffect(copied, cause);
                     if (copied.isAura() && !copied.isAttachedToEntity() && toBattlefield && (zoneFrom == null || !zoneFrom.is(ZoneType.Stack))) {
                        boolean found = false;
                        if (this.game.getPlayers().stream().anyMatch(PlayerPredicates.canBeAttached(copied, (SpellAbility)null))) {
                           found = true;
                        }

                        if (!found && lastBattlefield.anyMatch(CardPredicates.canBeAttached(copied, (SpellAbility)null))) {
                           found = true;
                        }

                        if (!found && lastGraveyard.anyMatch(CardPredicates.canBeAttached(copied, (SpellAbility)null))) {
                           found = true;
                        }

                        if (!found) {
                           c.clearControllers();
                           this.cleanStaticEffect(staticEff, copied);
                           return c;
                        }
                     }

                     GameEntityCounterTable table;
                     if (params != null && params.containsKey(AbilityKey.CounterTable)) {
                        table = (GameEntityCounterTable)params.get(AbilityKey.CounterTable);
                     } else {
                        table = new GameEntityCounterTable();
                     }

                     if (!suppress) {
                        if (fromBattlefield && !toBattlefield && c.isCommander() && c.hasMergedCard()) {
                           c.getOwner().setCommanderReplacementSuppressed(true);
                        }

                        Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(copied);
                        repParams.put(AbilityKey.CardLKI, lastKnownInfo);
                        repParams.put(AbilityKey.Cause, cause);
                        repParams.put(AbilityKey.Origin, zoneFrom != null ? zoneFrom.getZoneType() : null);
                        repParams.put(AbilityKey.Destination, zoneTo.getZoneType());
                        if (toBattlefield) {
                           repParams.put(AbilityKey.EffectOnly, true);
                           repParams.put(AbilityKey.CounterTable, table);
                           repParams.put(AbilityKey.CounterMap, table.column(copied));
                        }

                        if (params != null) {
                           repParams.putAll(params);
                        }

                        if (zoneFrom == null || zoneFrom.is(ZoneType.None)) {
                           copied.getOwner().addInboundToken(copied);
                        }

                        ReplacementResult repres = this.game.getReplacementHandler().run(ReplacementType.Moved, repParams);
                        copied.getOwner().removeInboundToken(copied);
                        if (repres != ReplacementResult.NotReplaced && repres != ReplacementResult.Updated) {
                           if ((c.isManifested() || c.isCloaked()) && !c.isInPlay()) {
                              c.forceTurnFaceUp();
                           }

                           if (repres == ReplacementResult.Prevented) {
                              c.clearControllers();
                              this.cleanStaticEffect(staticEff, copied);
                              if (cause != null) {
                                 if (cause.hasParam("Transformed") || cause.hasParam("FaceDown")) {
                                    c.setBackSide(false);
                                    c.changeToState(CardStateName.Original);
                                 }

                                 unattachCardLeavingBattlefield(c, c);
                              }

                              if (c.isInZone(ZoneType.Stack) && !zoneTo.is(ZoneType.Graveyard)) {
                                 return this.moveToGraveyard(c, cause, params);
                              }
                           } else if (toBattlefield && !c.isInPlay() && c.removeChangedState()) {
                              c.updateStateForView();
                           }

                           return c;
                        }
                     }

                     if (!zoneTo.is(ZoneType.Stack)) {
                        copied.setGameTimestamp(this.game.getNextTimestamp());
                     }

                     if (copied.isAura() && !copied.isAttachedToEntity() && toBattlefield) {
                        if (zoneFrom != null && zoneFrom.is(ZoneType.Stack) && this.game.getStack().isResolving(c)) {
                           boolean found = false;
                           if (this.game.getPlayers().stream().anyMatch(PlayerPredicates.canBeAttached(copied, (SpellAbility)null))) {
                              found = true;
                           }

                           if (lastBattlefield.anyMatch(CardPredicates.canBeAttached(copied, (SpellAbility)null))) {
                              found = true;
                           }

                           if (lastGraveyard.anyMatch(CardPredicates.canBeAttached(copied, (SpellAbility)null))) {
                              found = true;
                           }

                           if (!found) {
                              return this.moveToGraveyard(copied, cause, params);
                           }
                        }

                        this.attachAuraOnIndirectETB(copied, params);
                     }

                     CardCollection mergedCards = null;
                     if (fromBattlefield && !toBattlefield && c.hasMergedCard()) {
                        CardCollection cards = new CardCollection(c.getMergedCards());
                        cards.set(cards.indexOf(c), copied);
                        if (cause != null && zoneTo.getZoneType() == ZoneType.Exile) {
                           cards = (CardCollection)cause.getHostCard().getController().getController().orderMoveToZoneList(cards, zoneTo.getZoneType(), cause);
                        } else {
                           cards = (CardCollection)c.getOwner().getController().orderMoveToZoneList(cards, zoneTo.getZoneType(), cause);
                        }

                        cards.set(cards.indexOf(copied), c);
                        mergedCards = cards;
                        if (cause != null) {
                           SpellAbility saTargeting = cause.getSATargetingCard();
                           if (saTargeting != null) {
                              saTargeting.getTargets().replaceTargetCard(c, cards);
                           }

                           Card hostCard = cause.getHostCard();
                           if (!cause.hasParam("RememberLKI") && hostCard.isRemembered(c)) {
                              hostCard.removeRemembered(c);
                              hostCard.addRemembered(cards);
                           }
                        }
                     }

                     if (zoneFrom != null) {
                        if (fromBattlefield && this.game.getCombat() != null) {
                           if (!toBattlefield) {
                              this.game.getCombat().saveLKI(lastKnownInfo);
                           }

                           this.game.getCombat().removeFromCombat(c);
                        }

                        if (zoneFrom.getZoneType().isDeck() && zoneFrom == zoneTo && position.equals(zoneFrom.size()) && position != 0) {
                           position = position - 1;
                        }

                        if (mergedCards != null) {
                           for(Card card : mergedCards) {
                              c.getOwner().getZone(ZoneType.Merged).remove(card);
                           }
                        }

                        zoneFrom.remove(c);
                        if (c.hasEncodedCard()) {
                           for(Card e : c.getEncodedCards()) {
                              e.setEncodingCard((Card)null);
                           }
                        }

                        if (zoneFrom.is(ZoneType.Exile)) {
                           Card e = c.getEncodingCard();
                           if (e != null) {
                              e.removeEncodedCard(c);
                           }
                        }

                        if (zoneFrom.is(ZoneType.Stack) && toBattlefield) {
                           Multimap<StaticAbility, KeywordInterface> addKw = MultimapBuilder.hashKeys().arrayListValues().build();

                           for(KeywordInterface kw : c.getKeywords(Keyword.OFFSPRING)) {
                              if (!kw.isIntrinsic()) {
                                 addKw.put(kw.getStatic(), kw);
                              }
                           }

                           if (!addKw.isEmpty()) {
                              for(Map.Entry<StaticAbility, Collection<KeywordInterface>> e : addKw.asMap().entrySet()) {
                                 copied.addChangedCardKeywordsInternal((Collection)e.getValue(), (Collection)null, false, copied.getGameTimestamp(), (StaticAbility)e.getKey(), true);
                              }
                           }

                           copied.addExiledCards(c.getExiledCards());
                        }

                        if (cause != null && cause.isCraft() && toBattlefield) {
                           copied.retainPaidList(cause, "ExiledCards");
                        }
                     }

                     if (mergedCards != null) {
                        boolean wasToken = c.isToken();
                        c.getOwner().setCommanderReplacementSuppressed(false);
                        c.setZone(zoneTo);

                        for(Card card : mergedCards) {
                           if (card.isRealCommander()) {
                              card.setMoveToCommandZone(true);
                           }

                           if (wasToken && !card.isRealToken() || card.isRealCommander()) {
                              Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(card);
                              repParams.put(AbilityKey.CardLKI, card);
                              repParams.put(AbilityKey.Cause, cause);
                              repParams.put(AbilityKey.Origin, zoneFrom != null ? zoneFrom.getZoneType() : null);
                              repParams.put(AbilityKey.Destination, zoneTo.getZoneType());
                              if (params != null) {
                                 repParams.putAll(params);
                              }

                              ReplacementResult repres = this.game.getReplacementHandler().run(ReplacementType.Moved, repParams);
                              if (repres != ReplacementResult.NotReplaced) {
                                 continue;
                              }
                           }

                           if (card == c) {
                              this.storeChangesZoneAll(copied, zoneFrom, zoneTo, params);
                              zoneTo.add(copied, position, toBattlefield ? null : lastKnownInfo);
                           } else {
                              this.storeChangesZoneAll(card, zoneFrom, zoneTo, params);
                              zoneTo.add(card, position, CardCopyService.getLKICopy(card));
                              card.setState(CardStateName.Original, false);
                              card.setBackSide(false);
                              card.updateStateForView();
                           }

                           card.setZone(zoneTo);
                        }

                        copied.clearMergedCards();
                     } else {
                        if (!suppress) {
                           this.storeChangesZoneAll(copied, zoneFrom, zoneTo, params);
                        }

                        zoneTo.add(copied, position, toBattlefield ? null : lastKnownInfo);
                        c.setZone(zoneTo);
                     }

                     if (fromBattlefield) {
                        this.game.addLeftBattlefieldThisTurn(lastKnownInfo);
                        unattachCardLeavingBattlefield(copied, c);
                        c.runLeavesPlayCommands();
                        if (copied.isTapped()) {
                           copied.setTapped(false);
                           this.game.fireEvent(new GameEventCardTapped(c, false));
                        }
                     }

                     if (fromGraveyard) {
                        this.game.addLeftGraveyardThisTurn(lastKnownInfo);
                     }

                     if (c.hasMarkedColor()) {
                        copied.setMarkedColors(c.getMarkedColors());
                     }

                     copied.updateStateForView();
                     this.game.getTriggerHandler().suppressMode(TriggerType.Always);
                     this.checkStaticAbilities();
                     if (!suppress && toBattlefield) {
                        this.game.getTriggerHandler().registerActiveTrigger(copied, false);
                     }

                     table.replaceCounterEffect(this.game, (SpellAbility)null, true, true, params);
                     this.game.getTriggerHandler().clearSuppression(TriggerType.Always);
                     this.checkStaticAbilities();
                     if (toBattlefield) {
                        zoneTo.saveLKI(copied, lastKnownInfo);
                        if (copied.isRoom() && copied.getCastSA() != null) {
                           copied.unlockRoom(copied.getCastSA().getActivatingPlayer(), copied.getCastSA().getCardStateName());
                        }
                     }

                     if (!zoneTo.is(ZoneType.Stack)) {
                        c.cleanupExiledWith();
                     }

                     this.game.fireEvent(new GameEventCardChangeZone(c, zoneFrom, zoneTo));
                     this.game.getTriggerHandler().clearActiveTriggers(copied, (Zone)null);
                     this.game.getTriggerHandler().registerActiveTrigger(copied, false);
                     if (!suppress) {
                        Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(copied);
                        runParams.put(AbilityKey.CardLKI, lastKnownInfo);
                        runParams.put(AbilityKey.Cause, cause);
                        runParams.put(AbilityKey.Origin, zoneFrom != null ? zoneFrom.getZoneType().name() : null);
                        runParams.put(AbilityKey.Destination, zoneTo.getZoneType().name());
                        runParams.put(AbilityKey.IndividualCostPaymentInstance, this.game.costPaymentStack.peek());
                        runParams.put(AbilityKey.MergedCards, mergedCards);
                        if (params != null) {
                           runParams.putAll(params);
                        }

                        this.game.getTriggerHandler().runTrigger(TriggerType.ChangesZone, runParams, true);
                     }

                     if (fromBattlefield && !zoneFrom.getPlayer().equals(zoneTo.getPlayer())) {
                        Map<AbilityKey, Object> runParams2 = AbilityKey.mapFromCard(lastKnownInfo);
                        runParams2.put(AbilityKey.OriginalController, zoneFrom.getPlayer());
                        if (params != null) {
                           runParams2.putAll(params);
                        }

                        this.game.getTriggerHandler().runTrigger(TriggerType.ChangesController, runParams2, false);
                     }

                     if (zoneFrom == null) {
                        return copied;
                     } else {
                        if (wasFacedown && (fromBattlefield || zoneFrom.is(ZoneType.Stack) && !toBattlefield)) {
                           Card revealLKI = CardCopyService.getLKICopy(c);
                           revealLKI.forceTurnFaceUp();
                           this.reveal(new CardCollection(revealLKI), revealLKI.getOwner(), true, "Face-down card leaves the " + zoneFrom.toString() + ": ");
                        }

                        if (fromBattlefield) {
                           if (c.isPaired()) {
                              c.getPairedWith().setPairedWith((Card)null);
                              if (!c.isRealToken()) {
                                 c.setPairedWith((Card)null);
                              }
                           }

                           if (c.getMeldedWith() != null) {
                              Card unmeld = c.getMeldedWith();
                              ((PlayerZoneBattlefield)zoneFrom).removeFromMelded(unmeld);
                              if (position != null && (zoneTo.is(ZoneType.Library) || zoneTo.is(ZoneType.Graveyard))) {
                                 Integer var50 = position + 1;
                              }

                              unmeld = this.changeZone((Zone)null, zoneTo, unmeld, position, cause, params);
                              this.storeChangesZoneAll(unmeld, zoneFrom, zoneTo, params);
                           }
                        } else if (toBattlefield) {
                           for(Player p : this.game.getPlayers()) {
                              copied.getDamageHistory().setNotAttackedSinceLastUpkeepOf(p);
                              copied.getDamageHistory().setNotBlockedSinceLastUpkeepOf(p);
                              copied.getDamageHistory().setNotBeenBlockedSinceLastUpkeepOf(p);
                           }
                        }

                        if (!zoneTo.is(ZoneType.Battlefield) && !zoneTo.is(ZoneType.Stack)) {
                           copied.clearControllers();
                        }

                        return copied;
                     }
                  }
               } else {
                  return c;
               }
            } else {
               return c;
            }
         }
      } else {
         if (zoneFrom != null) {
            zoneFrom.remove(c);
         }

         return c;
      }
   }

   private Card setupStaticEffect(Card copied, SpellAbility cause) {
      if (cause != null && cause.hasParam("StaticEffect") && copied.isPermanent()) {
         Card source = cause.getHostCard();
         if (cause.hasParam("StaticEffectCheckSVar")) {
            String cmp = cause.getParamOrDefault("StaticEffectSVarCompare", "GE1");
            int lhs = AbilityUtils.calculateAmount(source, cause.getParam("StaticEffectCheckSVar"), cause);
            int rhs = AbilityUtils.calculateAmount(source, cmp.substring(2), cause);
            if (!Expressions.compare(lhs, cmp, rhs)) {
               return null;
            }
         }

         Long timestamp;
         if (cause.hasSVar("StaticEffectTimestamp")) {
            timestamp = Long.parseLong(cause.getSVar("StaticEffectTimestamp"));
         } else {
            timestamp = this.game.getNextTimestamp();
            cause.setSVar("StaticEffectTimestamp", String.valueOf(timestamp));
         }

         String name = "Static Effect #" + cause.getId();
         Optional<Card> opt = IterableUtil.tryFind(cause.getActivatingPlayer().getZone(ZoneType.Command).getCards(), CardPredicates.nameEquals(name));
         Card eff;
         if (opt.isPresent()) {
            eff = (Card)opt.get();
            eff.setLayerTimestamp(timestamp);
         } else {
            eff = SpellAbilityEffect.createEffect(cause, source, cause.getActivatingPlayer(), name, source.getImageKey(), timestamp);
            eff.setRenderForUI(false);
            StaticAbility stAb = eff.addStaticAbility(AbilityUtils.getSVar(cause, cause.getParam("StaticEffect")));
            stAb.setActiveZone(EnumSet.of(ZoneType.Command));
            stAb.putParam("AffectedZone", "All");
            eff.getOwner().getZone(ZoneType.Command).add(eff);
         }

         eff.addRemembered(copied);
         copied.addLeavesPlayCommand(() -> this.cleanStaticEffect(eff, copied));
         this.checkStaticAbilities(false, Sets.newHashSet(new Card[]{copied}), new CardCollection(copied));
         return eff;
      } else {
         return null;
      }
   }

   private void cleanStaticEffect(Card eff, Card copied) {
      if (eff != null) {
         eff.removeRemembered(copied);
         if (!eff.hasRemembered()) {
            this.exileEffect(eff);
         }
      }

   }

   private void storeChangesZoneAll(Card c, Zone zoneFrom, Zone zoneTo, Map<AbilityKey, Object> params) {
      if (params != null && params.containsKey(AbilityKey.InternalTriggerTable)) {
         ((CardZoneTable)params.get(AbilityKey.InternalTriggerTable)).put(zoneFrom != null ? zoneFrom.getZoneType() : null, zoneTo.getZoneType(), c);
      }

   }

   private static void unattachCardLeavingBattlefield(Card copied, Card old) {
      copied.unAttachAllCards(old);
      if (copied.isAttachedToEntity()) {
         copied.unattachFromEntity(copied.getEntityAttachedTo());
      }

   }

   public final Card moveTo(Zone zoneTo, Card c, SpellAbility cause) {
      return this.moveTo((Zone)zoneTo, c, cause, AbilityKey.newMap());
   }

   public final Card moveTo(Zone zoneTo, Card c, SpellAbility cause, Map<AbilityKey, Object> params) {
      return this.moveTo(zoneTo, c, (Integer)null, cause, params);
   }

   public final Card moveTo(ZoneType name, Card c, SpellAbility cause, Map<AbilityKey, Object> params) {
      return this.moveTo(name, c, 0, cause, params);
   }

   public final Card moveTo(ZoneType name, Card c, int libPosition, SpellAbility cause, Map<AbilityKey, Object> params) {
      try {
         Card var10000;
         switch (name) {
            case Hand:
               var10000 = this.moveToHand(c, cause, params);
               break;
            case Library:
               var10000 = this.moveToLibrary(c, libPosition, cause, params);
               break;
            case Battlefield:
               var10000 = this.moveToPlay(c, c.getController(), cause, params);
               break;
            case Graveyard:
               var10000 = this.moveToGraveyard(c, cause, params);
               break;
            case Exile:
               var10000 = !c.canExiledBy(cause, true) ? null : this.exile(c, cause, params);
               break;
            case Stack:
               var10000 = this.moveToStack(c, cause, params);
               break;
            case PlanarDeck:
            case SchemeDeck:
            case AttractionDeck:
            case ContraptionDeck:
               var10000 = this.moveToVariantDeck(c, name, libPosition, cause, params);
               break;
            case Junkyard:
               var10000 = this.moveToJunkyard(c, cause, params);
               break;
            default:
               var10000 = this.moveTo(c.getOwner().getZone(name), c, cause);
         }

         return var10000;
      } catch (Exception e) {
         String msg = "GameAction:moveTo: Exception occurred";
         Breadcrumb bread = new Breadcrumb(msg);
         bread.setData("Card", c.getName());
         bread.setData("SA", cause.toString());
         bread.setData("ZoneType", name.name());
         bread.setData("Player", c.getOwner());
         Sentry.addBreadcrumb(bread);
         throw new RuntimeException("Error in GameAction moveTo " + c.getName() + " to Player Zone " + name.name(), e);
      }
   }

   private Card moveTo(Zone zoneTo, Card c, Integer position, SpellAbility cause, Map<AbilityKey, Object> params) {
      Zone zoneFrom = this.game.getZoneOf(c);
      if (zoneTo.is(ZoneType.Subgame) && (c.hasMergedCard() || c.isMerged())) {
         c.moveMergedToSubgame(cause);
      }

      c = this.changeZone(zoneFrom, zoneTo, c, position, cause, params);
      Iterator var7 = c.getStaticAbilities().iterator();

      while(true) {
         StaticAbility stAb;
         label68:
         while(true) {
            if (!var7.hasNext()) {
               if (zoneFrom != null && zoneFrom.is(ZoneType.Sideboard) && this.game.getMaingame() != null) {
                  Card maingameCard = c.getOwner().getMappingMaingameCard(c);
                  if (maingameCard != null) {
                     if (maingameCard.getZone().is(ZoneType.Stack)) {
                        this.game.getMaingame().getStack().remove(maingameCard);
                     }

                     this.game.getMaingame().getAction().moveTo((ZoneType)ZoneType.Subgame, maingameCard, (SpellAbility)null, params);
                  }
               }

               if (c.isRealCommander()) {
                  c.setMoveToCommandZone(true);
               }

               return c;
            }

            stAb = (StaticAbility)var7.next();
            if (stAb.checkConditions()) {
               if (!stAb.checkMode(StaticAbilityMode.CantBlockBy)) {
                  break;
               }

               if (stAb.hasParam("ValidAttacker") && (!stAb.hasParam("ValidBlocker") || !stAb.getParam("ValidBlocker").equals("Creature.Self"))) {
                  Iterator var9 = IterableUtil.filter(this.game.getCardsIn(ZoneType.Battlefield), CardPredicates.CREATURES).iterator();

                  while(true) {
                     if (!var9.hasNext()) {
                        break label68;
                     }

                     Card creature = (Card)var9.next();
                     if (stAb.matchesValidParam("ValidAttacker", creature)) {
                        creature.updateAbilityTextForView();
                     }
                  }
               }
            }
         }

         if (stAb.checkMode(StaticAbilityMode.MinMaxBlocker)) {
            for(Card creature : IterableUtil.filter(this.game.getCardsIn(ZoneType.Battlefield), CardPredicates.CREATURES)) {
               if (stAb.matchesValidParam("ValidCard", creature)) {
                  creature.updateAbilityTextForView();
               }
            }
         }
      }
   }

   public final Card moveToStack(Card c, SpellAbility cause) {
      Map<AbilityKey, Object> params = AbilityKey.newMap();
      params.put(AbilityKey.LastStateBattlefield, this.game.getLastStateBattlefield());
      params.put(AbilityKey.LastStateGraveyard, this.game.getLastStateGraveyard());
      return this.moveToStack(c, cause, params);
   }

   public final Card moveToStack(Card c, SpellAbility cause, Map<AbilityKey, Object> params) {
      return this.moveTo(this.game.getStackZone(), c, cause, params);
   }

   public final Card moveToGraveyard(Card c, SpellAbility cause) {
      return this.moveToGraveyard(c, cause, AbilityKey.newMap());
   }

   public final Card moveToGraveyard(Card c, SpellAbility cause, Map<AbilityKey, Object> params) {
      PlayerZone grave = c.getOwner().getZone(ZoneType.Graveyard);
      return this.moveTo((Zone)grave, c, cause, params);
   }

   public final Card moveToHand(Card c, SpellAbility cause) {
      return this.moveToHand(c, cause, AbilityKey.newMap());
   }

   public final Card moveToHand(Card c, SpellAbility cause, Map<AbilityKey, Object> params) {
      PlayerZone hand = c.getOwner().getZone(ZoneType.Hand);
      return this.moveTo((Zone)hand, c, cause, params);
   }

   public final Card moveToPlay(Card c, SpellAbility cause, Map<AbilityKey, Object> params) {
      return this.moveToPlay(c, c.getController(), cause, params);
   }

   public final Card moveToPlay(Card c, Player p, SpellAbility cause, Map<AbilityKey, Object> params) {
      PlayerZone play = p.getZone(ZoneType.Battlefield);
      return this.moveTo((Zone)play, c, cause, params);
   }

   public final Card moveToBottomOfLibrary(Card c, SpellAbility cause) {
      return this.moveToBottomOfLibrary(c, cause, AbilityKey.newMap());
   }

   public final Card moveToBottomOfLibrary(Card c, SpellAbility cause, Map<AbilityKey, Object> params) {
      return this.moveToLibrary(c, -1, cause, params);
   }

   public final Card moveToLibrary(Card c, SpellAbility cause) {
      return this.moveToLibrary(c, cause, AbilityKey.newMap());
   }

   public final Card moveToLibrary(Card c, SpellAbility cause, Map<AbilityKey, Object> params) {
      return this.moveToLibrary(c, 0, cause, params);
   }

   public final Card moveToLibrary(Card c, int libPosition, SpellAbility cause) {
      return this.moveToLibrary(c, libPosition, cause, (Map)null);
   }

   public final Card moveToLibrary(Card c, int libPosition, SpellAbility cause, Map<AbilityKey, Object> params) {
      PlayerZone library = c.getOwner().getZone(ZoneType.Library);
      if (libPosition == -1 || libPosition > library.size()) {
         libPosition = library.size();
      }

      return this.changeZone(this.game.getZoneOf(c), library, c, libPosition, cause, params);
   }

   public final Card moveToVariantDeck(Card c, ZoneType zone, int deckPosition, SpellAbility cause, Map<AbilityKey, Object> params) {
      PlayerZone deck = c.getOwner().getZone(zone);
      if (deckPosition == -1 || deckPosition > deck.size()) {
         deckPosition = deck.size();
      }

      return this.changeZone(this.game.getZoneOf(c), deck, c, deckPosition, cause, params);
   }

   public final Card moveToJunkyard(Card c, SpellAbility cause, Map<AbilityKey, Object> params) {
      PlayerZone junkyard = c.getOwner().getZone(ZoneType.Junkyard);
      return this.moveTo((Zone)junkyard, c, cause, params);
   }

   public final CardCollection exile(CardCollection cards, SpellAbility cause, Map<AbilityKey, Object> params) {
      CardCollection result = new CardCollection();

      for(Card card : cards) {
         result.add(this.exile(card, cause, params));
      }

      return result;
   }

   public final Card exile(Card c, SpellAbility cause, Map<AbilityKey, Object> params) {
      Zone origin = c.getZone();
      PlayerZone removed = c.getOwner().getZone(ZoneType.Exile);
      Card copied = this.moveTo((Zone)removed, c, cause, params);
      if (c.isImmutable()) {
         return copied;
      } else {
         Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(c);
         runParams.put(AbilityKey.Cause, cause);
         if (origin != null) {
            runParams.put(AbilityKey.Origin, origin.getZoneType().name());
         }

         if (params != null) {
            runParams.putAll(params);
         }

         runParams.put(AbilityKey.CostStack, this.game.costPaymentStack);
         runParams.put(AbilityKey.IndividualCostPaymentInstance, this.game.costPaymentStack.peek());
         this.game.getTriggerHandler().runTrigger(TriggerType.Exiled, runParams, false);
         return copied;
      }
   }

   public final Card exileEffect(Card effect) {
      return this.exile((Card)effect, (SpellAbility)null, (Map)null);
   }

   public final void moveToCommand(Card effect, SpellAbility sa) {
      this.game.getTriggerHandler().suppressMode(TriggerType.ChangesZone);
      this.moveTo((ZoneType)ZoneType.Command, effect, sa, (Map)null);
      effect.updateStateForView();
      this.game.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);
   }

   public void ceaseToExist(Card c, boolean skipTrig) {
      if (c.isInZone(ZoneType.Stack)) {
         this.game.getStack().remove(c);
      }

      Zone origin = c.getZone();
      if (origin != null) {
         origin.remove(c);
         c.setZone(c.getOwner().getZone(ZoneType.None));
         if (origin.is(ZoneType.Battlefield)) {
            c.runLeavesPlayCommands();
         }
      }

      if (!skipTrig) {
         CardCollectionView lastBattlefield = this.game.getLastStateBattlefield();
         int idx = lastBattlefield.indexOf(c);
         Card lki = null;
         if (idx != -1) {
            lki = (Card)lastBattlefield.get(idx);
         }

         if (lki == null) {
            lki = CardCopyService.getLKICopy(c);
         }

         this.game.addChangeZoneLKIInfo(lki);
         if (lki.isInPlay() && !lki.isPhasedOut()) {
            if (this.game.getCombat() != null) {
               this.game.getCombat().saveLKI(lki);
               this.game.getCombat().removeFromCombat(c);
            }

            if (!lki.getController().equals(lki.getOwner())) {
               this.game.getTriggerHandler().registerActiveLTBTrigger(lki);
            }

            Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(c);
            runParams.put(AbilityKey.CardLKI, lki);
            runParams.put(AbilityKey.Origin, origin.getZoneType().name());
            this.game.getTriggerHandler().runTrigger(TriggerType.ChangesZone, runParams, false);
         }
      }

   }

   public final void controllerChangeZoneCorrection(Card c) {
      System.out.println("Correcting zone for " + c.toString());
      Zone oldBattlefield = this.game.getZoneOf(c);
      if (oldBattlefield != null && !oldBattlefield.is(ZoneType.Stack)) {
         Player original = oldBattlefield.getPlayer();
         Player controller = c.getController();
         if (original != null && controller != null && !original.equals(controller)) {
            PlayerZone newBattlefield = controller.getZone(oldBattlefield.getZoneType());
            if (newBattlefield != null && !oldBattlefield.equals(newBattlefield)) {
               if (c.isPaired()) {
                  Card partner = c.getPairedWith();
                  c.setPairedWith((Card)null);
                  partner.setPairedWith((Card)null);
                  partner.updateStateForView();
               }

               c.runChangeControllerCommands();
               this.game.getTriggerHandler().suppressMode(TriggerType.ChangesZone);
               oldBattlefield.remove(c);
               newBattlefield.add(c);
               if (this.game.getPhaseHandler().inCombat()) {
                  this.game.getCombat().removeFromCombat(c);
               }

               c.setCameUnderControlSinceLastUpkeep(true);
               c.handleChangedControllerSprocketReset();
               Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(c);
               runParams.put(AbilityKey.OriginalController, original);
               this.game.getTriggerHandler().runTrigger(TriggerType.ChangesController, runParams, false);
               this.game.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);
            }
         }
      }
   }

   private void setHoldCheckingStaticAbilities(boolean mode) {
      this.holdCheckingStaticAbilities = mode;
   }

   private boolean isCheckingStaticAbilitiesOnHold() {
      return this.holdCheckingStaticAbilities;
   }

   public boolean hasStaticAbilityAffectingZone(ZoneType zone, StaticAbilityLayer layer) {
      for(Card ca : this.game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
         for(StaticAbility stAb : ca.getStaticAbilities()) {
            if (stAb.checkConditions(StaticAbilityMode.Continuous) && (layer == null || stAb.getLayers().contains(layer)) && ZoneType.listValueOf(stAb.getParamOrDefault("AffectedZone", ZoneType.Battlefield.toString())).contains(zone)) {
               return true;
            }
         }
      }

      return false;
   }

   public final void checkStaticAbilities() {
      this.checkStaticAbilities(true);
   }

   public final void checkStaticAbilities(boolean runEvents) {
      this.checkStaticAbilities(runEvents, Sets.newHashSet(), CardCollection.EMPTY);
   }

   public final void checkStaticAbilities(boolean runEvents, Set<Card> affectedCards, CardCollectionView preList) {
      if (forge.game.card.TraitEpoch.DISABLED) {
         this.novaCheckStaticAbilities(runEvents, affectedCards, preList);
         return;
      }
      // Forge Nova: bracket the pass for NovaLayerMemo (answers never outlive the pass that computed them)
      forge.game.staticability.NovaLayerMemo.passBegin();
      try {
         this.novaCheckStaticAbilities(runEvents, affectedCards, preList);
      } finally {
         forge.game.staticability.NovaLayerMemo.passEnd();
      }
   }

   /** Forge Nova: the original checkStaticAbilities (with NovaStaticVisit and the NovaLayerMemo layer hooks). */
   private void novaCheckStaticAbilities(boolean runEvents, Set<Card> affectedCards, CardCollectionView preList) {
      if (!this.isCheckingStaticAbilitiesOnHold()) {
         if (!this.game.isGameOver()) {
            this.game.getTracker().freeze();
            Map<StaticAbilityLayer, Set<Card>> affectedPerLayer = Maps.newHashMap();
            this.game.getStaticEffects().clearStaticEffects(affectedCards, affectedPerLayer);
            FCollection<StaticAbility> staticAbilities = new FCollection();
            CardCollection staticList = new CardCollection();
            Table<StaticAbility, StaticAbility, Set<StaticAbilityLayer>> dependencies = null;
            if (preList.isEmpty()) {
               dependencies = HashBasedTable.create();
            }

            final forge.util.Visitor<Card> novaCollect = (cx) -> novaCollectStatics(cx, preList, staticAbilities, staticList);
            if (forge.game.card.TraitEpoch.DISABLED) {
               this.game.forEachCardInGame(novaCollect, true);
            } else {
               // Forge Nova: skip the cards whose visit cannot add anything, same order (NovaStaticVisit)
               NovaStaticVisit.visit(this.game, preList, novaCollect);
               if (forge.game.card.TraitEpoch.VERIFY) {
                  FCollection<StaticAbility> novaFresh = new FCollection<StaticAbility>();
                  CardCollection novaFreshList = new CardCollection();
                  this.game.forEachCardInGame((cx) -> novaCollectStatics(cx, preList, novaFresh, novaFreshList), true);
                  if (!forge.game.card.TraitEpoch.sameElements(staticAbilities, novaFresh) || !forge.game.card.TraitEpoch.sameElements(staticList, novaFreshList)) {
                     forge.game.card.TraitEpoch.mismatch("staticVisit", this.game, forge.game.card.TraitEpoch.toList(staticAbilities), forge.game.card.TraitEpoch.toList(novaFresh));
                  }
               }
            }
            staticAbilities.sort(effectOrder);
            Map<StaticAbility, CardCollectionView> affectedPerAbility = Maps.newHashMap();
            UnmodifiableIterator var9 = StaticAbilityLayer.CONTINUOUS_LAYERS.iterator();

            while(var9.hasNext()) {
               StaticAbilityLayer layer = (StaticAbilityLayer)var9.next();
               List<StaticAbility> toAdd = Lists.newArrayList();
               List<StaticAbility> staticsForLayer = Lists.newArrayList();

               for(StaticAbility stAb : staticAbilities) {
                  if (stAb.getLayers().contains(layer)) {
                     staticsForLayer.add(stAb);
                  }
               }

               // Forge Nova: affected-card answers are remembered for the rest of this layer (NovaLayerMemo)
               if (!forge.game.card.TraitEpoch.DISABLED) {
                  forge.game.staticability.NovaLayerMemo.begin(layer);
               }
               try {
               while(!staticsForLayer.isEmpty()) {
                  StaticAbility stAb = (StaticAbility)staticsForLayer.get(0);
                  if (!stAb.isCharacteristicDefining()) {
                     stAb = this.findStaticAbilityToApply(layer, staticsForLayer, preList, affectedPerAbility, dependencies);
                  }

                  staticsForLayer.remove(stAb);
                  CardCollectionView previouslyAffected = (CardCollectionView)affectedPerAbility.get(stAb);
                  CardCollectionView affectedHere;
                  if (previouslyAffected == null) {
                     affectedHere = stAb.applyContinuousAbilityBefore(layer, preList);
                     if (affectedHere != null) {
                        affectedPerAbility.put(stAb, affectedHere);
                     }
                  } else {
                     affectedHere = previouslyAffected;
                     stAb.applyContinuousAbility(layer, previouslyAffected);
                  }

                  if (affectedHere != null) {
                     ((Set)affectedPerLayer.computeIfAbsent(layer, (l) -> Sets.newHashSet())).addAll(affectedHere);

                     for(Card c : affectedHere) {
                        for(StaticAbility st2 : c.getStaticAbilities()) {
                           if (!staticAbilities.contains(st2) && st2.checkMode(StaticAbilityMode.Continuous) && st2.zonesCheck()) {
                              toAdd.add(st2);
                              CardCollectionView newAffected = st2.applyContinuousAbilityBefore(layer, preList);
                              if (newAffected != null) {
                                 ((Set)affectedPerLayer.computeIfAbsent(layer, (l) -> Sets.newHashSet())).addAll(newAffected);
                              }
                           }
                        }
                     }
                  }
               }

               } finally {
                  if (!forge.game.card.TraitEpoch.DISABLED) {
                     forge.game.staticability.NovaLayerMemo.end();
                  }
               }

               staticAbilities.addAll(toAdd);

               for(Player p : this.game.getPlayers()) {
                  p.afterStaticAbilityLayer(layer);
               }
            }

            for(CardCollectionView affected : affectedPerAbility.values()) {
               if (affected != null) {
                  Objects.requireNonNull(affectedCards);
                  affected.forEach(affectedCards::add);
               }
            }

            for(Card c : staticList) {
               List<Object[]> toRemove = Lists.newArrayList();

               for(Object[] staticCheck : c.getStaticCommandList()) {
                  String leftVar = (String)staticCheck[0];
                  String rightVar = (String)staticCheck[1];
                  Card affected = (Card)staticCheck[2];
                  int sVar = AbilityUtils.calculateAmount(affected, leftVar, (CardTraitBase)null);
                  String svarOperator = rightVar.substring(0, 2);
                  String svarOperand = rightVar.substring(2);
                  int operandValue = AbilityUtils.calculateAmount(c, svarOperand, (CardTraitBase)null);
                  if (Expressions.compare(sVar, svarOperator, operandValue)) {
                     ((GameCommand)staticCheck[3]).run();
                     toRemove.add(staticCheck);
                     affectedCards.add(c);
                  }
               }

               c.getStaticCommandList().removeAll(toRemove);
            }

            if (preList.isEmpty()) {
               for(Player p : this.game.getPlayers()) {
                  for(Card c : p.getCardsIn(ZoneType.Battlefield).threadSafeIterable()) {
                     if (!c.getController().equals(p)) {
                        this.controllerChangeZoneCorrection(c);
                        affectedCards.add(c);
                     }

                     if (c.isCreature() && c.isPaired()) {
                        Card partner = c.getPairedWith();
                        if (!partner.isCreature() || c.getController() != partner.getController() || !c.isInPlay()) {
                           c.setPairedWith((Card)null);
                           partner.setPairedWith((Card)null);
                           affectedCards.add(c);
                        }
                     }
                  }
               }

               Map<AbilityKey, Object> runParams = AbilityKey.newMap();
               this.game.getTriggerHandler().runTrigger(TriggerType.Always, runParams, false);
               this.game.getTriggerHandler().runTrigger(TriggerType.Immediate, runParams, false);
               this.game.getView().setDependencies(dependencies);
            }

            CardCollection affectedKeywords = new CardCollection();
            CardCollection affectedPT = new CardCollection();
            if (affectedPerLayer.containsKey(StaticAbilityLayer.TEXT)) {
               ((Set<Card>)affectedPerLayer.get(StaticAbilityLayer.TEXT)).forEach(Card::updateNameforView);
               affectedKeywords.addAll((Collection)affectedPerLayer.get(StaticAbilityLayer.TEXT));
               affectedPT.addAll((Collection)affectedPerLayer.get(StaticAbilityLayer.TEXT));
            }

            if (affectedPerLayer.containsKey(StaticAbilityLayer.TYPE)) {
               ((Set<Card>)affectedPerLayer.get(StaticAbilityLayer.TYPE)).forEach(Card::updateTypesForView);
               affectedKeywords.addAll((Collection)affectedPerLayer.get(StaticAbilityLayer.TYPE));
               affectedPT.addAll((Collection)affectedPerLayer.get(StaticAbilityLayer.TYPE));
            }

            if (affectedPerLayer.containsKey(StaticAbilityLayer.ABILITIES)) {
               affectedKeywords.addAll((Collection)affectedPerLayer.get(StaticAbilityLayer.ABILITIES));
            }

            if (affectedPerLayer.containsKey(StaticAbilityLayer.CHARACTERISTIC)) {
               affectedPT.addAll((Collection)affectedPerLayer.get(StaticAbilityLayer.CHARACTERISTIC));
            }

            if (affectedPerLayer.containsKey(StaticAbilityLayer.SETPT)) {
               affectedPT.addAll((Collection)affectedPerLayer.get(StaticAbilityLayer.SETPT));
            }

            if (affectedPerLayer.containsKey(StaticAbilityLayer.MODIFYPT)) {
               affectedPT.addAll((Collection)affectedPerLayer.get(StaticAbilityLayer.MODIFYPT));
            }

            affectedPT.forEach(Card::updatePTforView);
            affectedKeywords.forEach(Card::updateKeywords);
            if (affectedPerLayer.containsKey(StaticAbilityLayer.RULES)) {
               ((Set<Card>)affectedPerLayer.get(StaticAbilityLayer.RULES)).forEach(Card::updateNonAbilityTextForView);
            }

            if (runEvents && !affectedCards.isEmpty()) {
               this.game.fireEvent(new GameEventCardStatsChanged(affectedCards));
            }

            this.game.getTracker().unfreeze();
         }
      }
   }

   /** Forge Nova: checkStaticAbilities' per-card collection step, unchanged; always true (visit every card). */
   private static boolean novaCollectStatics(Card cx, CardCollectionView preList, FCollection<StaticAbility> staticAbilities, CardCollection staticList) {
      Card co = (Card)preList.get(cx);

      for(StaticAbility stAb : co.getStaticAbilities()) {
         if (stAb.checkMode(StaticAbilityMode.Continuous) && stAb.zonesCheck()) {
            staticAbilities.add(stAb);
         }
      }

      if (!co.getStaticCommandList().isEmpty()) {
         staticList.add(co);
      }

      for(StaticAbility stAb : co.getHiddenStaticAbilities()) {
         if (stAb.checkMode(StaticAbilityMode.Continuous) && stAb.zonesCheck()) {
            staticAbilities.add(stAb);
         }
      }

      return true;
   }

   private StaticAbility findStaticAbilityToApply(StaticAbilityLayer layer, List<StaticAbility> staticsForLayer, CardCollectionView preList, Map<StaticAbility, CardCollectionView> affectedPerAbility, Table<StaticAbility, StaticAbility, Set<StaticAbilityLayer>> dependencies) {
      StaticAbility first = (StaticAbility)staticsForLayer.get(0);
      if (staticsForLayer.size() == 1) {
         return first;
      } else if (!StaticAbilityLayer.CONTINUOUS_LAYERS_WITH_DEPENDENCY.contains(layer)) {
         return first;
      } else {
         Predicate<StaticAbility> isResolved = (stAbx) -> stAbx.getHostCard().isImmutable() && !stAbx.getHostCard().isEmblem();
         if (isResolved.test(first)) {
            return first;
         } else {
            DefaultDirectedGraph<StaticAbility, DefaultEdge> dependencyGraph = new DefaultDirectedGraph(DefaultEdge.class);

            for(StaticAbility stAb : staticsForLayer) {
               dependencyGraph.addVertex(stAb);
               if (!isResolved.test(stAb)) {
                  boolean exists = stAb.getHostCard().getStaticAbilities().contains(stAb);
                  boolean compareAffected = false;
                  CardCollectionView affectedHere = (CardCollectionView)affectedPerAbility.get(stAb);
                  if (affectedHere == null) {
                     affectedHere = StaticAbilityContinuous.getAffectedCards(stAb, preList);
                     compareAffected = true;
                  }

                  Iterable<Object> effectResults = this.generateContinuousEffectChanges(layer, stAb);

                  for(StaticAbility otherStAb : staticsForLayer) {
                     if (stAb != otherStAb) {
                        boolean removeFull = true;
                        CardCollectionView affectedOther = (CardCollectionView)affectedPerAbility.get(otherStAb);
                        if (affectedOther == null) {
                           affectedOther = otherStAb.applyContinuousAbilityBefore(layer, preList);
                           if (affectedOther == null) {
                              continue;
                           }
                        } else {
                           removeFull = false;
                           otherStAb.applyContinuousAbility(layer, affectedOther);
                        }

                        boolean dependency = exists != stAb.getHostCard().getStaticAbilities().contains(stAb);
                        if (!dependency && compareAffected) {
                           CardCollectionView affectedAfterOther = StaticAbilityContinuous.getAffectedCards(stAb, preList);
                           dependency = !Iterators.elementsEqual(affectedHere.iterator(), affectedAfterOther.iterator());
                        }

                        if (!dependency) {
                           Iterable<Object> effectResultsAfterOther = this.generateContinuousEffectChanges(layer, stAb);
                           dependency = !effectResults.equals(effectResultsAfterOther);
                        }

                        if (dependency) {
                           dependencyGraph.addVertex(otherStAb);
                           dependencyGraph.addEdge(stAb, otherStAb);
                           if (dependencies != null) {
                              if (dependencies.contains(stAb, otherStAb)) {
                                 ((Set)dependencies.get(stAb, otherStAb)).add(layer);
                              } else {
                                 dependencies.put(stAb, otherStAb, EnumSet.of(layer));
                              }
                           }
                        }

                        this.game.getStaticEffects().removeStaticEffect(otherStAb, layer, removeFull);
                     }
                  }

                  if (dependencyGraph.edgeSet().isEmpty() && stAb == first) {
                     return stAb;
                  }
               }
            }

            for(List<StaticAbility> cyc : (new SzwarcfiterLauerSimpleCycles<StaticAbility, DefaultEdge>(dependencyGraph)).findSimpleCycles()) {
               for(int i = 0; i < cyc.size() - 1; ++i) {
                  dependencyGraph.removeEdge((StaticAbility)cyc.get(i), (StaticAbility)cyc.get(i + 1));
               }

               dependencyGraph.removeEdge((StaticAbility)cyc.get(cyc.size() - 1), (StaticAbility)cyc.get(0));
            }

            Set<StaticAbility> toRemove = Sets.newHashSet();

            for(StaticAbility stAb : dependencyGraph.vertexSet()) {
               if (dependencyGraph.outDegreeOf(stAb) > 0) {
                  toRemove.add(stAb);
               }
            }

            dependencyGraph.removeAllVertices(toRemove);
            List<StaticAbility> statics = Lists.newArrayList(dependencyGraph.vertexSet());
            statics.sort(Comparator.comparing(StaticAbility::getTimestamp));
            return (StaticAbility)statics.get(0);
         }
      }
   }

   private Iterable<Object> generateContinuousEffectChanges(StaticAbilityLayer layer, StaticAbility stAb) {
      List<Object> result = Collections.emptyList();
      if (layer == StaticAbilityLayer.CONTROL) {
         result = Lists.newArrayList();
         result.addAll(AbilityUtils.getDefinedPlayers(stAb.getHostCard(), stAb.getParam("GainControl"), stAb));
      }

      return result;
   }

   public final boolean checkStateEffects(boolean runEvents) {
      return this.checkStateEffects(runEvents, Sets.newHashSet());
   }

   public boolean checkStateEffects(boolean runEvents, Set<Card> affectedCards) {
      this.checkGameOverCondition();
      if (this.game.isGameOver()) {
         return false;
      } else {
         boolean refreeze = this.game.getStack().isFrozen();
         this.game.getStack().setFrozen(true);
         this.game.getTracker().freeze();
         boolean performedSBA = false;
         boolean orderedDesCreats = false;
         boolean orderedNoRegCreats = false;
         boolean orderedSacrificeList = false;

         for(int q = 0; q < 9; ++q) {
            boolean checkAgain = false;
            CardCollection cardsToUpdateLKI = new CardCollection();
            this.checkStaticAbilities(false, affectedCards, CardCollection.EMPTY);
            CardZoneTable table = new CardZoneTable(this.game.getLastStateBattlefield(), this.game.getLastStateGraveyard());
            Map<AbilityKey, Object> mapParams = AbilityKey.newMap();
            AbilityKey.addCardZoneTableParams(mapParams, table);

            for(Player p : this.game.getPlayers()) {
               p.checkKeywordCard();

               for(ZoneType zt : ZoneType.values()) {
                  if (zt != ZoneType.Battlefield && zt != ZoneType.Flashback) {
                     for(Card c : p.getCardsIn(zt).threadSafeIterable()) {
                        checkAgain |= this.stateBasedAction704_5d(c);
                        if (zt == ZoneType.Command) {
                           this.stateBasedAction_Dungeon(c);
                           this.stateBasedAction_Scheme(c);
                        }
                     }
                  }
               }
            }

            CardCollection noRegCreats = new CardCollection();
            CardCollection desCreats = null;
            CardCollection unAttachList = new CardCollection();
            CardCollection sacrificeList = new CardCollection();
            PlayerCollection spaceSculptors = new PlayerCollection();

            for(Card c : this.game.getCardsIn(ZoneType.Battlefield)) {
               boolean checkAgainCard = false;
               if (c.hasKeyword(Keyword.SPACE_SCULPTOR)) {
                  spaceSculptors.add(c.getController());
               }

               if (c.isCreature()) {
                  if (c.getNetToughness() <= 0) {
                     noRegCreats.add(c);
                     checkAgainCard = true;
                  } else if (!c.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                     if (c.hasKeyword("CARDNAME can't be destroyed by lethal damage unless lethal damage dealt by a single source is marked on it.")) {
                        if (c.getLethal() <= c.getMaxDamageFromSource() || c.hasBeenDealtDeathtouchDamage()) {
                           if (desCreats == null) {
                              desCreats = new CardCollection();
                           }

                           desCreats.add(c);
                           c.setHasBeenDealtDeathtouchDamage(false);
                           checkAgainCard = true;
                        }
                     } else if (c.hasBeenDealtDeathtouchDamage() || c.getDamage() > 0 && c.getLethal() <= c.getDamage()) {
                        if (desCreats == null) {
                           desCreats = new CardCollection();
                        }

                        desCreats.add(c);
                        c.setHasBeenDealtDeathtouchDamage(false);
                        checkAgainCard = true;
                     }
                  }
               }

               checkAgainCard |= this.stateBasedAction_Saga(c, sacrificeList);
               checkAgainCard |= this.stateBasedAction_Battle(c, noRegCreats);
               checkAgainCard |= this.stateBasedAction_Role(c, unAttachList);
               checkAgainCard |= this.stateBasedAction704_attach(c, unAttachList);
               checkAgainCard |= this.stateBasedAction_Contraption(c, noRegCreats);
               checkAgainCard |= this.stateBasedAction704_5q(c);
               checkAgainCard |= this.stateBasedAction704_5r(c);
               if (c.hasKeyword("The number of loyalty counters on CARDNAME is equal to the number of Beebles you control.")) {
                  int beeble = CardLists.getValidCardCount(this.game.getCardsIn(ZoneType.Battlefield), "Beeble.YouCtrl", c.getController(), c, (CardTraitBase)null);
                  int loyal = c.getCounters(CounterEnumType.LOYALTY);
                  if (loyal < beeble) {
                     GameEntityCounterTable counterTable = new GameEntityCounterTable();
                     c.addCounter(CounterEnumType.LOYALTY, beeble - loyal, c.getController(), counterTable);
                     counterTable.replaceCounterEffect(this.game, (SpellAbility)null);
                  } else if (loyal > beeble) {
                     c.subtractCounter(CounterEnumType.LOYALTY, loyal - beeble, (Player)null);
                  }

                  if (c.getCounters(CounterEnumType.LOYALTY) != loyal) {
                     checkAgainCard = true;
                  }
               }

               if (c.isAura() && c.isInPlay() && !c.isEnchanting()) {
                  noRegCreats.add(c);
                  checkAgainCard = true;
               }

               if (checkAgainCard) {
                  cardsToUpdateLKI.add(c);
                  checkAgain = true;
               }
            }

            for(Card u : unAttachList) {
               u.unattachFromEntity(u.getEntityAttachedTo());
               if (u.isAura() && u.isInPlay() && !u.isEnchanting()) {
                  noRegCreats.add(u);
                  checkAgain = true;
               }
            }

            for(Player p : this.game.getPlayers()) {
               if (!spaceSculptors.isEmpty() && !spaceSculptors.contains(p)) {
                  checkAgain |= this.stateBasedAction704_5u(p);
               }

               checkAgain |= this.handleLegendRule(p, noRegCreats);
               if ((this.game.getRules().hasAppliedVariant(GameType.Commander) || this.game.getRules().hasAppliedVariant(GameType.Brawl) || this.game.getRules().hasAppliedVariant(GameType.Planeswalker)) && !checkAgain) {
                  for(Card c : p.getCardsIn(ZoneType.Graveyard).threadSafeIterable()) {
                     checkAgain |= this.stateBasedAction_Commander(c, mapParams);
                  }

                  for(Card c : p.getCardsIn(ZoneType.Exile).threadSafeIterable()) {
                     checkAgain |= this.stateBasedAction_Commander(c, mapParams);
                  }
               }

               if (p.getSpeed() == 0 && p.getCardsIn(ZoneType.Battlefield).anyMatch((cx) -> cx.hasKeyword(Keyword.START_YOUR_ENGINES))) {
                  p.increaseSpeed();
                  checkAgain = true;
               }

               checkAgain |= this.handlePlaneswalkerRule(p, noRegCreats);
            }

            for(Player p : spaceSculptors) {
               checkAgain |= this.stateBasedAction704_5u(p);
            }

            checkAgain |= this.handleWorldRule(noRegCreats);
            this.setHoldCheckingStaticAbilities(true);
            if (noRegCreats.size() > 1 && !orderedNoRegCreats) {
               noRegCreats = (CardCollection)GameActionUtil.orderCardsByTheirOwners(this.game, noRegCreats, ZoneType.Graveyard, (SpellAbility)null);
               orderedNoRegCreats = true;
            }

            for(Card c : noRegCreats) {
               c.updateWasDestroyed(true);
               this.sacrificeDestroy(c, (SpellAbility)null, mapParams);
            }

            if (desCreats != null) {
               if (desCreats.size() > 1 && !orderedDesCreats) {
                  desCreats = CardLists.filter(desCreats, Card::canBeDestroyed);
                  if (!desCreats.isEmpty()) {
                     desCreats = (CardCollection)GameActionUtil.orderCardsByTheirOwners(this.game, desCreats, ZoneType.Graveyard, (SpellAbility)null);
                  }

                  orderedDesCreats = true;
               }

               for(Card c : desCreats) {
                  this.destroy(c, (SpellAbility)null, true, mapParams);
               }
            }

            if (sacrificeList.size() > 1 && !orderedSacrificeList) {
               sacrificeList = (CardCollection)GameActionUtil.orderCardsByTheirOwners(this.game, sacrificeList, ZoneType.Graveyard, (SpellAbility)null);
               orderedSacrificeList = true;
            }

            this.sacrifice(sacrificeList, (SpellAbility)null, true, mapParams);
            this.setHoldCheckingStaticAbilities(false);
            table.triggerChangesZoneAll(this.game, (SpellAbility)null);
            this.game.getTriggerHandler().collectTriggerForWaiting();
            if (this.game.getTriggerHandler().runWaitingTriggers()) {
               checkAgain = true;
            }

            if (this.game.getCombat() != null) {
               this.game.getCombat().removeAbsentCombatants();
            }

            for(Card c : cardsToUpdateLKI) {
               this.game.updateLastStateForCard(c);
            }

            if (!checkAgain) {
               break;
            }

            performedSBA = true;
         }

         this.game.getTracker().unfreeze();
         if (runEvents && !affectedCards.isEmpty()) {
            this.game.fireEvent(new GameEventCardStatsChanged(affectedCards));
         }

         this.checkGameOverCondition();
         if (this.game.getAge() != GameStage.Play) {
            return false;
         } else {
            this.game.getTriggerHandler().resetActiveTriggers();
            this.checkStaticAbilities(false, affectedCards, CardCollection.EMPTY);
            if (!refreeze) {
               this.game.getStack().unfreezeStack();
            }

            this.game.runSBACheckedCommands();
            return performedSBA;
         }
      }
   }

   private boolean stateBasedAction_Saga(Card c, CardCollection sacrificeList) {
      boolean checkAgain = false;
      if (c.isSaga() && c.hasChapter()) {
         if (!c.canBeSacrificedBy((SpellAbility)null, true)) {
            return false;
         } else if (c.getCounters(CounterEnumType.LORE) < c.getFinalChapterNr()) {
            return false;
         } else {
            if (!this.game.getStack().hasSourceOnStack(c, SpellAbility::isChapter)) {
               sacrificeList.add(c);
               checkAgain = true;
            }

            return checkAgain;
         }
      } else {
         return false;
      }
   }

   private boolean stateBasedAction_Battle(Card c, CardCollection removeList) {
      boolean checkAgain = false;
      if (!c.isBattle()) {
         return checkAgain;
      } else {
         Player battleController = c.getController();
         Player battleProtector = c.getProtectingPlayer();
         if ((battleProtector == null || !battleProtector.isInGame()) && (this.game.getCombat() == null || this.game.getCombat().getAttackersOf(c).isEmpty()) || c.getType().hasStringType("Siege") && battleController.equals(battleProtector)) {
            Player newProtector;
            if (c.getType().getBattleTypes().contains("Siege")) {
               newProtector = (Player)battleController.getController().chooseSingleEntityForEffect(battleController.getOpponents(), new SpellAbility.EmptySa(ApiType.ChoosePlayer, c), "Choose an opponent to protect this battle", (Map)null);
            } else {
               newProtector = battleController;
            }

            if (newProtector == null) {
               removeList.add(c);
            } else {
               c.setProtectingPlayer(newProtector);
            }

            checkAgain = true;
         }

         if (c.getCounters(CounterEnumType.DEFENSE) > 0) {
            return checkAgain;
         } else {
            if (!this.game.getStack().hasSourceOnStack(c, SpellAbility::isTrigger)) {
               removeList.add(c);
               checkAgain = true;
            }

            return checkAgain;
         }
      }
   }

   private boolean stateBasedAction_Role(Card c, CardCollection removeList) {
      if (!c.hasCardAttachments()) {
         return false;
      } else {
         boolean checkAgain = false;
         CardCollection roles = CardLists.filter(c.getAttachedCards(), CardPredicates.isType("Role"));
         if (roles.isEmpty()) {
            return false;
         } else {
            for(Player p : this.game.getPlayers()) {
               CardCollection rolesByPlayer = CardLists.filterControlledBy(roles, p);
               if (rolesByPlayer.size() > 1) {
                  rolesByPlayer.sort(CardPredicates.compareByGameTimestamp());
                  removeList.addAll(rolesByPlayer.subList(0, rolesByPlayer.size() - 1));
                  checkAgain = true;
               }
            }

            return checkAgain;
         }
      }
   }

   private void stateBasedAction_Dungeon(Card c) {
      if (c.getType().isDungeon() && c.isInLastRoom()) {
         if (!this.game.getStack().hasSourceOnStack(c, (Predicate)null)) {
            this.completeDungeon(c.getController(), c);
         }

      }
   }

   private void stateBasedAction_Scheme(Card c) {
      if (c.isScheme() && !c.getType().hasSupertype(Supertype.Ongoing)) {
         if (!this.game.getStack().hasSourceOnStack(c, (Predicate)null)) {
            this.moveTo(ZoneType.SchemeDeck, c, -1, (SpellAbility)null, AbilityKey.newMap());
         }

      }
   }

   private boolean stateBasedAction704_attach(Card c, CardCollection unAttachList) {
      boolean checkAgain = false;
      if (c.isAttachedToEntity()) {
         GameEntity ge = c.getEntityAttachedTo();
         if (c.isCreature() || c.isBattle() || !ge.canBeAttached(c, (SpellAbility)null, true)) {
            unAttachList.add(c);
            checkAgain = true;
         }
      }

      if (c.hasCardAttachments()) {
         for(Card attach : c.getAttachedCards()) {
            if (!attach.isInPlay()) {
               unAttachList.add(attach);
               checkAgain = true;
            }
         }
      }

      return checkAgain;
   }

   private boolean stateBasedAction_Contraption(Card c, CardCollection removeList) {
      if (!c.isContraption()) {
         return false;
      } else {
         int currentSprocket = c.getSprocket();
         if (currentSprocket == 0) {
            removeList.add(c);
            return true;
         } else if (currentSprocket > 0 && currentSprocket <= 3) {
            return false;
         } else {
            int sprocket = c.getController().getController().chooseSprocket(c);
            c.setSprocket(sprocket);
            return true;
         }
      }
   }

   private boolean stateBasedAction704_5u(Player p) {
      boolean checkAgain = false;
      CardCollection toAssign = new CardCollection();

      for(Card c : p.getCreaturesInPlay().threadSafeIterable()) {
         if (!c.hasSector()) {
            toAssign.add(c);
            checkAgain = true;
         }
      }

      StringBuilder sb = new StringBuilder();

      for(Card assignee : toAssign) {
         String sector = p.getController().chooseSector(assignee, "Assign");
         assignee.assignSector(sector);
         if (sb.length() == 0) {
            sb.append(p).append(" ").append(Localizer.getInstance().getMessage("lblAssigns", new Object[0])).append("\n");
         }

         String var10000 = assignee.getTranslatedName();
         String creature = var10000 + " (" + assignee.getId() + ")";
         sb.append(creature).append(" ").append(sector).append("\n");
      }

      if (sb.length() > 0) {
         this.notifyOfValue((SpellAbility)null, p, sb.toString(), p);
      }

      return checkAgain;
   }

   private boolean stateBasedAction_Commander(Card c, Map<AbilityKey, Object> mapParams) {
      if (c.isRealCommander() && c.canMoveToCommandZone()) {
         this.game.getTracker().flush();
         c.setMoveToCommandZone(false);
         if (c.getOwner().getController().confirmAction(c.getCurrentState().getFirstSpellAbilityWithFallback(), PlayerActionConfirmMode.ChangeZoneToAltDestination, c.getDisplayName() + ": If a commander is in a graveyard or in exile and that card was put into that zone since the last time state-based actions were checked, its owner may put it into the command zone.", (Map)null)) {
            this.moveTo((Zone)c.getOwner().getZone(ZoneType.Command), c, (SpellAbility)null, mapParams);
            return true;
         }
      }

      return false;
   }

   private boolean stateBasedAction704_5q(Card c) {
      boolean checkAgain = false;
      CounterType p1p1 = CounterEnumType.P1P1;
      CounterType m1m1 = CounterEnumType.M1M1;
      int plusOneCounters = c.getCounters(p1p1);
      int minusOneCounters = c.getCounters(m1m1);
      if (plusOneCounters > 0 && minusOneCounters > 0) {
         if (!c.canRemoveCounters(p1p1) || !c.canRemoveCounters(m1m1)) {
            return checkAgain;
         }

         int remove = Math.min(plusOneCounters, minusOneCounters);
         c.subtractCounter(p1p1, remove, (Player)null);
         c.subtractCounter(m1m1, remove, (Player)null);
         checkAgain = true;
      }

      return checkAgain;
   }

   private boolean stateBasedAction704_5r(Card c) {
      CounterType dreamType = CounterEnumType.DREAM;
      int old = c.getCounters(dreamType);
      if (old <= 0) {
         return false;
      } else {
         Integer max = c.getCounterMax(dreamType);
         if (max == null) {
            return false;
         } else if (old > max) {
            if (!c.canRemoveCounters(dreamType)) {
               return false;
            } else {
               c.subtractCounter(dreamType, old - max, (Player)null);
               return true;
            }
         } else {
            return false;
         }
      }
   }

   private boolean stateBasedAction704_5d(Card c) {
      boolean checkAgain = false;
      if (c.isRealToken()) {
         Zone zoneFrom = this.game.getZoneOf(c);
         if (zoneFrom.is(ZoneType.Stack) && c.getCopiedPermanent() != null) {
            return false;
         }

         if (zoneFrom.is(ZoneType.Exile) && c.getCurrentStateName() == CardStateName.PreparedSpell) {
            return false;
         }

         if (!zoneFrom.is(ZoneType.Battlefield)) {
            zoneFrom.remove(c);
            checkAgain = true;
         }
      }

      return checkAgain;
   }

   public void checkGameOverCondition() {
      if (!this.game.isGameOver()) {
         GameEndReason reason = null;
         List<Player> losers = null;
         FCollectionView<Player> allPlayers = this.game.getPlayers();

         for(Player p : allPlayers) {
            if (p.hasWon()) {
               reason = GameEndReason.WinsGameSpellEffect;

               for(Player pl : allPlayers) {
                  if (!pl.equals(p)) {
                     if (!pl.loseConditionMet(GameLossReason.OpponentWon, p.getOutcome().altWinSourceName)) {
                        reason = null;
                     } else {
                        if (losers == null) {
                           losers = Lists.newArrayListWithCapacity(3);
                        }

                        losers.add(pl);
                     }
                  }
               }
               break;
            }
         }

         if (reason == null) {
            for(Player p : allPlayers) {
               if (p.checkLoseCondition()) {
                  if (losers == null) {
                     losers = Lists.newArrayListWithCapacity(3);
                  }

                  losers.add(p);
               }
            }
         }

         if (losers != null) {
            for(Player p : losers) {
               this.game.onPlayerLost(p);
            }
         }

         if (reason == null) {
            List<Player> notLost = Lists.newArrayList();
            Set<Integer> teams = Sets.newHashSet();

            for(Player p : allPlayers) {
               if (p.getOutcome() == null || p.getOutcome().hasWon()) {
                  notLost.add(p);
                  teams.add(p.getTeam());
               }
            }

            int cntNotLost = notLost.size();
            if (cntNotLost == 1) {
               reason = GameEndReason.AllOpponentsLost;
            } else if (cntNotLost == 0) {
               reason = GameEndReason.Draw;
            } else {
               if (teams.size() != 1) {
                  return;
               }

               reason = GameEndReason.AllOpposingTeamsLost;
            }
         }

         this.game.setGameOver(reason);
         this.game.getStack().clearSimultaneousStack();
      }
   }

   private boolean handlePlaneswalkerRule(Player p, CardCollection noRegCreats) {
      List<Card> list = p.getPlaneswalkersInPlay();
      boolean recheck = false;

      for(Card c : list) {
         if (c.getCounters(CounterEnumType.LOYALTY) <= 0 && !c.ignorePlaneswalkerZeroLoyaltyRule()) {
            noRegCreats.add(c);
            recheck = true;
         }
      }

      return recheck;
   }

   private boolean handleLegendRule(Player p, CardCollection noRegCreats) {
      List<Card> a = Lists.newArrayList();

      for(Card c : CardLists.getType(p.getCardsIn(ZoneType.Battlefield), "Legendary")) {
         if (!c.ignoreLegendRule()) {
            a.add(c);
         }
      }

      if (a.isEmpty()) {
         return false;
      } else {
         boolean recheck = false;
         CardCollection nonLegendaryNames = CardLists.filter(a, Card::hasNonLegendaryCreatureNames);
         Multimap<String, Card> uniqueLegends = Multimaps.index(a, Card::getName);
         CardCollection removed = new CardCollection();

         for(String name : uniqueLegends.keySet()) {
            if (!name.isEmpty()) {
               CardCollection cc = new CardCollection(uniqueLegends.get(name));
               if (!name.isEmpty() && StaticData.instance().getCommonCards().isNonLegendaryCreatureName(name)) {
                  cc.addAll(nonLegendaryNames);
               }

               if (cc.size() >= 2) {
                  recheck = true;
                  Card toKeep = (Card)p.getController().chooseSingleEntityForEffect(cc, new SpellAbility.EmptySa(ApiType.InternalLegendaryRule, new Card(-1, this.game), p), "You have multiple legendary permanents named \"" + name + "\" in play.\n\nChoose the one to stay on battlefield (the rest will be moved to graveyard)", (Map)null);
                  cc.remove(toKeep);
                  removed.addAll(cc);
               }
            }
         }

         CardCollection emptyNameAllNonLegendary = new CardCollection(nonLegendaryNames);
         emptyNameAllNonLegendary.removeAll(removed);
         if (emptyNameAllNonLegendary.size() > 1) {
            recheck = true;
            Card toKeep = (Card)p.getController().chooseSingleEntityForEffect(emptyNameAllNonLegendary, new SpellAbility.EmptySa(ApiType.InternalLegendaryRule, new Card(-1, this.game), p), "You have multiple legendary permanents with non legendary creature names in play.\n\nChoose the one to stay on battlefield (the rest will be moved to graveyard)", (Map)null);
            emptyNameAllNonLegendary.remove(toKeep);
            removed.addAll(emptyNameAllNonLegendary);
         }

         noRegCreats.addAll(removed);
         return recheck;
      }
   }

   private boolean handleWorldRule(CardCollection noRegCreats) {
      List<Card> worlds = CardLists.filter(this.game.getCardsIn(ZoneType.Battlefield), ((c) -> c.getType().hasSupertype(Supertype.World)));
      if (worlds.size() <= 1) {
         return false;
      } else {
         List<Card> toKeep = Lists.newArrayList();
         long ts = 0L;

         for(Card crd : worlds) {
            long crdTs = crd.getWorldTimestamp();
            if (crdTs > ts) {
               ts = crdTs;
               toKeep.clear();
            }

            if (crdTs == ts) {
               toKeep.add(crd);
            }
         }

         if (toKeep.size() == 1) {
            worlds.removeAll(toKeep);
         }

         noRegCreats.addAll(worlds);
         return true;
      }
   }

   public final CardCollection sacrifice(Iterable<Card> list, SpellAbility source, boolean effect, Map<AbilityKey, Object> params) {
      Multimap<Player, Card> lki = MultimapBuilder.hashKeys().arrayListValues().build();
      boolean showRevealDialog = source != null && source.hasParam("ShowSacrificedCards");
      CardCollection result = new CardCollection();

      for(Card c : list) {
         if (c != null && c.canBeSacrificedBy(source, effect)) {
            Card lkiCopy = (Card)((CardCollection)params.get(AbilityKey.LastStateBattlefield)).get(c);
            c.getController().addSacrificedThisTurn(lkiCopy, source);
            lki.put(c.getController(), lkiCopy);
            c.updateWasDestroyed(true);
            Card changed = this.sacrificeDestroy(c, source, params);
            if (changed != null) {
               result.add(changed);
            }

            if (showRevealDialog) {
               String message = Localizer.getInstance().getMessage("lblSacrifice", new Object[0]);
               this.reveal(result, ZoneType.Graveyard, c.getOwner(), false, message, false);
            }
         }
      }

      for(Map.Entry<Player, Collection<Card>> e : lki.asMap().entrySet()) {
         Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer((Player)e.getKey());
         runParams.put(AbilityKey.Cards, new CardCollection((Iterable)e.getValue()));
         runParams.put(AbilityKey.Cause, source);
         this.game.getTriggerHandler().runTrigger(TriggerType.SacrificedOnce, runParams, false);
      }

      return result;
   }

   public final boolean destroy(Card c, SpellAbility sa, boolean regenerate, Map<AbilityKey, Object> params) {
      if (!c.canBeDestroyed()) {
         return false;
      } else {
         Map<AbilityKey, Object> repRunParams = AbilityKey.mapFromAffected(c);
         repRunParams.put(AbilityKey.Cause, sa);
         repRunParams.put(AbilityKey.Regeneration, regenerate);
         if (params != null) {
            repRunParams.putAll(params);
         }

         if (this.game.getReplacementHandler().run(ReplacementType.Destroy, repRunParams) != ReplacementResult.NotReplaced) {
            return false;
         } else {
            Player activator = null;
            if (sa != null) {
               activator = sa.getActivatingPlayer();
            }

            c.updateWasDestroyed(true);
            this.game.fireEvent(new GameEventCardDestroyed());
            Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(c);
            runParams.put(AbilityKey.Causer, activator);
            if (params != null) {
               runParams.putAll(params);
            }

            this.game.getTriggerHandler().runTrigger(TriggerType.Destroyed, runParams, false);
            Card sacrificed = this.sacrificeDestroy(c, sa, params);
            return sacrificed != null;
         }
      }
   }

   protected final Card sacrificeDestroy(Card c, SpellAbility cause, Map<AbilityKey, Object> params) {
      if (!c.isInPlay()) {
         return null;
      } else {
         Card newCard = this.moveToGraveyard(c, cause, params);
         return newCard;
      }
   }

   public void revealTo(Card card, Player to) {
      this.revealTo((Card)card, (Iterable)Collections.singleton(to));
   }

   public void revealTo(CardCollectionView cards, Player to) {
      this.revealTo(cards, to, (String)null);
   }

   public void revealTo(CardCollectionView cards, Player to, String messagePrefix) {
      this.revealTo(cards, Collections.singleton(to), messagePrefix, true);
   }

   public void revealTo(Card card, Iterable<Player> to) {
      this.revealTo((CardCollectionView)(new CardCollection(card)), (Iterable)to);
   }

   public void revealTo(CardCollectionView cards, Iterable<Player> to) {
      this.revealTo(cards, to, (String)null, true);
   }

   public void revealTo(CardCollectionView cards, Iterable<Player> to, String messagePrefix, boolean addSuffix) {
      if (!cards.isEmpty()) {
         ZoneType zone = ((Card)cards.getFirst()).getZone().getZoneType();
         Player owner = ((Card)cards.getFirst()).getOwner();

         for(Player p : to) {
            p.getController().reveal(cards, zone, owner, messagePrefix, addSuffix);
         }

      }
   }

   public void reveal(CardCollectionView cards, Player cardOwner) {
      this.reveal(cards, cardOwner, true);
   }

   public void reveal(CardCollectionView cards, Player cardOwner, boolean dontRevealToOwner) {
      this.reveal(cards, cardOwner, dontRevealToOwner, (String)null);
   }

   public void reveal(CardCollectionView cards, Player cardOwner, boolean dontRevealToOwner, String messagePrefix) {
      this.reveal(cards, cardOwner, dontRevealToOwner, messagePrefix, true);
   }

   public void reveal(CardCollectionView cards, Player cardOwner, boolean dontRevealToOwner, String messagePrefix, boolean msgAddSuffix) {
      Card firstCard = (Card)Iterables.getFirst(cards, (Object)null);
      if (firstCard != null) {
         this.reveal(cards, this.game.getZoneOf(firstCard).getZoneType(), cardOwner, dontRevealToOwner, messagePrefix, msgAddSuffix);
      }
   }

   public void reveal(CardCollectionView cards, ZoneType zt, Player cardOwner, boolean dontRevealToOwner, String messagePrefix) {
      this.reveal(cards, zt, cardOwner, dontRevealToOwner, messagePrefix, true);
   }

   public void reveal(CardCollectionView cards, ZoneType zt, Player cardOwner, boolean dontRevealToOwner, String messagePrefix, boolean msgAddSuffix) {
      for(Player p : this.game.getPlayers()) {
         if (!dontRevealToOwner || cardOwner != p) {
            p.getController().reveal(cards, zt, cardOwner, messagePrefix, msgAddSuffix);
         }
      }

   }

   public void revealUnplayableByAI(String title, Map<Player, Map<DeckSection, List<? extends PaperCard>>> unplayableCards) {
      for(Player p : this.game.getPlayers()) {
         p.getController().revealAISkipCards(title, unplayableCards);
      }

   }

   public void revealAnte(String title, Multimap<Player, PaperCard> removedAnteCards) {
      for(Player p : this.game.getPlayers()) {
         p.getController().revealAnte(title, removedAnteCards);
      }

   }

   public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {
      for(Player p : this.game.getPlayers()) {
         p.getController().revealUnsupported(unsupported);
      }

   }

   public void notifyOfValue(SpellAbility saSource, GameObject relatedTarget, String value, Player playerExcept) {
      if (saSource != null) {
         String name = saSource.getHostCard().getTranslatedName();
         value = TextUtil.fastReplace(value, "CARDNAME", name);
         value = TextUtil.fastReplace(value, "NICKNAME", Lang.getInstance().getNickName(name));
      }

      for(Player p : this.game.getPlayers()) {
         if (playerExcept != p) {
            p.getController().notifyOfValue(saSource, relatedTarget, value);
         }
      }

   }

   private void drawStartingHand(Player p1) {
      List<Card> lib1 = Lists.newArrayList(p1.getZone(ZoneType.Library).getCards().threadSafeIterable());
      List<Card> hand1 = lib1.subList(0, p1.getMaxHandSize());
      List<Card> shuffledCards = Lists.newArrayList(p1.getZone(ZoneType.Library).getCards().threadSafeIterable());
      Collections.shuffle(shuffledCards, MyRandom.getRandom());
      List<Card> hand2 = shuffledCards.subList(0, p1.getMaxHandSize());
      float averageLandRatio = this.getLandRatio(lib1);
      if (this.getHandScore(hand1, averageLandRatio) > this.getHandScore(hand2, averageLandRatio)) {
         p1.getZone(ZoneType.Library).setCards(shuffledCards);
      }

      p1.drawCards(p1.getMaxHandSize());
   }

   private float getLandRatio(List<Card> deck) {
      int landCount = 0;

      for(Card c : deck) {
         if (c.isLand()) {
            ++landCount;
         }
      }

      if (landCount == 0) {
         return 0.0F;
      } else {
         return (float)landCount / (float)deck.size();
      }
   }

   private float getHandScore(List<Card> hand, float landRatio) {
      int landCount = 0;

      for(Card c : hand) {
         if (c.isLand()) {
            ++landCount;
         }
      }

      float averageCount = landRatio * (float)hand.size();
      return Math.abs(averageCount - (float)landCount);
   }

   public void startGame(GameOutcome lastGameOutcome) {
      this.startGame(lastGameOutcome, (Runnable)null);
   }

   public void startGame(GameOutcome lastGameOutcome, Runnable startGameHook) {
      Player first = this.determineFirstTurnPlayer(lastGameOutcome);
      GameType gameType = this.game.getRules().getGameType();

      while(!this.game.isGameOver()) {
         this.game.fireEvent(new GameEventGameStarted(gameType, first, this.game.getPlayers()));
         this.runPreOpeningHandActions(first);
         this.game.setAge(GameStage.Mulligan);

         for(Player p1 : this.game.getPlayers()) {
            if (StaticData.instance().getFilteredHandsEnabled()) {
               this.drawStartingHand(p1);
            } else {
               p1.drawCards(p1.getStartingHandSize());
            }

            BackupPlanService backupPlans = new BackupPlanService(p1);
            if (backupPlans.initializeExtraHands()) {
               backupPlans.chooseHand();
            }
         }

         if (this.game.getRules().getGameType() != GameType.Puzzle) {
            (new MulliganService(first)).perform();
         }

         if (this.game.isGameOver()) {
            break;
         }

         this.game.setAge(GameStage.Play);
         if (this.game.getRules().hasAppliedVariant(GameType.Planechase)) {
            first.initPlane();

            for(Player p1 : this.game.getPlayers()) {
               p1.createPlanechaseEffects(this.game);
            }
         }

         first = this.runOpeningHandActions(first);
         this.checkStateEffects(true);
         this.game.getTriggerHandler().runTrigger(TriggerType.NewGame, AbilityKey.newMap(), true);
         this.game.setStartingPlayer(first);
         this.game.getPhaseHandler().startFirstTurn(first, startGameHook);

         for(Player p : this.game.getRegisteredPlayers()) {
            p.setNumCardsInHandStartedThisTurnWith(p.getCardsIn(ZoneType.Hand).size());
            p.getController().autoPassCancel();
         }

         first = this.game.getPhaseHandler().getPlayerTurn();
         if (this.game.getAge() != GameStage.RestartedByKarn) {
            break;
         }
      }

   }

   private Player determineFirstTurnPlayer(GameOutcome lastGameOutcome) {
      Player goesFirst = null;
      if (this.game != null) {
         if (this.game.getRules().getGameType().equals(GameType.Puzzle)) {
            return (Player)this.game.getPlayers().get(0);
         }

         if (this.game.getRules().hasAppliedVariant(GameType.Archenemy)) {
            for(Player p : this.game.getPlayers()) {
               if (p.isArchenemy()) {
                  return p;
               }
            }
         }
      }

      Set<Player> powerPlayers = Sets.newHashSet();

      for(Card c : this.game.getCardsIn(ZoneType.Command)) {
         if (c.getName().equals("Power Play")) {
            powerPlayers.add(c.getOwner());
         }
      }

      if (!powerPlayers.isEmpty()) {
         List<Player> players = Lists.newArrayList(powerPlayers);
         Collections.shuffle(players, MyRandom.getRandom());
         return (Player)players.get(0);
      } else {
         boolean isFirstGame = lastGameOutcome == null;
         if (isFirstGame) {
            this.game.fireEvent(new GameEventFlipCoin());
            goesFirst = (Player)Aggregates.random(this.game.getPlayers());
         } else {
            for(Player p : this.game.getPlayers()) {
               if (!lastGameOutcome.isWinner(p.getRegisteredPlayer())) {
                  goesFirst = p;
                  break;
               }
            }
         }

         if (goesFirst == null) {
            goesFirst = (Player)this.game.getPlayers().get(0);
         }

         for(Player p : this.game.getPlayers()) {
            if (p != goesFirst) {
               p.getController().awaitNextInput();
            }
         }

         goesFirst = goesFirst.getController().chooseStartingPlayer(isFirstGame);
         return goesFirst;
      }
   }

   private void runPreOpeningHandActions(Player first) {
      Player takesAction = first;

      do {
         List<Card> ploys = CardLists.filter(takesAction.getCardsIn(ZoneType.Command), ((input) -> input.getName().equals("Emissary's Ploy")));
         CardCollectionView all = CardLists.filterControlledBy(this.game.getCardsInGame(), takesAction);
         List<Card> spires = CardLists.filter(all, ((input) -> input.getName().equals("Cryptic Spires")));
         int chosen = 1;
         List<Integer> cmc = Lists.newArrayList(new Integer[]{1, 2, 3});

         for(Card c : ploys) {
            if (!cmc.isEmpty()) {
               SpellAbility sa = new SpellAbility.EmptySa(ApiType.ChooseNumber, c, takesAction);
               chosen = takesAction.getController().chooseNumber(sa, "Emissary's Ploy", cmc, c.getOwner());
               cmc.remove(chosen);
            }

            c.setChosenNumber(chosen);
         }

         for(Card c : spires) {
            if (!c.hasMarkedColor() && takesAction.isAI()) {
               String var10000 = c.getTranslatedName();
               String prompt = var10000 + ": " + Localizer.getInstance().getMessage("lblChooseNColors", new Object[]{Lang.getNumeral(2)});
               SpellAbility sa = new SpellAbility.EmptySa(ApiType.ChooseColor, c, takesAction);
               sa.putParam("AILogic", "MostProminentInComputerDeck");
               c.setMarkedColors(takesAction.getController().chooseColors(prompt, sa, 2, 2, ColorSet.WUBRG));
            }
         }

         takesAction = this.game.getNextPlayerAfter(takesAction);
      } while(takesAction != first);

   }

   private Player runOpeningHandActions(Player first) {
      Player takesAction = first;
      Player newFirst = first;

      do {
         List<SpellAbility> usableFromOpeningHand = Lists.newArrayList();

         for(Card c : takesAction.getCardsIn(ZoneType.Hand)) {
            for(KeywordInterface inst : c.getKeywords()) {
               String kw = inst.getOriginal();
               if (kw.startsWith("MayEffectFromOpeningHand")) {
                  String[] split = kw.split(":");
                  String effName = split[1];
                  if (split.length <= 2 || !split[2].equalsIgnoreCase("!PlayFirst") || first != takesAction) {
                     SpellAbility effect = AbilityFactory.getAbility(c.getSVar(effName), c);
                     effect.setActivatingPlayer(takesAction);
                     usableFromOpeningHand.add(effect);
                  }
               }
            }
         }

         if (!usableFromOpeningHand.isEmpty()) {
            usableFromOpeningHand = takesAction.getController().chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
         }

         for(SpellAbility sa : usableFromOpeningHand) {
            if (takesAction.getZone(ZoneType.Hand).contains(sa.getHostCard())) {
               takesAction.getController().playSpellAbilityNoStack(sa, true);
               if (sa.hasParam("BecomeStartingPlayer")) {
                  newFirst = takesAction;
               }
            }
         }

         takesAction = this.game.getNextPlayerAfter(takesAction);
      } while(takesAction != first);

      return newFirst;
   }

   public void invoke(Runnable proc) {
      if (ThreadUtil.isGameThread()) {
         proc.run();
      } else {
         ThreadUtil.invokeInGameThread(proc);
      }

   }

   public void becomeMonarch(Player p, String set) {
      Player previous = this.game.getMonarch();
      if (p != null && !p.equals(previous)) {
         if (previous != null) {
            previous.removeMonarchEffect();
         }

         if (p.canBecomeMonarch()) {
            p.createMonarchEffect(set);
            this.game.setMonarch(p);
            Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(p);
            this.game.getTriggerHandler().runTrigger(TriggerType.BecomeMonarch, runParams, false);
         }
      }
   }

   public void takeInitiative(Player p, String set) {
      Player previous = this.game.getHasInitiative();
      if (p != null) {
         if (!p.equals(previous)) {
            if (previous != null) {
               previous.removeInitiativeEffect();
            }

            if (p.hasLost()) {
               this.takeInitiative(this.game.getNextPlayerAfter(p), set);
            }

            this.game.setHasInitiative(p);
            p.createInitiativeEffect(set);
         }

         Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(p);
         this.game.getTriggerHandler().runTrigger(TriggerType.TakesInitiative, runParams, false);
      }
   }

   public void scry(List<Player> players, int numScry, SpellAbility cause) {
      if (numScry > 0) {
         Map<Player, Integer> actualPlayers = Maps.newLinkedHashMap();

         for(Player p : players) {
            int playerScry = numScry;
            Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(p);
            repParams.put(AbilityKey.Source, cause);
            repParams.put(AbilityKey.Num, numScry);
            switch (this.game.getReplacementHandler().run(ReplacementType.Scry, repParams)) {
               case Updated:
                  playerScry = (Integer)repParams.get(AbilityKey.Num);
               case NotReplaced:
                  if (playerScry > 0) {
                     actualPlayers.put(p, playerScry);
                     if (players.size() > 1) {
                        this.revealTo(p.getCardsIn(ZoneType.Library, playerScry), p);
                     }
                  }
            }
         }

         Map<Player, ImmutablePair<CardCollection, CardCollection>> decisions = Maps.newLinkedHashMap();

         for(Map.Entry<Player, Integer> e : actualPlayers.entrySet()) {
            Player p = (Player)e.getKey();
            CardCollection topN = new CardCollection(p.getCardsIn(ZoneType.Library, (Integer)e.getValue()));
            ImmutablePair<CardCollection, CardCollection> decision = p.getController().arrangeForScry(topN);
            decisions.put(p, decision);
            int numToTop = decision.getLeft() == null ? 0 : ((CardCollection)decision.getLeft()).size();
            int numToBottom = decision.getRight() == null ? 0 : ((CardCollection)decision.getRight()).size();
            this.game.fireEvent(new GameEventScry(PlayerView.get(p), numToTop, numToBottom));
         }

         for(Map.Entry<Player, ImmutablePair<CardCollection, CardCollection>> e : decisions.entrySet()) {
            Player p = (Player)e.getKey();
            CardCollection toTop = (CardCollection)((ImmutablePair)e.getValue()).getLeft();
            CardCollection toBottom = (CardCollection)((ImmutablePair)e.getValue()).getRight();
            int numLookedAt = 0;
            if (toTop != null) {
               numLookedAt += toTop.size();
               Collections.reverse(toTop);

               for(Card c : toTop) {
                  this.moveToLibrary(c, cause, (Map)null);
               }
            }

            if (toBottom != null) {
               numLookedAt += toBottom.size();

               for(Card c : toBottom) {
                  this.moveToBottomOfLibrary(c, cause, (Map)null);
               }
            }

            if (cause != null) {
               Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(p);
               runParams.put(AbilityKey.ScryNum, numLookedAt);
               runParams.put(AbilityKey.ScryBottom, toBottom == null ? 0 : toBottom.size());
               this.game.getTriggerHandler().runTrigger(TriggerType.Scry, runParams, false);
            }

            p.incScryThisTurn();
         }

      }
   }

   public CardCollection mill(PlayerCollection millers, int numCards, ZoneType destination, SpellAbility sa, Map<AbilityKey, Object> moveParams) {
      boolean showRevealDialog = sa != null && sa.hasParam("ShowMilledCards");
      CardCollection milled = new CardCollection();

      for(Player p : millers) {
         if (p.isInGame()) {
            CardCollectionView milledPlayer = p.mill(numCards, destination, sa, moveParams);
            milled.addAll(milledPlayer);
            String toZoneStr = destination.equals(ZoneType.Graveyard) ? "" : " (" + Localizer.getInstance().getMessage("lblMilledToZone", new Object[]{destination.getTranslatedName()}) + ")";
            if (showRevealDialog) {
               String message = Localizer.getInstance().getMessage("lblMilledCards", new Object[0]);
               boolean addSuffix = !toZoneStr.isEmpty();
               this.reveal(milledPlayer, destination, p, false, message, addSuffix);
            }

            Game var10000 = this.game;
            GameLogEntryType var10003 = GameLogEntryType.ZONE_CHANGE;
            String var10004 = String.valueOf(p);
            var10000.fireEvent(new GameEventAddLog(var10003, var10004 + " milled " + Lang.joinHomogenous(milledPlayer) + toZoneStr + "."));
         }
      }

      if (!milled.isEmpty()) {
         Map<AbilityKey, Object> runParams = AbilityKey.newMap();
         runParams.put(AbilityKey.Cards, milled);
         this.game.getTriggerHandler().runTrigger(TriggerType.MilledAll, runParams, false);
      }

      return milled;
   }

   public void dealDamage(boolean isCombat, CardDamageTable damageMap, CardDamageTable preventMap, GameEntityCounterTable counterTable, SpellAbility cause) {
      if (isCombat) {
         for(Map.Entry<GameEntity, Map<Card, Integer>> et : damageMap.columnMap().entrySet()) {
            GameEntity ge = (GameEntity)et.getKey();
            if (ge instanceof Card) {
               Card c = (Card)ge;
               c.clearAssignedDamage();
            }
         }
      }

      this.game.getReplacementHandler().runReplaceDamage(isCombat, damageMap, preventMap, counterTable, cause);
      Map<Card, Integer> lethalDamage = Maps.newHashMap();
      Map<Integer, Card> lkiCache = Maps.newHashMap();

      for(Map.Entry<Card, Map<GameEntity, Integer>> et : damageMap.rowMap().entrySet()) {
         Card sourceLKI = (Card)et.getKey();
         int sum = 0;

         for(Map.Entry<GameEntity, Integer> e : ((Map<GameEntity, Integer>)et.getValue()).entrySet()) {
            if ((Integer)e.getValue() > 0) {
               Object var15 = e.getKey();
               if (var15 instanceof Card) {
                  Card c = (Card)var15;
                  if (!lethalDamage.containsKey(c)) {
                     lethalDamage.put(c, c.getExcessDamageValue(false));
                  }
               }

               e.setValue(((GameEntity)e.getKey()).addDamageAfterPrevention((Integer)e.getValue(), sourceLKI, cause, isCombat, counterTable));
               sum = NovaMath.add(sum, (Integer)e.getValue()); // Forge Nova: saturated (lifelink from huge damage)
               sourceLKI.getDamageHistory().registerDamage((Integer)e.getValue(), isCombat, sourceLKI, (GameEntity)e.getKey(), lkiCache);
            }
         }

         if (sum > 0 && sourceLKI.hasKeyword(Keyword.LIFELINK)) {
            sourceLKI.getController().gainLife(sum, sourceLKI, cause);
         }
      }

      damageMap.triggerExcessDamage(isCombat, lethalDamage, this.game, cause, lkiCache);
      Map<Player, Integer> lifeLostAllDamageMap = Maps.newHashMap();

      for(Player p : this.game.getPlayers()) {
         int lost = p.processDamage();
         if (lost > 0) {
            lifeLostAllDamageMap.put(p, lost);
         }
      }

      if (isCombat) {
         this.game.getTriggerHandler().runWaitingTriggers();
      }

      if (!lifeLostAllDamageMap.isEmpty()) {
         Map<AbilityKey, Object> runParams = AbilityKey.mapFromPIMap(lifeLostAllDamageMap);
         this.game.getTriggerHandler().runTrigger(TriggerType.LifeLostAll, runParams, false);
      }

      if (cause != null) {
         Card sourceLKI = this.game.getChangeZoneLKIInfo(cause.getHostCard());
         if (cause.hasParam("RememberDamaged")) {
            for(GameEntity e : damageMap.row(sourceLKI).keySet()) {
               cause.getHostCard().addRemembered(e);
            }
         }

         if (cause.hasParam("RememberAmount")) {
            cause.getHostCard().addRemembered(damageMap.totalAmount());
         }
      }

      preventMap.triggerPreventDamage(isCombat);
      preventMap.clear();
      damageMap.triggerDamageDoneOnce(isCombat, this.game);
      damageMap.clear();
      counterTable.replaceCounterEffect(this.game, cause);
      counterTable.clear();
   }

   public void completeDungeon(Player player, Card dungeon) {
      player.addCompletedDungeon(dungeon);
      this.ceaseToExist(dungeon, true);
      Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(dungeon);
      runParams.put(AbilityKey.Player, player);
      this.game.getTriggerHandler().runTrigger(TriggerType.DungeonCompleted, runParams, false);
   }

   private boolean attachAuraOnIndirectETB(Card source, Map<AbilityKey, Object> params) {
      if (!source.hasKeyword(Keyword.ENCHANT)) {
         return false;
      } else {
         SpellAbility aura = source.getCurrentState().getAuraSpell();
         if (aura == null) {
            return false;
         } else {
            aura.setActivatingPlayer(source.getController());
            Set<ZoneType> zones = EnumSet.noneOf(ZoneType.class);
            boolean canTargetPlayer = false;

            for(KeywordInterface ki : source.getKeywords(Keyword.ENCHANT)) {
               String o = ki.getOriginal();
               String[] m = o.split(":");
               String v = m[1];
               if (v.contains("inZone")) {
                  zones.add(ZoneType.Graveyard);
               } else {
                  zones.add(ZoneType.Battlefield);
               }

               if (v.startsWith("Player") || v.startsWith("Opponent")) {
                  canTargetPlayer = true;
               }
            }

            Player p = source.getController();
            if (canTargetPlayer) {
               FCollection<Player> players = this.game.getPlayers().filter(PlayerPredicates.canBeAttached(source, (SpellAbility)null));
               Player pa = (Player)p.getController().chooseSingleEntityForEffect(players, aura, Localizer.getInstance().getMessage("lblSelectAPlayerAttachSourceTo", new Object[]{source.getTranslatedName()}), (Map)null);
               if (pa != null) {
                  source.attachToEntity(pa, (SpellAbility)null, true);
                  return true;
               }
            } else {
               CardCollection list = new CardCollection();
               if (params != null) {
                  if (zones.contains(ZoneType.Battlefield)) {
                     list.addAll((CardCollectionView)params.get(AbilityKey.LastStateBattlefield));
                     zones.remove(ZoneType.Battlefield);
                  }

                  if (zones.contains(ZoneType.Graveyard)) {
                     list.addAll((CardCollectionView)params.get(AbilityKey.LastStateGraveyard));
                     zones.remove(ZoneType.Graveyard);
                  }
               }

               list.addAll(this.game.getCardsIn((Iterable)zones));
               list = CardLists.filter(list, CardPredicates.canBeAttached(source, (SpellAbility)null));
               if (list.isEmpty()) {
                  return false;
               }

               Card o = (Card)p.getController().chooseSingleEntityForEffect(list, aura, Localizer.getInstance().getMessage("lblSelectACardAttachSourceTo", new Object[]{source.getTranslatedName()}), (Map)null);
               if (o != null) {
                  source.attachToEntity(this.game.getCardState(o), (SpellAbility)null, true);
                  return true;
               }
            }

            return false;
         }
      }
   }

   public CardCollectionView getLastState(AbilityKey key, SpellAbility cause, Map<AbilityKey, Object> params, boolean refreshIfEmpty) {
      CardCollectionView lastState = null;
      if (params != null) {
         lastState = (CardCollectionView)params.get(key);
      }

      if (lastState == null && cause != null) {
         if (key == AbilityKey.LastStateBattlefield) {
            lastState = cause.getLastStateBattlefield();
         }

         if (key == AbilityKey.LastStateGraveyard) {
            lastState = cause.getLastStateGraveyard();
         }
      }

      if (lastState == null) {
         if (key == AbilityKey.LastStateBattlefield) {
            if (refreshIfEmpty) {
               lastState = this.game.copyLastStateBattlefield();
            } else {
               lastState = this.game.getLastStateBattlefield();
            }
         }

         if (key == AbilityKey.LastStateGraveyard) {
            if (refreshIfEmpty) {
               lastState = this.game.copyLastStateGraveyard();
            } else {
               lastState = this.game.getLastStateGraveyard();
            }
         }
      }

      return lastState;
   }
}
