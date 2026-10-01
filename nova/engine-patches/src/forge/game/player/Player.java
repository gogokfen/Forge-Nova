package forge.game.player;

import forge.game.NovaMath;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.TreeBasedTable;
import forge.ImageKeys;
import forge.LobbyPlayer;
import forge.StaticData;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.GamePieceType;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameEntity;
import forge.game.GameEntityCounterTable;
import forge.game.GameLogEntryType;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.DetachedCardEffect;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.card.CardFactoryUtil;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CardState;
import forge.game.card.CardUtil;
import forge.game.card.CardView;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.event.EventValueChangeType;
import forge.game.event.GameEventAddLog;
import forge.game.event.GameEventCardSacrificed;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventManaBurn;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventPlayerControl;
import forge.game.event.GameEventPlayerCounters;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventPlayerPoisoned;
import forge.game.event.GameEventPlayerRadiation;
import forge.game.event.GameEventPlayerShardsChanged;
import forge.game.event.GameEventPlayerStatsChanged;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpeedChanged;
import forge.game.event.GameEventSurveil;
import forge.game.event.GameEventZone;
import forge.game.keyword.Companion;
import forge.game.keyword.IKeywordsChange;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordCollection;
import forge.game.keyword.KeywordInterface;
import forge.game.keyword.KeywordsChange;
import forge.game.mana.ManaPool;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementResult;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantBeCast;
import forge.game.staticability.StaticAbilityCantBecomeMonarch;
import forge.game.staticability.StaticAbilityCantDiscard;
import forge.game.staticability.StaticAbilityCantDraw;
import forge.game.staticability.StaticAbilityCantGainLosePayLife;
import forge.game.staticability.StaticAbilityCantPutCounter;
import forge.game.staticability.StaticAbilityCantTarget;
import forge.game.staticability.StaticAbilityDevotion;
import forge.game.staticability.StaticAbilityLayer;
import forge.game.staticability.StaticAbilitySurveilNum;
import forge.game.staticability.StaticAbilityTurnPhaseReversed;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.zone.PlayerZone;
import forge.game.zone.PlayerZoneBattlefield;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.util.Aggregates;
import forge.util.IterableUtil;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.util.collect.FCollection;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;

public class Player extends GameEntity implements Comparable<Player> {
   public static final List<ZoneType> ALL_ZONES;
   private int life = 20;
   private int startingLife = 20;
   private int lifeStartedThisTurnWith;
   private int lifeLostThisTurn;
   private int lifeLostLastTurn;
   private int lifeGainedThisTurn;
   private int lifeGainedTimesThisTurn;
   private int lifeGainedByTeamThisTurn;
   private int maxHandSize;
   private int startingHandSize;
   private boolean unlimitedHandSize;
   private Card lastDrawnCard;
   private int numDrawnThisTurn;
   private int numExtraDrawnThisTurn;
   private int numDrawnLastTurn;
   private int numDrawnThisDrawStep;
   private int numCardsInHandStartedThisTurnWith;
   private int numExploredThisTurn;
   private int numTokenCreatedThisTurn;
   private int numForetoldThisTurn;
   private int landsPlayedThisTurn;
   private int landsPlayedLastTurn;
   private int numPowerSurgeLands;
   private int spellsCastThisGame;
   private int spellsCastLastTurn;
   private List<Card> spellsCastSinceBeginningOfLastTurn;
   private int investigatedThisTurn;
   private int scryThisTurn;
   private int surveilThisTurn;
   private int committedCrimeThisTurn;
   private int numFlipsThisTurn;
   private int numRollsThisTurn;
   private List<Integer> diceRollsThisTurn;
   private int expentThisTurn;
   private int numLibrarySearchedOwn;
   private int venturedThisTurn;
   private int attractionsVisitedThisTurn;
   private int descended;
   private int numRingTemptedYou;
   private int devotionMod;
   private Card ringBearer;
   private Card theRing;
   private int speed;
   private List<Card> discardedThisTurn;
   private List<Card> sacrificedThisTurn;
   private int simultaneousDamage;
   private int lastTurnNr;
   private String namedCard;
   private final Map<String, FCollection<String>> notes;
   private final Map<String, Integer> notedNum;
   private final Map<String, String> draftNotes;
   private CardCollection inboundTokens;
   private KeywordCollection keywords;
   private final Table<Long, String, KeywordInterface> storedKeywords;
   private Table<Long, Long, IKeywordsChange> changedKeywords;
   private Map<GameEntity, List<Card>> attackedThisTurn;
   private List<Player> attackedPlayersLastTurn;
   private List<Player> attackedPlayersThisCombat;
   private boolean beenDealtCombatDamageSinceLastTurn;
   private boolean tappedLandForManaThisTurn;
   private final Map<ZoneType, PlayerZone> zones;
   private List<PlayerZone> extraZones;
   private final Map<Long, Integer> adjustLandPlays;
   private final Set<Long> adjustLandPlaysInfinite;
   private Map<Card, Card> maingameCardsMap;
   private CardCollection currentPlanes;
   private CardCollection planeswalkedToThisTurn;
   private NavigableMap<Long, Pair<Player, PlayerController>> controlledBy;
   private NavigableMap<Long, Player> controlledWhileSearching;
   private int numManaShards;
   private int teamNumber;
   private PlayerController controller;
   private final Game game;
   private boolean triedToDrawFromEmptyLibrary;
   private CardCollection lostOwnership;
   private CardCollection gainedOwnership;
   private ManaPool manaPool;
   private Deque<SpellAbility> paidForStack;
   private List<Card> completedDungeons;
   private final CardCollection commanders;
   private final Map<Card, Integer> commanderCast;
   private final Map<Card, Integer> commanderDamage;
   private DetachedCardEffect commanderEffect;
   private Card monarchEffect;
   private Card initiativeEffect;
   private Card blessingEffect;
   private Card enduringStoryEffect;
   private Card contraptionSprocketEffect;
   private Card radiationEffect;
   private Card keywordEffect;
   private Card speedEffect;
   private Map<Long, Integer> additionalVotes;
   private Map<Long, Integer> additionalOptionalVotes;
   private SortedSet<Long> controlVotes;
   private Map<Long, Integer> additionalVillainousChoices;
   private EnumSet<TriggerType> elementalBendThisTurn;
   private NavigableMap<Long, Player> declaresAttackers;
   private NavigableMap<Long, Player> declaresBlockers;
   private int crankCounter;
   private PlayerStatistics stats;
   private final AchievementTracker achievementTracker;
   private final PlayerView view;

   public Player(String name0, Game game0, int id0) {
      super(id0);
      this.lifeStartedThisTurnWith = this.startingLife;
      this.maxHandSize = 7;
      this.startingHandSize = 7;
      this.unlimitedHandSize = false;
      this.spellsCastSinceBeginningOfLastTurn = Lists.newArrayList();
      this.diceRollsThisTurn = Lists.newArrayList();
      this.discardedThisTurn = new ArrayList();
      this.sacrificedThisTurn = new ArrayList();
      this.simultaneousDamage = 0;
      this.lastTurnNr = 0;
      this.namedCard = "";
      this.notes = Maps.newHashMap();
      this.notedNum = Maps.newHashMap();
      this.draftNotes = Maps.newHashMap();
      this.inboundTokens = new CardCollection();
      this.keywords = new KeywordCollection();
      this.storedKeywords = TreeBasedTable.create();
      this.changedKeywords = TreeBasedTable.create();
      this.attackedThisTurn = new HashMap();
      this.attackedPlayersLastTurn = new ArrayList();
      this.attackedPlayersThisCombat = new ArrayList();
      this.beenDealtCombatDamageSinceLastTurn = false;
      this.tappedLandForManaThisTurn = false;
      this.zones = Maps.newEnumMap(ZoneType.class);
      this.extraZones = null;
      this.adjustLandPlays = Maps.newHashMap();
      this.adjustLandPlaysInfinite = Sets.newHashSet();
      this.maingameCardsMap = Maps.newHashMap();
      this.currentPlanes = new CardCollection();
      this.planeswalkedToThisTurn = new CardCollection();
      this.controlledBy = Maps.newTreeMap();
      this.controlledWhileSearching = Maps.newTreeMap();
      this.teamNumber = -1;
      this.triedToDrawFromEmptyLibrary = false;
      this.lostOwnership = new CardCollection();
      this.gainedOwnership = new CardCollection();
      this.manaPool = new ManaPool(this);
      this.paidForStack = new ArrayDeque();
      this.completedDungeons = new ArrayList();
      this.commanders = new CardCollection();
      this.commanderCast = Maps.newHashMap();
      this.commanderDamage = Maps.newHashMap();
      this.commanderEffect = null;
      this.additionalVotes = Maps.newHashMap();
      this.additionalOptionalVotes = Maps.newHashMap();
      this.controlVotes = Sets.newTreeSet();
      this.additionalVillainousChoices = Maps.newHashMap();
      this.elementalBendThisTurn = EnumSet.noneOf(TriggerType.class);
      this.declaresAttackers = Maps.newTreeMap();
      this.declaresBlockers = Maps.newTreeMap();
      this.crankCounter = 3;
      this.stats = new PlayerStatistics();
      this.achievementTracker = new AchievementTracker();
      this.game = game0;

      for(ZoneType z : ALL_ZONES) {
         PlayerZone toPut = (PlayerZone)(z == ZoneType.Battlefield ? new PlayerZoneBattlefield(z, this) : new PlayerZone(z, this));
         this.zones.put(z, toPut);
      }

      this.view = new PlayerView(id0, this.game.getTracker());
      this.view.updateMaxHandSize(this);
      this.view.updateKeywords(this);
      this.view.updateMaxLandPlay(this);
      this.view.setDraftNotes(this.getDraftNotes());
      this.setName(this.chooseName(name0));
   }

   public final AchievementTracker getAchievementTracker() {
      return this.achievementTracker;
   }

   private String chooseName(String originalName) {
      String nameCandidate = originalName;

      for(int i = 2; i <= 8; ++i) {
         boolean haveDuplicates = false;

         for(Player p : this.game.getPlayers()) {
            if (p.getName().equals(nameCandidate)) {
               haveDuplicates = true;
               break;
            }
         }

         if (!haveDuplicates) {
            return nameCandidate;
         }

         String var10000 = Lang.getInstance().getOrdinal(i);
         nameCandidate = var10000 + " " + originalName;
      }

      return nameCandidate;
   }

   public Game getGame() {
      return this.game;
   }

   public final PlayerStatistics getStats() {
      return this.stats;
   }

   public final int getTeam() {
      return this.teamNumber;
   }

   public final void setTeam(int iTeam) {
      this.teamNumber = iTeam;
   }

   public boolean isArchenemy() {
      return this.getZone(ZoneType.SchemeDeck).size() > 0;
   }

   public void setSchemeInMotion(SpellAbility cause) {
      this.setSchemeInMotion(cause, this.getZone(ZoneType.SchemeDeck).get(0));
   }

   public void setSchemeInMotion(SpellAbility cause, Card scheme) {
      if (this.game.getReplacementHandler().run(ReplacementType.SetInMotion, AbilityKey.mapFromAffected(this)) == ReplacementResult.NotReplaced) {
         Map<AbilityKey, Object> moveParams = AbilityKey.newMap();
         moveParams.put(AbilityKey.LastStateBattlefield, this.game.getLastStateBattlefield());
         moveParams.put(AbilityKey.LastStateGraveyard, this.game.getLastStateGraveyard());
         this.game.getAction().moveToCommand(scheme, cause);
         Map<AbilityKey, Object> runParams = AbilityKey.newMap();
         runParams.put(AbilityKey.Scheme, scheme);
         this.game.getTriggerHandler().runTrigger(TriggerType.SetInMotion, runParams, false);
      }
   }

   public final PlayerCollection getOpponents() {
      return this.game.getPlayersInTurnOrder(this).filter(PlayerPredicates.isOpponentOf(this));
   }

   public final PlayerCollection getRegisteredOpponents() {
      return this.game.getRegisteredPlayers().filter(PlayerPredicates.isOpponentOf(this));
   }

   public void updateOpponentsForView() {
      this.view.updateOpponents(this);
   }

   public void updateFlashbackForView() {
      this.view.updateFlashback(this);
      this.game.fireEvent(new GameEventZone(ZoneType.Flashback, this, EventValueChangeType.Added, (Card)null));
   }

   public Player getSingleOpponent() {
      if (this.game.getRegisteredPlayers().size() == 2) {
         for(Player p : this.game.getRegisteredPlayers()) {
            if (p.isOpponentOf(this)) {
               return p;
            }
         }
      }

      return null;
   }

   public final int getOpponentsSmallestLifeTotal() {
      return Aggregates.min(this.getOpponents(), Player::getLife);
   }

   public final int getOpponentsGreatestLifeTotal() {
      return Aggregates.max(this.getOpponents(), Player::getLife);
   }

   public final int getOpponentsTotalPoisonCounters() {
      return Aggregates.sum(this.getOpponents(), Player::getPoisonCounters);
   }

   public final PlayerCollection getAllies() {
      return this.getAllOtherPlayers().filter(PlayerPredicates.isOpponentOf(this).negate());
   }

   public final PlayerCollection getTeamMates(boolean inclThis) {
      PlayerCollection col = new PlayerCollection();
      if (inclThis) {
         col.add(this);
      }

      col.addAll(this.getAllOtherPlayers().filter(PlayerPredicates.sameTeam(this)));
      return col;
   }

   public final PlayerCollection getYourTeam() {
      return this.getTeamMates(true);
   }

   public final PlayerCollection getAllOtherPlayers() {
      PlayerCollection result = new PlayerCollection(this.game.getPlayers());
      result.remove(this);
      return result;
   }

   public final Player getWeakestOpponent() {
      return this.getOpponents().min(PlayerPredicates.compareByLife());
   }

   public final Player getStrongestOpponent() {
      return this.getOpponents().max(PlayerPredicates.compareByLife());
   }

   public boolean isOpponentOf(Player other) {
      return other != this && other != null && (other.teamNumber < 0 || other.teamNumber != this.teamNumber);
   }

   public boolean isOpponentOf(String other) {
      Player otherPlayer = null;

      for(Player p : this.game.getPlayers()) {
         if (p.getName().equals(other)) {
            otherPlayer = p;
            break;
         }
      }

      return this.isOpponentOf(otherPlayer);
   }

   public final boolean setLife(int newLife, SpellAbility sa) {
      boolean change = false;
      if (this.life > newLife) {
         change = this.loseLife(NovaMath.sub(this.life, newLife), false, false, sa) > 0; // Forge Nova: saturated
      } else if (newLife > this.life) {
         change = this.gainLife(NovaMath.sub(newLife, this.life), sa == null ? null : sa.getHostCard(), sa);
      }

      return change;
   }

   public final int getStartingLife() {
      return this.startingLife;
   }

   public final void setStartingLife(int startLife) {
      this.startingLife = startLife;
      this.life = startLife;
      this.view.updateLife(this);
   }

   public final int getStartingLibrarySize() {
      return this.getRegisteredPlayer().getDeck().getMain().countAll();
   }

   public final int getLife() {
      return this.life;
   }

   public final boolean gainLife(int lifeGain, Card source, SpellAbility sa) {
      if (this.canGainLife() && lifeGain > 0) {
         Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
         repParams.put(AbilityKey.LifeGained, lifeGain);
         repParams.put(AbilityKey.SourceSA, sa);
         switch (this.getGame().getReplacementHandler().run(ReplacementType.GainLife, repParams)) {
            case Updated:
               if (!this.equals(repParams.get(AbilityKey.Affected))) {
                  return false;
               } else {
                  lifeGain = (Integer)repParams.get(AbilityKey.LifeGained);
               }
            case NotReplaced:
               if (lifeGain <= 0) {
                  return false;
               }

               int oldLife = this.life;
               this.life = NovaMath.add(this.life, lifeGain); // Forge Nova: saturated (lifelink from huge damage)
               this.view.updateLife(this);
               boolean firstGain = this.lifeGainedTimesThisTurn == 0;
               this.lifeGainedThisTurn = NovaMath.add(this.lifeGainedThisTurn, lifeGain);
               ++this.lifeGainedTimesThisTurn;

               for(Player p : this.getTeamMates(true)) {
                  p.addLifeGainedByTeamThisTurn(lifeGain);
               }

               Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
               runParams.put(AbilityKey.LifeAmount, lifeGain);
               runParams.put(AbilityKey.Source, source);
               runParams.put(AbilityKey.SourceSA, sa);
               runParams.put(AbilityKey.FirstTime, firstGain);
               this.game.getTriggerHandler().runTrigger(TriggerType.LifeGained, runParams, false);
               this.game.fireEvent(new GameEventPlayerLivesChanged(this, oldLife, this.life));
               return true;
            default:
               return false;
         }
      } else {
         return false;
      }
   }

   public final boolean canGainLife() {
      return this.isInGame() && !StaticAbilityCantGainLosePayLife.anyCantGainLife(this);
   }

   public final int loseLife(int toLose, boolean damage, boolean manaBurn, SpellAbility cause) {
      if (toLose > 0 && this.canLoseLife()) {
         int oldLife = this.life;
         Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
         repParams.put(AbilityKey.Amount, toLose);
         repParams.put(AbilityKey.IsDamage, damage);
         switch (this.getGame().getReplacementHandler().run(ReplacementType.LifeReduced, repParams)) {
            case Updated:
               if (!this.equals(repParams.get(AbilityKey.Affected))) {
                  return 0;
               } else {
                  toLose = (Integer)repParams.get(AbilityKey.Amount);
                  if (toLose <= 0) {
                     return 0;
                  }
               }
            case NotReplaced:
               this.life = NovaMath.sub(this.life, toLose); // Forge Nova: saturated (a negative life minus huge damage wrapped to a positive one)
               this.view.updateLife(this);
               if (manaBurn) {
                  this.game.fireEvent(new GameEventManaBurn(PlayerView.get(this), true, toLose));
               } else {
                  this.game.fireEvent(new GameEventPlayerLivesChanged(this, oldLife, this.life));
               }

               boolean firstLost = this.lifeLostThisTurn == 0;
               this.lifeLostThisTurn = NovaMath.add(this.lifeLostThisTurn, toLose);
               Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
               runParams.put(AbilityKey.LifeAmount, toLose);
               runParams.put(AbilityKey.FirstTime, firstLost);
               runParams.put(AbilityKey.SpellAbility, cause);
               this.game.getTriggerHandler().runTrigger(TriggerType.LifeLost, runParams, false);
               return toLose;
            default:
               return 0;
         }
      } else {
         return 0;
      }
   }

   public final boolean canLoseLife() {
      return this.isInGame() && !StaticAbilityCantGainLosePayLife.anyCantLoseLife(this);
   }

   public final boolean canPayLife(int lifePayment, boolean effect, SpellAbility cause) {
      if (lifePayment > 0 && this.life < lifePayment) {
         return false;
      } else {
         return lifePayment <= 0 || !StaticAbilityCantGainLosePayLife.anyCantPayLife(this, effect, cause);
      }
   }

   public final boolean payLife(int lifePayment, SpellAbility cause, boolean effect) {
      if (lifePayment <= 0) {
         cause.setPaidLife(0);
         return true;
      } else if (!this.canPayLife(lifePayment, effect, cause)) {
         return false;
      } else {
         Map<AbilityKey, Object> replaceParams = AbilityKey.mapFromAffected(this);
         replaceParams.put(AbilityKey.Amount, lifePayment);
         replaceParams.put(AbilityKey.Cause, cause);
         replaceParams.put(AbilityKey.EffectOnly, effect);
         if (cause.isReplacementAbility() && effect) {
            replaceParams.putAll(cause.getReplacingObjects());
         }

         switch (this.getGame().getReplacementHandler().run(ReplacementType.PayLife, replaceParams)) {
            case Replaced:
               return true;
            case Prevented:
            case Skipped:
               return false;
            default:
               int lost = this.loseLife(lifePayment, false, false, cause);
               cause.setPaidLife(lifePayment);
               Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
               runParams.put(AbilityKey.LifeAmount, lifePayment);
               this.game.getTriggerHandler().runTrigger(TriggerType.PayLife, runParams, false);
               if (lost > 0) {
                  boolean runAll = false;
                  Map<Player, Integer> lossMap = cause.getLoseLifeMap();
                  if (lossMap == null) {
                     lossMap = Maps.newHashMap();
                     runAll = true;
                  }

                  lossMap.put(this, lost);
                  if (runAll) {
                     Map<AbilityKey, Object> runParams2 = AbilityKey.mapFromPIMap(lossMap);
                     this.game.getTriggerHandler().runTrigger(TriggerType.LifeLostAll, runParams2, false);
                  }
               }

               return true;
         }
      }
   }

   public final boolean canPayEnergy(int energyPayment) {
      int cnt = this.getCounters(CounterEnumType.ENERGY);
      return cnt >= energyPayment;
   }

   public final boolean loseEnergy(int lostEnergy) {
      int cnt = this.getCounters(CounterEnumType.ENERGY);
      if (lostEnergy > cnt) {
         return false;
      } else {
         this.subtractCounter(CounterEnumType.ENERGY, lostEnergy, this);
         return true;
      }
   }

   public final boolean payEnergy(int energyPayment, Card source) {
      if (energyPayment <= 0) {
         return true;
      } else {
         return this.canPayEnergy(energyPayment) && this.loseEnergy(energyPayment);
      }
   }

   public final boolean canPayShards(int shardPayment) {
      int cnt = this.getNumManaShards();
      return cnt >= shardPayment;
   }

   public final int loseShards(int lostShards) {
      int cnt = this.getNumManaShards();
      if (lostShards > cnt) {
         return -1;
      } else {
         cnt -= lostShards;
         this.setNumManaShards(cnt);
         return cnt;
      }
   }

   public final boolean payShards(int shardPayment, Card source) {
      if (shardPayment <= 0) {
         return true;
      } else {
         return this.canPayShards(shardPayment) && this.loseShards(shardPayment) > -1;
      }
   }

   public final int addDamageAfterPrevention(int amount, Card source, SpellAbility cause, boolean isCombat, GameEntityCounterTable counterTable) {
      if (amount > 0 && !this.hasLost()) {
         boolean infect = source.isInfectDamage(this);
         int poisonCounters = 0;
         if (infect) {
            poisonCounters = NovaMath.add(poisonCounters, amount);
         } else {
            this.simultaneousDamage = NovaMath.add(this.simultaneousDamage, amount); // Forge Nova: two huge attackers wrapped to "no damage"
         }

         if (isCombat) {
            poisonCounters = NovaMath.add(poisonCounters, source.getKeywordMagnitude(Keyword.TOXIC));
         }

         if (poisonCounters > 0) {
            this.addPoisonCounters(poisonCounters, source.getController(), counterTable);
         }

         if (source.isCommander() && isCombat && !this.getGame().getRules().hasAppliedVariant(GameType.Oathbreaker) && !this.getGame().getRules().hasAppliedVariant(GameType.TinyLeaders) && !this.getGame().getRules().hasAppliedVariant(GameType.Brawl)) {
            Card realCommander = source.getRealCommander();
            this.addCommanderDamage(realCommander, amount);
            this.view.updateCommanderDamage(this);
            if (realCommander != source) {
               this.view.updateMergedCommanderDamage(source, realCommander);
            }
         }

         Map<AbilityKey, Object> runParams = AbilityKey.newMap();
         runParams.put(AbilityKey.DamageSource, source);
         runParams.put(AbilityKey.DamageTarget, this);
         runParams.put(AbilityKey.Cause, cause);
         runParams.put(AbilityKey.DamageAmount, amount);
         runParams.put(AbilityKey.IsCombatDamage, isCombat);
         runParams.put(AbilityKey.DefendingPlayer, this.game.getCombat() != null ? this.game.getCombat().getDefendingPlayerRelatedTo(source) : null);
         this.game.getTriggerHandler().runTrigger(TriggerType.DamageDone, runParams, isCombat);
         this.game.fireEvent(new GameEventPlayerDamaged(PlayerView.get(this), CardView.get(source), amount, isCombat, infect));
         return amount;
      } else {
         return 0;
      }
   }

   public final int staticReplaceDamage(int damage, Card source, boolean isCombat) {
      int restDamage = damage;

      for(Card c : this.game.getCardsIn(ZoneType.Battlefield)) {
         if (c.getName().equals("Sulfuric Vapors")) {
            if (source.isSpell() && source.isRed()) {
               ++restDamage;
            }
         } else if (c.getName().equals("Pyromancer's Swath")) {
            if (c.getController().equals(source.getController()) && (source.isInstant() || source.isSorcery())) {
               restDamage += 2;
            }
         } else if (c.getName().equals("Pyromancer's Gauntlet")) {
            if (c.getController().equals(source.getController()) && source.isRed() && (source.isInstant() || source.isSorcery() || source.isPlaneswalker())) {
               restDamage += 2;
            }
         } else if (!c.getName().equals("Furnace of Rath") && !c.getName().equals("Dictate of the Twin Gods")) {
            if (c.getName().equals("Gratuitous Violence")) {
               if (c.getController().equals(source.getController()) && source.isCreature()) {
                  restDamage *= 2;
               }
            } else if (c.getName().equals("Fire Servant")) {
               if (c.getController().equals(source.getController()) && source.isRed() && (source.isInstant() || source.isSorcery())) {
                  restDamage *= 2;
               }
            } else if (c.getName().equals("Curse of Bloodletting")) {
               if (c.getEntityAttachedTo().equals(this)) {
                  restDamage *= 2;
               }
            } else if (c.getName().equals("Gisela, Blade of Goldnight")) {
               if (!c.getController().equals(this)) {
                  restDamage *= 2;
               }
            } else if (c.getName().equals("Inquisitor's Flail")) {
               if (isCombat && c.getEquipping() != null && c.getEquipping().equals(source)) {
                  restDamage *= 2;
               }
            } else if (c.getName().equals("Ghosts of the Innocent")) {
               restDamage /= 2;
            } else if (c.getName().equals("Benevolent Unicorn")) {
               if (source.isSpell()) {
                  --restDamage;
               }
            } else if (c.getName().equals("Divine Presence")) {
               if (restDamage > 3) {
                  restDamage = 3;
               }
            } else if (c.getName().equals("Forethought Amulet")) {
               if (c.getController().equals(this) && (source.isInstant() || source.isSorcery()) && restDamage > 2) {
                  restDamage = 2;
               }
            } else if (c.getName().equals("Elderscale Wurm")) {
               if (c.getController().equals(this) && this.getLife() >= 7 && this.getLife() - restDamage < 7) {
                  restDamage = this.getLife() - 7;
                  if (restDamage < 0) {
                     restDamage = 0;
                  }
               }
            } else if (c.getName().equals("Obosh, the Preypiercer") && c.getController().equals(source.getController()) && source.getCMC() % 2 != 0) {
               restDamage *= 2;
            }
         } else {
            restDamage *= 2;
         }
      }

      for(Card c : this.game.getCardsIn(ZoneType.Command)) {
         if (c.getName().equals("Insult Effect")) {
            if (c.getController().equals(source.getController())) {
               restDamage *= 2;
            }
         } else if (c.getName().equals("Mishra") && c.isCreature() && c.getController().equals(source.getController())) {
            restDamage *= 2;
         }
      }

      return restDamage;
   }

   public final int processDamage() {
      int lost = this.loseLife(this.simultaneousDamage, true, false, (SpellAbility)null);
      this.simultaneousDamage = 0;
      return lost;
   }

   public final int getOpponentsAssignedDamage() {
      return Aggregates.sum(this.getOpponents(), GameEntity::getAssignedDamage);
   }

   public final int getMaxOpponentAssignedDamage() {
      return Aggregates.max(this.getRegisteredOpponents(), GameEntity::getAssignedDamage);
   }

   public final int getMaxAssignedCombatDamage() {
      return Aggregates.max(this.game.getRegisteredPlayers(), GameEntity::getAssignedCombatDamage);
   }

   public final boolean canReceiveCounters(CounterType type) {
      if (!this.isInGame()) {
         return false;
      } else {
         return !StaticAbilityCantPutCounter.anyCantPutCounter(this, type);
      }
   }

   public final boolean canRemoveCounters(CounterType type) {
      return this.isInGame();
   }

   /** Forge Nova: see addCounterInternal. */
   private static final int NOVA_PER_COUNTER_EVENTS = 10000;

   public void addCounterInternal(CounterType counterType, int n, Player source, boolean fireEvents, GameEntityCounterTable table, Map<AbilityKey, Object> params) {
      int addAmount = n;
      if (n > 0 && this.canReceiveCounters(counterType)) {
         int oldValue = this.getCounters(counterType);
         // Forge Nova: a counter count stops at the largest int instead of wrapping around to a negative number
         if ((long)n + oldValue > Integer.MAX_VALUE) {
            addAmount = Integer.MAX_VALUE - oldValue;
            if (addAmount <= 0) {
               return;
            }
         }

         int newValue = addAmount + oldValue;
         this.setCounters(counterType, newValue, source, fireEvents);
         if (counterType.is(CounterEnumType.RAD) && newValue > 0) {
            String setCode = null;
            if (params.containsKey(AbilityKey.Cause)) {
               SpellAbility cause = (SpellAbility)params.get(AbilityKey.Cause);
               setCode = cause.getHostCard().getSetCode();
            }

            this.createRadiationEffect(setCode);
         }

         Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
         runParams.put(AbilityKey.Source, source);
         runParams.put(AbilityKey.CounterType, counterType);
         if (params != null) {
            runParams.putAll(params);
         }

         // Forge Nova: one "counter added" trigger event per counter, for at most NOVA_PER_COUNTER_EVENTS counters (billions
         // of poison counters from huge infect damage would otherwise keep the game busy for hours)
         int perCounter = Math.min(addAmount, NOVA_PER_COUNTER_EVENTS);
         for(int i = 0; i < perCounter; ++i) {
            runParams.put(AbilityKey.CounterAmount, oldValue + i + 1);
            this.getGame().getTriggerHandler().runTrigger(TriggerType.CounterAdded, AbilityKey.newMap(runParams), false);
         }

         runParams.put(AbilityKey.CounterAmount, addAmount);
         this.getGame().getTriggerHandler().runTrigger(TriggerType.CounterAddedOnce, AbilityKey.newMap(runParams), false);
         if (table != null) {
            table.put(source, this, counterType, addAmount);
         }

      }
   }

   public int subtractCounter(CounterType counterName, int num, Player remover) {
      int oldValue = this.getCounters(counterName);
      int newValue = Math.max(oldValue - num, 0);
      int delta = oldValue - newValue;
      if (delta == 0) {
         return 0;
      } else {
         this.setCounters(counterName, newValue, (Player)null, true);
         this.getGame().addCounterRemovedThisTurn(counterName, this, delta);
         return delta;
      }
   }

   public final void clearCounters() {
      if (!this.counters.isEmpty()) {
         this.counters.clear();
         this.view.updateCounters(this);
         this.getGame().fireEvent(new GameEventPlayerCounters(this, (CounterType)null, 0, 0));
      }
   }

   public void setCounters(CounterType counterType, Integer num, Player source, boolean fireEvents) {
      int old = this.getCounters(counterType);
      this.setCounters(counterType, num);
      this.view.updateCounters(this);
      if (fireEvents) {
         this.getGame().fireEvent(new GameEventPlayerCounters(this, counterType, old, num));
         if (counterType.is(CounterEnumType.POISON)) {
            this.getGame().fireEvent(new GameEventPlayerPoisoned(this, source, old, num - old));
         } else if (counterType.is(CounterEnumType.RAD)) {
            this.getGame().fireEvent(new GameEventPlayerRadiation(this, source, num - old));
         }
      }

      if (counterType.is(CounterEnumType.RAD) && num <= 0) {
         this.removeRadiationEffect();
      }

   }

   public void setCounters(Multiset<CounterType> allCounters) {
      this.counters = allCounters;
      this.view.updateCounters(this);
      this.getGame().fireEvent(new GameEventPlayerCounters(this, (CounterType)null, 0, 0));
      if (this.counters.count(CounterEnumType.RAD) > 0) {
         this.createRadiationEffect((String)null);
      } else {
         this.removeRadiationEffect();
      }

   }

   public final void addRadCounters(int num, Player source, GameEntityCounterTable table) {
      this.addCounter(CounterEnumType.RAD, num, source, table);
   }

   public final void removeRadCounters(int num) {
      this.subtractCounter(CounterEnumType.RAD, num, this);
   }

   public final int getPoisonCounters() {
      return this.getCounters(CounterEnumType.POISON);
   }

   public final void setPoisonCounters(int num, Player source) {
      this.setCounters(CounterEnumType.POISON, num, source, true);
   }

   public final void addPoisonCounters(int num, Player source, GameEntityCounterTable table) {
      this.addCounter(CounterEnumType.POISON, num, source, table);
   }

   public final void removePoisonCounters(int num, Player source) {
      this.subtractCounter(CounterEnumType.POISON, num, source);
   }

   public final void addChangedKeywords(List<String> addKeywords, List<String> removeKeywords, Long timestamp, long staticId) {
      List<KeywordInterface> kws = Lists.newArrayList();
      if (addKeywords != null) {
         for(String kw : addKeywords) {
            kws.add(this.getKeywordForStaticAbility(kw, staticId));
         }
      }

      KeywordsChange cks = new KeywordsChange(kws, removeKeywords, false);
      if (cks.hasTraits()) {
         this.getKeywordCard().addChangedCardTraits(cks, timestamp, staticId, true);
      }

      this.changedKeywords.put(timestamp, staticId, cks);
      this.updateKeywords();
      this.game.fireEvent(new GameEventPlayerStatsChanged(this));
   }

   public final KeywordInterface getKeywordForStaticAbility(String kw, long staticId) {
      KeywordInterface result;
      if (staticId >= 1L && this.storedKeywords.contains(staticId, kw)) {
         result = (KeywordInterface)this.storedKeywords.get(staticId, kw);
      } else {
         result = Keyword.getInstance(kw);
         result.createTraits(this, false);
      }

      return result;
   }

   public final IKeywordsChange removeChangedKeywords(Long timestamp, long staticId) {
      IKeywordsChange change = (IKeywordsChange)this.changedKeywords.remove(timestamp, staticId);
      if (change != null) {
         if (this.keywordEffect != null) {
            this.getKeywordCard().removeChangedCardTraits(timestamp, staticId);
         }

         this.updateKeywords();
         this.game.fireEvent(new GameEventPlayerStatsChanged(this));
      }

      return change;
   }

   public final boolean hasKeyword(String keyword) {
      return this.keywords.contains(keyword);
   }

   public final boolean hasKeyword(Keyword keyword) {
      return this.keywords.contains(keyword);
   }

   private void updateKeywords() {
      this.keywords.clear();
      this.keywords.applyChanges(this.changedKeywords.values());
      this.view.updateKeywords(this);
      this.updateKeywordCardAbilityText();
   }

   public final KeywordCollection getKeywords() {
      return this.keywords;
   }

   public final boolean canBeTargetedBy(SpellAbility sa) {
      if (this.hasLost()) {
         return false;
      } else {
         return StaticAbilityCantTarget.cantTarget(this, sa) == null;
      }
   }

   public void surveil(int num, SpellAbility cause, Map<AbilityKey, Object> params) {
      num += StaticAbilitySurveilNum.surveilNumMod(this);
      CardCollection topN = this.getTopXCardsFromLibrary(num);
      if (!topN.isEmpty()) {
         Pair<CardCollection, CardCollection> lists = this.getController().arrangeForSurveil(topN);
         CardCollection toTop = (CardCollection)lists.getLeft();
         CardCollection toGrave = (CardCollection)lists.getRight();
         int numToGrave = 0;
         int numToTop = 0;
         if (toGrave != null) {
            for(Card c : toGrave) {
               Card moved = this.getGame().getAction().moveToGraveyard(c, cause, params);
               moved.setSurveilled(true);
               ++numToGrave;
            }

            if (cause.hasParam("RememberMoved")) {
               cause.getHostCard().addRemembered(toGrave);
            }
         }

         if (toTop != null) {
            Collections.reverse(toTop);

            for(Card c : toTop) {
               this.getGame().getAction().moveToLibrary(c, cause, params);
               ++numToTop;
            }

            if (cause.hasParam("RememberKept")) {
               cause.getHostCard().addRemembered(toTop);
            }
         }

         this.getGame().fireEvent(new GameEventSurveil(PlayerView.get(this), numToTop, numToGrave));
      }

      Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
      runParams.put(AbilityKey.FirstTime, this.surveilThisTurn == 0);
      if (params != null) {
         runParams.putAll(params);
      }

      this.getGame().getTriggerHandler().runTrigger(TriggerType.Surveil, runParams, false);
      ++this.surveilThisTurn;
   }

   public int getSurveilThisTurn() {
      return this.surveilThisTurn;
   }

   public int getScryThisTurn() {
      return this.scryThisTurn;
   }

   public void incScryThisTurn() {
      ++this.scryThisTurn;
   }

   public boolean canMulligan() {
      return !this.getZone(ZoneType.Hand).isEmpty();
   }

   public final boolean canDraw() {
      return this.canDraw(1);
   }

   public final boolean canDraw(int amount) {
      return StaticAbilityCantDraw.canDrawThisAmount(this, amount);
   }

   public final CardCollectionView drawCard() {
      return this.drawCards(1);
   }

   public final CardCollectionView drawCards(int n) {
      return this.drawCards(n, (SpellAbility)null, AbilityKey.newMap(), this.getZone(ZoneType.Hand));
   }

   public final CardCollectionView drawCards(int n, PlayerZone zone) {
      return this.drawCards(n, (SpellAbility)null, AbilityKey.newMap(), zone);
   }

   public final CardCollectionView drawCards(int n, SpellAbility cause, Map<AbilityKey, Object> params) {
      return this.drawCards(n, cause, params, this.getZone(ZoneType.Hand));
   }

   public final CardCollectionView drawCards(int n, SpellAbility cause, Map<AbilityKey, Object> params, PlayerZone zone) {
      CardCollection drawn = new CardCollection();
      if (n <= 0) {
         return drawn;
      } else {
         boolean gameStarted = this.game.getAge().ordinal() > GameStage.Mulligan.ordinal();
         if (gameStarted) {
            Map<AbilityKey, Object> repRunParams = AbilityKey.mapFromAffected(this);
            repRunParams.put(AbilityKey.Number, n);
            if (params != null) {
               repRunParams.putAll(params);
            }

            if (this.game.getReplacementHandler().run(ReplacementType.DrawCards, repRunParams) != ReplacementResult.NotReplaced) {
               return drawn;
            }
         }

         Map<Player, CardCollection> toReveal = Maps.newHashMap();

         for(int i = 0; i < n; ++i) {
            if (gameStarted && !this.canDraw()) {
               return drawn;
            }

            CardCollectionView cards = this.doDraw(toReveal, cause, params, zone);
            if (cards == null) {
               break;
            }

            drawn.addAll(cards);
         }

         for(Map.Entry<Player, CardCollection> e : toReveal.entrySet()) {
            if (((CardCollection)e.getValue()).size() > 1) {
               this.game.getAction().revealTo((CardCollectionView)e.getValue(), (Player)e.getKey(), "Revealing cards drawn from ");
            }
         }

         return drawn;
      }
   }

   private CardCollectionView doDraw(Map<Player, CardCollection> revealed, SpellAbility sa, Map<AbilityKey, Object> params, PlayerZone hand) {
      CardCollection drawn = new CardCollection();
      PlayerZone library = this.getZone(ZoneType.Library);
      SpellAbility cause = sa;
      if (sa != null && sa.isReplacementAbility()) {
         cause = (SpellAbility)sa.getReplacingObject(AbilityKey.Cause);
      }

      boolean gameStarted = this.game.getAge().ordinal() > GameStage.Mulligan.ordinal();
      if (gameStarted) {
         Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
         repParams.put(AbilityKey.Cause, cause);
         repParams.put(AbilityKey.ExtraDraws, this.numExtraDrawnThisTurn);
         if (params != null) {
            repParams.putAll(params);
         }

         if (this.game.getReplacementHandler().run(ReplacementType.Draw, repParams) != ReplacementResult.NotReplaced) {
            return drawn;
         }

         if (library.isEmpty() && this.game.getReplacementHandler().getReplacementList(ReplacementType.Draw, repParams, (ReplacementLayer)null).isEmpty()) {
            drawn = null;
         }
      }

      if (!library.isEmpty()) {
         Card c;
         if (this.hasKeyword("You draw cards from the bottom of your library instead of the top of your library.")) {
            c = library.get(library.size() - 1);
         } else {
            c = library.get(0);
         }

         List<Player> pList = Lists.newArrayList();

         for(Player p : this.getAllOtherPlayers()) {
            if (c.mayPlayerLook(p)) {
               pList.add(p);
            }
         }

         c = this.game.getAction().moveTo(hand, c, cause, params);
         drawn.add(c);
         if (cause != null && "AllReplaced".equals(cause.getParam("RememberDrawn"))) {
            cause.getHostCard().addRemembered(drawn);
         }

         for(Player p : pList) {
            if (!revealed.containsKey(p)) {
               revealed.put(p, new CardCollection());
            }

            ((CardCollection)revealed.get(p)).add(c);
         }

         if (gameStarted) {
            this.setLastDrawnCard(c);
            c.setDrawnThisTurn(true);
            ++this.numDrawnThisTurn;
            ++this.numExtraDrawnThisTurn;
            if (this.game.getPhaseHandler().is(PhaseType.DRAW)) {
               ++this.numDrawnThisDrawStep;
               if (this.numDrawnThisDrawStep == 1) {
                  --this.numExtraDrawnThisTurn;
               }
            }

            this.view.updateNumDrawnThisTurn(this);
            Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
            if (params != null) {
               runParams.putAll(params);
            }

            if (this.game.getTopLibForPlayer(this) != null && this.getPaidForSA() != null && cause != null && this.getPaidForSA() != cause.getRootAbility()) {
               c.turnFaceDown();
               this.game.addFacedownWhileCasting(c, this.numDrawnThisTurn);
               runParams.put(AbilityKey.CanReveal, false);
            }

            runParams.put(AbilityKey.Card, c);
            runParams.put(AbilityKey.Number, this.numDrawnThisTurn);
            this.game.getTriggerHandler().runTrigger(TriggerType.Drawn, runParams, false);
         }
      } else {
         this.triedToDrawFromEmptyLibrary = true;
      }

      return drawn;
   }

   public final void resetNumDrawnThisDrawStep() {
      this.numDrawnThisDrawStep = 0;
   }

   public final void resetNumDrawnThisTurn() {
      this.numDrawnThisTurn = 0;
      this.numExtraDrawnThisTurn = 0;
      this.view.updateNumDrawnThisTurn(this);
   }

   public final int getNumDrawnThisTurn() {
      return this.numDrawnThisTurn;
   }

   public final int getNumDrawnLastTurn() {
      return this.numDrawnLastTurn;
   }

   public final int numDrawnThisDrawStep() {
      return this.numDrawnThisDrawStep;
   }

   public final PlayerZone getZone(ZoneType zone) {
      return (PlayerZone)this.zones.get(zone);
   }

   public void updateZoneForView(PlayerZone zone) {
      this.view.updateZone(zone);
   }

   public void updateAllZonesForView() {
      for(PlayerZone zone : this.zones.values()) {
         this.updateZoneForView(zone);
      }

   }

   public final List<PlayerZone> getExtraZones() {
      return this.extraZones;
   }

   public void resetExtraZones(ZoneType type) {
      this.extraZones.removeIf((z) -> z.getZoneType().equals(type));
      if (this.extraZones.isEmpty()) {
         this.extraZones = null;
      }

   }

   public final CardCollectionView getCardsIn(ZoneType zoneType) {
      return this.getCardsIn(zoneType, true);
   }

   public final CardCollectionView getCardsIn(ZoneType zoneType, boolean filterOutPhasedOut) {
      if (zoneType == ZoneType.Stack) {
         CardCollection cards = new CardCollection();

         for(Card c : this.game.getStackZone().getCards()) {
            if (c.getOwner().equals(this)) {
               cards.add(c);
            }
         }

         return cards;
      } else if (zoneType == ZoneType.Flashback) {
         return this.getCardsActivatableInExternalZones(true);
      } else {
         PlayerZone zone = this.getZone(zoneType);
         return zone == null ? CardCollection.EMPTY : zone.getCards(filterOutPhasedOut);
      }
   }

   public final CardCollectionView getCardsIn(ZoneType zone, int n) {
      return new CardCollection(this.getCardsIn(zone).stream().limit((long)n));
   }

   public final CardCollectionView getCardsIn(Iterable<ZoneType> zones) {
      return this.getCardsIn(zones, true);
   }

   public final CardCollectionView getCardsIn(Iterable<ZoneType> zones, boolean filterOutPhasedOut) {
      CardCollection result = new CardCollection();

      for(ZoneType z : zones) {
         result.addAll(this.getCardsIn(z, filterOutPhasedOut));
      }

      return result;
   }

   public final CardCollectionView getCardsIn(ZoneType... zones) {
      CardCollection result = new CardCollection();

      for(ZoneType z : zones) {
         result.addAll(this.getCardsIn(z));
      }

      return result;
   }

   public final CardCollectionView getCardsIn(ZoneType zone, String cardName) {
      return CardLists.filter(this.getCardsIn(zone), CardPredicates.nameEquals(cardName));
   }

   public CardCollectionView getCardsActivatableInExternalZones(boolean includeCommandZone) {
      CardCollection cl = new CardCollection();
      cl.addAll(this.getZone(ZoneType.Graveyard).getCardsPlayerCanActivate(this));
      cl.addAll(this.getZone(ZoneType.Exile).getCardsPlayerCanActivate(this));
      cl.addAll(this.getZone(ZoneType.Library).getCardsPlayerCanActivate(this));
      if (includeCommandZone) {
         cl.addAll(this.getZone(ZoneType.Command).getCardsPlayerCanActivate(this));
         cl.addAll(this.getZone(ZoneType.Sideboard).getCardsPlayerCanActivate(this));
      }

      for(Player other : this.getAllOtherPlayers()) {
         cl.addAll(other.getZone(ZoneType.Exile).getCardsPlayerCanActivate(this));
         cl.addAll(other.getZone(ZoneType.Graveyard).getCardsPlayerCanActivate(this));
         cl.addAll(other.getZone(ZoneType.Library).getCardsPlayerCanActivate(this));
         cl.addAll(other.getZone(ZoneType.Hand).getCardsPlayerCanActivate(this));
      }

      cl.addAll(this.getGame().getCardsPlayerCanActivateInStack());
      return cl;
   }

   public final CardCollectionView getAllCards() {
      return CardCollection.combine(new CardCollectionView[]{this.getCardsIn((Iterable)ALL_ZONES), this.getCardsIn(ZoneType.Stack), this.inboundTokens});
   }

   public final void resetNumRollsThisTurn() {
      this.numRollsThisTurn = 0;
   }

   public final int getNumRollsThisTurn() {
      return this.numRollsThisTurn;
   }

   public void roll() {
      ++this.numRollsThisTurn;
   }

   public final void resetNumFlipsThisTurn() {
      this.numFlipsThisTurn = 0;
   }

   public final int getNumFlipsThisTurn() {
      return this.numFlipsThisTurn;
   }

   public void flip() {
      ++this.numFlipsThisTurn;
   }

   public final Card discard(Card c, SpellAbility sa, boolean effect, Map<AbilityKey, Object> params) {
      if (!c.canBeDiscardedBy(sa, effect)) {
         return null;
      } else {
         this.discardedThisTurn.add(CardCopyService.getLKICopy(c));
         params.put(AbilityKey.Discard, true);
         params.put(AbilityKey.EffectOnly, effect);
         Card newCard = this.game.getAction().moveToGraveyard(c, sa, params);
         StringBuilder sb = new StringBuilder();
         sb.append(this).append(" discards ").append(c);
         sb.append(".");
         this.game.fireEvent(new GameEventAddLog(GameLogEntryType.DISCARD, sb.toString(), c));
         newCard.setDiscarded(true);
         if (sa != null && sa.hasParam("RememberDiscarded")) {
            sa.getHostCard().addRemembered(newCard);
         }

         Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
         runParams.put(AbilityKey.Card, c);
         runParams.put(AbilityKey.Cause, sa);
         runParams.putAll(params);
         this.game.getTriggerHandler().runTrigger(TriggerType.Discarded, runParams, false);
         return newCard;
      }
   }

   public final void addTokensCreatedThisTurn(Card token) {
      ++this.numTokenCreatedThisTurn;
      Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
      runParams.put(AbilityKey.Num, this.numTokenCreatedThisTurn);
      runParams.put(AbilityKey.Card, token);
      this.game.getTriggerHandler().runTrigger(TriggerType.TokenCreated, runParams, false);
   }

   public final int getNumTokenCreatedThisTurn() {
      return this.numTokenCreatedThisTurn;
   }

   public final void resetNumTokenCreatedThisTurn() {
      this.numTokenCreatedThisTurn = 0;
   }

   public final int getNumForetoldThisTurn() {
      return this.numForetoldThisTurn;
   }

   public final void addForetoldThisTurn() {
      ++this.numForetoldThisTurn;
      Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
      runParams.put(AbilityKey.Num, this.numForetoldThisTurn);
      this.game.getTriggerHandler().runTrigger(TriggerType.Foretell, runParams, false);
   }

   public final void resetNumForetoldThisTurn() {
      this.numForetoldThisTurn = 0;
   }

   public final List<Card> getDiscardedThisTurn() {
      return this.discardedThisTurn;
   }

   public final void resetDiscardedThisTurn() {
      this.discardedThisTurn.clear();
   }

   public final int getNumExploredThisTurn() {
      return this.numExploredThisTurn;
   }

   public final void addExploredThisTurn() {
      ++this.numExploredThisTurn;
   }

   public final void resetNumExploredThisTurn() {
      this.numExploredThisTurn = 0;
   }

   public int getNumCardsInHandStartedThisTurnWith() {
      return this.numCardsInHandStartedThisTurnWith;
   }

   public void setNumCardsInHandStartedThisTurnWith(int num) {
      this.numCardsInHandStartedThisTurnWith = num;
   }

   public int getLifeStartedThisTurnWith() {
      return this.lifeStartedThisTurnWith;
   }

   public void setLifeStartedThisTurnWith(int l) {
      this.lifeStartedThisTurnWith = l;
   }

   public void addNoteForName(String notedFor, String noted) {
      if (!this.notes.containsKey(notedFor)) {
         this.notes.put(notedFor, new FCollection());
      }

      ((FCollection)this.notes.get(notedFor)).add(noted);
   }

   public FCollection<String> getNotesForName(String notedFor) {
      if (!this.notes.containsKey(notedFor)) {
         this.notes.put(notedFor, new FCollection());
      }

      return (FCollection)this.notes.get(notedFor);
   }

   public void clearNotesForName(String notedFor) {
      if (this.notes.containsKey(notedFor)) {
         ((FCollection)this.notes.get(notedFor)).clear();
      }

   }

   public void noteNumberForName(String notedFor, int noted) {
      this.notedNum.put(notedFor, noted);
   }

   public int getNotedNumberForName(String notedFor) {
      return !this.notedNum.containsKey(notedFor) ? 0 : (Integer)this.notedNum.get(notedFor);
   }

   public final CardCollectionView mill(int n, ZoneType destination, SpellAbility sa, Map<AbilityKey, Object> params) {
      Map<AbilityKey, Object> repRunParams = AbilityKey.mapFromAffected(this);
      repRunParams.put(AbilityKey.Number, n);
      if (params != null) {
         repRunParams.putAll(params);
      }

      if (destination == ZoneType.Graveyard) {
         switch (this.getGame().getReplacementHandler().run(ReplacementType.Mill, repRunParams)) {
            case NotReplaced:
               break;
            case Updated:
               if (!this.equals(repRunParams.get(AbilityKey.Affected))) {
                  return CardCollection.EMPTY;
               }

               n = (Integer)repRunParams.get(AbilityKey.Number);
               break;
            default:
               return CardCollection.EMPTY;
         }
      }

      Iterable<Card> milledView = this.getCardsIn(ZoneType.Library);
      if (sa.getRootAbility().getReplacingObject(AbilityKey.SimultaneousETB) != null) {
         milledView = IterableUtil.filter(milledView, (c) -> !((CardCollection)sa.getRootAbility().getReplacingObject(AbilityKey.SimultaneousETB)).contains(c));
      }

      CardCollectionView milled = new CardCollection(Iterables.limit(milledView, n));
      if (destination == ZoneType.Graveyard) {
         milled = GameActionUtil.orderCardsByTheirOwners(this.game, milled, ZoneType.Graveyard, sa);
      }

      for(Card m : milled) {
         Card moved = this.game.getAction().moveTo(destination, m, sa, params);
         moved.setMilled(true);
         Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
         runParams.put(AbilityKey.Card, m);
         this.game.getTriggerHandler().runTrigger(TriggerType.Milled, runParams, false);
      }

      if (!milled.isEmpty()) {
         Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
         runParams.put(AbilityKey.Cards, milled);
         this.game.getTriggerHandler().runTrigger(TriggerType.MilledOnce, runParams, false);
      }

      return milled;
   }

   public final CardCollection getTopXCardsFromLibrary(int amount) {
      CardCollection topCards = new CardCollection();
      PlayerZone lib = this.getZone(ZoneType.Library);
      int maxCards = lib.size();
      maxCards = Math.min(maxCards, amount);

      for(int j = 0; j < maxCards; ++j) {
         topCards.add(lib.get(j));
      }

      return topCards;
   }

   public final void shuffle(SpellAbility sa) {
      CardCollection list = new CardCollection(this.getCardsIn(ZoneType.Library));
      Collections.shuffle(list, MyRandom.getRandom());
      this.getZone(ZoneType.Library).setCards(this.getController().cheatShuffle(list));
      Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
      runParams.put(AbilityKey.Source, sa);
      this.game.getTriggerHandler().runTrigger(TriggerType.Shuffled, runParams, false);
      this.game.fireEvent(new GameEventShuffle(this));
   }

   public final Card playLand(Card land, SpellAbility cause) {
      land.setController(this, 0L);
      if (land.isFaceDown()) {
         land.turnFaceUp((SpellAbility)null);
         if (cause.isLandAbility()) {
            land.changeToState(cause.getCardStateName());
         }
      }

      Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(land);
      runParams.put(AbilityKey.Origin, land.getZone().getZoneType().name());
      this.game.copyLastState();
      Card c = this.game.getAction().moveTo(this.getZone(ZoneType.Battlefield), land, cause);
      this.game.updateLastStateForCard(c);
      runParams.put(AbilityKey.SpellAbility, cause);
      this.game.getTriggerHandler().runTrigger(TriggerType.LandPlayed, runParams, false);
      this.game.getStack().unfreezeStack();
      this.addLandPlayedThisTurn();
      this.game.fireEvent(new GameEventLandPlayed(PlayerView.get(this), CardView.get(c)));
      return c;
   }

   public final boolean canPlayLand(Card land, boolean ignoreZoneAndTiming, SpellAbility landSa) {
      if (!ignoreZoneAndTiming) {
         if (!this.game.getPhaseHandler().isPlayerTurn(this)) {
            return false;
         }

         if (!this.canCastSorcery() && (landSa == null || !landSa.withFlash(land, this))) {
            return false;
         }

         boolean mayPlay = landSa == null ? !land.mayPlay(this).isEmpty() : landSa.getMayPlay() != null;
         if (land.getOwner() != this && !mayPlay) {
            return false;
         }

         Zone zone = this.game.getZoneOf(land);
         if (zone != null && (zone.is(ZoneType.Battlefield) || !zone.is(ZoneType.Hand) && !mayPlay && (landSa == null || !landSa.isAlternativeCost(AlternativeCost.Mayhem)))) {
            return false;
         }
      }

      if (StaticAbilityCantBeCast.cantPlayLandAbility(landSa, land, this)) {
         return false;
      } else if (this.getMaxLandPlaysInfinite()) {
         return true;
      } else {
         return this.getLandsPlayedThisTurn() < this.getMaxLandPlays();
      }
   }

   public final int getMaxLandPlays() {
      int adjMax = 1;

      for(Integer i : this.adjustLandPlays.values()) {
         adjMax += i;
      }

      return adjMax;
   }

   public final void addMaxLandPlays(long timestamp, int value) {
      this.adjustLandPlays.put(timestamp, value);
      this.getView().updateMaxLandPlay(this);
      this.getGame().fireEvent(new GameEventPlayerStatsChanged(this));
   }

   public final boolean removeMaxLandPlays(long timestamp) {
      boolean changed = this.adjustLandPlays.remove(timestamp) != null;
      if (changed) {
         this.getView().updateMaxLandPlay(this);
         this.getGame().fireEvent(new GameEventPlayerStatsChanged(this));
      }

      return changed;
   }

   public final void addMaxLandPlaysInfinite(long timestamp) {
      this.adjustLandPlaysInfinite.add(timestamp);
      this.getView().updateUnlimitedLandPlay(this);
      this.getGame().fireEvent(new GameEventPlayerStatsChanged(this));
   }

   public final boolean removeMaxLandPlaysInfinite(long timestamp) {
      boolean changed = this.adjustLandPlaysInfinite.remove(timestamp);
      if (changed) {
         this.getView().updateUnlimitedLandPlay(this);
         this.getGame().fireEvent(new GameEventPlayerStatsChanged(this));
      }

      return changed;
   }

   public final boolean getMaxLandPlaysInfinite() {
      if (this.getController().canPlayUnlimitedLands()) {
         return true;
      } else {
         return !this.adjustLandPlaysInfinite.isEmpty();
      }
   }

   public final void addMaingameCardMapping(Card subgameCard, Card maingameCard) {
      this.maingameCardsMap.put(subgameCard, maingameCard);
   }

   public final Card getMappingMaingameCard(Card subgameCard) {
      return (Card)this.maingameCardsMap.get(subgameCard);
   }

   public final ManaPool getManaPool() {
      return this.manaPool;
   }

   public void updateManaForView() {
      this.view.updateMana(this);
   }

   public final int getNumPowerSurgeLands() {
      return this.numPowerSurgeLands;
   }

   public final int setNumPowerSurgeLands(int n) {
      this.numPowerSurgeLands = n;
      return this.numPowerSurgeLands;
   }

   public final Card getLastDrawnCard() {
      return this.lastDrawnCard;
   }

   private Card setLastDrawnCard(Card c) {
      this.lastDrawnCard = c;
      return this.lastDrawnCard;
   }

   public final Card getRingBearer() {
      return this.ringBearer;
   }

   public final Card getTheRing() {
      return this.theRing;
   }

   public final void clearTheRing() {
      this.theRing = null;
   }

   public final void setRingBearer(Card bearer) {
      if (bearer != null) {
         this.clearRingBearer();
         this.ringBearer = bearer;
         this.ringBearer.setRingBearer(true);
      }
   }

   public void clearRingBearer() {
      if (this.ringBearer != null) {
         this.ringBearer.setRingBearer(false);
         this.ringBearer = null;
      }
   }

   public final String getNamedCard() {
      return this.namedCard;
   }

   public final void setNamedCard(String s) {
      this.namedCard = s;
   }

   public final int getTurn() {
      return this.stats.getTurnsPlayed();
   }

   public final void incrementTurn() {
      this.stats.nextTurn();
   }

   public final int getLastTurnNr() {
      return this.lastTurnNr;
   }

   public boolean hasTappedLandForManaThisTurn() {
      return this.tappedLandForManaThisTurn;
   }

   public void setTappedLandForManaThisTurn(boolean tappedLandForManaThisTurn) {
      this.tappedLandForManaThisTurn = tappedLandForManaThisTurn;
   }

   public final boolean hasBeenDealtCombatDamageSinceLastTurn() {
      return this.beenDealtCombatDamageSinceLastTurn;
   }

   public final void setBeenDealtCombatDamageSinceLastTurn(boolean b) {
      this.beenDealtCombatDamageSinceLastTurn = b;
   }

   public final List<Card> getCreaturesAttackedThisTurn() {
      List<Card> result = Lists.newArrayList(Iterables.concat(this.attackedThisTurn.values()));
      return result;
   }

   public final List<Card> getCreaturesAttackedThisTurn(GameEntity e) {
      return (List)this.attackedThisTurn.getOrDefault(e, Lists.newArrayList());
   }

   public final void addCreaturesAttackedThisTurn(Card c, GameEntity e) {
      List<Card> creatures = (List)this.attackedThisTurn.getOrDefault(e, Lists.newArrayList());
      creatures.add(c);
      this.attackedThisTurn.putIfAbsent(e, creatures);
      if (e instanceof Player && !this.attackedPlayersThisCombat.contains(e)) {
         this.attackedPlayersThisCombat.add((Player)e);
      }

   }

   public final Iterable<Player> getAttackedPlayersMyTurn() {
      return IterableUtil.filter(this.attackedThisTurn.keySet(), Player.class);
   }

   public final List<Player> getAttackedPlayersMyLastTurn() {
      return this.attackedPlayersLastTurn;
   }

   public final void clearAttackedMyTurn() {
      this.attackedThisTurn.clear();
   }

   public final void setAttackedPlayersMyLastTurn(Iterable<Player> players) {
      this.attackedPlayersLastTurn.clear();
      List var10001 = this.attackedPlayersLastTurn;
      Objects.requireNonNull(var10001);
      players.forEach(var10001::add);
   }

   public final List<Player> getAttackedPlayersMyCombat() {
      return this.attackedPlayersThisCombat;
   }

   public final void clearAttackedPlayersMyCombat() {
      this.attackedPlayersThisCombat.clear();
   }

   public final int getVenturedThisTurn() {
      return this.venturedThisTurn;
   }

   public final void incrementVenturedThisTurn() {
      ++this.venturedThisTurn;
   }

   public final void resetVenturedThisTurn() {
      this.venturedThisTurn = 0;
   }

   public final List<Card> getCompletedDungeons() {
      return this.completedDungeons;
   }

   public void addCompletedDungeon(Card dungeon) {
      this.completedDungeons.add(dungeon);
   }

   public void resetCompletedDungeons() {
      this.completedDungeons.clear();
   }

   public final int getSpeed() {
      return this.speed;
   }

   public final void increaseSpeed() {
      if (!this.maxSpeed()) {
         int old = this.speed++;
         this.getGame().fireEvent(new GameEventSpeedChanged(this, old, this.speed));
         this.updateSpeedEffect();
      }

   }

   public final void decreaseSpeed() {
      if (this.speed > 1) {
         int old = this.speed--;
         this.game.fireEvent(new GameEventSpeedChanged(this, old, this.speed));
         this.updateSpeedEffect();
      }

   }

   public final boolean noSpeed() {
      return this.speed == 0;
   }

   public final boolean maxSpeed() {
      return this.speed == 4;
   }

   public final void setSpeed(int i) {
      this.speed = i;
      if (this.speedEffect != null) {
         this.updateSpeedEffect();
      }

   }

   public final void createSpeedEffect() {
      if (this.speedEffect == null && !this.noSpeed()) {
         this.speedEffect = new Card(this.game.nextCardId(), (IPaperCard)null, this.game);
         this.speedEffect.setOwner(this);
         this.speedEffect.setGamePieceType(GamePieceType.EFFECT);
         this.speedEffect.addAlternateState(CardStateName.Backside, false);
         CardState speedFront = this.speedEffect.getState(CardStateName.Original);
         CardState speedBack = this.speedEffect.getState(CardStateName.Backside);
         speedFront.setImageKey(StaticData.instance().getOtherImageKey("speed", "???"));
         speedFront.setName("Start Your Engines!");
         speedBack.setImageKey(StaticData.instance().getOtherImageKey("max_speed", "???"));
         speedBack.setName("Max Speed!");
         String label = Localizer.getInstance().getMessage("lblSpeed", new Object[]{this.speed});
         this.speedEffect.setOverlayText(label);
         String trigger = "Mode$ LifeLostAll | ValidPlayer$ Opponent | TriggerZones$ Command | ActivationLimit$ 1 | PlayerTurn$ True | CheckSVar$ Count$YourSpeed | SVarCompare$ LT4 | TriggerDescription$ Whenever one or more opponents lose life during your turn, if your speed is less than 4, your speed increases by 1. This ability triggers only once each turn.";
         String speedUp = "DB$ ChangeSpeed";
         Trigger lifeLostTrigger = TriggerHandler.parseTrigger(trigger, this.speedEffect, true);
         lifeLostTrigger.setOverridingAbility(AbilityFactory.getAbility(speedUp, this.speedEffect));
         speedFront.addTrigger(lifeLostTrigger);
         this.speedEffect.updateStateForView();
         if (this.maxSpeed()) {
            this.speedEffect.setState(CardStateName.Backside, true);
         }

         PlayerZone com = this.getZone(ZoneType.Command);
         com.add(this.speedEffect);
         this.updateZoneForView(com);
      }
   }

   protected void updateSpeedEffect() {
      if (this.speedEffect == null) {
         if (this.noSpeed()) {
            return;
         }

         this.createSpeedEffect();
      }

      Localizer localizer = Localizer.getInstance();
      String label = this.maxSpeed() ? localizer.getMessage("lblMaxSpeed", new Object[0]) : localizer.getMessage("lblSpeed", new Object[]{this.speed});
      this.speedEffect.setOverlayText(label);
      if (this.maxSpeed() && this.speedEffect.getCurrentStateName() == CardStateName.Original) {
         this.speedEffect.setState(CardStateName.Backside, true);
      } else if (!this.maxSpeed() && this.speedEffect.getCurrentStateName() == CardStateName.Backside) {
         this.speedEffect.setState(CardStateName.Original, true);
      }

   }

   public final void altWinBySpellEffect(String sourceName) {
      if (!this.cantWin()) {
         this.setOutcome(PlayerOutcome.altWin(sourceName));
      }
   }

   public final boolean loseConditionMet(GameLossReason state, String spellName) {
      if (state != GameLossReason.OpponentWon) {
         Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
         repParams.put(AbilityKey.LoseReason, state);
         if (this.game.getReplacementHandler().run(ReplacementType.GameLoss, repParams) != ReplacementResult.NotReplaced) {
            return false;
         }
      }

      this.setOutcome(PlayerOutcome.loss(state, spellName));
      return true;
   }

   public final void concede() {
      this.setOutcome(PlayerOutcome.concede());
   }

   public final void intentionalDraw() {
      this.setOutcome(PlayerOutcome.draw());
   }

   public final boolean conceded() {
      return this.getOutcome() != null && this.getOutcome().lossState == GameLossReason.Conceded;
   }

   public final boolean cantLose() {
      return this.conceded() ? false : this.cantLoseCheck((GameLossReason)null);
   }

   public final boolean cantLoseForZeroOrLessLife() {
      return this.conceded() ? false : this.cantLoseCheck(GameLossReason.LifeReachedZero);
   }

   public final boolean cantLoseCheck(GameLossReason state) {
      Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
      repParams.put(AbilityKey.LoseReason, state);
      return this.game.getReplacementHandler().cantHappenCheck(ReplacementType.GameLoss, repParams);
   }

   public final boolean cantWin() {
      return this.game.getReplacementHandler().cantHappenCheck(ReplacementType.GameWin, AbilityKey.mapFromAffected(this));
   }

   public final boolean checkLoseCondition() {
      if (this.hasLost()) {
         return true;
      } else {
         if (this.triedToDrawFromEmptyLibrary) {
            this.triedToDrawFromEmptyLibrary = false;
            if (this.loseConditionMet(GameLossReason.Milled, (String)null)) {
               return true;
            }
         }

         boolean hasNoLife = this.getLife() <= 0;
         if (hasNoLife && this.loseConditionMet(GameLossReason.LifeReachedZero, (String)null)) {
            return true;
         } else if (this.getCounters(CounterEnumType.POISON) >= 10 && this.loseConditionMet(GameLossReason.Poisoned, (String)null)) {
            return true;
         } else {
            if (this.game.getRules().hasAppliedVariant(GameType.Commander)) {
               for(Map.Entry<Card, Integer> entry : this.getCommanderDamage()) {
                  if ((Integer)entry.getValue() >= 21 && this.loseConditionMet(GameLossReason.CommanderDamage, (String)null)) {
                     return true;
                  }
               }
            }

            return false;
         }
      }
   }

   public final boolean hasLost() {
      return this.getOutcome() != null && this.getOutcome().lossState != null;
   }

   public final boolean hasWon() {
      if (this.getOutcome() != null && this.getOutcome().lossState == null) {
         return !this.cantWin();
      } else {
         return false;
      }
   }

   public final boolean isInGame() {
      return this.getOutcome() == null;
   }

   public final boolean hasMetalcraft() {
      return CardLists.count(this.getCardsIn(ZoneType.Battlefield), CardPredicates.ARTIFACTS) >= 3;
   }

   public final boolean hasThreshold() {
      return this.getZone(ZoneType.Graveyard).size() >= 7;
   }

   public final boolean hasHellbent() {
      return this.getZone(ZoneType.Hand).isEmpty();
   }

   public final boolean hasRevolt() {
      return this.getGame().getLeftBattlefieldThisTurn().stream().anyMatch(CardPredicates.isController(this));
   }

   public final int getDescended() {
      return this.descended;
   }

   public final void descend() {
      ++this.descended;
   }

   public final void setDescended(int n) {
      this.descended = n;
   }

   public final boolean hasDelirium() {
      return AbilityUtils.countCardTypesFromList(this.getCardsIn(ZoneType.Graveyard), false) >= 4;
   }

   public final boolean hasLandfall() {
      return this.getZone(ZoneType.Battlefield).getCardsAddedThisTurn((ZoneType)null).stream().anyMatch(CardPredicates.LANDS);
   }

   public boolean hasFerocious() {
      return !CardLists.filterPower(this.getCreaturesInPlay(), 4).isEmpty();
   }

   public final boolean hasSurge() {
      PlayerCollection team = this.getYourTeam();
      return this.game.getStack().getSpellsCastThisTurn().stream().anyMatch((sp) -> team.contains(sp.getActivatingPlayer()));
   }

   public final boolean hasBloodthirst() {
      for(Player p : this.getRegisteredOpponents()) {
         if (p.getAssignedDamage() > 0) {
            return true;
         }
      }

      return false;
   }

   public final int getBloodthirstAmount() {
      return Aggregates.sum(this.getRegisteredOpponents(), GameEntity::getAssignedDamage);
   }

   public final int getOpponentLostLifeThisTurn() {
      int lost = 0;

      for(Player opp : this.getRegisteredOpponents()) {
         lost += opp.getLifeLostThisTurn();
      }

      return lost;
   }

   public final boolean hasProwl(SpellAbility sa) {
      return !this.game.getDamageDoneThisTurn(true, true, "Card.YouCtrl+sharesCreatureTypeWith", "Player", sa.getHostCard(), this, sa).isEmpty();
   }

   public final boolean hasFreerunning() {
      return !this.game.getDamageDoneThisTurn(true, true, "Card.Assassin+YouCtrl,Card.IsCommander+YouCtrl", "Player", (Card)null, this, (CardTraitBase)null).isEmpty();
   }

   public final void setLibrarySearched(int l) {
      this.numLibrarySearchedOwn = l;
   }

   public final int getLibrarySearched() {
      return this.numLibrarySearchedOwn;
   }

   public final void incLibrarySearched() {
      ++this.numLibrarySearchedOwn;
   }

   public final boolean isValid(String restriction, Player sourceController, Card source, CardTraitBase spellAbility) {
      String[] incR = restriction.split("\\.", 2);
      if (incR[0].equals("Opponent")) {
         if (this.equals(sourceController) || !this.isOpponentOf(sourceController)) {
            return false;
         }
      } else if (incR[0].equals("You")) {
         if (!this.equals(sourceController)) {
            return false;
         }
      } else if (!incR[0].equals("Any") && !incR[0].equals("Player")) {
         return false;
      }

      if (incR.length > 1) {
         String excR = incR[1];
         String[] exR = excR.split("\\+");

         for(String s : exR) {
            if (!this.hasProperty(s, sourceController, source, spellAbility)) {
               return false;
            }
         }
      }

      return true;
   }

   public final boolean hasProperty(String property, Player sourceController, Card source, CardTraitBase spellAbility) {
      if (property.startsWith("!")) {
         return !PlayerProperty.playerHasProperty(this, property.substring(1), sourceController, source, spellAbility);
      } else {
         return PlayerProperty.playerHasProperty(this, property, sourceController, source, spellAbility);
      }
   }

   public final int getMaxHandSize() {
      return this.maxHandSize;
   }

   public final void setMaxHandSize(int size) {
      if (this.maxHandSize != size) {
         this.maxHandSize = size;
         this.view.updateMaxHandSize(this);
      }
   }

   public boolean isUnlimitedHandSize() {
      return this.unlimitedHandSize;
   }

   public void setUnlimitedHandSize(boolean unlimited) {
      if (this.unlimitedHandSize != unlimited) {
         this.unlimitedHandSize = unlimited;
         this.view.updateUnlimitedHandSize(this);
      }
   }

   public int getStartingHandSize() {
      return this.startingHandSize;
   }

   public void setStartingHandSize(int shs) {
      this.startingHandSize = shs;
   }

   public final int getLandsPlayedThisTurn() {
      return this.landsPlayedThisTurn;
   }

   public final int getLandsPlayedLastTurn() {
      return this.landsPlayedLastTurn;
   }

   public final void addLandPlayedThisTurn() {
      ++this.landsPlayedThisTurn;
      ++this.achievementTracker.landsPlayed;
      this.view.updateNumLandThisTurn(this);
   }

   public final void resetLandsPlayedThisTurn() {
      this.landsPlayedThisTurn = 0;
      this.view.updateNumLandThisTurn(this);
   }

   public final void setLandsPlayedThisTurn(int num) {
      this.landsPlayedThisTurn = num;
      this.view.updateNumLandThisTurn(this);
   }

   public final void setLandsPlayedLastTurn(int num) {
      this.landsPlayedLastTurn = num;
   }

   public final void setNumDrawnLastTurn(int num) {
      this.numDrawnLastTurn = num;
   }

   public final int getInvestigateNumThisTurn() {
      return this.investigatedThisTurn;
   }

   public final void addInvestigatedThisTurn() {
      ++this.investigatedThisTurn;
      Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
      runParams.put(AbilityKey.FirstTime, this.investigatedThisTurn == 1);
      this.game.getTriggerHandler().runTrigger(TriggerType.Investigated, runParams, false);
   }

   public final void addSacrificedThisTurn(Card cpy, SpellAbility source) {
      this.game.fireEvent(new GameEventCardSacrificed(CardView.get(cpy)));
      this.sacrificedThisTurn.add(cpy);
      Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
      runParams.put(AbilityKey.Card, cpy);
      runParams.put(AbilityKey.Cause, source);
      runParams.put(AbilityKey.CostStack, this.game.costPaymentStack);
      runParams.put(AbilityKey.IndividualCostPaymentInstance, this.game.costPaymentStack.peek());
      this.game.getTriggerHandler().runTrigger(TriggerType.Sacrificed, runParams, false);
   }

   public final List<Card> getSacrificedThisTurn() {
      return this.sacrificedThisTurn;
   }

   public final void resetSacrificedThisTurn() {
      this.sacrificedThisTurn.clear();
   }

   public final List<Card> getSpellsCastSinceBegOfYourLastTurn() {
      List<Card> all = new ArrayList(this.game.getStack().getSpellCardsCastThisTurn());
      all.addAll(this.spellsCastSinceBeginningOfLastTurn);
      return all;
   }

   public final void resetSpellCastSinceBegOfYourLastTurn() {
      this.spellsCastSinceBeginningOfLastTurn = Lists.newArrayList();
   }

   public final void addSpellCastSinceBegOfYourLastTurn(List<Card> spells) {
      this.spellsCastSinceBeginningOfLastTurn.addAll(spells);
   }

   public final int getSpellsCastThisTurn() {
      return (int)this.getGame().getStack().getSpellsCastThisTurn().stream().filter((sp) -> this.equals(sp.getActivatingPlayer())).count();
   }

   public final int getSpellsCastLastTurn() {
      return this.spellsCastLastTurn;
   }

   public final void addSpellCastThisTurn() {
      ++this.spellsCastThisGame;
      ++this.achievementTracker.spellsCast;
      this.achievementTracker.maxStormCount = Math.max(this.achievementTracker.maxStormCount, this.getSpellsCastThisTurn());
   }

   public final void setSpellsCastLastTurn(int num) {
      this.spellsCastLastTurn = num;
   }

   public final int getSpellsCastThisGame() {
      return this.spellsCastThisGame;
   }

   public final void resetSpellCastThisGame() {
      this.spellsCastThisGame = 0;
   }

   public final int getLifeGainedByTeamThisTurn() {
      return this.lifeGainedByTeamThisTurn;
   }

   public final void addLifeGainedByTeamThisTurn(int val) {
      this.lifeGainedByTeamThisTurn = NovaMath.add(this.lifeGainedByTeamThisTurn, val);
   }

   public final int getLifeGainedThisTurn() {
      return this.lifeGainedThisTurn;
   }

   public final void setLifeGainedThisTurn(int n) {
      this.lifeGainedThisTurn = n;
   }

   public final int getLifeGainedTimesThisTurn() {
      return this.lifeGainedTimesThisTurn;
   }

   public final int getLifeLostThisTurn() {
      return this.lifeLostThisTurn;
   }

   public final void setLifeLostThisTurn(int n) {
      this.lifeLostThisTurn = n;
   }

   public final int getLifeLostLastTurn() {
      return this.lifeLostLastTurn;
   }

   public final void setLifeLostLastTurn(int n) {
      this.lifeLostLastTurn = n;
   }

   public final int getNumManaShards() {
      return this.numManaShards;
   }

   public final void setNumManaShards(int n) {
      int old = this.numManaShards;
      this.numManaShards = n;
      this.view.updateNumManaShards(this);
      this.game.fireEvent(new GameEventPlayerShardsChanged(this, old, this.numManaShards));
   }

   public int compareTo(Player o) {
      return o == null ? 1 : this.getName().compareTo(o.getName());
   }

   public void setDraftNotes(Map<String, String> notes) {
      this.draftNotes.clear();
      this.draftNotes.putAll(notes);
   }

   public Map<String, String> getDraftNotes() {
      return this.draftNotes;
   }

   public final LobbyPlayer getLobbyPlayer() {
      return this.getController().getLobbyPlayer();
   }

   public final LobbyPlayer getOriginalLobbyPlayer() {
      return this.controller.getLobbyPlayer();
   }

   public final RegisteredPlayer getRegisteredPlayer() {
      return (RegisteredPlayer)this.game.getMatch().getPlayers().get(this.game.getRegisteredPlayers().indexOf(this));
   }

   public final PlayerOutcome getOutcome() {
      return this.stats.getOutcome();
   }

   private void setOutcome(PlayerOutcome outcome) {
      this.stats.setOutcome(outcome);
   }

   public void onGameOver() {
      if (null == this.stats.getOutcome()) {
         this.setOutcome(PlayerOutcome.win());
      }

   }

   public CardCollection getCreaturesInPlay() {
      return CardLists.filter(this.getCardsIn(ZoneType.Battlefield), CardPredicates.CREATURES);
   }

   public CardCollection getPlaneswalkersInPlay() {
      return CardLists.filter(this.getCardsIn(ZoneType.Battlefield), CardPredicates.PLANESWALKERS);
   }

   public CardCollection getBattlesInPlay() {
      return CardLists.filter(this.getCardsIn(ZoneType.Battlefield), CardPredicates.BATTLES);
   }

   public CardCollection getTokensInPlay() {
      return CardLists.filter(this.getCardsIn(ZoneType.Battlefield), CardPredicates.TOKEN);
   }

   public CardCollection getLandsInPlay() {
      return CardLists.filter(this.getCardsIn(ZoneType.Battlefield), CardPredicates.LANDS);
   }

   public boolean isCardInPlay(String cardName) {
      return this.getZone(ZoneType.Battlefield).contains(CardPredicates.nameEquals(cardName));
   }

   public boolean isCardInCommand(String cardName) {
      return this.getZone(ZoneType.Command).contains(CardPredicates.nameEquals(cardName));
   }

   public CardCollectionView getColoredCardsInPlay(byte color) {
      return CardLists.getColor(this.getCardsIn(ZoneType.Battlefield), color);
   }

   public final int getAmountOfKeyword(String k) {
      return this.keywords.getAmount(k);
   }

   public boolean isTurnOrderReversed() {
      return StaticAbilityTurnPhaseReversed.isTurnReversed(this);
   }

   public boolean isPhasesReversed() {
      return StaticAbilityTurnPhaseReversed.isPhaseReversed(this);
   }

   public void onCleanupPhase() {
      for(Card c : this.getCardsIn(ZoneType.Hand)) {
         c.setDrawnThisTurn(false);
      }

      for(PlayerZone pz : this.zones.values()) {
         pz.resetCardsAddedThisTurn();
      }

      this.setNumDrawnLastTurn(this.getNumDrawnThisTurn());
      this.resetNumDrawnThisTurn();
      this.resetNumRollsThisTurn();
      this.resetNumFlipsThisTurn();
      this.resetNumExploredThisTurn();
      this.resetNumForetoldThisTurn();
      this.resetNumTokenCreatedThisTurn();
      this.setNumCardsInHandStartedThisTurnWith(this.getCardsIn(ZoneType.Hand).size());
      this.setTappedLandForManaThisTurn(false);
      this.setLandsPlayedLastTurn(this.getLandsPlayedThisTurn());
      this.resetLandsPlayedThisTurn();
      this.investigatedThisTurn = 0;
      this.scryThisTurn = 0;
      this.surveilThisTurn = 0;
      this.resetDiscardedThisTurn();
      this.resetSacrificedThisTurn();
      this.resetVenturedThisTurn();
      this.setDescended(0);
      this.setSpellsCastLastTurn(this.getSpellsCastThisTurn());
      this.setLifeLostLastTurn(this.getLifeLostThisTurn());
      this.setLifeLostThisTurn(0);
      this.setLifeGainedThisTurn(0);
      this.lifeGainedTimesThisTurn = 0;
      this.lifeGainedByTeamThisTurn = 0;
      this.setLifeStartedThisTurnWith(this.getLife());
      this.setLibrarySearched(0);
      this.setCommitedCrimeThisTurn(0);
      this.diceRollsThisTurn = Lists.newArrayList();
      this.setExpentThisTurn(0);
      this.attractionsVisitedThisTurn = 0;
      this.damageReceivedThisTurn.clear();
      this.planeswalkedToThisTurn.clear();
      this.elementalBendThisTurn.clear();
      if (this.game.getPhaseHandler().isPlayerTurn(this)) {
         this.setBeenDealtCombatDamageSinceLastTurn(false);
         this.setAttackedPlayersMyLastTurn(this.getAttackedPlayersMyTurn());
         this.clearAttackedMyTurn();
         this.lastTurnNr = this.game.getPhaseHandler().getTurn();
      }

   }

   public boolean canCastSorcery() {
      PhaseHandler now = this.game.getPhaseHandler();
      return now.isPlayerTurn(this) && now.getPhase().isMain() && this.game.getStack().isEmpty();
   }

   public final PlayerController getController() {
      return !this.controlledBy.isEmpty() ? (PlayerController)((Pair)this.controlledBy.lastEntry().getValue()).getValue() : this.controller;
   }

   public final Player getControllingPlayer() {
      return !this.controlledBy.isEmpty() ? (Player)((Pair)this.controlledBy.lastEntry().getValue()).getKey() : null;
   }

   public final boolean isControlled() {
      Player ctrlPlayer = this.getControllingPlayer();
      return ctrlPlayer != null && ctrlPlayer != this;
   }

   public void addController(long timestamp, Player pl) {
      IGameEntitiesFactory master = (IGameEntitiesFactory)pl.getLobbyPlayer();
      this.addController(timestamp, pl, master.createMindSlaveController(pl, this), true);
   }

   public void addController(long timestamp, Player pl, PlayerController pc, boolean event) {
      this.controlledBy.put(timestamp, Pair.of(pl, pc));
      this.getView().updateMindSlaveMaster(this);
      if (event) {
         this.game.fireEvent(new GameEventPlayerControl(this.getView(), this.getLobbyPlayer().getName(), !this.getController().isAI()));
      }

   }

   public void removeController(Player p) {
      for(Map.Entry<Long, Pair<Player, PlayerController>> controller : Sets.newHashSet(this.controlledBy.entrySet())) {
         if (((Player)((Pair)controller.getValue()).getLeft()).equals(p)) {
            this.removeController((Long)controller.getKey());
         }
      }

   }

   public void removeController(long timestamp) {
      this.removeController(timestamp, true);
   }

   public void removeController(long timestamp, boolean event) {
      this.controlledBy.remove(timestamp);
      this.getView().updateMindSlaveMaster(this);
      if (event) {
         this.game.fireEvent(new GameEventPlayerControl(this.getView(), this.getLobbyPlayer().getName(), !this.getController().isAI()));
      }

   }

   public void clearController() {
      this.controlledBy.clear();
      this.game.fireEvent(new GameEventPlayerControl(this.getView(), (String)null, !this.getController().isAI()));
   }

   public Map.Entry<Long, Player> getControlledWhileSearching() {
      return this.controlledWhileSearching.isEmpty() ? null : this.controlledWhileSearching.lastEntry();
   }

   public void addControlledWhileSearching(long timestamp, Player pl) {
      this.controlledWhileSearching.put(timestamp, pl);
   }

   public void removeControlledWhileSearching(long timestamp) {
      this.controlledWhileSearching.remove(timestamp);
   }

   public final void setFirstController(PlayerController ctrlr) {
      if (this.controller != null) {
         throw new IllegalStateException("Controller creator already assigned");
      } else {
         this.dangerouslySetController(ctrlr);
      }
   }

   public final void dangerouslySetController(PlayerController ctrlr) {
      this.controller = ctrlr;
      this.updateAvatar();
      this.updateSleeve();
      this.view.updateIsAI(this);
      this.view.updateLobbyPlayerName(this);
   }

   public void updateAvatar() {
      this.view.updateAvatarIndex(this);
      this.view.updateAvatarCardImageKey(this);
      this.view.setAvatarLifeDifference(0);
      this.view.setHasLost(false);
   }

   public void updateSleeve() {
      this.view.updateSleeveIndex(this);
      this.view.updateSleeveArtKey(this);
      this.view.updateSleeveArtOffset(this);
   }

   public void runWithController(Runnable proc, PlayerController tempController) {
      long ts = this.game.getNextTimestamp();
      this.addController(ts, this, tempController, false);

      try {
         proc.run();
      } finally {
         this.removeController(ts, false);
      }

   }

   public boolean isSkippingCombat() {
      return !this.isInGame();
   }

   public final List<Card> getPlaneswalkedToThisTurn() {
      return this.planeswalkedToThisTurn;
   }

   public void planeswalk(SpellAbility sa) {
      this.planeswalkTo(sa, new CardCollection(this.getZone(ZoneType.PlanarDeck).get(0)));
   }

   public void planeswalkTo(SpellAbility sa, CardCollectionView destinations) {
      PrintStream var10000 = System.out;
      String var10001 = this.getName();
      var10000.println(var10001 + " planeswalks to " + destinations.toString());
      this.game.getView().updatePlanarPlayer(this.getView());

      for(Card c : destinations) {
         this.currentPlanes.add(this.game.getAction().moveTo(this.getZone(ZoneType.Command), c, sa, AbilityKey.newMap()));
         this.planeswalkedToThisTurn.add(c);
      }

      this.game.setActivePlanes(this.currentPlanes);
      Map<AbilityKey, Object> runParams = AbilityKey.newMap();
      runParams.put(AbilityKey.Cards, destinations);
      this.game.getTriggerHandler().runTrigger(TriggerType.PlaneswalkedTo, runParams, false);
      this.view.updateCurrentPlaneName(this.currentPlanes.toString().replaceAll(" \\(.*", "").replace("[", ""));
   }

   public void leaveCurrentPlane() {
      Map<AbilityKey, Object> runParams = AbilityKey.newMap();
      runParams.put(AbilityKey.Cards, new CardCollection(this.currentPlanes));
      this.game.getTriggerHandler().runTrigger(TriggerType.PlaneswalkedFrom, runParams, false);

      for(Card plane : this.currentPlanes) {
         plane.clearControllers();
         this.game.getAction().moveTo(ZoneType.PlanarDeck, plane, -1, (SpellAbility)null, AbilityKey.newMap());
      }

      this.currentPlanes.clear();
   }

   public void removeCurrentPlane(Card c) {
      this.currentPlanes.remove(c);
   }

   public void initPlane() {
      if (!this.game.isGameOver()) {
         this.view.updateCurrentPlaneName("");
         this.game.getView().updatePlanarPlayer(this.getView());
         PlayerZone planarDeck = this.getZone(ZoneType.PlanarDeck);

         while(!planarDeck.isEmpty()) {
            Card firstPlane = planarDeck.get(0);
            planarDeck.remove(firstPlane);
            if (!firstPlane.getType().isPhenomenon()) {
               this.currentPlanes.add(firstPlane);
               this.getZone(ZoneType.Command).add(firstPlane);
               break;
            }

            planarDeck.add(firstPlane);
         }

         this.game.setActivePlanes(this.currentPlanes);
         this.view.updateCurrentPlaneName(this.currentPlanes.toString().replaceAll(" \\(.*", "").replace("[", ""));
      }
   }

   public CardCollectionView getInboundTokens() {
      return this.inboundTokens;
   }

   public void addInboundToken(Card c) {
      this.inboundTokens.add(c);
   }

   public void removeInboundToken(Card c) {
      this.inboundTokens.remove(c);
   }

   public void onMulliganned() {
      this.game.fireEvent(new GameEventMulligan(PlayerView.get(this)));
      int newHand = this.getCardsIn(ZoneType.Hand).size();
      this.stats.notifyHasMulliganed();
      this.stats.notifyOpeningHandSize(newHand);
      this.achievementTracker.mulliganTo = newHand;
   }

   public List<Card> getCommanders() {
      return this.commanders;
   }

   public void copyCommandersToSnapshot(Player toPlayer, Function<Card, Card> mapper) {
      Function<Card, Card> mapCommander = (cx) -> (Card)mapper.apply(this.game.getCardState(cx));
      toPlayer.resetCommanderStats();
      toPlayer.commanders.clear();

      for(Card c : this.getCommanders()) {
         Card newCommander = (Card)mapCommander.apply(c);
         if (newCommander == null) {
            System.err.println("Unable to find commander in game snapshot: " + String.valueOf(c));
         } else {
            toPlayer.commanders.add(newCommander);
            newCommander.setCommander(true);
         }
      }

      for(Map.Entry<Card, Integer> entry : this.commanderCast.entrySet()) {
         Card commander = (Card)mapCommander.apply((Card)entry.getKey());
         if (commander != null) {
            toPlayer.commanderCast.put(commander, (Integer)entry.getValue());
         }
      }

      for(Map.Entry<Card, Integer> entry : this.getCommanderDamage()) {
         Card commander = (Card)mapCommander.apply((Card)entry.getKey());
         if (commander != null) {
            int damage = (Integer)entry.getValue();
            toPlayer.addCommanderDamage(commander, damage);
         }
      }

      if (this.commanderEffect != null) {
         Card commanderEffect = (Card)mapper.apply(this.commanderEffect);
         toPlayer.commanderEffect = (DetachedCardEffect)commanderEffect;
      }

   }

   public void copyEffectCardsToSnapshot(Player toPlayer, Function<Card, Card> mapper) {
      toPlayer.keywordEffect = mapEffectCard(this.keywordEffect, mapper);
      toPlayer.monarchEffect = mapEffectCard(this.monarchEffect, mapper);
      toPlayer.initiativeEffect = mapEffectCard(this.initiativeEffect, mapper);
      toPlayer.blessingEffect = mapEffectCard(this.blessingEffect, mapper);
      toPlayer.enduringStoryEffect = mapEffectCard(this.enduringStoryEffect, mapper);
      toPlayer.contraptionSprocketEffect = mapEffectCard(this.contraptionSprocketEffect, mapper);
      toPlayer.radiationEffect = mapEffectCard(this.radiationEffect, mapper);
      toPlayer.speedEffect = mapEffectCard(this.speedEffect, mapper);
   }

   private static Card mapEffectCard(Card effect, Function<Card, Card> mapper) {
      return effect != null && effect.getZone() != null ? (Card)mapper.apply(effect) : null;
   }

   public void addCommander(Card commander) {
      assert this.equals(commander.getOwner());

      if (!this.commanders.contains(commander)) {
         this.commanders.add(commander);
         if (this.commanderEffect == null) {
            this.createCommanderEffect();
         }

         commander.setCommander(true);
         this.view.updateCommander(this);
      }
   }

   public void removeCommander(Card commander) {
      if (this.commanders.remove(commander)) {
         commander.setCommander(false);
         this.view.updateCommander(this);
      }
   }

   public void setCommanderReplacementSuppressed(boolean suppress) {
      if (this.commanderEffect != null) {
         for(ReplacementEffect re : this.commanderEffect.getReplacementEffects()) {
            re.setSuppressed(suppress);
         }

      }
   }

   public Iterable<Map.Entry<Card, Integer>> getCommanderDamage() {
      return this.commanderDamage.entrySet();
   }

   public int getCommanderDamage(Card commander) {
      Integer damage = (Integer)this.commanderDamage.get(commander);
      return damage == null ? 0 : damage;
   }

   public void addCommanderDamage(Card commander, int damage) {
      this.commanderDamage.merge(commander, damage, NovaMath::add);
   }

   public ColorSet getCommanderColorID() {
      if (this.commanders.isEmpty()) {
         return null;
      } else {
         byte ci = 0;

         for(Card c : this.commanders) {
            ci |= c.getRules().getColorIdentity().getColor();
         }

         ColorSet identity = ColorSet.fromMask(ci);
         return identity;
      }
   }

   public int getCommanderCast(Card commander) {
      return (Integer)this.commanderCast.getOrDefault(commander, 0);
   }

   public void incCommanderCast(Card commander) {
      this.commanderCast.merge(commander, 1, Integer::sum);
      this.getView().updateCommanderCast(this, commander);
      this.getGame().fireEvent(new GameEventPlayerStatsChanged(this));
   }

   public void resetCommanderStats() {
      this.commanderCast.clear();
      this.commanderDamage.clear();
   }

   public void updateMergedCommanderInfo(Card target, Card commander) {
      this.getView().updateMergedCommanderCast(this, target, commander);
      this.getView().updateMergedCommanderDamage(target, commander);
   }

   public int getTotalCommanderCast() {
      int result = 0;

      for(Integer i : this.commanderCast.values()) {
         result += i;
      }

      return result;
   }

   public boolean isExtraTurn() {
      return this.view.getIsExtraTurn();
   }

   public void setExtraTurn(boolean b) {
      this.view.setIsExtraTurn(b);
   }

   public void setHasLost(boolean b) {
      this.view.setHasLost(b);
   }

   public int getExtraTurnCount() {
      return this.view.getExtraTurnCount();
   }

   public void setExtraTurnCount(int val) {
      this.view.setExtraTurnCount(val);
   }

   public void setHasPriority(boolean val) {
      this.view.setHasPriority(val);
   }

   public boolean isAI() {
      return this.view.isAI();
   }

   public void initVariantsZones(RegisteredPlayer registeredPlayer) {
      PlayerZone bf = this.getZone(ZoneType.Battlefield);
      Iterable<? extends IPaperCard> cards = registeredPlayer.getCardsOnBattlefield();
      if (cards != null) {
         for(IPaperCard cp : cards) {
            Card c = Card.fromPaperCard(cp, this);
            bf.add(c);
            c.setCollectible(false);
            c.setSickness(true);
            c.setStartsGameInPlay(true);
            if (registeredPlayer.hasEnableETBCountersEffect()) {
               for(KeywordInterface inst : c.getKeywords()) {
                  String keyword = inst.getOriginal();

                  try {
                     if (keyword.startsWith("etbCounter")) {
                        String[] p = keyword.split(":");
                        c.addCounterInternal(CounterType.getType(p[1]), Integer.parseInt(p[2]), (Player)null, false, (GameEntityCounterTable)null, (Map)null);
                     }
                  } catch (Exception e) {
                     e.printStackTrace();
                  }
               }
            }
         }
      }

      PlayerZone com = this.getZone(ZoneType.Command);
      if (registeredPlayer.getVanguardAvatars() != null) {
         for(PaperCard avatar : registeredPlayer.getVanguardAvatars()) {
            Card c = Card.fromPaperCard(avatar, this);
            c.setCollectible(true);
            com.add(c);
         }
      }

      CardCollection sd = new CardCollection();

      for(IPaperCard cp : registeredPlayer.getSchemes()) {
         sd.add(Card.fromPaperCard(cp, this));
      }

      if (!sd.isEmpty()) {
         for(Card c : sd) {
            c.setCollectible(true);
            this.getZone(ZoneType.SchemeDeck).add(c);
         }

         this.getZone(ZoneType.SchemeDeck).shuffle();
      }

      CardCollection l = new CardCollection();

      for(IPaperCard cp : registeredPlayer.getPlanes()) {
         l.add(Card.fromPaperCard(cp, this));
      }

      if (!l.isEmpty()) {
         for(Card c : l) {
            c.setCollectible(true);
            this.getZone(ZoneType.PlanarDeck).add(c);
         }

         this.getZone(ZoneType.PlanarDeck).shuffle();
      }

      if (!registeredPlayer.getCommanders().isEmpty()) {
         for(PaperCard pc : registeredPlayer.getCommanders()) {
            Card cmd = Card.fromPaperCard(pc, this);
            this.initCommanderColor(cmd);
            cmd.setCollectible(true);
            com.add(cmd);
            this.addCommander(cmd);
         }
      } else if (registeredPlayer.getPlaneswalker() != null) {
         Card cmd = Card.fromPaperCard(registeredPlayer.getPlaneswalker(), this);
         cmd.setCollectible(true);
         cmd.setCommander(true);
         com.add(cmd);
         this.addCommander(cmd);
      }

      for(IPaperCard cp : registeredPlayer.getConspiracies()) {
         Card conspire = Card.fromPaperCard(cp, this);
         boolean addToCommand = true;

         for(KeywordInterface ki : conspire.getKeywords(Keyword.HIDDEN_AGENDA)) {
            if (!CardFactoryUtil.handleHiddenAgenda(this, conspire, ki)) {
               addToCommand = false;
            }
         }

         for(KeywordInterface ki : conspire.getKeywords(Keyword.DOUBLE_AGENDA)) {
            if (!CardFactoryUtil.handleHiddenAgenda(this, conspire, ki)) {
               addToCommand = false;
            }
         }

         if (Objects.equals(conspire.getName(), "Backup Plan")) {
            PlayerZone hand = new PlayerZone(ZoneType.ExtraHand, this);
            if (this.extraZones == null) {
               this.extraZones = new ArrayList();
            }

            this.extraZones.add(hand);
         }

         if (addToCommand) {
            com.add(conspire);
         }
      }

      PlayerZone attractionDeck = this.getZone(ZoneType.AttractionDeck);

      for(IPaperCard cp : registeredPlayer.getAttractions()) {
         Card c = Card.fromPaperCard(cp, this);
         c.setCollectible(true);
         attractionDeck.add(c);
      }

      if (!attractionDeck.isEmpty()) {
         attractionDeck.shuffle();
      }

      PlayerZone contraptionDeck = this.getZone(ZoneType.ContraptionDeck);

      for(IPaperCard cp : registeredPlayer.getContraptions()) {
         Card c = Card.fromPaperCard(cp, this);
         c.setCollectible(true);
         contraptionDeck.add(c);
      }

      if (!contraptionDeck.isEmpty()) {
         contraptionDeck.shuffle();
      }

      Iterable<? extends IPaperCard> adventureItemCards = registeredPlayer.getExtraCardsInCommandZone();
      if (adventureItemCards != null) {
         for(IPaperCard cp : adventureItemCards) {
            Card c = Card.fromPaperCard(cp, this);
            com.add(c);
            c.setStartsGameInPlay(true);
         }
      }

      for(Card c : List.copyOf(this.getCardsIn(ZoneType.Library))) {
         for(KeywordInterface inst : c.getKeywords()) {
            String kw = inst.getOriginal();
            if (kw.startsWith("MayEffectFromOpeningDeck")) {
               String[] split = kw.split(":");
               String effName = split[1];
               SpellAbility effect = AbilityFactory.getAbility(c.getSVar(effName), c);
               effect.setActivatingPlayer(this);
               this.getController().playSpellAbilityNoStack(effect, true);
            }
         }
      }

   }

   public void initCommanderColor(Card cmd) {
      if (cmd.getRules().getAddsWildCardColor()) {
         Player p = cmd.getController();
         String prompt = Localizer.getInstance().getMessage("lblChooseAColorFor", new Object[]{cmd.getName()});
         SpellAbility cmdColorsa = new SpellAbility.EmptySa(ApiType.ChooseColor, cmd, p);
         byte chosenColor = p.getController().chooseColor(prompt, cmdColorsa, ColorSet.WUBRG);
         cmd.setChosenColors(List.of(MagicColor.toLongString(chosenColor)));
         p.getGame().getAction().notifyOfValue(cmdColorsa, cmd, Localizer.getInstance().getMessage("lblPlayerPickedChosen", new Object[]{p.getName(), MagicColor.toLongString(chosenColor)}), p);
      }

   }

   public boolean allCardsUniqueManaSymbols() {
      for(Card c : this.getCardsIn(ZoneType.Library)) {
         Set<CardStateName> cardStateNames = c.isSplitCard() ? EnumSet.of(CardStateName.LeftSplit, CardStateName.RightSplit) : EnumSet.of(CardStateName.Original);
         Set<ManaCostShard> coloredManaSymbols = new HashSet();
         Set<Integer> genericManaSymbols = new HashSet();

         for(CardStateName cardStateName : cardStateNames) {
            ManaCost manaCost = c.getState(cardStateName).getManaCost();

            for(ManaCostShard manaSymbol : manaCost) {
               if (!coloredManaSymbols.add(manaSymbol)) {
                  return false;
               }
            }

            int generic = manaCost.getGenericCost();
            if ((generic > 0 || manaCost.getCMC() == 0) && !genericManaSymbols.add(generic)) {
               return false;
            }
         }
      }

      return true;
   }

   public void assignCompanion(Game game, PlayerController player) {
      List<Card> legalCompanions = Lists.newArrayList();
      boolean uniqueNames = true;
      Set<String> cardNames = new HashSet();
      Set<CardType.CoreType> cardTypes = EnumSet.allOf(CardType.CoreType.class);

      for(Card c : CardLists.getNotType(this.getCardsIn(ZoneType.Library), "Land")) {
         if (uniqueNames) {
            if (cardNames.contains(c.getName())) {
               uniqueNames = false;
            } else {
               cardNames.add(c.getName());
            }
         }

         cardTypes.retainAll(c.getPaperCard().getRules().getType().getCoreTypes());
      }

      int deckSize = this.getCardsIn(ZoneType.Library).size();
      int minSize = (Integer)game.getMatch().getRules().getGameType().getDeckFormat().getMainRange().getMinimum();
      game.getAction().checkStaticAbilities(false);

      for(Card c : this.getCardsIn(ZoneType.Sideboard)) {
         for(KeywordInterface inst : c.getKeywords(Keyword.COMPANION)) {
            if (inst instanceof Companion kwInstance) {
               if (kwInstance.hasSpecialRestriction()) {
                  String specialRules = kwInstance.getSpecialRules();
                  if (specialRules.equals("UniqueNames")) {
                     if (uniqueNames) {
                        legalCompanions.add(c);
                     }
                  } else if (specialRules.equals("UniqueManaSymbols")) {
                     if (this.allCardsUniqueManaSymbols()) {
                        legalCompanions.add(c);
                     }
                  } else if (specialRules.equals("DeckSizePlus20")) {
                     if (deckSize >= minSize + 20) {
                        legalCompanions.add(c);
                     }
                  } else if (specialRules.equals("SharesCardType") && !cardTypes.isEmpty()) {
                     legalCompanions.add(c);
                  }
               } else {
                  String restriction = kwInstance.getDeckRestriction();
                  if (this.deckMatchesDeckRestriction(c, restriction)) {
                     legalCompanions.add(c);
                  }
               }
            }
         }
      }

      if (!legalCompanions.isEmpty()) {
         CardCollectionView view = CardCollection.getView(legalCompanions);
         SpellAbility fakeSa = new SpellAbility.EmptySa(ApiType.CompanionChoose, (Card)legalCompanions.get(0), this);
         Card companion = (Card)player.chooseSingleEntityForEffect(view, fakeSa, Localizer.getInstance().getMessage("lblChooseACompanion", new Object[0]), true, (Map)null);
         PlayerZone commandZone = this.getZone(ZoneType.Command);
         companion = game.getAction().moveTo(ZoneType.Command, companion, (SpellAbility)null, AbilityKey.newMap());
         commandZone.add(createCompanionEffect(companion));
         this.updateZoneForView(commandZone);
      }
   }

   public boolean deckMatchesDeckRestriction(Card source, String restriction) {
      for(Card c : this.getCardsIn(ZoneType.Library)) {
         if (!c.isValid(restriction.split(","), this, source, (CardTraitBase)null)) {
            return false;
         }
      }

      return true;
   }

   public static DetachedCardEffect createCompanionEffect(Card companion) {
      String name = Lang.getInstance().getPossesive(companion.getDisplayName()) + " Companion Effect";
      DetachedCardEffect eff = new DetachedCardEffect(companion, name);
      String addToHandAbility = "Mode$ Continuous | EffectZone$ Command | Affected$ Card.YouOwn+EffectSource | AffectedZone$ Command | AddAbility$ MoveToHand";
      String moveToHand = "ST$ ChangeZone | Cost$ 3 | Defined$ Self | Origin$ Command | Destination$ Hand | SorcerySpeed$ True | ActivationZone$ Command | SpellDescription$ Companion - Put CARDNAME in to your hand";
      StaticAbility stAb = StaticAbility.create(addToHandAbility, eff, eff.getCurrentState(), true);
      stAb.setSVar("MoveToHand", moveToHand);
      eff.addStaticAbility(stAb);
      return eff;
   }

   public void createCommanderEffect() {
      PlayerZone com = this.getZone(ZoneType.Command);
      if (this.commanderEffect != null) {
         com.remove(this.commanderEffect);
      }

      DetachedCardEffect eff = new DetachedCardEffect(this, "Commander Effect");
      String validCommander = "Card.IsCommander+YouOwn";
      if (this.game.getRules().hasAppliedVariant(GameType.Oathbreaker)) {
         String effStr = "DB$ ChangeZone | Origin$ Stack | Destination$ Command | Defined$ ReplacedCard";
         String moved = "Event$ Moved | ValidCard$ Spell.IsCommander+YouOwn | Secondary$ True | Destination$ Graveyard,Exile,Hand,Library | Description$ If a signature spell would be put into another zone from the stack, put it into the command zone instead.";
         ReplacementEffect re = ReplacementHandler.parseReplacement(moved, eff, true);
         re.setOverridingAbility(AbilityFactory.getAbility(effStr, eff));
         eff.addReplacementEffect(re);
         validCommander = "Permanent.IsCommander+YouOwn";
         String castRestriction = "Mode$ CantBeCast | ValidCard$ Spell.IsCommander+YouOwn | EffectZone$ Command | IsPresent$ Permanent.IsCommander+YouOwn+YouCtrl | PresentZone$ Battlefield | PresentCompare$ EQ0 | Description$ Signature spell can only be cast if your oathbreaker is on the battlefield under your control.";
         eff.addStaticAbility(castRestriction);
      }

      String effStr = "DB$ ChangeZone | Origin$ Battlefield,Graveyard,Exile,Library,Hand | Destination$ Command | Defined$ ReplacedCard";
      String moved = "Event$ Moved | ValidCard$ " + validCommander + " | Secondary$ True | Optional$ True | OptionalDecider$ You | CommanderMoveReplacement$ True ";
      if (this.game.getRules().hasAppliedVariant(GameType.TinyLeaders)) {
         moved = moved + " | Destination$ Graveyard,Exile | Description$ If a commander would be put into its owner's graveyard or exile from anywhere, that player may put it into the command zone instead.";
      } else if (this.game.getRules().hasAppliedVariant(GameType.Oathbreaker)) {
         moved = moved + " | Destination$ Graveyard,Exile,Hand,Library | Description$ If a commander would be exiled or put into hand, graveyard, or library from anywhere, that player may put it into the command zone instead.";
      } else {
         moved = moved + " | Destination$ Hand,Library | Description$ If a commander would be put into its owner's hand or library from anywhere, its owner may put it into the command zone instead.";
      }

      ReplacementEffect re = ReplacementHandler.parseReplacement(moved, eff, true);
      re.setOverridingAbility(AbilityFactory.getAbility(effStr, eff));
      eff.addReplacementEffect(re);
      String mayBePlayedAbility = "Mode$ Continuous | EffectZone$ Command | MayPlay$ True | Affected$ Card.IsCommander+YouOwn | AffectedZone$ Command";
      if (this.game.getRules().hasAppliedVariant(GameType.Planeswalker)) {
         mayBePlayedAbility = mayBePlayedAbility + " | MayPlayIgnoreColor$ True";
      }

      eff.addStaticAbility(mayBePlayedAbility);
      this.commanderEffect = eff;
      com.add(eff);
   }

   public void createPlanechaseEffects(Game game) {
      PlayerZone com = this.getZone(ZoneType.Command);
      String name = "Planar Dice";
      Card eff = new Card(game.nextCardId(), game);
      eff.setGameTimestamp(game.getNextTimestamp());
      eff.setName("Planar Dice");
      eff.setOwner(this);
      eff.setGamePieceType(GamePieceType.EFFECT);
      String image = ImageKeys.getTokenKey("planechase");
      eff.setImageKey(image);
      String trigger = "Mode$ PlanarDice | Result$ Planeswalk | TriggerZones$ Command | ValidPlayer$ You | Secondary$ True | TriggerDescription$ Whenever you roll the Planeswalker symbol on the planar die, planeswalk.";
      String rolledWalk = "DB$ Planeswalk | Cause$ PlanarDie";
      Trigger planesWalkTrigger = TriggerHandler.parseTrigger(trigger, eff, true);
      planesWalkTrigger.setOverridingAbility(AbilityFactory.getAbility(rolledWalk, eff));
      eff.addTrigger(planesWalkTrigger);
      String specialA = "ST$ RollPlanarDice | Cost$ X | SorcerySpeed$ True | Activator$ Player | SpecialAction$ True | ActivationZone$ Command | SpellDescription$ Roll the planar dice. X is equal to the number of times you have previously taken this action this turn. | CostDesc$ {X}: ";
      SpellAbility planarRoll = AbilityFactory.getAbility(specialA, eff);
      planarRoll.setSVar("X", "Count$PlanarDiceSpecialActionThisTurn");
      eff.addSpellAbility(planarRoll);
      eff.updateStateForView();
      com.add(eff);
      this.updateZoneForView(com);
   }

   public void createTheRing(String set) {
      if (this.theRing == null) {
         PlayerZone com = this.getZone(ZoneType.Command);
         this.theRing = new Card(this.game.nextCardId(), (IPaperCard)null, this.game);
         this.theRing.setOwner(this);
         this.theRing.setGamePieceType(GamePieceType.EFFECT);
         this.theRing.setImageKey(StaticData.instance().getOtherImageKey("the_ring", set));
         if (set != null) {
            this.theRing.setSetCode(set);
         }

         this.theRing.setName("The Ring");
         this.theRing.updateStateForView();
         com.add(this.theRing);
         this.updateZoneForView(com);
      }

   }

   public void setRingLevel(int level) {
      if (this.getTheRing() == null) {
         this.createTheRing((String)null);
      }

      if (level == 1) {
         String legendary = "Mode$ Continuous | EffectZone$ Command | Affected$ Card.YouCtrl+IsRingbearer | AddType$ Legendary | Description$ Your Ring-bearer is legendary.";
         String cantBeBlocked = "Mode$ CantBlockBy | EffectZone$ Command | ValidAttacker$ Card.YouCtrl+IsRingbearer | ValidBlockerRelative$ Creature.powerGTX | Description$ Your Ring-bearer can't be blocked by creatures with greater power.";
         this.getTheRing().addStaticAbility(legendary);
         StaticAbility st = this.getTheRing().addStaticAbility(cantBeBlocked);
         st.setSVar("X", "Count$CardPower");
      } else if (level == 2) {
         String attackTrig = "Mode$ Attacks | ValidCard$ Card.YouCtrl+IsRingbearer | TriggerDescription$ Whenever your ring-bearer attacks, draw a card, then discard a card. | TriggerZones$ Command";
         String drawEffect = "DB$ Draw | Defined$ You | NumCards$ 1";
         String discardEffect = "DB$ Discard | Defined$ You | NumCards$ 1 | Mode$ TgtChoose";
         Trigger attackTrigger = TriggerHandler.parseTrigger("Mode$ Attacks | ValidCard$ Card.YouCtrl+IsRingbearer | TriggerDescription$ Whenever your ring-bearer attacks, draw a card, then discard a card. | TriggerZones$ Command", this.getTheRing(), true);
         SpellAbility drawExecute = AbilityFactory.getAbility("DB$ Draw | Defined$ You | NumCards$ 1", this.getTheRing());
         AbilitySub discardExecute = (AbilitySub)AbilityFactory.getAbility("DB$ Discard | Defined$ You | NumCards$ 1 | Mode$ TgtChoose", this.getTheRing());
         drawExecute.setSubAbility(discardExecute);
         attackTrigger.setOverridingAbility(drawExecute);
         this.getTheRing().addTrigger(attackTrigger);
      } else if (level == 3) {
         String becomesBlockedTrig = "Mode$ AttackerBlockedByCreature | ValidCard$ Card.YouCtrl+IsRingbearer| ValidBlocker$ Creature | TriggerZones$ Command | TriggerDescription$ Whenever your Ring-bearer becomes blocked a creature, that creature's controller sacrifices it at the end of combat.";
         String endOfCombatTrig = "DB$ DelayedTrigger | Mode$ Phase | Phase$ EndCombat | RememberObjects$ TriggeredBlockerLKICopy | TriggerDescription$ At end of combat, the controller of the creature that blocked CARDNAME sacrifices that creature.";
         String sacBlockerEffect = "DB$ SacrificeAll | Defined$ DelayTriggerRememberedLKI";
         Trigger becomesBlockedTrigger = TriggerHandler.parseTrigger("Mode$ AttackerBlockedByCreature | ValidCard$ Card.YouCtrl+IsRingbearer| ValidBlocker$ Creature | TriggerZones$ Command | TriggerDescription$ Whenever your Ring-bearer becomes blocked a creature, that creature's controller sacrifices it at the end of combat.", this.getTheRing(), true);
         SpellAbility endCombatExecute = AbilityFactory.getAbility("DB$ DelayedTrigger | Mode$ Phase | Phase$ EndCombat | RememberObjects$ TriggeredBlockerLKICopy | TriggerDescription$ At end of combat, the controller of the creature that blocked CARDNAME sacrifices that creature.", this.getTheRing());
         AbilitySub sacExecute = (AbilitySub)AbilityFactory.getAbility("DB$ SacrificeAll | Defined$ DelayTriggerRememberedLKI", this.getTheRing());
         endCombatExecute.setAdditionalAbility("Execute", sacExecute);
         becomesBlockedTrigger.setOverridingAbility(endCombatExecute);
         this.getTheRing().addTrigger(becomesBlockedTrigger);
      } else if (level == 4) {
         String damageTrig = "Mode$ DamageDone | ValidSource$ Card.YouCtrl+IsRingbearer | ValidTarget$ Player | CombatDamage$ True | TriggerZones$ Command | TriggerDescription$ Whenever your Ring-bearer deals combat damage to a player, each opponent loses 3 life.";
         String loseEffect = "DB$ LoseLife | Defined$ Opponent | LifeAmount$ 3";
         Trigger damageTrigger = TriggerHandler.parseTrigger("Mode$ DamageDone | ValidSource$ Card.YouCtrl+IsRingbearer | ValidTarget$ Player | CombatDamage$ True | TriggerZones$ Command | TriggerDescription$ Whenever your Ring-bearer deals combat damage to a player, each opponent loses 3 life.", this.getTheRing(), true);
         SpellAbility loseExecute = AbilityFactory.getAbility("DB$ LoseLife | Defined$ Opponent | LifeAmount$ 3", this.getTheRing());
         damageTrigger.setOverridingAbility(loseExecute);
         this.getTheRing().addTrigger(damageTrigger);
      }

      this.getTheRing().updateStateForView();
   }

   public final int getNumRingTemptedYou() {
      return this.numRingTemptedYou;
   }

   public final void incrementRingTemptedYou() {
      ++this.numRingTemptedYou;
   }

   public final void setNumRingTemptedYou(int value) {
      this.numRingTemptedYou = value;
   }

   public final void resetRingTemptedYou() {
      this.numRingTemptedYou = 0;
   }

   public void changeOwnership(Card card) {
      Player oldOwner = card.getOwner();
      if (!this.equals(oldOwner)) {
         card.setOwner(this);
         if (card.isCollectible()) {
            if (card.getGame().getRules().useAnte()) {
               if (this.lostOwnership.contains(card)) {
                  this.lostOwnership.remove(card);
               } else {
                  this.gainedOwnership.add(card);
               }

               if (oldOwner.gainedOwnership.contains(card)) {
                  oldOwner.gainedOwnership.remove(card);
               } else {
                  oldOwner.lostOwnership.add(card);
               }

            }
         }
      }
   }

   public void destroyPhysicalCard(Card card) {
      if (card.isCollectible()) {
         card.getOwner().lostOwnership.add(card);
      }
   }

   public CardCollectionView getLostOwnership() {
      return this.lostOwnership;
   }

   public CardCollectionView getGainedOwnership() {
      return this.gainedOwnership;
   }

   public PlayerView getView() {
      return this.view;
   }

   public SpellAbility getPaidForSA() {
      return (SpellAbility)this.paidForStack.peek();
   }

   public void pushPaidForSA(SpellAbility sa) {
      this.paidForStack.push(sa);
   }

   public void popPaidForSA() {
      this.paidForStack.poll();
   }

   public void clearPaidForSA() {
      this.paidForStack.clear();
   }

   public boolean isStartingPlayer() {
      return this.equals(this.game.getStartingPlayer());
   }

   public boolean isMonarch() {
      return this.equals(this.game.getMonarch());
   }

   public String getMonarchSet() {
      return this.monarchEffect == null ? this.monarchEffect.getSetCode() : null;
   }

   public void createMonarchEffect(String set) {
      PlayerZone com = this.getZone(ZoneType.Command);
      if (this.monarchEffect == null) {
         this.monarchEffect = new Card(this.game.nextCardId(), (IPaperCard)null, this.game);
         this.monarchEffect.setOwner(this);
         this.monarchEffect.setGamePieceType(GamePieceType.EFFECT);
         this.monarchEffect.setImageKey(StaticData.instance().getOtherImageKey("monarch", set));
         this.monarchEffect.setSetCode(set);
         this.monarchEffect.setName("The Monarch");
         String drawTrig = "Mode$ Phase | Phase$ End of Turn | TriggerZones$ Command | ValidPlayer$ You |  TriggerDescription$ At the beginning of your end step, draw a card.";
         String drawEff = "DB$ Draw | Defined$ You";
         Trigger drawTrigger = TriggerHandler.parseTrigger("Mode$ Phase | Phase$ End of Turn | TriggerZones$ Command | ValidPlayer$ You |  TriggerDescription$ At the beginning of your end step, draw a card.", this.monarchEffect, true);
         drawTrigger.setOverridingAbility(AbilityFactory.getAbility("DB$ Draw | Defined$ You", this.monarchEffect));
         this.monarchEffect.addTrigger(drawTrigger);
         drawTrig = "Mode$ DamageDone | ValidSource$ Creature | ValidTarget$ You | CombatDamage$ True | TriggerZones$ Command | TriggerDescription$ Whenever a creature deals combat damage to you, its controller becomes the monarch.";
         drawEff = "DB$ BecomeMonarch | Defined$ TriggeredSourceController";
         drawTrigger = TriggerHandler.parseTrigger("Mode$ DamageDone | ValidSource$ Creature | ValidTarget$ You | CombatDamage$ True | TriggerZones$ Command | TriggerDescription$ Whenever a creature deals combat damage to you, its controller becomes the monarch.", this.monarchEffect, true);
         drawTrigger.setOverridingAbility(AbilityFactory.getAbility("DB$ BecomeMonarch | Defined$ TriggeredSourceController", this.monarchEffect));
         this.monarchEffect.addTrigger(drawTrigger);
         this.monarchEffect.updateStateForView();
      }

      com.add(this.monarchEffect);
      this.updateZoneForView(com);
   }

   public void removeMonarchEffect() {
      PlayerZone com = this.getZone(ZoneType.Command);
      if (this.monarchEffect != null) {
         com.remove(this.monarchEffect);
         this.updateZoneForView(com);
      }

   }

   public boolean canBecomeMonarch() {
      return !StaticAbilityCantBecomeMonarch.anyCantBecomeMonarch(this);
   }

   public String getInitiativeSet() {
      return this.initiativeEffect != null ? this.initiativeEffect.getSetCode() : null;
   }

   public void createInitiativeEffect(String set) {
      PlayerZone com = this.getZone(ZoneType.Command);
      if (this.initiativeEffect == null) {
         this.initiativeEffect = new Card(this.game.nextCardId(), (IPaperCard)null, this.game);
         this.initiativeEffect.setOwner(this);
         this.initiativeEffect.setGamePieceType(GamePieceType.EFFECT);
         this.initiativeEffect.setImageKey(StaticData.instance().getOtherImageKey("initiative", set));
         this.initiativeEffect.setSetCode(set);
         this.initiativeEffect.setName("The Initiative");
         String damageTrig = "Mode$ DamageDoneOnceByController | ValidSource$ Player | ValidTarget$ You | CombatDamage$ True | TriggerZones$ Command | TriggerDescription$ Whenever one or more creatures a player controls deal combat damage to you, that player takes the initiative.";
         String damageEff = "DB$ TakeInitiative | Defined$ TriggeredSource";
         Trigger damageTrigger = TriggerHandler.parseTrigger("Mode$ DamageDoneOnceByController | ValidSource$ Player | ValidTarget$ You | CombatDamage$ True | TriggerZones$ Command | TriggerDescription$ Whenever one or more creatures a player controls deal combat damage to you, that player takes the initiative.", this.initiativeEffect, true);
         damageTrigger.setOverridingAbility(AbilityFactory.getAbility("DB$ TakeInitiative | Defined$ TriggeredSource", this.initiativeEffect));
         this.initiativeEffect.addTrigger(damageTrigger);
         String ventureTakeTrig = "Mode$ TakesInitiative | ValidPlayer$ You | TriggerZones$ Command | TriggerDescription$ Whenever you take the initiative and at the beginning of your upkeep, venture into Undercity. (If you're in a dungeon, advance to the next room. If not, enter Undercity. You can take the initiative even if you already have it.)";
         String ventureUpkpTrig = "Mode$ Phase | Phase$ Upkeep | TriggerZones$ Command | ValidPlayer$ You | TriggerDescription$ Whenever you take the initiative and at the beginning of your upkeep, venture into Undercity. (If you're in a dungeon, advance to the next room. If not, enter Undercity. You can take the initiative even if you already have it.) | Secondary$ True";
         String ventureEff = "DB$ Venture | Dungeon$ Undercity";
         Trigger ventureUTrigger = TriggerHandler.parseTrigger("Mode$ Phase | Phase$ Upkeep | TriggerZones$ Command | ValidPlayer$ You | TriggerDescription$ Whenever you take the initiative and at the beginning of your upkeep, venture into Undercity. (If you're in a dungeon, advance to the next room. If not, enter Undercity. You can take the initiative even if you already have it.) | Secondary$ True", this.initiativeEffect, true);
         ventureUTrigger.setOverridingAbility(AbilityFactory.getAbility("DB$ Venture | Dungeon$ Undercity", this.initiativeEffect));
         this.initiativeEffect.addTrigger(ventureUTrigger);
         Trigger ventureTTrigger = TriggerHandler.parseTrigger("Mode$ TakesInitiative | ValidPlayer$ You | TriggerZones$ Command | TriggerDescription$ Whenever you take the initiative and at the beginning of your upkeep, venture into Undercity. (If you're in a dungeon, advance to the next room. If not, enter Undercity. You can take the initiative even if you already have it.)", this.initiativeEffect, true);
         ventureTTrigger.setOverridingAbility(AbilityFactory.getAbility("DB$ Venture | Dungeon$ Undercity", this.initiativeEffect));
         this.initiativeEffect.addTrigger(ventureTTrigger);
         this.initiativeEffect.updateStateForView();
      }

      TriggerHandler triggerHandler = this.game.getTriggerHandler();
      com.add(this.initiativeEffect);
      triggerHandler.clearActiveTriggers(this.initiativeEffect, (Zone)null);
      triggerHandler.registerActiveTrigger(this.initiativeEffect, false);
      this.updateZoneForView(com);
   }

   public boolean hasInitiative() {
      return this.equals(this.game.getHasInitiative());
   }

   public void removeInitiativeEffect() {
      PlayerZone com = this.getZone(ZoneType.Command);
      if (this.initiativeEffect != null) {
         com.remove(this.initiativeEffect);
         this.updateZoneForView(com);
      }

   }

   public final Card getRadiationEffect() {
      return this.radiationEffect;
   }

   public void createRadiationEffect(String setCode) {
      PlayerZone com = this.getZone(ZoneType.Command);
      if (this.radiationEffect == null) {
         this.radiationEffect = new Card(this.game.nextCardId(), (IPaperCard)null, this.game);
         this.radiationEffect.setOwner(this);
         this.radiationEffect.setGamePieceType(GamePieceType.EFFECT);
         this.radiationEffect.setImageKey(StaticData.instance().getOtherImageKey("radiation", setCode));
         this.radiationEffect.setName("Radiation");
         if (setCode != null) {
            this.radiationEffect.setSetCode(setCode);
         }

         String trigStr = "Mode$ Phase | Phase$ Main1 | ValidPlayer$ You | TriggerZones$ Command | TriggerDescription$ At the beginning of your precombat main phase, if you have any rad counters, mill that many cards. For each nonland card milled this way, you lose 1 life and a rad counter.";
         Trigger tr = TriggerHandler.parseTrigger(trigStr, this.radiationEffect, true);
         SpellAbility sa = AbilityFactory.getAbility("DB$ InternalRadiation", this.radiationEffect);
         tr.setOverridingAbility(sa);
         this.radiationEffect.addTrigger(tr);
         this.radiationEffect.updateStateForView();
      }

      com.add(this.radiationEffect);
      this.updateZoneForView(com);
   }

   public void removeRadiationEffect() {
      PlayerZone com = this.getZone(ZoneType.Command);
      if (this.radiationEffect != null) {
         com.remove(this.radiationEffect);
         this.radiationEffect = null;
         this.updateZoneForView(com);
      }

   }

   public boolean hasRadiationEffect() {
      return this.radiationEffect != null;
   }

   public Card getKeywordCard() {
      if (this.keywordEffect != null) {
         return this.keywordEffect;
      } else {
         PlayerZone com = this.getZone(ZoneType.Command);
         this.keywordEffect = new Card(this.game.nextCardId(), (IPaperCard)null, this.game);
         this.keywordEffect.setGamePieceType(GamePieceType.EFFECT);
         this.keywordEffect.setOwner(this);
         this.keywordEffect.setName("Keyword Effects");
         this.keywordEffect.setImageKey("hidden");
         this.keywordEffect.updateStateForView();
         com.add(this.keywordEffect);
         this.updateZoneForView(com);
         return this.keywordEffect;
      }
   }

   public void updateKeywordCardAbilityText() {
      if (this.getKeywordCard() != null) {
         PlayerZone com = this.getZone(ZoneType.Command);
         this.keywordEffect.setText("");
         boolean headerAdded = false;
         StringBuilder kw = new StringBuilder();

         for(KeywordInterface k : this.keywords) {
            if (!headerAdded) {
               headerAdded = true;
               kw.append(this.getName()).append(" has: \n");
            }

            kw.append(k.getTitle()).append("\n");
         }

         if (!kw.toString().isEmpty()) {
            this.keywordEffect.setText(kw.toString());
         }

         this.keywordEffect.updateAbilityTextForView();
         this.updateZoneForView(com);
      }
   }

   public void checkKeywordCard() {
      if (this.keywordEffect != null) {
         PlayerZone com = this.getZone(ZoneType.Command);
         if (this.keywordEffect.getAbilityText().isEmpty()) {
            com.remove(this.keywordEffect);
            this.updateZoneForView(com);
            this.keywordEffect = null;
         }

      }
   }

   public boolean hasBlessing() {
      return this.blessingEffect != null;
   }

   public void setBlessing(boolean bless, String setCode) {
      if (this.blessingEffect != null != bless) {
         PlayerZone com = this.getZone(ZoneType.Command);
         if (bless) {
            this.blessingEffect = new Card(this.game.nextCardId(), (IPaperCard)null, this.game);
            this.blessingEffect.setOwner(this);
            this.blessingEffect.setImageKey(StaticData.instance().getOtherImageKey("blessing", setCode));
            this.blessingEffect.setName("City's Blessing");
            this.blessingEffect.setGamePieceType(GamePieceType.EFFECT);
            if (setCode != null) {
               this.blessingEffect.setSetCode(setCode);
            }

            this.blessingEffect.updateStateForView();
            com.add(this.blessingEffect);
            this.game.getAction().checkStaticAbilities();
         } else {
            com.remove(this.blessingEffect);
            this.blessingEffect = null;
         }

         this.updateZoneForView(com);
      }
   }

   public boolean hasEnduringStory() {
      return this.enduringStoryEffect != null;
   }

   public void setEnduringStory(boolean story, String setCode) {
      if (this.enduringStoryEffect != null != story) {
         PlayerZone com = this.getZone(ZoneType.Command);
         if (story) {
            this.enduringStoryEffect = new Card(this.game.nextCardId(), (IPaperCard)null, this.game);
            this.enduringStoryEffect.setOwner(this);
            this.enduringStoryEffect.setImageKey(StaticData.instance().getOtherImageKey("enduring_story", setCode));
            this.enduringStoryEffect.setName("An Enduring Story");
            this.enduringStoryEffect.setGamePieceType(GamePieceType.EFFECT);
            if (setCode != null) {
               this.enduringStoryEffect.setSetCode(setCode);
            }

            this.enduringStoryEffect.updateStateForView();
            com.add(this.enduringStoryEffect);
            this.game.getAction().checkStaticAbilities();
         } else {
            com.remove(this.enduringStoryEffect);
            this.enduringStoryEffect = null;
         }

         this.updateZoneForView(com);
      }
   }

   public final boolean sameTeam(Player other) {
      if (this.equals(other)) {
         return true;
      } else if (this.teamNumber >= 0 && other.getTeam() >= 0) {
         return this.teamNumber == other.getTeam();
      } else {
         return false;
      }
   }

   public final boolean isCursed() {
      return CardLists.count(this.getAttachedCards(), Card::isCurse) > 0;
   }

   public boolean canDiscardBy(SpellAbility sa, boolean effect) {
      if (sa == null) {
         return true;
      } else {
         return !StaticAbilityCantDiscard.cantDiscard(this, sa, effect);
      }
   }

   public boolean canSearchLibraryWith(SpellAbility sa, Player targetPlayer) {
      if (sa == null) {
         return true;
      } else if (this.hasKeyword("CantSearchLibrary")) {
         return false;
      } else {
         return targetPlayer == null || !targetPlayer.equals(sa.getActivatingPlayer()) || !this.hasKeyword("Spells and abilities you control can't cause you to search your library.");
      }
   }

   public void addAdditionalVote(long timestamp, int value) {
      this.additionalVotes.put(timestamp, value);
      this.getView().updateAdditionalVote(this);
      this.getGame().fireEvent(new GameEventPlayerStatsChanged(this));
   }

   public void removeAdditionalVote(long timestamp) {
      if (this.additionalVotes.remove(timestamp) != null) {
         this.getView().updateAdditionalVote(this);
         this.getGame().fireEvent(new GameEventPlayerStatsChanged(this));
      }

   }

   public int getAdditionalVotesAmount() {
      int value = 0;

      for(Integer i : this.additionalVotes.values()) {
         value += i;
      }

      return value;
   }

   public void addAdditionalOptionalVote(long timestamp, int value) {
      this.additionalOptionalVotes.put(timestamp, value);
      this.getView().updateOptionalAdditionalVote(this);
      this.getGame().fireEvent(new GameEventPlayerStatsChanged(this));
   }

   public void removeAdditionalOptionalVote(long timestamp) {
      if (this.additionalOptionalVotes.remove(timestamp) != null) {
         this.getView().updateOptionalAdditionalVote(this);
         this.getGame().fireEvent(new GameEventPlayerStatsChanged(this));
      }

   }

   public int getAdditionalOptionalVotesAmount() {
      int value = 0;

      for(Integer i : this.additionalOptionalVotes.values()) {
         value += i;
      }

      return value;
   }

   public boolean addControlVote(long timestamp) {
      if (this.controlVotes.add(timestamp)) {
         this.updateControlVote();
         return true;
      } else {
         return false;
      }
   }

   public boolean removeControlVote(long timestamp) {
      if (this.controlVotes.remove(timestamp)) {
         this.updateControlVote();
         return true;
      } else {
         return false;
      }
   }

   void updateControlVote() {
      Player control = this.getGame().getControlVote();

      for(Player pl : this.getGame().getPlayers()) {
         pl.getView().updateControlVote(pl.equals(control));
         this.getGame().fireEvent(new GameEventPlayerStatsChanged(pl));
      }

   }

   public Set<Long> getControlVote() {
      return this.controlVotes;
   }

   public void setControlVote(Set<Long> value) {
      this.controlVotes.clear();
      this.controlVotes.addAll(value);
      this.updateControlVote();
   }

   public Long getHighestControlVote() {
      return this.controlVotes.isEmpty() ? null : (Long)this.controlVotes.last();
   }

   public void addAdditionalVillainousChoices(long timestamp, int value) {
      this.additionalVillainousChoices.put(timestamp, value);
      this.getView().updateAdditionalVillainousChoices(this);
      this.getGame().fireEvent(new GameEventPlayerStatsChanged(this));
   }

   public void removeAdditionalVillainousChoices(long timestamp) {
      if (this.additionalVillainousChoices.remove(timestamp) != null) {
         this.getView().updateAdditionalVillainousChoices(this);
         this.getGame().fireEvent(new GameEventPlayerStatsChanged(this));
      }

   }

   public int getAdditionalVillainousChoices() {
      int value = 0;

      for(Integer i : this.additionalVillainousChoices.values()) {
         value += i;
      }

      return value;
   }

   public void addCycled(SpellAbility sp) {
      Map<AbilityKey, Object> cycleParams = AbilityKey.mapFromCard(CardCopyService.getLKICopy(this.game.getCardState(sp.getHostCard())));
      cycleParams.put(AbilityKey.Cause, sp);
      cycleParams.put(AbilityKey.Player, this);
      cycleParams.put(AbilityKey.FirstTime, CardUtil.getThisTurnActivated("Activated.Cycling+YouCtrl", sp.getHostCard(), sp, this).size() == 1);
      this.game.getTriggerHandler().runTrigger(TriggerType.Cycled, cycleParams, false);
   }

   public boolean hasUrzaLands() {
      CardCollectionView landsControlled = this.getCardsIn(ZoneType.Battlefield);
      return landsControlled.anyMatch(CardPredicates.isType("Urza's").and(CardPredicates.isType("Mine"))) && landsControlled.anyMatch(CardPredicates.isType("Urza's").and(CardPredicates.isType("Power-Plant"))) && landsControlled.anyMatch(CardPredicates.isType("Urza's").and(CardPredicates.isType("Tower")));
   }

   public void revealFaceDownCards() {
      List<List<ZoneType>> revealZones = Arrays.asList(Arrays.asList(ZoneType.Battlefield, ZoneType.Merged), Arrays.asList(ZoneType.Exile));
      PlayerCollection otherPlayers = new PlayerCollection(this.game.getRegisteredPlayers());
      otherPlayers.remove(this);

      for(List<ZoneType> z : revealZones) {
         CardCollection revealCards = new CardCollection();

         for(Card c : this.game.getCardsInOwnedBy(z, this)) {
            if (c.isRealFaceDown()) {
               Card lki = CardCopyService.getLKICopy(c);
               lki.forceTurnFaceUp();
               lki.setZone(c.getZone());
               revealCards.add(lki);
            }
         }

         this.game.getAction().revealTo(revealCards, otherPlayers, Localizer.getInstance().getMessage("lblRevealFaceDownCards", new Object[0]), true);
      }

   }

   public void learnLesson(SpellAbility sa, Map<AbilityKey, Object> params) {
      if (!this.hasLost()) {
         Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
         repParams.put(AbilityKey.Cause, sa);
         repParams.putAll(params);
         if (this.game.getReplacementHandler().run(ReplacementType.Learn, repParams) == ReplacementResult.NotReplaced) {
            CardCollection list = new CardCollection();
            if (!this.isControlled()) {
               list.addAll(CardLists.getType(this.getZone(ZoneType.Sideboard), "Lesson"));
            }

            list.addAll(this.getZone(ZoneType.Hand));
            if (!list.isEmpty()) {
               Card c = this.getController().chooseSingleCardForZoneChange(ZoneType.Hand, List.of(ZoneType.Sideboard, ZoneType.Hand), sa, list, (DelayedReveal)null, Localizer.getInstance().getMessage("lblLearnALesson", new Object[0]), true, this);
               if (c != null) {
                  if (c.isInZone(ZoneType.Sideboard)) {
                     this.game.getAction().reveal(new CardCollection(c), c.getOwner(), true);
                     this.game.getAction().moveTo(ZoneType.Hand, c, sa, params);
                  } else if (c.isInZone(ZoneType.Hand)) {
                     List<Card> discardedBefore = Lists.newArrayList(this.getDiscardedThisTurn());
                     Card moved = this.discard(c, sa, true, params);
                     if (moved != null) {
                        Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
                        runParams.put(AbilityKey.Cards, new CardCollection(moved));
                        runParams.put(AbilityKey.Cause, sa);
                        runParams.put(AbilityKey.DiscardedBefore, discardedBefore);
                        if (params != null) {
                           runParams.putAll(params);
                        }

                        this.getGame().getTriggerHandler().runTrigger(TriggerType.DiscardedAll, runParams, false);
                        this.drawCards(1, sa, params);
                     }
                  }

               }
            }
         }
      }
   }

   public void commitCrime() {
      ++this.committedCrimeThisTurn;
      Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
      this.game.getTriggerHandler().runTrigger(TriggerType.CommitCrime, runParams, false);
   }

   public int getCommittedCrimeThisTurn() {
      return this.committedCrimeThisTurn;
   }

   public void setCommitedCrimeThisTurn(int v) {
      this.committedCrimeThisTurn = v;
   }

   public List<Integer> getDiceRollsThisTurn() {
      return this.diceRollsThisTurn;
   }

   public void addDieRollThisTurn(List<Integer> rolls) {
      this.diceRollsThisTurn.addAll(rolls);
   }

   public int getExpentThisTurn() {
      return this.expentThisTurn;
   }

   public void setExpentThisTurn(int v) {
      this.expentThisTurn = v;
   }

   public void addExpentThisTurn(int v, SpellAbility sp) {
      if (v > 0) {
         int startingMana = this.expentThisTurn;
         int totalMana = this.expentThisTurn += v;

         for(int i = startingMana + 1; i <= totalMana; ++i) {
            Map<AbilityKey, Object> expendParams = AbilityKey.mapFromPlayer(this);
            expendParams.put(AbilityKey.SpellAbility, sp);
            expendParams.put(AbilityKey.Amount, i);
            this.game.getTriggerHandler().runTrigger(TriggerType.ManaExpend, expendParams, true);
         }

      }
   }

   public void visitAttractions(int light) {
      for(Card c : CardLists.filter(this.getCardsIn(ZoneType.Battlefield), CardPredicates.isAttractionWithLight(light))) {
         if (!c.wasVisitedThisTurn()) {
            ++this.attractionsVisitedThisTurn;
         }

         c.visitAttraction(this);
      }

   }

   public void rollToVisitAttractions() {
      this.visitAttractions(RollDiceEffect.rollDiceForPlayerToVisitAttractions(this));
   }

   public int getAttractionsVisitedThisTurn() {
      return this.attractionsVisitedThisTurn;
   }

   public int getCrankCounter() {
      return this.crankCounter;
   }

   public void setCrankCounter(int counters) {
      this.crankCounter = counters;
      if (this.contraptionSprocketEffect != null) {
         String label = Localizer.getInstance().getMessage("lblCrank", new Object[]{this.crankCounter});
         this.contraptionSprocketEffect.setOverlayText(label);
      } else if (this.getCardsIn(ZoneType.Battlefield).anyMatch(Card::isContraption)) {
         this.createContraptionSprockets();
      }

   }

   public void advanceCrankCounter() {
      this.setCrankCounter(this.crankCounter % 3 + 1);
      CardCollection contraptions = CardLists.filter(this.getCardsIn(ZoneType.Battlefield), CardPredicates.isContraptionOnSprocket(this.crankCounter));

      for(Card c : this.getController().chooseContraptionsToCrank(contraptions)) {
         Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(c);
         runParams.put(AbilityKey.Player, this);
         this.game.getTriggerHandler().runTrigger(TriggerType.CrankContraption, runParams, false);
      }

   }

   public void createContraptionSprockets() {
      if (this.contraptionSprocketEffect == null) {
         this.contraptionSprocketEffect = new Card(this.game.nextCardId(), (IPaperCard)null, this.game);
         this.contraptionSprocketEffect.setOwner(this);
         this.contraptionSprocketEffect.setImageKey("t:sprockets");
         this.contraptionSprocketEffect.setName("Contraption Sprockets");
         this.contraptionSprocketEffect.setGamePieceType(GamePieceType.EFFECT);
         String label = Localizer.getInstance().getMessage("lblCrank", new Object[]{this.crankCounter});
         this.contraptionSprocketEffect.setOverlayText(label);
         this.contraptionSprocketEffect.setText("At the beginning of your upkeep, if you control a Contraption, move the CRANK! counter to the next sprocket and crank any number of that sprocket's Contraptions.");
         this.contraptionSprocketEffect.updateStateForView();
         PlayerZone com = this.getZone(ZoneType.Command);
         com.add(this.contraptionSprocketEffect);
         this.updateZoneForView(com);
      }
   }

   public void addDeclaresAttackers(long ts, Player p) {
      this.declaresAttackers.put(ts, p);
   }

   public void removeDeclaresAttackers(long ts) {
      this.declaresAttackers.remove(ts);
   }

   public Player getDeclaresAttackers() {
      Map.Entry<Long, Player> e = this.declaresAttackers.lastEntry();
      return e == null ? null : (Player)e.getValue();
   }

   public void addDeclaresBlockers(long ts, Player p) {
      this.declaresBlockers.put(ts, p);
   }

   public void removeDeclaresBlockers(long ts) {
      this.declaresBlockers.remove(ts);
   }

   public Player getDeclaresBlockers() {
      Map.Entry<Long, Player> e = this.declaresBlockers.lastEntry();
      return e == null ? null : (Player)e.getValue();
   }

   public List<String> getUnlockedDoors() {
      return (List)this.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isRoom).map(Card::getUnlockedRoomNames).flatMap(Collection::stream).collect(Collectors.toList());
   }

   public int getDevotionMod() {
      return this.devotionMod;
   }

   public void afterStaticAbilityLayer(StaticAbilityLayer layer) {
      if (layer == StaticAbilityLayer.TEXT) {
         this.devotionMod = StaticAbilityDevotion.getDevotionMod(this);
      }
   }

   public void triggerElementalBend(TriggerType type) {
      this.elementalBendThisTurn.add(type);
      Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
      this.getGame().getTriggerHandler().runTrigger(TriggerType.ElementalBend, runParams, false);
      this.getGame().getTriggerHandler().runTrigger(type, runParams, false);
   }

   public boolean hasAllElementBend() {
      return this.elementalBendThisTurn.size() >= 4;
   }

   static {
      ALL_ZONES = Collections.unmodifiableList(Arrays.asList(ZoneType.Battlefield, ZoneType.Library, ZoneType.Graveyard, ZoneType.Hand, ZoneType.Exile, ZoneType.Command, ZoneType.Ante, ZoneType.Sideboard, ZoneType.PlanarDeck, ZoneType.SchemeDeck, ZoneType.AttractionDeck, ZoneType.ContraptionDeck, ZoneType.Junkyard, ZoneType.Merged, ZoneType.Subgame, ZoneType.None));
   }
}
