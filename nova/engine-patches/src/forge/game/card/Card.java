package forge.game.card;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.TreeBasedTable;
import forge.GameCommand;
import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardChangedType;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardSplitType;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.CardTypeView;
import forge.card.ColorSet;
import forge.card.GamePieceType;
import forge.card.ICardChangedType;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.RemoveType;
import forge.card.StateChangedType;
import forge.card.WordChangedType;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.deck.DeckRule;
import forge.game.CardTraitBase;
import forge.game.Direction;
import forge.game.EvenOdd;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameEntity;
import forge.game.GameEntityCounterTable;
import forge.game.GameStage;
import forge.game.IHasSVars;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.perpetual.PerpetualInterface;
import forge.game.combat.Combat;
import forge.game.combat.CombatLki;
import forge.game.cost.Cost;
import forge.game.event.GameEventCardAttachment;
import forge.game.event.GameEventCardCounters;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventCardPhased;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.event.GameEventCardTapped;
import forge.game.event.GameEventDoorChanged;
import forge.game.event.GameEventSprocketUpdate;
import forge.game.keyword.Companion;
import forge.game.keyword.Equip;
import forge.game.keyword.IKeywordsChange;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordCollection;
import forge.game.keyword.KeywordInterface;
import forge.game.keyword.KeywordWithType;
import forge.game.keyword.KeywordsChange;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.replacement.ReplaceMoved;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.replacement.ReplacementResult;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityPredicates;
import forge.game.spellability.SpellPermanent;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityActivateAbilityAsIfHaste;
import forge.game.staticability.StaticAbilityAssignNoCombatDamage;
import forge.game.staticability.StaticAbilityCantAttackBlock;
import forge.game.staticability.StaticAbilityCantBeSuspected;
import forge.game.staticability.StaticAbilityCantCrew;
import forge.game.staticability.StaticAbilityCantExile;
import forge.game.staticability.StaticAbilityCantGainControl;
import forge.game.staticability.StaticAbilityCantPhase;
import forge.game.staticability.StaticAbilityCantPreventDamage;
import forge.game.staticability.StaticAbilityCantPutCounter;
import forge.game.staticability.StaticAbilityCantRegenerate;
import forge.game.staticability.StaticAbilityCantSacrifice;
import forge.game.staticability.StaticAbilityCantTarget;
import forge.game.staticability.StaticAbilityCantTransform;
import forge.game.staticability.StaticAbilityCombatDamageToughness;
import forge.game.staticability.StaticAbilityIgnoreLegendRule;
import forge.game.staticability.StaticAbilityIgnoreZeroLoyalty;
import forge.game.staticability.StaticAbilityInfectDamage;
import forge.game.staticability.StaticAbilityMaxCounter;
import forge.game.staticability.StaticAbilityMode;
import forge.game.staticability.StaticAbilityNumLoyaltyAct;
import forge.game.staticability.StaticAbilityWitherDamage;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.trackable.TrackableProperty;
import forge.trackable.Tracker;
import forge.util.CardTranslation;
import forge.util.ITranslatable;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.TextUtil;
import forge.util.Visitor;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.tinylog.Logger;

public class Card extends GameEntity implements Comparable<Card>, IHasSVars, ITranslatable {
   private Game game;
   private final IPaperCard paperCard;
   private final Map<CardStateName, CardState> states;
   private CardState currentState;
   private CardStateName currentStateName;
   private GamePieceType gamePieceType;
   private Zone castFrom;
   private SpellAbility castSA;
   private final Table<Long, Long, List<String>> hiddenExtrinsicKeywords;
   private CardCollection hauntedBy;
   private CardCollection devouredCards;
   private CardCollection exploitedCards;
   private CardCollection delvedCards;
   private CardCollection imprintedCards;
   private CardCollection exiledCards;
   private CardCollection encodedCards;
   private CardCollection gainControlTargets;
   private CardCollection chosenCards;
   private CardCollection mergedCards;
   private Map<Long, CardCollection> mustBlockCards;
   private List<Card> blockedThisTurn;
   private List<Card> blockedByThisTurn;
   private CardCollection untilLeavesBattlefield;
   private Card encoding;
   private Card cloneOrigin;
   private Card haunting;
   private Card effectSource;
   private Card pairedWith;
   private Card meldedWith;
   private Card mergedTo;
   private SpellAbility effectSourceAbility;
   private SpellAbility tokenSpawningAbility;
   private GameEntity entityAttachedTo;
   private final Table<Long, Long, ICardChangedType> changedCardTypesByText;
   private final Table<Long, Long, ICardChangedType> changedCardTypesCharacterDefining;
   private final Table<Long, Long, ICardChangedType> changedCardTypes;
   private final Table<Long, Long, CardChangedName> changedCardNames;
   private final Table<Long, Long, IKeywordsChange> changedCardKeywordsByText;
   protected KeywordsChange changedCardKeywordsByWord;
   private final Table<Long, Long, KeywordsChange> changedCardKeywords;
   private final Map<Triple<String, Long, Long>, KeywordInterface> storedKeywords;
   private final Table<Long, Long, CardTraitChanges> changedCardTraitsByText;
   private final Table<Long, Long, ICardTraitChanges> changedCardTraits;
   private final Table<StaticAbility, String, SpellAbility> storedSpellAbility;
   private final Table<StaticAbility, String, Trigger> storedTrigger;
   private final Table<StaticAbility, SpellAbility, SpellAbility> storedAbilityForTrigger;
   private final Table<StaticAbility, String, ReplacementEffect> storedReplacementEffect;
   private final Table<StaticAbility, String, StaticAbility> storedStaticAbility;
   private final Table<StaticAbility, SpellAbility, SpellAbility> storedSpellAbililityByText;
   private final Table<StaticAbility, String, SpellAbility> storedSpellAbililityGainedByText;
   private final Table<StaticAbility, Trigger, Trigger> storedTriggerByText;
   private final Table<StaticAbility, ReplacementEffect, ReplacementEffect> storedReplacementEffectByText;
   private final Table<StaticAbility, StaticAbility, StaticAbility> storedStaticAbilityByText;
   private final Map<Triple<String, Long, Long>, KeywordInterface> storedKeywordByText;
   private final Table<Long, Long, CardColor> changedCardColorsByText;
   private final Table<Long, Long, CardColor> changedCardColorsCharacterDefining;
   private final Table<Long, Long, CardColor> changedCardColors;
   protected final Table<Long, Long, CardManaCost> changedCardManaCost;
   private final NavigableMap<Long, CardCloneStates> clonedStates;
   private final Table<Long, Long, Map<String, String>> changedSVars;
   private Map<StaticAbility, CardPlayOption> mayPlay;
   private final Map<Long, PlayerCollection> mayLook;
   private final PlayerCollection mayLookFaceDownExile;
   private final PlayerCollection mayLookTemp;
   private final Multimap<Long, Keyword> cantHaveKeywords;
   private final Map<CounterType, StaticAbility> counterTypeKeywordStatic;
   private final Map<Long, Integer> canBlockAdditional;
   private final Set<Long> canBlockAny;
   private final Set<Long> lethalDamageByPower;
   private final CardChangedWords changedTextColors;
   private final CardChangedWords changedTextTypes;
   private final Set<Object> rememberedObjects;
   private final List<String> draftActions;
   private Map<Player, String> flipResult;
   private List<Integer> storedRolls;
   private boolean isCommander;
   private boolean canMoveToCommandZone;
   private boolean startsGameInPlay;
   private boolean drawnThisTurn;
   private boolean foughtThisTurn;
   private boolean enlistedThisCombat;
   private boolean startedTheTurnUntapped;
   private boolean cameUnderControlSinceLastUpkeep;
   private boolean tapped;
   private int tappedThisTurn;
   private boolean sickness;
   private boolean collectible;
   private boolean tokenCard;
   private Card copiedPermanent;
   private boolean unearthed;
   private boolean ringbearer;
   private boolean monstrous;
   private boolean harnessed;
   private boolean renowned;
   private boolean solved;
   private boolean tributed;
   private Card preparedEffect;
   private StaticAbility suspectedStatic;
   private SpellAbility manifestedSA;
   private SpellAbility cloakedSA;
   private boolean foretold;
   private boolean foretoldCostByEffect;
   private boolean plotted;
   private Set<CardStateName> unlockedRooms;
   private Map<CardStateName, SpellAbility> unlockAbilities;
   private boolean specialized;
   private int timesCrewedThisTurn;
   private CardCollection crewedByThisTurn;
   private boolean saddled;
   private int timesSaddledThisTurn;
   private CardCollection saddledByThisTurn;
   private boolean visitedThisTurn;
   private int classLevel;
   private boolean discarded;
   private boolean surveilled;
   private boolean milled;
   private boolean flipped;
   private boolean facedown;
   private boolean turnedFaceUpThisTurn;
   private boolean backside;
   private Player phasedOut;
   private boolean directlyPhasedOut;
   private boolean wontPhaseInNormal;
   private boolean usedToPayCost;
   private boolean isEmblem;
   private boolean isBoon;
   private int exertThisTurn;
   private PlayerCollection exertedByPlayer;
   private PlayerCollection detainedByPlayer;
   private PlayerCollection targetedFromThisTurn;
   private long worldTimestamp;
   private long bestowTimestamp;
   private long transformedTimestamp;
   private long prototypeTimestamp;
   private long mutatedTimestamp;
   private int timesMutated;
   private long gameTimestamp;
   private long layerTimestamp;
   private Table<Long, Long, Pair<Integer, Integer>> newPTText;
   private Table<Long, Long, Pair<Integer, Integer>> newPTCharacterDefining;
   private Table<Long, Long, Pair<Integer, Integer>> newPT;
   private Table<Long, Long, Pair<Integer, Integer>> boostPT;
   private CardDamageHistory damageHistory;
   private final Map<Card, Integer> assignedDamageMap;
   private Map<Integer, Integer> damage;
   private boolean hasBeenDealtDeathtouchDamage;
   private boolean hasBeenDealtExcessDamageThisTurn;
   private int excessDamageThisTurnAmount;
   private int shieldCount;
   private int regeneratedThisTurn;
   private int turnInZone;
   private Player turnInController;
   private Map<String, Integer> xManaCostPaidByColor;
   private Player owner;
   private Player controller;
   private long controllerTimestamp;
   private NavigableMap<Long, Player> tempControllers;
   private String originalText;
   private String text;
   private String chosenType;
   private String chosenType2;
   private List<String> notedTypes;
   private List<String> chosenColors;
   private ColorSet markedColor;
   private List<String> chosenName;
   private Integer chosenNumber;
   private Player chosenPlayer;
   private Player promisedGift;
   private Player protectingPlayer;
   private EvenOdd chosenEvenOdd;
   private Direction chosenDirection;
   private String chosenMode;
   private String currentRoom;
   private String sector;
   private String chosenSector;
   private int sprocket;
   private Map<Player, CardCollection> chosenMap;
   private Card exiledWith;
   private Player exiledBy;
   private SpellAbility exiledSA;
   private Map<Long, Player> goad;
   private List<GameCommand> leavePlayCommandList;
   private final List<GameCommand> untapCommandList;
   private final List<GameCommand> changeControllerCommandList;
   private final List<GameCommand> unattachCommandList;
   private final List<GameCommand> faceupCommandList;
   private final List<GameCommand> facedownCommandList;
   private final List<GameCommand> phaseOutCommandList;
   private final List<Object[]> staticCommandList;
   private Zone currentZone;
   private Zone savedLastKnownZone;
   private int lkiCMC;
   private CombatLki combatLKI;
   protected boolean renderForUi;
   private final CardView view;
   private String overlayText;
   private int planeswalkerAbilityActivated;
   private boolean planeswalkerActivationLimitUsed;
   private final ActivationTable numberTurnActivations;
   private final ActivationTable numberGameActivations;
   private final ActivationTable numberAbilityResolved;
   private final Map<SpellAbility, List<String>> chosenModesTurn;
   private final Map<SpellAbility, List<String>> chosenModesGame;
   private final Map<SpellAbility, List<String>> chosenModesYourCombat;
   private final Map<SpellAbility, List<String>> chosenModesYourLastCombat;
   private final Table<SpellAbility, StaticAbility, List<String>> chosenModesTurnStatic;
   private final Table<SpellAbility, StaticAbility, List<String>> chosenModesGameStatic;
   private final Table<SpellAbility, StaticAbility, List<String>> chosenModesYourCombatStatic;
   private final Table<SpellAbility, StaticAbility, List<String>> chosenModesYourLastCombatStatic;
   private ReplacementEffect shieldCounterReplaceDamage;
   private ReplacementEffect shieldCounterReplaceDestroy;
   private ReplacementEffect stunCounterReplaceUntap;
   private ReplacementEffect finalityCounterReplaceDying;
   private int intensity;
   private List<PerpetualInterface> perpetual;
   private static final Map<PaperCard, Card> cp2card = Maps.newHashMap();

   // ---- Forge Nova: ability cache versioning (see TraitEpoch)
   private volatile long traitEpoch = 1L;
   private static final java.util.concurrent.atomic.AtomicLongFieldUpdater<Card> TRAIT_EPOCH =
         java.util.concurrent.atomic.AtomicLongFieldUpdater.newUpdater(Card.class, "traitEpoch");

   public final long getTraitEpoch() {
      return this.traitEpoch;
   }

   public final void bumpTraitEpoch() {
      TRAIT_EPOCH.incrementAndGet(this);
      // Forge Nova: the global epoch keys indexes built from zone contents (TraitEpoch.global() users). A card
      // without a zone (an LKI copy, a card being built) is in no zone list: Zone.add/setCards give a card its
      // zone before listing it (and bump), and nothing clears a card's zone. So only zoned cards need to bump it;
      // this keeps the indexes valid while Forge makes last-known-information copies of whole battlefields.
      if (this.currentZone != null || TraitEpoch.DISABLED) {
         TraitEpoch.bumpGlobal();
      }
      final Zone novaZone = this.currentZone;
      if (novaZone != null) {
         novaZone.novaBumpVersion(); // Forge Nova: NovaStaticVisit's per-zone summaries
      }
   }

   public Card(int id0, Game game0) {
      this(id0, (IPaperCard)null, game0);
   }

   public Card(int id0, IPaperCard paperCard0, Game game0) {
      this(id0, paperCard0, game0, game0 == null ? null : game0.getTracker());
   }

   public Card(int id0, IPaperCard paperCard0, Game game0, Tracker tracker0) {
      this(id0, paperCard0, game0, tracker0, false);
   }

   public Card(int id0, IPaperCard paperCard0, Game game0, Tracker tracker0, boolean textFromSource) {
      super(id0);
      this.states = Maps.newEnumMap(CardStateName.class);
      this.currentStateName = CardStateName.Original;
      this.bumpTraitEpoch();
      this.gamePieceType = GamePieceType.CARD;
      this.hiddenExtrinsicKeywords = TreeBasedTable.create();
      this.mustBlockCards = Maps.newHashMap();
      this.blockedThisTurn = Lists.newArrayList();
      this.blockedByThisTurn = Lists.newArrayList();
      this.untilLeavesBattlefield = new CardCollection();
      this.changedCardTypesByText = TreeBasedTable.create();
      this.changedCardTypesCharacterDefining = TreeBasedTable.create();
      this.changedCardTypes = TreeBasedTable.create();
      this.changedCardNames = TreeBasedTable.create();
      this.changedCardKeywordsByText = TreeBasedTable.create();
      this.changedCardKeywordsByWord = new KeywordsChange(ImmutableList.<KeywordInterface>of(), ImmutableList.<KeywordInterface>of(), false);
      this.bumpTraitEpoch();
      this.changedCardKeywords = TreeBasedTable.create();
      this.storedKeywords = Maps.newHashMap();
      this.changedCardTraitsByText = TreeBasedTable.create();
      this.changedCardTraits = TreeBasedTable.create();
      this.storedSpellAbility = TreeBasedTable.create();
      this.storedTrigger = TreeBasedTable.create();
      this.storedAbilityForTrigger = HashBasedTable.create();
      this.storedReplacementEffect = TreeBasedTable.create();
      this.storedStaticAbility = TreeBasedTable.create();
      this.storedSpellAbililityByText = HashBasedTable.create();
      this.storedSpellAbililityGainedByText = TreeBasedTable.create();
      this.storedTriggerByText = HashBasedTable.create();
      this.storedReplacementEffectByText = HashBasedTable.create();
      this.storedStaticAbilityByText = HashBasedTable.create();
      this.storedKeywordByText = Maps.newHashMap();
      this.changedCardColorsByText = TreeBasedTable.create();
      this.changedCardColorsCharacterDefining = TreeBasedTable.create();
      this.changedCardColors = TreeBasedTable.create();
      this.changedCardManaCost = TreeBasedTable.create();
      this.clonedStates = Maps.newTreeMap();
      this.changedSVars = TreeBasedTable.create();
      this.mayPlay = Maps.newHashMap();
      this.mayLook = Maps.newHashMap();
      this.mayLookFaceDownExile = new PlayerCollection();
      this.mayLookTemp = new PlayerCollection();
      this.cantHaveKeywords = MultimapBuilder.hashKeys().hashSetValues().build();
      this.counterTypeKeywordStatic = Maps.newHashMap();
      this.canBlockAdditional = Maps.newTreeMap();
      this.canBlockAny = Sets.newHashSet();
      this.lethalDamageByPower = Sets.newHashSet();
      this.changedTextColors = new CardChangedWords();
      this.changedTextTypes = new CardChangedWords();
      this.rememberedObjects = Sets.newLinkedHashSet();
      this.draftActions = Lists.newArrayList();
      this.isCommander = false;
      this.canMoveToCommandZone = false;
      this.startsGameInPlay = false;
      this.drawnThisTurn = false;
      this.foughtThisTurn = false;
      this.enlistedThisCombat = false;
      this.startedTheTurnUntapped = false;
      this.cameUnderControlSinceLastUpkeep = true;
      this.tapped = false;
      this.sickness = true;
      this.collectible = false;
      this.tokenCard = false;
      this.suspectedStatic = null;
      this.unlockedRooms = EnumSet.noneOf(CardStateName.class);
      this.unlockAbilities = Maps.newEnumMap(CardStateName.class);
      this.timesCrewedThisTurn = 0;
      this.saddled = false;
      this.timesSaddledThisTurn = 0;
      this.visitedThisTurn = false;
      this.classLevel = 1;
      this.flipped = false;
      this.facedown = false;
      this.turnedFaceUpThisTurn = false;
      this.backside = false;
      this.directlyPhasedOut = true;
      this.wontPhaseInNormal = false;
      this.usedToPayCost = false;
      this.isEmblem = false;
      this.isBoon = false;
      this.exertThisTurn = 0;
      this.exertedByPlayer = new PlayerCollection();
      this.detainedByPlayer = new PlayerCollection();
      this.targetedFromThisTurn = new PlayerCollection();
      this.worldTimestamp = -1L;
      this.bestowTimestamp = -1L;
      this.transformedTimestamp = -1L;
      this.prototypeTimestamp = -1L;
      this.mutatedTimestamp = -1L;
      this.timesMutated = 0;
      this.gameTimestamp = -1L;
      this.layerTimestamp = -1L;
      this.newPTText = TreeBasedTable.create();
      this.newPTCharacterDefining = TreeBasedTable.create();
      this.newPT = TreeBasedTable.create();
      this.boostPT = TreeBasedTable.create();
      this.damageHistory = new CardDamageHistory();
      this.assignedDamageMap = Maps.newTreeMap();
      this.damage = Maps.newHashMap();
      this.excessDamageThisTurnAmount = 0;
      this.shieldCount = 0;
      this.tempControllers = Maps.newTreeMap();
      this.originalText = "";
      this.text = "";
      this.chosenType = "";
      this.chosenType2 = "";
      this.notedTypes = new ArrayList();
      this.chosenName = new ArrayList();
      this.chosenEvenOdd = null;
      this.chosenDirection = null;
      this.chosenMode = "";
      this.currentRoom = null;
      this.sector = null;
      this.chosenSector = null;
      this.sprocket = 0;
      this.chosenMap = Maps.newHashMap();
      this.goad = Maps.newTreeMap();
      this.leavePlayCommandList = Lists.newArrayList();
      this.untapCommandList = Lists.newArrayList();
      this.changeControllerCommandList = Lists.newArrayList();
      this.unattachCommandList = Lists.newArrayList();
      this.faceupCommandList = Lists.newArrayList();
      this.facedownCommandList = Lists.newArrayList();
      this.phaseOutCommandList = Lists.newArrayList();
      this.staticCommandList = Lists.newArrayList();
      this.lkiCMC = -1;
      this.renderForUi = true;
      this.overlayText = null;
      this.numberTurnActivations = new ActivationTable();
      this.numberGameActivations = new ActivationTable();
      this.numberAbilityResolved = new ActivationTable();
      this.chosenModesTurn = Maps.newHashMap();
      this.chosenModesGame = Maps.newHashMap();
      this.chosenModesYourCombat = Maps.newHashMap();
      this.chosenModesYourLastCombat = Maps.newHashMap();
      this.chosenModesTurnStatic = HashBasedTable.create();
      this.chosenModesGameStatic = HashBasedTable.create();
      this.chosenModesYourCombatStatic = HashBasedTable.create();
      this.chosenModesYourLastCombatStatic = HashBasedTable.create();
      this.shieldCounterReplaceDamage = null;
      this.shieldCounterReplaceDestroy = null;
      this.stunCounterReplaceUntap = null;
      this.finalityCounterReplaceDying = null;
      this.intensity = 0;
      this.perpetual = new ArrayList();
      this.game = game0;
      this.paperCard = paperCard0;
      this.view = (CardView)(!textFromSource && (game0 == null || !game0.isNoGUIUser()) ? new CardView(id0, tracker0) : new DummyCardView(id0, tracker0));
      this.currentState = new CardState(this.view.getCurrentState(), this);
      this.bumpTraitEpoch();
      this.states.put(CardStateName.Original, this.currentState);
      this.view.updateChangedColorWords(this);
      this.view.updateChangedTypes(this);
      this.view.updateSickness(this);
      this.view.updateClassLevel(this);
      this.view.updateDraftAction(this);
      if (this.paperCard != null) {
         this.setMarkedColors(this.paperCard.getMarkedColors());
         this.setPaperFoil(this.paperCard.isFoil());
      }

   }

   public int getHiddenId() {
      return this.view.getHiddenId();
   }

   public long getPrototypeTimestamp() {
      return this.prototypeTimestamp;
   }

   public long getTransformedTimestamp() {
      return this.transformedTimestamp;
   }

   public void setTransformedTimestamp(long ts) {
      this.transformedTimestamp = ts;
   }

   public void updateAbilityTextForView() {
      this.view.getCurrentState().updateAbilityText(this, this.getCurrentState());
   }

   public void updateNonAbilityTextForView() {
      this.view.updateNonAbilityText(this);
   }

   public void updateManaCostForView() {
      this.currentState.getView().updateManaCost(this);
      if (this.getFirstSpellAbility() != null && this.getFirstSpellAbility().isSpell()) {
         this.getFirstSpellAbility().setPayCosts(this.getFirstSpellAbility().getPayCosts().copyWithDefinedMana(this.getManaCost()));
      }

   }

   public void updatePTforView() {
      this.getView().updateLethalDamage(this);
      this.currentState.getView().updatePower(this);
      this.currentState.getView().updateToughness(this);
   }

   public final void updateTypesForView() {
      this.currentState.updateTypesForView();
   }

   public final void updateColorForView() {
      this.currentState.getView().updateColors(this);
      this.currentState.getView().updateHasChangeColors(this.hasChangedCardColors());
   }

   public void updateAttackingForView() {
      this.view.updateAttacking(this);
      this.getGame().updateCombatForView();
   }

   public void updateBlockingForView() {
      this.view.updateBlocking(this);
      this.getGame().updateCombatForView();
   }

   public void updateStateForView() {
      this.view.updateState(this);
   }

   public CardState getCurrentState() {
      return this.currentState;
   }

   public CardStateName getAlternateStateName() {
      if (this.hasAlternateState()) {
         if (this.isSplitCard()) {
            return this.currentStateName == CardStateName.RightSplit ? CardStateName.LeftSplit : CardStateName.RightSplit;
         } else {
            if (this.getRules() != null) {
               CardStateName changedState = this.getRules().getSplitType().getChangedStateName();
               if (this.currentStateName != changedState) {
                  return changedState;
               }
            }

            return CardStateName.Original;
         }
      } else {
         return this.isFaceDown() ? CardStateName.Original : null;
      }
   }

   public CardState getAlternateState() {
      return !this.hasAlternateState() && !this.isFaceDown() ? null : (CardState)this.states.get(this.getAlternateStateName());
   }

   public CardState getState(CardStateName state) {
      if (state == CardStateName.FaceDown) {
         return this.getFaceDownState();
      } else {
         CardCloneStates clStates = this.getLastClonedState();
         return clStates == null ? this.getOriginalState(state) : clStates.get(state);
      }
   }

   public boolean hasState(CardStateName state) {
      if (state != CardStateName.FaceDown && state != CardStateName.EmptyRoom) {
         CardCloneStates clStates = this.getLastClonedState();
         return clStates == null ? this.states.containsKey(state) : clStates.containsKey(state);
      } else {
         return true;
      }
   }

   public CardState getOriginalState(CardStateName state) {
      if (state == CardStateName.FaceDown) {
         return this.getFaceDownState();
      } else {
         return state == CardStateName.EmptyRoom ? this.getEmptyRoomState() : (CardState)this.states.get(state);
      }
   }

   public CardState getFaceDownState() {
      if (!this.states.containsKey(CardStateName.FaceDown)) {
         this.states.put(CardStateName.FaceDown, CardUtil.getFaceDownCharacteristic(this));
         this.bumpTraitEpoch(); // Forge Nova: the set of states feeds getAllSpellAbilities()
      }

      return (CardState)this.states.get(CardStateName.FaceDown);
   }

   public void setOriginalStateAsFaceDown() {
      this.currentState = CardUtil.getFaceDownCharacteristic(this, CardStateName.Original);
      this.bumpTraitEpoch();
      this.states.put(CardStateName.Original, this.currentState);
      this.bumpTraitEpoch(); // Forge Nova: after the change too (the set of states feeds getAllSpellAbilities())
   }

   public boolean changeToState(CardStateName state) {
      return this.hasState(state) ? this.setState(state, true) : false;
   }

   public boolean setState(CardStateName state, boolean updateView) {
      return this.setState(state, updateView, false);
   }

   public boolean setState(CardStateName state, boolean updateView, boolean forceUpdate) {
      boolean rollback = state == CardStateName.Original && (this.currentStateName == CardStateName.Flipped || this.currentStateName == CardStateName.Backside);
      boolean transform = state == CardStateName.Flipped || state == CardStateName.Backside || state == CardStateName.Meld;
      boolean needsTransformAnimation = transform || rollback;
      if (state != CardStateName.FaceDown && state != CardStateName.EmptyRoom) {
         CardCloneStates cloneStates = this.getLastClonedState();
         if (cloneStates != null) {
            if (!cloneStates.containsKey(state)) {
               String var10002 = this.getName();
               throw new RuntimeException(var10002 + " tried to switch to non-existant cloned state \"" + String.valueOf(state) + "\"!");
            }
         } else if (!this.states.containsKey(state)) {
            PrintStream var10000 = System.out;
            String var10001 = this.getName();
            var10000.println(var10001 + " tried to switch to non-existant state \"" + String.valueOf(state) + "\"!");
            return false;
         }
      }

      if (state.equals(this.currentStateName) && !forceUpdate) {
         return false;
      } else {
         if (this.currentStateName.equals(CardStateName.FaceDown) && state.equals(CardStateName.Original)) {
            this.setManifested((SpellAbility)null);
            this.setCloaked((SpellAbility)null);
         }

         this.currentStateName = state;
         this.bumpTraitEpoch();
         this.currentState = this.getState(state);
         this.bumpTraitEpoch();
         this.updateTypeCache();
         if (updateView) {
            this.updateStateForView();
            this.view.updateNeedsTransformAnimation(needsTransformAnimation);
            if (this.game != null) {
               if (!this.changedCardTypes.isEmpty()) {
                  this.updateTypesForView();
               }

               this.updateColorForView();
               if (!this.changedCardKeywords.isEmpty()) {
                  this.updateKeywords();
               }

               if (state == CardStateName.FaceDown) {
                  this.view.updateHiddenId(this.game.nextHiddenCardId());
               }

               this.game.fireEvent(new GameEventCardStatsChanged(this));
            }
         }

         return true;
      }
   }

   public Set<CardStateName> getStates() {
      return this.states.keySet();
   }

   public CardStateName getCurrentStateName() {
      return this.currentStateName;
   }

   public void setStates(Map<CardStateName, CardState> map) {
      this.states.clear();
      this.states.putAll(map);
      this.bumpTraitEpoch(); // Forge Nova: the set of states feeds getAllSpellAbilities()
   }

   public final void addAlternateState(CardStateName state, boolean updateView) {
      this.states.put(state, new CardState(this, state));
      this.bumpTraitEpoch(); // Forge Nova: the set of states feeds getAllSpellAbilities()
      if (updateView) {
         this.updateStateForView();
      }

   }

   public void clearStates(CardStateName state, boolean updateView) {
      if (this.states.remove(state) != null) {
         this.bumpTraitEpoch(); // Forge Nova: the set of states feeds getAllSpellAbilities()
         if (state == this.currentStateName) {
            this.currentStateName = CardStateName.Original;
            this.bumpTraitEpoch();
         }

         if (updateView) {
            this.updateStateForView();
         }

      }
   }

   public boolean changeCardState(String mode, String customState, SpellAbility cause) {
      if (this.isPhasedOut()) {
         return false;
      } else if (mode == null) {
         return this.changeToState(CardStateName.smartValueOf(customState));
      } else if (!mode.equals("Transform") || !this.isTransformable() && !this.hasMergedCard()) {
         if (mode.equals("Flip")) {
            if (this.isFlipped()) {
               return false;
            } else {
               boolean retResult = false;
               if (!this.isFlipCard() && !this.hasMergedCard()) {
                  retResult = true;
                  this.flipped = true;
               } else {
                  if (this.hasMergedCard()) {
                     this.removeMutatedStates();
                  }

                  for(Card c : this.hasMergedCard() ? this.getMergedCards() : new CardCollection(this)) {
                     c.flipped = true;
                     if (!c.facedown) {
                        boolean result = c.changeToState(CardStateName.Flipped);
                        retResult = retResult || result;
                     }
                  }

                  if (this.hasMergedCard()) {
                     this.rebuildMutatedStates(cause);
                     this.game.getTriggerHandler().clearActiveTriggers(this, (Zone)null);
                     this.game.getTriggerHandler().registerActiveTrigger(this, false);
                  }
               }

               return retResult;
            }
         } else {
            if (mode.equals("TurnFaceUp")) {
               if (this.isFaceDown()) {
                  return this.turnFaceUp(cause);
               }
            } else if (mode.equals("TurnFaceDown")) {
               CardStateName oldState = this.getCurrentStateName();
               if (oldState == CardStateName.Original || oldState == CardStateName.Flipped || oldState == CardStateName.LeftSplit || oldState == CardStateName.RightSplit || oldState == CardStateName.EmptyRoom) {
                  return this.turnFaceDown();
               }
            } else {
               if (mode.equals("Meld") && this.isMeldable()) {
                  return this.changeToState(CardStateName.Meld);
               }

               if (mode.equals("Specialize") && this.canSpecialize()) {
                  if (customState.equalsIgnoreCase("white")) {
                     return this.changeToState(CardStateName.SpecializeW);
                  }

                  if (customState.equalsIgnoreCase("blue")) {
                     return this.changeToState(CardStateName.SpecializeU);
                  }

                  if (customState.equalsIgnoreCase("black")) {
                     return this.changeToState(CardStateName.SpecializeB);
                  }

                  if (customState.equalsIgnoreCase("red")) {
                     return this.changeToState(CardStateName.SpecializeR);
                  }

                  if (customState.equalsIgnoreCase("green")) {
                     return this.changeToState(CardStateName.SpecializeG);
                  }
               } else if (mode.equals("Unspecialize") && this.isSpecialized()) {
                  return this.changeToState(CardStateName.Original);
               }
            }

            return false;
         }
      } else if (!this.canTransform(cause)) {
         return false;
      } else {
         if (this.hasMergedCard()) {
            this.removeMutatedStates();
         }

         long ts = this.game.getNextTimestamp();
         CardCollectionView cards = (CardCollectionView)(this.hasMergedCard() ? this.getMergedCards() : new CardCollection(this));
         boolean retResult = false;

         for(Card c : cards) {
            if (c.isTransformable()) {
               c.backside = !c.backside;
               c.setLayerTimestamp(ts);
               boolean result = c.changeToState(c.backside ? CardStateName.Backside : CardStateName.Original);
               retResult = retResult || result;
            }
         }

         if (this.hasMergedCard()) {
            this.rebuildMutatedStates(cause);
         }

         this.getGame().getReplacementHandler().run(ReplacementType.Transform, AbilityKey.mapFromAffected(this));
         this.getGame().getTriggerHandler().clearActiveTriggers(this, (Zone)null);
         this.getGame().getTriggerHandler().registerActiveTrigger(this, false);
         if (cause == null || !cause.hasParam("ETB")) {
            Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(this);
            this.getGame().getTriggerHandler().runTrigger(TriggerType.Transformed, runParams, false);
         }

         this.setTransformedTimestamp(ts);
         return retResult;
      }
   }

   public Card manifest(Player p, SpellAbility sa, Map<AbilityKey, Object> params) {
      if (!this.turnFaceDown(true) && !this.isFaceDown()) {
         return null;
      } else {
         this.setController(p, this.game.getNextTimestamp());
         this.setManifested(sa);
         Card c = this.game.getAction().moveToPlay(this, p, sa, params);
         if (c.isInPlay()) {
            c.setManifested(sa);
            c.turnFaceDown(true);
            c.updateStateForView();
         }

         return c;
      }
   }

   public Card cloak(Player p, SpellAbility sa, Map<AbilityKey, Object> params) {
      if (!this.turnFaceDown(true) && !this.isFaceDown()) {
         return null;
      } else {
         this.setController(p, this.game.getNextTimestamp());
         this.setCloaked(sa);
         this.getFaceDownState().addIntrinsicKeyword("Ward:2", true);
         Card c = this.game.getAction().moveToPlay(this, p, sa, params);
         if (c.isInPlay()) {
            c.setCloaked(sa);
            c.turnFaceDown(true);
            c.updateStateForView();
         }

         return c;
      }
   }

   public boolean turnFaceDown() {
      return this.turnFaceDown(false);
   }

   public boolean turnFaceDown(boolean override) {
      CardCollectionView cards = (CardCollectionView)(this.hasMergedCard() ? this.getMergedCards() : new CardCollection(this));
      boolean retResult = false;
      long ts = this.game.getNextTimestamp();

      for(Card c : cards) {
         if (override || !c.isDoubleFaced()) {
            c.facedown = true;
            c.setLayerTimestamp(ts);
            if (c.setState(CardStateName.FaceDown, true)) {
               c.runFacedownCommands();
               retResult = true;
            }
         }
      }

      if (retResult && this.hasMergedCard()) {
         this.removeMutatedStates();
         this.rebuildMutatedStates((CardTraitBase)null);
         this.game.getTriggerHandler().clearActiveTriggers(this, (Zone)null);
         this.game.getTriggerHandler().registerActiveTrigger(this, false);
      }

      return retResult;
   }

   public boolean turnFaceDownNoUpdate() {
      this.facedown = true;
      return this.setState(CardStateName.FaceDown, false);
   }

   public boolean canBeTurnedFaceUp() {
      Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
      return !this.getGame().getReplacementHandler().cantHappenCheck(ReplacementType.TurnFaceUp, repParams);
   }

   public void forceTurnFaceUp() {
      this.turnFaceUp(false, (SpellAbility)null);
   }

   public boolean turnFaceUp(SpellAbility cause) {
      return this.turnFaceUp(true, cause);
   }

   public boolean turnFaceUp(boolean runTriggers, SpellAbility cause) {
      if (this.isFaceDown() && this.canBeTurnedFaceUp()) {
         CardCollectionView cards = (CardCollectionView)(this.hasMergedCard() ? this.getMergedCards() : new CardCollection(this));
         boolean retResult = false;
         long ts = this.game.getNextTimestamp();

         for(Card c : cards) {
            boolean result;
            if (c.isFlipped() && c.isFlipCard()) {
               result = c.setState(CardStateName.Flipped, true);
            } else {
               result = c.setState(CardStateName.Original, true);
            }

            c.facedown = false;
            c.setLayerTimestamp(ts);
            c.turnedFaceUpThisTurn = true;
            if (c.isInPlay()) {
               c.updateRooms();
            }

            c.updateStateForView();
            if (result) {
               c.runFaceupCommands();
            }

            retResult = retResult || result;
         }

         if (!retResult) {
            return false;
         } else {
            TriggerHandler triggerHandler = this.game.getTriggerHandler();
            if (this.hasMergedCard()) {
               this.removeMutatedStates();
               this.rebuildMutatedStates(cause);
               triggerHandler.clearActiveTriggers(this, (Zone)null);
               triggerHandler.registerActiveTrigger(this, false);
            }

            if (runTriggers) {
               Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
               repParams.put(AbilityKey.Cause, cause);
               this.game.getReplacementHandler().run(ReplacementType.TurnFaceUp, repParams);
               Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(this);
               runParams.put(AbilityKey.Cause, cause);
               triggerHandler.registerActiveTrigger(this, false);
               triggerHandler.runTrigger(TriggerType.TurnFaceUp, runParams, false);
            }

            return true;
         }
      } else {
         return false;
      }
   }

   public boolean wasTurnedFaceUpThisTurn() {
      return this.turnedFaceUpThisTurn;
   }

   public boolean canTransform(SpellAbility cause) {
      if (this.isFaceDown()) {
         return false;
      } else {
         Card transformCard = this;
         if (this.hasMergedCard()) {
            boolean hasTransformCard = false;

            for(Card c : this.getMergedCards()) {
               if (c.isTransformable()) {
                  hasTransformCard = true;
                  transformCard = c;
                  break;
               }
            }

            if (!hasTransformCard) {
               return false;
            }
         } else if (!this.isTransformable()) {
            return false;
         }

         if (!this.isInPlay()) {
            return true;
         } else {
            CardStateName destState = transformCard.backside ? CardStateName.Original : CardStateName.Backside;
            if (!transformCard.getOriginalState(destState).getType().isPermanent()) {
               return false;
            } else {
               return !StaticAbilityCantTransform.cantTransform(this, cause);
            }
         }
      }
   }

   public final String getName() {
      return this.getName(this.currentState);
   }

   public final String getName(CardState state) {
      String name = state.getName();
      if (this.changedCardNames.isEmpty()) {
         return name; // Forge Nova: common case, avoid creating a table iterator
      }

      for(CardChangedName change : this.changedCardNames.values()) {
         if (change.isOverwrite()) {
            name = change.newName();
         }
      }

      return name;
   }

   public final String getDisplayName() {
      return this.getDisplayName(this.currentState);
   }

   public final String getDisplayName(CardState state) {
      return state.getFlavorName() != null && !this.hasNameOverwrite() ? state.getFlavorName() : this.getName(state);
   }

   public final boolean hasNameOverwrite() {
      return this.changedCardNames.values().stream().anyMatch(CardChangedName::isOverwrite);
   }

   public final boolean hasNonLegendaryCreatureNames() {
      boolean result = false;

      for(CardChangedName change : this.changedCardNames.values()) {
         if (change.isOverwrite()) {
            result = false;
         } else if (change.addNonLegendaryCreatureNames()) {
            result = true;
         }
      }

      return result;
   }

   public final void setName(String name0) {
      this.currentState.setName(name0);
   }

   public void addChangedName(String name0, boolean addNonLegendaryCreatureNames, long timestamp, long staticId) {
      this.changedCardNames.put(timestamp, staticId, new CardChangedName(name0, addNonLegendaryCreatureNames));
      this.bumpTraitEpoch(); // Forge Nova: names key NovaStaticSourceIndex.namedCards
      this.updateNameforView();
   }

   public boolean removeChangedName(long timestamp, long staticId) {
      return this.removeChangedName(timestamp, staticId, true);
   }

   public boolean removeChangedName(long timestamp, long staticId, boolean updateView) {
      boolean changed = this.changedCardNames.remove(timestamp, staticId) != null;
      if (changed) {
         this.bumpTraitEpoch(); // Forge Nova: names key NovaStaticSourceIndex.namedCards
      }
      if (changed && updateView) {
         this.updateNameforView();
      }

      return changed;
   }

   public boolean clearChangedName() {
      boolean changed = !this.changedCardNames.isEmpty();
      this.changedCardNames.clear();
      this.bumpTraitEpoch(); // Forge Nova: names key NovaStaticSourceIndex.namedCards
      return changed;
   }

   public void updateNameforView() {
      this.currentState.getView().updateName(this.currentState);
   }

   public void setGamePieceType(GamePieceType gamePieceType) {
      this.gamePieceType = gamePieceType;
      this.view.updateGamePieceType(this);
      this.updateTokenView();
   }

   public GamePieceType getGamePieceType() {
      return this.gamePieceType;
   }

   public final boolean isToken() {
      if (this.isInPlay() && this.hasMergedCard()) {
         return this.getTopMergedCard().gamePieceType == GamePieceType.TOKEN;
      } else {
         return this.gamePieceType == GamePieceType.TOKEN;
      }
   }

   public final boolean isRealToken() {
      return this.gamePieceType == GamePieceType.TOKEN;
   }

   public final boolean isCopiedSpell() {
      return this.gamePieceType == GamePieceType.COPIED_SPELL;
   }

   public final boolean isImmutable() {
      return this.gamePieceType == GamePieceType.EFFECT;
   }

   public final boolean isInAlternateState() {
      return this.currentStateName != CardStateName.Original;
   }

   public final boolean hasAlternateState() {
      int threshold = this.states.containsKey(CardStateName.FaceDown) ? 2 : 1;
      int numStates = this.states.size();
      return numStates > threshold;
   }

   public final boolean isTransformable() {
      return this.getRules() != null && this.getRules().isTransformable();
   }

   public final boolean isMeldable() {
      return this.getRules() != null && this.getRules().getSplitType() == CardSplitType.Meld;
   }

   public final boolean isModal() {
      return this.getRules() != null && this.getRules().getSplitType() == CardSplitType.Modal;
   }

   public final boolean isDoubleFaced() {
      return this.isTransformable() || this.isMeldable();
   }

   public final boolean isFlipCard() {
      return this.hasState(CardStateName.Flipped);
   }

   public final boolean isSplitCard() {
      return this.getRules() != null && this.getRules().getSplitType() == CardSplitType.Split ? true : this.hasState(CardStateName.LeftSplit);
   }

   public final boolean isAdventureCard() {
      return !this.hasState(CardStateName.Secondary) ? false : this.getState(CardStateName.Secondary).getType().hasSubtype("Adventure");
   }

   public final boolean isOnAdventure() {
      if (!this.isAdventureCard()) {
         return false;
      } else if (!this.equals(this.getExiledWith())) {
         return false;
      } else if (!CardStateName.Secondary.equals(this.getExiledWith().getCurrentStateName())) {
         return false;
      } else {
         return this.getExiledWith().getType().hasSubtype("Adventure");
      }
   }

   public final boolean isBackSide() {
      return this.backside;
   }

   public final void setBackSide(boolean value) {
      this.backside = value;
   }

   public boolean isCloned() {
      return !this.clonedStates.isEmpty() && (Long)this.clonedStates.lastEntry().getKey() != this.mutatedTimestamp && (Long)this.clonedStates.lastEntry().getKey() != this.prototypeTimestamp;
   }

   public final boolean isFaceDown() {
      return this.hasMergedCard() ? this.getTopMergedCard().facedown : this.facedown;
   }

   public final boolean isRealFaceDown() {
      return this.facedown;
   }

   public final void setFaceDown(boolean value) {
      this.facedown = value;
   }

   public final boolean isTransformed() {
      if (!this.isMeldable() && !this.hasMergedCard()) {
         return this.isTransformable() && this.isBackSide();
      } else {
         return false;
      }
   }

   public final boolean isFlipped() {
      return this.flipped;
   }

   public final void setFlipped(boolean value) {
      this.flipped = value;
   }

   public final CardCollectionView getDevouredCards() {
      return CardCollection.getView(this.devouredCards);
   }

   public final void addDevoured(Card c) {
      if (this.devouredCards == null) {
         this.devouredCards = new CardCollection();
      }

      this.devouredCards.add(c);
   }

   public final CardCollectionView getExploited() {
      return CardCollection.getView(this.exploitedCards);
   }

   public final void addExploited(Card c) {
      if (this.exploitedCards == null) {
         this.exploitedCards = new CardCollection();
      }

      this.exploitedCards.add(c);
   }

   public final CardCollectionView getDelved() {
      return CardCollection.getView(this.delvedCards);
   }

   public final void addDelved(Card c) {
      if (this.delvedCards == null) {
         this.delvedCards = new CardCollection();
      }

      this.delvedCards.add(c);
   }

   public final void clearDelved() {
      this.delvedCards = null;
   }

   public final CardCollectionView getConvoked() {
      return (CardCollectionView)(this.getCastSA() == null ? CardCollection.EMPTY : this.getCastSA().getTappedForConvoke());
   }

   public final CardCollectionView getEmerged() {
      return (CardCollectionView)(this.getCastSA() == null ? CardCollection.EMPTY : new CardCollection(this.getCastSA().getSacrificedAsEmerge()));
   }

   public final Iterable<Object> getRemembered() {
      return this.rememberedObjects;
   }

   public final boolean hasRemembered() {
      return !this.rememberedObjects.isEmpty();
   }

   public final int getRememberedCount() {
      return this.rememberedObjects.size();
   }

   public final Object getFirstRemembered() {
      return Iterables.getFirst(this.rememberedObjects, (Object)null);
   }

   public final <T> boolean isRemembered(T o) {
      return this.rememberedObjects.contains(o);
   }

   public final <T> void addRemembered(T o) {
      if (this.rememberedObjects.add(o)) {
         this.view.updateRemembered(this);
      }

   }

   public final <T> void addRemembered(Iterable<T> objects) {
      boolean changed = false;

      for(T o : objects) {
         if (this.rememberedObjects.add(o)) {
            changed = true;
         }
      }

      if (changed) {
         this.view.updateRemembered(this);
      }

   }

   public final <T> void removeRemembered(T o) {
      if (this.rememberedObjects.remove(o)) {
         this.view.updateRemembered(this);
      }

   }

   public final <T> void removeRemembered(Iterable<T> list) {
      boolean changed = false;

      for(T o : list) {
         if (this.rememberedObjects.remove(o)) {
            changed = true;
         }
      }

      if (changed) {
         this.view.updateRemembered(this);
      }

   }

   public final void clearRemembered() {
      if (!this.rememberedObjects.isEmpty()) {
         this.rememberedObjects.clear();
         this.view.updateRemembered(this);
      }
   }

   public final void updateRemembered() {
      this.view.updateRemembered(this);
   }

   public final CardCollectionView getImprintedCards() {
      return CardCollection.getView(this.imprintedCards);
   }

   public final boolean hasImprintedCard() {
      return FCollection.hasElements(this.imprintedCards);
   }

   public final boolean hasImprintedCard(Card c) {
      return FCollection.hasElement(this.imprintedCards, c);
   }

   public final void addImprintedCard(Card c) {
      this.imprintedCards = this.view.addCard(this.imprintedCards, c, TrackableProperty.ImprintedCards);
   }

   public final void addImprintedCards(Iterable<Card> cards) {
      this.imprintedCards = this.view.addCards(this.imprintedCards, cards, TrackableProperty.ImprintedCards);
   }

   public final void removeImprintedCard(Card c) {
      this.imprintedCards = this.view.removeCard(this.imprintedCards, c, TrackableProperty.ImprintedCards);
   }

   public final void removeImprintedCards(Iterable<Card> cards) {
      this.imprintedCards = this.view.removeCards(this.imprintedCards, cards, TrackableProperty.ImprintedCards);
   }

   public final void clearImprintedCards() {
      this.imprintedCards = this.view.clearCards(this.imprintedCards, TrackableProperty.ImprintedCards);
   }

   public final void addToChosenMap(Player p, CardCollection chosen) {
      this.chosenMap.put(p, chosen);
   }

   public final Map<Player, CardCollection> getChosenMap() {
      return this.chosenMap;
   }

   public final CardCollectionView getGainControlTargets() {
      return CardCollection.getView(this.gainControlTargets);
   }

   public final void addGainControlTarget(Card c) {
      this.gainControlTargets = this.view.addCard(this.gainControlTargets, c, TrackableProperty.GainControlTargets);
   }

   public final void removeGainControlTargets(Card c) {
      this.gainControlTargets = this.view.removeCard(this.gainControlTargets, c, TrackableProperty.GainControlTargets);
   }

   public final boolean hasGainControlTarget() {
      return FCollection.hasElements(this.gainControlTargets);
   }

   public final boolean hasGainControlTarget(Card c) {
      return FCollection.hasElement(this.gainControlTargets, c);
   }

   public final CardCollectionView getUntilLeavesBattlefield() {
      return CardCollection.getView(this.untilLeavesBattlefield);
   }

   public final void addUntilLeavesBattlefield(Card c) {
      this.untilLeavesBattlefield = this.view.addCard(this.untilLeavesBattlefield, c, TrackableProperty.UntilLeavesBattlefield);
   }

   public final void addUntilLeavesBattlefield(Iterable<Card> cards) {
      this.untilLeavesBattlefield = this.view.addCards(this.untilLeavesBattlefield, cards, TrackableProperty.UntilLeavesBattlefield);
   }

   public final void removeUntilLeavesBattlefield(Card c) {
      this.untilLeavesBattlefield = this.view.removeCard(this.untilLeavesBattlefield, c, TrackableProperty.UntilLeavesBattlefield);
   }

   public final CardCollectionView getExiledCards() {
      return CardCollection.getView(this.exiledCards);
   }

   public final boolean hasExiledCard() {
      return FCollection.hasElements(this.exiledCards);
   }

   public final boolean hasExiledCard(Card c) {
      return FCollection.hasElement(this.exiledCards, c);
   }

   public final void addExiledCard(Card c) {
      this.exiledCards = this.view.addCard(this.exiledCards, c, TrackableProperty.ExiledCards);
   }

   public final void addExiledCards(Iterable<Card> cards) {
      this.exiledCards = this.view.addCards(this.exiledCards, cards, TrackableProperty.ExiledCards);
   }

   public final void removeExiledCard(Card c) {
      this.exiledCards = this.view.removeCard(this.exiledCards, c, TrackableProperty.ExiledCards);
   }

   public final CardCollectionView getHauntedBy() {
      return CardCollection.getView(this.hauntedBy);
   }

   public final boolean isHaunted() {
      return FCollection.hasElements(this.hauntedBy);
   }

   public final boolean isHauntedBy(Card c) {
      return FCollection.hasElement(this.hauntedBy, c);
   }

   public final void addHauntedBy(Card c, boolean update) {
      this.hauntedBy = this.view.addCard(this.hauntedBy, c, TrackableProperty.HauntedBy);
      if (c != null && update) {
         c.setHaunting(this);
      }

   }

   public final void addHauntedBy(Card c) {
      this.addHauntedBy(c, true);
   }

   public final void removeHauntedBy(Card c) {
      this.hauntedBy = this.view.removeCard(this.hauntedBy, c, TrackableProperty.HauntedBy);
   }

   public final Card getHaunting() {
      return this.haunting;
   }

   public final void setHaunting(Card c) {
      this.haunting = this.view.setCard(this.haunting, c, TrackableProperty.Haunting);
   }

   public final Card getPairedWith() {
      return this.pairedWith;
   }

   public final void setPairedWith(Card c) {
      this.pairedWith = this.view.setCard(this.pairedWith, c, TrackableProperty.PairedWith);
   }

   public final boolean isPaired() {
      return this.pairedWith != null;
   }

   public Card getMeldedWith() {
      return this.meldedWith;
   }

   public void setMeldedWith(Card meldedWith) {
      this.meldedWith = meldedWith;
   }

   public final CardCollectionView getEncodedCards() {
      return CardCollection.getView(this.encodedCards);
   }

   public final boolean hasEncodedCard() {
      return FCollection.hasElements(this.encodedCards);
   }

   public final boolean hasEncodedCard(Card c) {
      return FCollection.hasElement(this.encodedCards, c);
   }

   public final void addEncodedCard(Card c) {
      this.encodedCards = this.view.addCard(this.encodedCards, c, TrackableProperty.EncodedCards);
   }

   public final void addEncodedCards(Iterable<Card> cards) {
      this.encodedCards = this.view.addCards(this.encodedCards, cards, TrackableProperty.EncodedCards);
   }

   public final void removeEncodedCard(Card c) {
      this.encodedCards = this.view.removeCard(this.encodedCards, c, TrackableProperty.EncodedCards);
   }

   public final void clearEncodedCards() {
      this.encodedCards = this.view.clearCards(this.encodedCards, TrackableProperty.EncodedCards);
   }

   public final Card getEncodingCard() {
      return this.encoding;
   }

   public final void setEncodingCard(Card e) {
      this.encoding = e;
   }

   public final CardCollectionView getMergedCards() {
      return CardCollection.getView(this.mergedCards);
   }

   public final void setMergedCards(Iterable<Card> mc) {
      this.mergedCards = new CardCollection(mc);
   }

   public final Card getTopMergedCard() {
      return (Card)this.mergedCards.get(0);
   }

   public final boolean hasMergedCard() {
      return FCollection.hasElements(this.mergedCards);
   }

   public final void addMergedCard(Card c) {
      if (this.mergedCards == null) {
         this.mergedCards = new CardCollection();
      }

      this.mergedCards.add(c);
   }

   public final void addMergedCardToTop(Card c) {
      this.mergedCards.add(0, c);
   }

   public final void removeMergedCard(Card c) {
      this.mergedCards.remove(c);
   }

   public final void clearMergedCards() {
      this.mergedCards.clear();
   }

   public final Card getMergedToCard() {
      return this.mergedTo;
   }

   public final void setMergedToCard(Card c) {
      this.mergedTo = c;
   }

   public final boolean isMerged() {
      return this.getMergedToCard() != null;
   }

   public final boolean isMutated() {
      return this.mutatedTimestamp != -1L;
   }

   public final long getMutatedTimestamp() {
      return this.mutatedTimestamp;
   }

   public final void setMutatedTimestamp(long t) {
      this.mutatedTimestamp = t;
   }

   public final int getTimesMutated() {
      return this.timesMutated;
   }

   public final void setTimesMutated(int t) {
      this.timesMutated = t;
   }

   public final void removeMutatedStates() {
      if (this.isMutated()) {
         this.removeCloneState(this.getMutatedTimestamp());
      }

   }

   public final void rebuildMutatedStates(CardTraitBase sa) {
      if (!this.isFaceDown()) {
         CardCloneStates mutatedStates = CardFactory.getMutatedCloneStates(this, sa);
         this.addCloneState(mutatedStates, this.getMutatedTimestamp());
      }

   }

   public final CardCollection getAllComponentCards(boolean includeSelf) {
      CardCollection out = new CardCollection();
      if (includeSelf) {
         out.add(this);
      }

      if (this.getMeldedWith() != null) {
         out.add(this.getMeldedWith());
      }

      if (this.mergedTo != null) {
         out.addAll(this.mergedTo.getAllComponentCards(true));
      }

      if (this.hasMergedCard()) {
         out.addAll(this.mergedCards);
      }

      if (!includeSelf) {
         out.remove(this);
      }

      return out;
   }

   public final void moveMergedToSubgame(SpellAbility cause) {
      if (this.hasMergedCard()) {
         Zone zone = this.getZone();
         int pos = -1;

         for(int i = 0; i < zone.size(); ++i) {
            if (zone.get(i) == this) {
               pos = i;
               break;
            }
         }

         Card newTop = null;

         for(Card c : this.mergedCards) {
            if (c != this) {
               newTop = c;
            }
         }

         if (newTop != null) {
            this.removeMutatedStates();
            newTop.mergedCards = this.mergedCards;
            newTop.mergedTo = null;
            this.mergedCards = null;
            this.mergedTo = newTop;
            newTop.mutatedTimestamp = this.mutatedTimestamp;
            newTop.timesMutated = this.timesMutated;
            this.mutatedTimestamp = -1L;
            this.timesMutated = 0;
            zone.remove(this);
            newTop.getZone().add(this);
            this.setZone(newTop.getZone());
            newTop.getZone().remove(newTop);
            zone.add(newTop, pos);
            newTop.setZone(zone);
         }
      }

      Card topCard = this.getMergedToCard();
      if (topCard != null) {
         this.setMergedToCard((Card)null);
         topCard.removeMergedCard(this);
         topCard.removeMutatedStates();
         topCard.rebuildMutatedStates(cause);
      }

   }

   public final void retainPaidList(SpellAbility cause, String list) {
      for(Card craft : cause.getPaidList(list)) {
         if (!craft.equals(this) && !craft.isToken()) {
            this.addExiledCard(craft);
            craft.setExiledWith(this);
            craft.setExiledBy(cause.getActivatingPlayer());
         }
      }

   }

   public final List<Integer> getStoredRolls() {
      return this.storedRolls;
   }

   public final List<String> getStoredRollsForView() {
      List<String> forView = new ArrayList();

      for(Integer i : this.storedRolls) {
         forView.add(String.valueOf(i));
      }

      return forView;
   }

   public final void addStoredRolls(List<Integer> results) {
      if (this.storedRolls == null) {
         this.storedRolls = Lists.newArrayList();
      }

      this.storedRolls.addAll(results);
      this.storedRolls.sort((Comparator)null);
      this.view.updateStoredRolls(this);
   }

   public final void replaceStoredRoll(Map<Integer, Integer> replaceMap) {
      for(Integer oldValue : replaceMap.keySet()) {
         this.storedRolls.remove(oldValue);
         this.storedRolls.add((Integer)replaceMap.get(oldValue));
      }

      this.storedRolls.sort((Comparator)null);
      this.view.updateStoredRolls(this);
   }

   public final String getFlipResult(Player flipper) {
      return this.flipResult == null ? null : (String)this.flipResult.get(flipper);
   }

   public final void addFlipResult(Player flipper, String result) {
      if (this.flipResult == null) {
         this.flipResult = Maps.newTreeMap();
      }

      this.flipResult.put(flipper, result);
   }

   public final void clearFlipResult() {
      this.flipResult = null;
   }

   public final int getXManaCostPaid() {
      if (this.getCastSA() != null) {
         Integer paid = this.getCastSA().getXManaCostPaid();
         return paid == null ? 0 : paid;
      } else {
         return 0;
      }
   }

   public final Map<String, Integer> getXManaCostPaidByColor() {
      return this.xManaCostPaidByColor;
   }

   public final void setXManaCostPaidByColor(Map<String, Integer> xByColor) {
      this.xManaCostPaidByColor = xByColor;
   }

   public final int getXManaCostPaidCount(String colors) {
      int count = 0;
      if (this.xManaCostPaidByColor != null) {
         for(Map.Entry<String, Integer> m : this.xManaCostPaidByColor.entrySet()) {
            if (colors.contains((CharSequence)m.getKey())) {
               count += (Integer)m.getValue();
            }
         }
      }

      return count;
   }

   public List<Card> getBlockedThisTurn() {
      return this.blockedThisTurn;
   }

   public void addBlockedThisTurn(Card attacker) {
      this.blockedThisTurn.add(attacker);
   }

   public void clearBlockedThisTurn() {
      this.blockedThisTurn.clear();
   }

   public List<Card> getBlockedByThisTurn() {
      return this.blockedByThisTurn;
   }

   public void addBlockedByThisTurn(Card blocker) {
      this.blockedByThisTurn.add(blocker);
   }

   public void clearBlockedByThisTurn() {
      this.blockedByThisTurn.clear();
   }

   public final CardCollectionView getMustBlockCards() {
      return CardCollection.getView(Iterables.concat(this.mustBlockCards.values()));
   }

   public final void addMustBlockCard(long ts, Card c) {
      this.mustBlockCards.put(ts, new CardCollection(c));
      this.view.updateMustBlockCards(this);
   }

   public final void addMustBlockCards(long ts, Iterable<Card> attackersToBlock) {
      this.mustBlockCards.put(ts, new CardCollection(attackersToBlock));
      this.view.updateMustBlockCards(this);
   }

   public final void removeMustBlockCards(long ts) {
      this.mustBlockCards.remove(ts);
      this.view.updateMustBlockCards(this);
   }

   public final void clearMustBlockCards() {
      this.mustBlockCards.clear();
      this.view.updateMustBlockCards(this);
   }

   public final Card getCloneOrigin() {
      return this.cloneOrigin;
   }

   public final void setCloneOrigin(Card cloneOrigin0) {
      this.cloneOrigin = this.view.setCard(this.cloneOrigin, cloneOrigin0, TrackableProperty.CloneOrigin);
   }

   public final boolean hasFirstStrike() {
      return this.hasKeyword(Keyword.FIRST_STRIKE);
   }

   public final boolean hasDoubleStrike() {
      return this.hasKeyword(Keyword.DOUBLE_STRIKE);
   }

   public final boolean hasSecondStrike() {
      return this.hasDoubleStrike() || !this.hasFirstStrike();
   }

   public final boolean hasSuspend() {
      return this.hasKeyword(Keyword.SUSPEND) && this.getLastKnownZone().is(ZoneType.Exile) && this.getCounters(CounterEnumType.TIME) >= 1;
   }

   public final boolean hasConverge() {
      return "Count$Converge".equals(this.getSVar("X")) || "Count$Converge".equals(this.getSVar("Y")) || this.hasKeyword(Keyword.SUNBURST) || this.hasKeyword("Modular:Sunburst");
   }

   public final boolean canReceiveCounters(CounterType type) {
      if (this.isPhasedOut()) {
         return false;
      } else {
         return !StaticAbilityCantPutCounter.anyCantPutCounter(this, type);
      }
   }

   public final boolean canRemoveCounters(CounterType type) {
      if (this.isPhasedOut()) {
         return false;
      } else {
         Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
         repParams.put(AbilityKey.CounterType, type);
         repParams.put(AbilityKey.Result, 0);
         repParams.put(AbilityKey.IsDamage, false);
         return !this.game.getReplacementHandler().cantHappenCheck(ReplacementType.RemoveCounter, repParams);
      }
   }

   public Integer getCounterMax(CounterType counterType) {
      return counterType.is(CounterEnumType.DREAM) ? StaticAbilityMaxCounter.maxCounter(this, counterType) : null;
   }

   /** Forge Nova: see addCounterInternal. */
   private static final int NOVA_PER_COUNTER_EVENTS = 10000;

   public void addCounterInternal(CounterType counterType, int n, Player source, boolean fireEvents, GameEntityCounterTable table, Map<AbilityKey, Object> params) {
      int addAmount = n;
      if (n > 0 && this.canReceiveCounters(counterType)) {
         int oldValue = this.getCounters(counterType);
         Integer max = this.getCounterMax(counterType);
         if (max != null) {
            addAmount = Math.min(n, max - oldValue);
            if (addAmount <= 0) {
               return;
            }
         }

         // Forge Nova: a counter count stops at the largest int instead of wrapping around to a negative number
         if ((long)addAmount + oldValue > Integer.MAX_VALUE) {
            addAmount = Integer.MAX_VALUE - oldValue;
            if (addAmount <= 0) {
               return;
            }
         }

         int newValue = addAmount + oldValue;
         if (fireEvents) {
            this.getGame().updateLastStateForCard(this);
            SpellAbility cause = (SpellAbility)params.get(AbilityKey.Cause);
            int powerBonusBefore = this.getPowerBonusFromCounters();
            int toughnessBonusBefore = this.getToughnessBonusFromCounters();
            int loyaltyBefore = this.getCurrentLoyalty();
            int addedThisTurn = this.getGame().getCounterAddedThisTurn(counterType, this);
            this.setCounters(counterType, newValue);
            this.getGame().addCounterAddedThisTurn(source, counterType, this, addAmount);
            this.view.updateCounters(this);
            if (powerBonusBefore != this.getPowerBonusFromCounters() || toughnessBonusBefore != this.getToughnessBonusFromCounters() || loyaltyBefore != this.getCurrentLoyalty()) {
               this.getGame().fireEvent(new GameEventCardStatsChanged(this));
            }

            this.getGame().fireEvent(new GameEventCardCounters(this, counterType, oldValue, newValue));
            Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(this);
            runParams.put(AbilityKey.Source, source);
            runParams.put(AbilityKey.CounterType, counterType);
            if (params != null) {
               runParams.putAll(params);
            }

            // Forge Nova: one "counter added" trigger event per counter, for at most NOVA_PER_COUNTER_EVENTS counters (a
            // billion counters added at once would otherwise keep the game busy for hours)
            int perCounter = Math.min(addAmount, NOVA_PER_COUNTER_EVENTS);
            for(int i = 0; i < perCounter; ++i) {
               runParams.put(AbilityKey.CounterAmount, oldValue + i + 1);
               this.getGame().getTriggerHandler().runTrigger(TriggerType.CounterAdded, AbilityKey.newMap(runParams), false);
            }

            if (addAmount > 0) {
               runParams.put(AbilityKey.CounterAmount, addAmount);
               runParams.put(AbilityKey.FirstTime, addedThisTurn == 0);
               this.getGame().getTriggerHandler().runTrigger(TriggerType.CounterAddedOnce, AbilityKey.newMap(runParams), false);
               if (cause != null) {
                  if (cause.isKeyword(Keyword.EVOLVE) && counterType.is(CounterEnumType.P1P1)) {
                     this.getGame().getTriggerHandler().runTrigger(TriggerType.Evolved, AbilityKey.mapFromCard(this), false);
                  }

                  if (cause.isKeyword(Keyword.TRAINING) && counterType.is(CounterEnumType.P1P1)) {
                     this.getGame().getTriggerHandler().runTrigger(TriggerType.Trains, AbilityKey.mapFromCard(this), false);
                  }
               }
            }
         } else {
            this.setCounters(counterType, newValue);
            this.getGame().addCounterAddedThisTurn(source, counterType, this, addAmount);
            this.view.updateCounters(this);
         }

         if (this.createCounterStatic(counterType)) {
            this.updateKeywords();
         }

         if (table != null) {
            table.put(source, this, counterType, addAmount);
         }

      }
   }

   public boolean createCounterStatic(CounterType counterType) {
      final int novaSize = this.counterTypeKeywordStatic.size();
      try {
         return this.createCounterStaticInternal(counterType);
      } finally {
         if (this.counterTypeKeywordStatic.size() != novaSize) {
            this.bumpTraitEpoch(); // Forge Nova: getHiddenStaticAbilities() can include the new static
         }
      }
   }

   private boolean createCounterStaticInternal(CounterType counterType) {
      StaticAbility result;
      if (counterType.is(CounterEnumType.MANABOND)) {
         result = (StaticAbility)this.counterTypeKeywordStatic.computeIfAbsent(counterType, (ct) -> {
            String s = "Mode$ Continuous | AffectedDefined$ Self | EffectZone$ All | AddType$ Land | RemoveCardTypes$ True | RemoveSubTypes$ True | RemoveAllAbilities$ True | AddAbility$ ManaReflected";
            StaticAbility stAb = StaticAbility.create(s, this, this.currentState, true);
            String abStr = "AB$ ManaReflected | Cost$ T | Valid$ Defined.Self | ColorOrType$ Color | ReflectProperty$ Is | SpellDescription$ Add one mana of any of this card's colors.";
            stAb.setSVar("ManaReflected", abStr);
            return stAb;
         });
      } else if (counterType.isKeywordCounter()) {
         result = (StaticAbility)this.counterTypeKeywordStatic.computeIfAbsent(counterType, (ct) -> StaticAbility.create("Mode$ Continuous | AffectedDefined$ Self | EffectZone$ All | AddKeyword$ " + ct.toString(), this, this.currentState, true));
         if (!Keyword.smartValueOf(counterType.toString().split(":")[0]).isMultipleRedundant()) {
            result.putParam("KeywordMultiplier", String.valueOf(this.getCounters(counterType)));
         }
      } else {
         if (!counterType.is(CounterEnumType.HONE)) {
            return false;
         }

         result = (StaticAbility)this.counterTypeKeywordStatic.computeIfAbsent(counterType, (ct) -> {
            StaticAbility stAb = StaticAbility.create("Mode$ Continuous | EffectZone$ Battlefield | Affected$ Creature.EquippedBy | AddPower$ HoneCounters | Description$ Equipped creature gets +1/+0 for each hone counter on this Equipment.", this, this.currentState, true);
            stAb.setSVar("HoneCounters", "Count$CardCounters.HONE");
            return stAb;
         });
      }

      result.putParam("Timestamp", String.valueOf(this.game.getNextTimestamp()));
      return true;
   }

   public final int subtractCounter(CounterType counterName, int n, Player remover) {
      return this.subtractCounter(counterName, n, remover, false);
   }

   public final int subtractCounter(CounterType counterName, int n, Player remover, boolean isDamage) {
      int oldValue = this.getCounters(counterName);
      int newValue = Math.max(oldValue - n, 0);
      Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
      repParams.put(AbilityKey.CounterType, counterName);
      repParams.put(AbilityKey.Result, newValue);
      repParams.put(AbilityKey.IsDamage, isDamage);
      switch (this.getGame().getReplacementHandler().run(ReplacementType.RemoveCounter, repParams)) {
         case Updated:
            int result = (Integer)repParams.get(AbilityKey.Result);
            newValue = result;
            if (result <= 0) {
               newValue = 0;
            }
         case NotReplaced:
         default:
            int delta = oldValue - newValue;
            if (delta == 0) {
               return 0;
            }

            int powerBonusBefore = this.getPowerBonusFromCounters();
            int toughnessBonusBefore = this.getToughnessBonusFromCounters();
            int loyaltyBefore = this.getCurrentLoyalty();
            this.setCounters(counterName, newValue);
            this.view.updateCounters(this);
            if (newValue <= 0 && (counterName.is(CounterEnumType.MANABOND) || counterName.isKeywordCounter())) {
               this.updateKeywords();
            }

            if (powerBonusBefore != this.getPowerBonusFromCounters() || toughnessBonusBefore != this.getToughnessBonusFromCounters() || loyaltyBefore != this.getCurrentLoyalty()) {
               this.getGame().fireEvent(new GameEventCardStatsChanged(this));
            }

            this.getGame().fireEvent(new GameEventCardCounters(this, counterName, oldValue, newValue));
            this.getGame().addCounterRemovedThisTurn(counterName, this, delta);
            int curCounters = oldValue;
            Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(this);
            runParams.put(AbilityKey.CounterType, counterName);
            runParams.put(AbilityKey.Player, remover);

            for(int i = 0; i < delta && curCounters != 0; ++i) {
               --curCounters;
               runParams.put(AbilityKey.NewCounterAmount, curCounters);
               this.getGame().getTriggerHandler().runTrigger(TriggerType.CounterRemoved, AbilityKey.newMap(runParams), false);
            }

            runParams.put(AbilityKey.CounterAmount, delta);
            runParams.put(AbilityKey.NewCounterAmount, newValue);
            this.getGame().getTriggerHandler().runTrigger(TriggerType.CounterRemovedOnce, runParams, false);
            return delta;
         case Replaced:
            return 0;
      }
   }

   /** Forge Nova: shield/stun/finality counters add replacement effects (see TraitEpoch.replacementExtras). */
   @Override
   public void setCounters(CounterType counterType, Integer num) {
      super.setCounters(counterType, num);
      if (this.currentZone != null && (counterType.is(CounterEnumType.SHIELD) || counterType.is(CounterEnumType.STUN) || counterType.is(CounterEnumType.FINALITY))) {
         TraitEpoch.bumpReplacementExtras();
      }
   }

   public final void setCounters(Multiset<CounterType> allCounters) {
      boolean changed = this.counters.contains(CounterEnumType.MANABOND) || this.counters.elementSet().stream().anyMatch(CounterType::isKeywordCounter);
      this.counters = allCounters;
      if (this.currentZone != null) {
         TraitEpoch.bumpReplacementExtras(); // Forge Nova: any counter may have changed
      }
      this.view.updateCounters(this);
      if (!this.isLKI()) {
         for(CounterType ct : this.counters.elementSet()) {
            if (this.createCounterStatic(ct)) {
               changed = true;
            }
         }
      }

      if (changed) {
         this.updateKeywords();
      }

   }

   public final void clearCounters() {
      if (!this.counters.isEmpty()) {
         boolean changed = this.counters.contains(CounterEnumType.MANABOND) || this.counters.elementSet().stream().anyMatch(CounterType::isKeywordCounter);
         this.counters.clear();
         if (this.currentZone != null) {
            TraitEpoch.bumpReplacementExtras(); // Forge Nova: any counter may have changed
         }
         this.view.updateCounters(this);
         if (changed) {
            this.updateKeywords();
         }

      }
   }

   public final void putEtbCounters(Map<Optional<Player>, Multiset<CounterType>> etbCounters) {
      if (etbCounters != null) {
         for(Multiset<CounterType> m : etbCounters.values()) {
            for(Multiset.Entry<CounterType> e : m.entrySet()) {
               CounterType ct = (CounterType)e.getElement();
               if (this.canReceiveCounters(ct)) {
                  this.setCounters(ct, this.getCounters(ct) + e.getCount());
               }
            }
         }

      }
   }

   public final String getSVar(String var) {
      for(Map<String, String> map : this.changedSVars.values()) {
         if (map.containsKey(var)) {
            return (String)map.get(var);
         }
      }

      return this.currentState.getSVar(var);
   }

   public final boolean hasSVar(String var) {
      for(Map<String, String> map : this.changedSVars.values()) {
         if (map.containsKey(var)) {
            return true;
         }
      }

      return this.currentState.hasSVar(var);
   }

   public final void setSVar(String var, String str) {
      this.currentState.setSVar(var, str);
   }

   public final void copyChangedSVarsFrom(Card other) {
      this.changedSVars.clear();
      this.changedSVars.putAll(other.changedSVars);
   }

   public final Map<String, String> getSVars() {
      return this.currentState.getSVars();
   }

   public final void setSVars(Map<String, String> newSVars) {
      this.currentState.setSVars(newSVars);
   }

   public final void removeSVar(String var) {
      this.currentState.removeSVar(var);
   }

   public final void addChangedSVars(Map<String, String> map, long timestamp, long staticId) {
      this.changedSVars.put(timestamp, staticId, map);
   }

   public final void removeChangedSVars(long timestamp, long staticId) {
      this.changedSVars.remove(timestamp, staticId);
   }

   public final int getTurnInZone() {
      return this.turnInZone;
   }

   public final void setTurnInZone(int turn) {
      this.turnInZone = turn;
   }

   public final boolean enteredThisTurn() {
      return this.getTurnInZone() == this.game.getPhaseHandler().getTurn();
   }

   public final Player getTurnInController() {
      return this.turnInController;
   }

   public final void setTurnInController(Player p) {
      this.turnInController = p;
   }

   public final void setManaCost(ManaCost s) {
      this.currentState.setManaCost(s);
   }

   public final ManaCost getOriginalManaCost() {
      return this.currentState.getManaCost();
   }

   public final ManaCost getManaCost() {
      ManaCost result = this.getOriginalManaCost();
      if (this.changedCardManaCost.isEmpty()) {
         return result;
      } else {
         for(CardManaCost mc : this.changedCardManaCost.values()) {
            if (mc.additional()) {
               result = ManaCost.combine(result, mc.mana());
            } else {
               result = mc.mana();
            }
         }

         return result;
      }
   }

   public void calculatePerpetualAdjustedManaCost() {
      this.currentState.calculatePerpetualAdjustedManaCost();
      if (this.isSplitCard()) {
         if (this.currentState.getCard().getState(CardStateName.LeftSplit) != null) {
            this.currentState.getCard().getState(CardStateName.LeftSplit).calculatePerpetualAdjustedManaCost();
         }

         if (this.currentState.getCard().getState(CardStateName.RightSplit) != null) {
            this.currentState.getCard().getState(CardStateName.RightSplit).calculatePerpetualAdjustedManaCost();
         }
      }

   }

   public void addChangedManaCost(ManaCost cost, boolean additional, long timestamp, long staticId) {
      this.changedCardManaCost.put(timestamp, staticId, new CardManaCost(cost, additional));
      this.updateManaCostForView();
   }

   public boolean removeChangedManaCost(long timestamp, long staticId) {
      boolean result = this.changedCardManaCost.remove(timestamp, staticId) != null;
      this.updateManaCostForView();
      return result;
   }

   public final boolean hasChosenPlayer() {
      return this.chosenPlayer != null;
   }

   public final Player getChosenPlayer() {
      return this.chosenPlayer;
   }

   public final void setChosenPlayer(Player p) {
      if (this.chosenPlayer != p) {
         this.chosenPlayer = p;
         this.view.updateChosenPlayer(this);
      }
   }

   public final void setSecretChosenPlayer(Player p) {
      this.chosenPlayer = p;
   }

   public final void revealChosenPlayer() {
      this.view.updateChosenPlayer(this);
   }

   public final boolean hasPromisedGift() {
      return this.promisedGift != null;
   }

   public final Player getPromisedGift() {
      return this.promisedGift;
   }

   public final void setPromisedGift(Player p) {
      if (this.promisedGift != p) {
         this.promisedGift = p;
         this.view.updatePromisedGift(this);
      }
   }

   public final Player getProtectingPlayer() {
      return this.protectingPlayer;
   }

   public final void setProtectingPlayer(Player p) {
      if (this.protectingPlayer != p) {
         this.protectingPlayer = p;
         this.view.updateProtectingPlayer(this);
      }
   }

   public final boolean hasChosenNumber() {
      return this.chosenNumber != null;
   }

   public final Integer getChosenNumber() {
      return this.chosenNumber;
   }

   public final void setChosenNumber(int i) {
      this.setChosenNumber(i, false);
   }

   public final void setChosenNumber(int i, boolean secret) {
      this.chosenNumber = i;
      if (!secret) {
         this.view.updateChosenNumber(this);
      }

   }

   public final void clearChosenNumber() {
      this.chosenNumber = null;
      this.view.clearChosenNumber();
   }

   public final Card getExiledWith() {
      return this.exiledWith;
   }

   public final void setExiledWith(Card e) {
      this.exiledWith = this.view.setCard(this.exiledWith, e, TrackableProperty.ExiledWith);
   }

   public final void cleanupExiledWith() {
      if (this.exiledWith != null && !this.exiledWith.isLKI()) {
         this.exiledWith.removeExiledCard(this);
         this.exiledWith.removeUntilLeavesBattlefield(this);
         this.exiledWith = null;
         this.exiledBy = null;
         this.exiledSA = null;
      }
   }

   public final Player getExiledBy() {
      return this.exiledBy;
   }

   public final void setExiledBy(Player ep) {
      this.exiledBy = ep;
   }

   public final SpellAbility getExiledSA() {
      return this.exiledSA;
   }

   public final void setExiledSA(SpellAbility sa) {
      this.exiledSA = sa;
   }

   public final String getChosenType() {
      return this.chosenType;
   }

   public final void setChosenType(String s) {
      this.chosenType = s;
      this.view.updateChosenType(this);
   }

   public final boolean hasChosenType() {
      return this.chosenType != null && !this.chosenType.isEmpty();
   }

   public final void setSecretChosenType(String s) {
      this.chosenType = s;
   }

   public final void revealChosenType() {
      this.view.updateChosenType(this);
   }

   public final String getChosenType2() {
      return this.chosenType2;
   }

   public final void setChosenType2(String s) {
      this.chosenType2 = s;
      this.view.updateChosenType2(this);
   }

   public final boolean hasChosenType2() {
      return this.chosenType2 != null && !this.chosenType2.isEmpty();
   }

   public final boolean hasAnyNotedType() {
      return this.notedTypes != null && !this.notedTypes.isEmpty();
   }

   public final void addNotedType(String type) {
      this.notedTypes.add(type);
      this.view.updateNotedTypes(this);
   }

   public final Iterable<String> getNotedTypes() {
      return (Iterable<String>)(this.notedTypes == null ? Lists.newArrayList() : this.notedTypes);
   }

   public final int getNumNotedTypes() {
      return this.notedTypes == null ? 0 : this.notedTypes.size();
   }

   public final String getChosenColor() {
      return this.hasChosenColor() ? (String)this.chosenColors.get(0) : "";
   }

   public final Iterable<String> getChosenColors() {
      return (Iterable<String>)(this.chosenColors == null ? Lists.newArrayList() : this.chosenColors);
   }

   public final void setChosenColors(List<String> s) {
      this.chosenColors = s;
      this.view.updateChosenColors(this);
   }

   public boolean hasChosenColor() {
      return this.chosenColors != null && !this.chosenColors.isEmpty();
   }

   public boolean hasChosenColor(String s) {
      return this.chosenColors != null && this.chosenColors.contains(s);
   }

   public final boolean hasPaperFoil() {
      return this.view.hasPaperFoil();
   }

   public final void setPaperFoil(boolean v) {
      this.view.updatePaperFoil(v);
   }

   public final ColorSet getMarkedColors() {
      return this.markedColor == null ? ColorSet.C : this.markedColor;
   }

   public final void setMarkedColors(ColorSet s) {
      this.markedColor = s;
      this.view.updateMarkedColors(this);
   }

   public boolean hasMarkedColor() {
      return this.markedColor != null && !this.markedColor.isColorless();
   }

   public final Card getChosenCard() {
      return (Card)this.getChosenCards().getFirst();
   }

   public final CardCollectionView getChosenCards() {
      return CardCollection.getView(this.chosenCards);
   }

   public final void setChosenCards(Iterable<Card> cards) {
      this.chosenCards = this.view.setCards(this.chosenCards, cards, TrackableProperty.ChosenCards);
   }

   public boolean hasChosenCard() {
      return FCollection.hasElements(this.chosenCards);
   }

   public boolean hasChosenCard(Card c) {
      return FCollection.hasElement(this.chosenCards, c);
   }

   public Direction getChosenDirection() {
      return this.chosenDirection;
   }

   public void setChosenDirection(Direction chosenDirection0) {
      if (this.chosenDirection != chosenDirection0) {
         this.chosenDirection = chosenDirection0;
         this.view.updateChosenDirection(this);
      }
   }

   public String getChosenMode() {
      return this.chosenMode;
   }

   public void setChosenMode(String mode) {
      this.chosenMode = mode;
      this.view.updateChosenMode(this);
   }

   public String getCurrentRoom() {
      return this.currentRoom;
   }

   public void setCurrentRoom(String room) {
      this.currentRoom = room;
      this.view.updateCurrentRoom(this);
      this.updateAbilityTextForView();
   }

   public boolean isInLastRoom() {
      for(Trigger t : this.getTriggers()) {
         SpellAbility sa = t.getOverridingAbility();
         if (sa.getParam("RoomName").equals(this.currentRoom) && !sa.hasParam("NextRoom")) {
            return true;
         }
      }

      return false;
   }

   public String getSector() {
      return this.sector;
   }

   public void assignSector(String s) {
      this.sector = s;
      this.view.updateSector(this);
   }

   public boolean hasSector() {
      return this.sector != null;
   }

   public String getChosenSector() {
      return this.chosenSector;
   }

   public final void setChosenSector(String s) {
      this.chosenSector = s;
   }

   public int getSprocket() {
      return this.sprocket;
   }

   public void setSprocket(int sprocket) {
      int oldSprocket = this.sprocket;
      this.sprocket = sprocket;
      this.view.updateSprocket(this);
      this.game.fireEvent(new GameEventSprocketUpdate(this, oldSprocket, sprocket));
   }

   public void handleChangedControllerSprocketReset() {
      if (this.sprocket != 0) {
         this.setSprocket(-1);
      }

   }

   public final String getNamedCard() {
      return this.hasNamedCard() ? (String)Iterables.getLast(this.chosenName) : "";
   }

   public final List<String> getNamedCards() {
      return this.chosenName;
   }

   public final void setNamedCards(List<String> s) {
      this.chosenName = s;
      this.view.updateNamedCard(this);
   }

   public final void addNamedCard(String s) {
      this.chosenName.add(s);
      this.view.updateNamedCard(this);
   }

   public boolean hasNamedCard() {
      return !this.chosenName.isEmpty();
   }

   public boolean hasChosenEvenOdd() {
      return this.chosenEvenOdd != null;
   }

   public EvenOdd getChosenEvenOdd() {
      return this.chosenEvenOdd;
   }

   public void setChosenEvenOdd(EvenOdd chosenEvenOdd0) {
      if (this.chosenEvenOdd != chosenEvenOdd0) {
         this.chosenEvenOdd = chosenEvenOdd0;
         this.view.updateChosenEvenOdd(this);
      }
   }

   public final String getSpellText() {
      return this.text;
   }

   public final void setText(String t) {
      this.originalText = t;
      this.text = this.originalText;
   }

   public final String getNonAbilityText() {
      StringBuilder sb = new StringBuilder();
      StringBuilder sbLong = new StringBuilder();

      for(String keyword : this.getHiddenExtrinsicKeywords()) {
         sbLong.append(keyword).append("\r\n");
      }

      if (sb.length() > 0) {
         sb.append("\r\n");
         if (sbLong.length() > 0) {
            sb.append("\r\n");
         }
      }

      if (sbLong.length() > 0) {
         sbLong.append("\r\n");
      }

      sb.append(sbLong);
      if (!this.mayPlay.isEmpty()) {
         PlayerCollection players = new PlayerCollection();

         for(CardPlayOption o : this.mayPlay.values()) {
            if (this.getController() == o.getPlayer() || o.grantsZonePermissions()) {
               players.add(o.getPlayer());
            }
         }

         if (!players.isEmpty()) {
            sb.append("May be played by: ");
            sb.append(Lang.joinHomogenous(players));
            sb.append("\r\n");
         }
      }

      return sb.toString();
   }

   private String keywordsToText(Collection<KeywordInterface> keywords) {
      StringBuilder sb = new StringBuilder();
      StringBuilder sbLong = new StringBuilder();
      List<String> printedKW = new ArrayList();
      int i = 0;

      for(KeywordInterface inst : keywords) {
         String keyword = inst.getOriginal();

         try {
            if (keyword.startsWith("etbCounter")) {
               String[] p = keyword.split(":");
               StringBuilder s = new StringBuilder();
               if (p.length > 4) {
                  if (!"no desc".equals(p[4])) {
                     s.append(p[4]);
                  }
               } else {
                  s.append(this.getName()).append(" enters with ");
                  s.append(Lang.nounWithNumeralExceptOne(p[2], CounterType.getType(p[1]).getName().toLowerCase() + " counter"));
                  s.append(" on it.");
               }

               sbLong.append(s).append("\r\n");
            } else if (keyword.startsWith("DeckLimit")) {
               String[] k = keyword.split(":");
               sbLong.append(k[2]).append("\r\n");
            } else if (keyword.startsWith("Enchant") && inst instanceof KeywordWithType) {
               KeywordWithType kwt = (KeywordWithType)inst;
               String desc = kwt.getTypeDescription();
               sbLong.append("Enchant ").append(desc).append("\r\n");
            } else if (!keyword.startsWith("Morph") && !keyword.startsWith("Megamorph") && !keyword.startsWith("Multikicker") && !keyword.startsWith("Echo") && !keyword.startsWith("Disguise") && !keyword.startsWith("Reflect") && !keyword.startsWith("Mayhem") && !keyword.startsWith("Recover") && !keyword.startsWith("Sneak") && !keyword.startsWith("Squad") && !keyword.startsWith("Emerge") && !keyword.startsWith("More Than Meets the Eye") && !keyword.startsWith("Level up") && !keyword.startsWith("Plot") && !keyword.startsWith("Impending") && !keyword.equals("Suspend")) {
               if (!keyword.startsWith("Escape") && !keyword.startsWith("Foretell:") && !keyword.startsWith("Madness:") && !keyword.startsWith("Reconfigure") && !keyword.startsWith("Miracle") && !keyword.startsWith("Offspring")) {
                  if (keyword.startsWith("Cumulative upkeep")) {
                     sbLong.append("Cumulative upkeep ");
                     String[] upkeepCostParams = keyword.split(":");
                     sbLong.append(upkeepCostParams.length > 2 ? "— " + upkeepCostParams[2] : ManaCostParser.parse(upkeepCostParams[1]));
                     sbLong.append("\r\n");
                  } else if (keyword.startsWith("AlternateAdditionalCost")) {
                     String[] costs = keyword.split(":", 2)[1].split(":");
                     sbLong.append("As an additional cost to cast this spell, ");

                     for(int n = 0; n < costs.length; ++n) {
                        Cost cost = new Cost(costs[n], false);
                        if (cost.isOnlyManaCost()) {
                           sbLong.append(" pay ");
                        }

                        sbLong.append(StringUtils.uncapitalize(cost.toSimpleString()));
                        sbLong.append(n + 1 == costs.length ? ".\r\n\r\n" : (n + 2 == costs.length && costs.length > 2 ? ", or " : (n + 2 == costs.length ? " or " : ", ")));
                     }
                  } else if (keyword.startsWith("Kicker")) {
                     sbLong.append(this.kickerDesc(keyword, inst.getReminderText())).append("\r\n");
                  } else if (keyword.startsWith("Trample:")) {
                     sbLong.append(inst.getTitle()).append(" (").append(inst.getReminderText()).append(")").append("\r\n");
                  } else if (keyword.startsWith("Hexproof:")) {
                     String[] k = keyword.split(":");
                     sbLong.append(inst.getTitle());
                     if (k.length <= 2 || !k[2].contains(" and ") && !k[2].contains("each")) {
                        sbLong.append(" (").append(inst.getReminderText()).append(")");
                     }

                     sbLong.append("\r\n");
                  } else if (keyword.startsWith("Protection:")) {
                     String[] k = keyword.split(":");
                     if (k.length > 2) {
                        sbLong.append("Protection from ").append(k[2]);
                     } else {
                        sbLong.append(inst.getTitle());
                     }

                     sbLong.append("\r\n");
                  } else if (inst.getKeyword().equals(Keyword.COMPANION)) {
                     sbLong.append("Companion — ");
                     sbLong.append(((Companion)inst).getDescription());
                  } else if (keyword.startsWith("MayFlash")) {
                     sbLong.append(inst.getReminderText()).append("\r\n");
                  } else if (!keyword.equals("Provoke") && !keyword.equals("Ingest") && !keyword.equals("Unleash") && !keyword.equals("Living Weapon") && !keyword.equals("Myriad") && !keyword.equals("Exploit") && !keyword.equals("Changeling") && !keyword.equals("Delve") && !keyword.equals("Decayed") && !keyword.equals("Split second") && !keyword.equals("Sunburst") && !keyword.equals("Riot") && !keyword.equals("Soulbond") && !keyword.equals("Retrace") && !keyword.equals("Double team") && !keyword.equals("Living metal") && !keyword.equals("Foretell") && !keyword.equals("Ascend") && !keyword.equals("Umbra armor") && !keyword.equals("Battle cry") && !keyword.equals("Devoid") && !keyword.equals("Daybound") && !keyword.equals("Nightbound") && !keyword.equals("Increment") && !keyword.equals("Choose a Background") && !keyword.equals("Compleated") && !keyword.equals("Space sculptor") && !keyword.equals("Doctor's companion") && !keyword.equals("Start your engines") && !keyword.startsWith("Modular") && !keyword.startsWith("Bloodthirst") && !keyword.startsWith("Dredge") && !keyword.startsWith("Fabricate") && !keyword.startsWith("Soulshift") && !keyword.startsWith("Bushido") && !keyword.startsWith("Saddle") && !keyword.startsWith("Tribute") && !keyword.startsWith("Absorb") && !keyword.startsWith("Graft") && !keyword.startsWith("Fading") && !keyword.startsWith("Vanishing:") && !keyword.startsWith("Afterlife") && !keyword.startsWith("Hideaway") && !keyword.startsWith("Toxic") && !keyword.startsWith("Afflict") && !keyword.startsWith("Poisonous") && !keyword.startsWith("Rampage") && !keyword.startsWith("Renown") && !keyword.startsWith("Annihilator") && !keyword.startsWith("Ripple") && !keyword.startsWith("Ward")) {
                     if (keyword.startsWith("Partner with:")) {
                        String[] k = keyword.split(":");
                        sbLong.append("Partner with ").append(k[1]).append(" (").append(inst.getReminderText()).append(")");
                     } else if (keyword.startsWith("Partner")) {
                        sbLong.append(inst.getTitle()).append(" (").append(inst.getReminderText()).append(")");
                     } else if (keyword.startsWith("Prototype")) {
                        String[] k = keyword.split(":");
                        Cost cost = new Cost(k[1], false);
                        sbLong.append(k[0]).append(" ").append(cost.toSimpleString()).append(" ").append("[").append(k[2]);
                        sbLong.append("/").append(k[3]).append("] ").append("(").append(inst.getReminderText()).append(")");
                     } else if (keyword.startsWith("Crew")) {
                        String[] k = keyword.split(":");
                        sbLong.append("Crew ").append(k[1]);
                        if (k.length > 2 && k[2].contains("ActivationLimit$ 1")) {
                           sbLong.append(". Activate only once each turn.");
                        }

                        sbLong.append(" (").append(inst.getReminderText()).append(")");
                     } else if (keyword.startsWith("Casualty")) {
                        String[] k = keyword.split(":");
                        sbLong.append("Casualty ").append(k[1]);
                        if (k.length >= 4) {
                           sbLong.append(". ").append(k[3]);
                        }

                        sbLong.append(" (").append(inst.getReminderText()).append(")");
                     } else if (keyword.equals("Gift")) {
                        sbLong.append(keyword);
                        Trigger trig = (Trigger)inst.getTriggers().stream().findFirst().orElse(null);
                        if (trig != null && trig.getCardState().getFirstSpellAbilityWithFallback().hasAdditionalAbility("GiftAbility")) {
                           sbLong.append(" ").append(trig.getCardState().getFirstSpellAbilityWithFallback().getAdditionalAbility("GiftAbility").getParam("GiftDescription"));
                        }

                        sbLong.append("\r\n");
                     } else if (keyword.startsWith("Starting intensity")) {
                        sbLong.append(TextUtil.fastReplace(keyword, ":", " "));
                     } else if (keyword.contains("Haunt")) {
                        sb.append("\r\nHaunt (").append(inst.getReminderText()).append(")");
                     } else if (keyword.startsWith("Bands with other")) {
                        String[] k = keyword.split(":");
                        String desc = k.length > 2 ? k[2] : CardType.getPluralType(k[1]);
                        sbLong.append(k[0]).append(" ").append(desc).append(" (").append(inst.getReminderText()).append(")");
                     } else if (!keyword.equals("Convoke") && !keyword.equals("Dethrone") && !keyword.equals("Fear") && !keyword.equals("Melee") && !keyword.equals("Improvise") && !keyword.equals("Shroud") && !keyword.equals("Banding") && !keyword.equals("Intimidate") && !keyword.equals("Evolve") && !keyword.equals("Exalted") && !keyword.equals("Extort") && !keyword.equals("Flanking") && !keyword.equals("Horsemanship") && !keyword.equals("Infect") && !keyword.equals("Persist") && !keyword.equals("Phasing") && !keyword.equals("Shadow") && !keyword.equals("Skulk") && !keyword.equals("Undying") && !keyword.equals("Wither") && !keyword.equals("Bargain") && !keyword.equals("Mentor") && !keyword.equals("Training")) {
                        if (keyword.equals("Cascade")) {
                           if (printedKW.contains(keyword)) {
                              continue;
                           }

                           if (sb.length() != 0) {
                              sb.append("\r\n");
                           }

                           StringBuilder descStr = new StringBuilder(keyword);
                           int times = 0;

                           for(KeywordInterface keyw : keywords) {
                              String kw = keyw.getOriginal();
                              if (kw.equals(keyword)) {
                                 descStr.append(times == 0 ? "" : ", " + StringUtils.uncapitalize(keyword));
                                 ++times;
                              }
                           }

                           sb.append(descStr).append(" ").append(" (").append(inst.getReminderText()).append(")");
                           printedKW.add(keyword);
                        } else if (keyword.startsWith("Offering")) {
                           String type = keyword.split(":")[1];
                           if (sb.length() != 0) {
                              sb.append("\r\n");
                           }

                           sbLong.append(type).append(" offering");
                           sbLong.append(" (").append(inst.getReminderText()).append(")");
                        } else if (!keyword.startsWith("Equip") && !keyword.startsWith("Fortify") && !keyword.startsWith("Unearth") && !keyword.startsWith("Scavenge") && !keyword.startsWith("Spectacle") && !keyword.startsWith("Evoke") && !keyword.startsWith("Bestow") && !keyword.startsWith("Surge") && !keyword.startsWith("Transmute") && !keyword.startsWith("Suspend") && !keyword.startsWith("Dash") && !keyword.startsWith("Disturb") && !keyword.equals("Undaunted") && !keyword.startsWith("Cycling") && !keyword.startsWith("TypeCycling") && !keyword.startsWith("Embalm") && !keyword.equals("Prowess") && !keyword.startsWith("Strive") && !keyword.startsWith("Escalate") && !keyword.startsWith("Eternalize") && !keyword.startsWith("Reinforce") && !keyword.startsWith("Outlast") && !keyword.startsWith("Champion") && !keyword.startsWith("Freerunning") && !keyword.startsWith("Prowl") && !keyword.startsWith("Amplify") && !keyword.startsWith("Ninjutsu") && !keyword.startsWith("Chapter") && !keyword.startsWith("Transfigure") && !keyword.startsWith("Aura swap") && !keyword.startsWith("ETBReplacement") && !keyword.startsWith("Encore") && !keyword.startsWith("Mutate") && !keyword.startsWith("Dungeon") && !keyword.startsWith("Class") && !keyword.startsWith("Blitz") && !keyword.startsWith("Web-slinging") && !keyword.startsWith("Specialize") && !keyword.equals("Ravenous") && !keyword.startsWith("Firebending") && !keyword.equals("For Mirrodin") && !keyword.equals("Job select") && !keyword.startsWith("Craft") && !keyword.startsWith("Landwalk") && !keyword.startsWith("Visit") && !keyword.startsWith("Mobilize") && !keyword.startsWith("Station") && !keyword.startsWith("Warp") && !keyword.startsWith("Devour") && !keyword.startsWith("Affinity")) {
                           if (keyword.equals("Read ahead")) {
                              sb.append(Localizer.getInstance().getMessage("lblReadAhead")).append(" (").append(Localizer.getInstance().getMessage("lblReadAheadDesc"));
                              sb.append(" ").append(Localizer.getInstance().getMessage("lblSagaFooter")).append(" ").append(TextUtil.toRoman(this.getFinalChapterNr())).append(".");
                              sb.append(")").append("\r\n\r\n");
                           } else if (keyword.startsWith("Backup")) {
                              if (printedKW.contains("Backup")) {
                                 continue;
                              }

                              boolean plural = false;
                              StringBuilder descStr = new StringBuilder("Backup ");
                              int times = 0;

                              for(KeywordInterface keyw : keywords) {
                                 String kw = keyw.getOriginal();
                                 if (kw.startsWith("Backup")) {
                                    String[] k = keyword.split(":");
                                    String magnitude = k[1];
                                    if (times == 0 && k[2].endsWith("s")) {
                                       plural = true;
                                    }

                                    descStr.append(times == 0 ? magnitude : ", backup " + magnitude);
                                    ++times;
                                 }
                              }

                              sb.append(descStr).append(" ").append(" (");
                              String remStr = inst.getReminderText();
                              if (plural) {
                                 remStr = remStr.replace("ability", "abilities");
                              }

                              sb.append(remStr).append(times > 1 ? " Each backup ability triggers separately." : "").append(")");
                              printedKW.add("Backup");
                           } else if (keyword.startsWith("MayEffectFromOpening")) {
                              String[] k = keyword.split(":");
                              String desc = (String)AbilityFactory.getMapParams(this.getSVar(k[1])).get("SpellDescription");
                              sbLong.append(desc);
                           } else if (keyword.endsWith(".")) {
                              sbLong.append(keyword).append("\r\n");
                           } else {
                              if (keyword.contains("Strike")) {
                                 keyword = keyword.replace("Strike", "strike");
                              }

                              sb.append(i != 0 && sb.length() != 0 ? ", " : "");
                              sb.append(i > 0 && sb.length() != 0 ? StringUtils.uncapitalize(keyword) : keyword);
                           }
                        }
                     } else {
                        if (sb.length() != 0) {
                           sb.append("\r\n");
                        }

                        sb.append(keyword);
                        if (!printedKW.contains(keyword)) {
                           sb.append(" (").append(inst.getReminderText()).append(")");
                           printedKW.add(keyword);
                        }
                     }
                  } else {
                     sbLong.append(inst.getTitle()).append(" (").append(inst.getReminderText()).append(")");
                  }
               } else {
                  String[] k = keyword.split(":");
                  sbLong.append(k[0]);
                  if (k.length > 1) {
                     Cost mCost;
                     if (!"ManaCost".equals(k[1])) {
                        mCost = new Cost(k[1], true);
                     } else {
                        ManaCost cost;
                        if (keyword.startsWith("Miracle") && k.length > 2) {
                           ManaCostBeingPaid mcbp = new ManaCostBeingPaid(this.getManaCost());
                           mcbp.decreaseGenericMana(Integer.valueOf(k[2]));
                           cost = mcbp.toManaCost();
                        } else {
                           cost = this.getManaCost();
                        }

                        mCost = new Cost(cost, true);
                     }

                     if (mCost.isOnlyManaCost()) {
                        sbLong.append(" ");
                     } else {
                        sbLong.append("—");
                     }

                     if (keyword.startsWith("Reconfigure") && k.length > 2) {
                        String[] altCost = (new Cost(k[2], true)).toString().split(" ");
                        sbLong.append("—").append(altCost[0]).append(" ").append(mCost.toString()).append(" or ").append(altCost[1]);
                     } else {
                        sbLong.append(mCost.toString());
                        if (!mCost.isOnlyManaCost()) {
                           sbLong.append(".");
                        }

                        if (k.length > 3) {
                           sbLong.append(". ").append(k[3]);
                        }
                     }

                     sbLong.append(" (").append(inst.getReminderText()).append(")");
                     sbLong.append("\r\n");
                  }
               }
            } else {
               sbLong.append(inst.getTitle()).append(" (").append(inst.getReminderText()).append(")");
               sbLong.append("\r\n");
            }

            if (sbLong.length() > 0) {
               sbLong.append("\r\n");
            }

            if (!keyword.equals("Flash") && !keyword.startsWith("Backup")) {
               ++i;
            } else {
               sb.append("\r\n\r\n");
               i = 0;
            }
         } catch (Exception e) {
            String msg = "Card:keywordToText: crash in Keyword parsing";
            Breadcrumb bread = new Breadcrumb(msg);
            bread.setData("Card", this.getName());
            bread.setData("Keyword", keyword);
            Sentry.addBreadcrumb(bread);
            throw new RuntimeException("Error in Card " + this.getName() + " with Keyword " + keyword, e);
         }
      }

      if (sb.length() > 0) {
         sb.append("\r\n");
         if (sbLong.length() > 0) {
            sb.append("\r\n");
         }
      }

      if (sbLong.length() > 0) {
         sbLong.append("\r\n");
      }

      sb.append(sbLong);
      return CardTranslation.translateMultipleDescriptionText(sb.toString(), this);
   }

   private String kickerDesc(String keyword, String remText) {
      StringBuilder sbx = new StringBuilder();
      String[] n = keyword.split(":");
      Cost cost = new Cost(n[1], false);
      String costStr = cost.toSimpleString();
      boolean manaOnly = cost.isOnlyManaCost();
      sbx.append("Kicker").append(manaOnly ? " " + costStr : "—" + costStr + ".");
      if (Lists.newArrayList(n).size() > 2) {
         sbx.append(" and/or ");
         Cost cost2 = new Cost(n[2], false);
         sbx.append(cost2.toSimpleString());
      }

      if (!manaOnly) {
         if (cost.hasNoManaCost()) {
            remText = remText.replaceFirst(" pay an additional", "");
            remText = remText.replace(remText.charAt(8), Character.toLowerCase(remText.charAt(8)));
         } else {
            remText = remText.replaceFirst(" an additional", "");
            char c = remText.charAt(remText.indexOf(",") + 2);
            remText = remText.replace(c, Character.toLowerCase(c));
            remText = remText.replaceFirst(", ", " and ");
         }

         remText = remText.replaceFirst("as", "in addition to any other costs as");
         if (remText.contains(" tap ")) {
            if (remText.contains("tap a")) {
               String noun = remText.substring(remText.indexOf("untapped") + 9, remText.indexOf(" in "));
               remText = remText.replace(remText.substring(12, remText.indexOf(" in ")), Lang.nounWithNumeralExceptOne(1, noun) + " ");
            } else {
               remText = remText.replaceFirst(" untapped ", "");
            }
         }
      }

      sbx.append(" (").append(remText).append(")\r\n");
      return sbx.toString();
   }

   public String getAbilityText() {
      return this.getAbilityText(this.currentState);
   }

   public String getAbilityText(CardState state) {
      String linebreak = "\r\n\r\n";
      boolean useGrayTag = true;
      if (this.getGame() != null) {
         useGrayTag = this.game.getRules().useGrayText();
      }

      String grayTag = useGrayTag ? "<span style=\"color:gray;\">" : "";
      String endTag = useGrayTag ? "</span>" : "";
      CardTypeView type = state.getType();
      StringBuilder sb = new StringBuilder();
      if (this.plotted) {
         sb.append("Plotted\r\n");
      }

      if (!type.isInstant() && !type.isSorcery()) {
         if (type.hasSubtype("Class")) {
            sb.append("(Gain the next level as a sorcery to add its ability.)").append("\r\n\r\n");
         }

         if (state.getStateName().equals(CardStateName.Backside) && state.getCard().isTransformable() && state.getView().getOracleText().startsWith("(Transforms")) {
            sb.append("(").append(Localizer.getInstance().getMessage("lblTransformsFrom", CardTranslation.getTranslatedName(state.getCard().getState(CardStateName.Original).getName())));
            sb.append(")").append("\r\n\r\n");
         }

         if (type.hasSubtype("Saga") && !state.hasKeyword(Keyword.READ_AHEAD) && state.getFinalChapterNr() > 0) {
            sb.append("(").append(Localizer.getInstance().getMessage("lblSagaHeader"));
            if (!state.getCard().isTransformable()) {
               sb.append(" ").append(Localizer.getInstance().getMessage("lblSagaFooter")).append(" ").append(TextUtil.toRoman(state.getFinalChapterNr())).append(".");
            }

            sb.append(")").append("\r\n\r\n");
         }

         SpellAbility first = state.getFirstAbility();
         if (first != null && type.isPermanent() && first.isSpell()) {
            Cost cost = first.getPayCosts();
            if (cost != null && !cost.isOnlyManaCost()) {
               String additionalDesc = "";
               if (first.hasParam("AdditionalDesc")) {
                  additionalDesc = first.getParam("AdditionalDesc");
               }

               sb.append(cost.toString().replace("\n", "")).append(" ").append(additionalDesc);
               sb.append("\r\n\r\n");
            }
         }

         if (this.monstrous) {
            sb.append("Monstrous\r\n");
         }

         if (this.harnessed) {
            sb.append("Harnessed\r\n");
         }

         if (this.renowned) {
            sb.append("Renowned\r\n");
         }

         if (this.solved) {
            sb.append("Solved\r\n");
         }

         if (this.saddled) {
            sb.append("Saddled\r\n");
         }

         if (this.isSuspected()) {
            sb.append("Suspected\r\n");
         }

         if (this.isManifested()) {
            sb.append("Manifested\r\n");
         }

         if (this.isCloaked()) {
            sb.append("Cloaked\r\n");
         }

         if (this.isPrepared()) {
            sb.append("Prepared\r\n");
         }

         String keywordText = this.keywordsToText(this.getUnhiddenKeywords(state).getValues());
         sb.append(keywordText).append(keywordText.length() > 0 ? "\r\n\r\n" : "");
         if (this.getRules() != null) {
            for(DeckRule rule : DeckRule.parseAll(this.getRules().getDeckRules())) {
               String desc = rule.getDescription();
               if (!desc.isEmpty()) {
                  sb.append(desc).append("\r\n\r\n");
               }
            }
         }

         StringBuilder replacementEffects = new StringBuilder();

         for(ReplacementEffect replacementEffect : state.getReplacementEffects()) {
            if (!replacementEffect.isSecondary() && !replacementEffect.isClassAbility()) {
               String text = replacementEffect.getDescription();
               if (replacementEffect.hasParam("Description") && replacementEffect.getParam("Description").contains("enters")) {
                  sb.append(text).append("\r\n\r\n");
               } else {
                  replacementEffects.append(text).append("\r\n\r\n");
               }
            }
         }

         if (this.getRules() != null && state.getStateName().equals(CardStateName.Original)) {
            boolean hasMeldEffect = this.hasSVar("Meld") || state.getNonManaAbilities().anyMatch(SpellAbilityPredicates.isApi(ApiType.Meld));
            String meld = this.getRules().getMeldWith();
            if (meld != "" && !hasMeldEffect) {
               sb.append("\r\n");
               sb.append("(Melds with ").append(meld).append(".)");
               sb.append("\r\n");
            }
         }

         sb.append(this.text.replaceAll("\\\\r\\\\n", "\r\n"));
         sb.append("\r\n\r\n");

         for(Trigger trig : state.getTriggers()) {
            if (!trig.isSecondary() && !trig.isClassAbility()) {
               boolean disabled;
               if (type.isDungeon()) {
                  disabled = !trig.getOverridingAbility().getParam("RoomName").equals(this.getCurrentRoom());
               } else {
                  disabled = this.getGame() != null && !trig.requirementsCheck(this.getGame());
               }

               String trigStr = trig.replaceAbilityText(trig.toString(), state);
               if (disabled) {
                  sb.append(grayTag);
               }

               sb.append(trigStr.replaceAll("\\\\r\\\\n", "\r\n"));
               if (disabled) {
                  sb.append(endTag);
               }

               sb.append("\r\n\r\n");
            }
         }

         sb.append(replacementEffects);

         for(StaticAbility stAb : state.getStaticAbilities()) {
            if (!stAb.isSecondary() && !stAb.isClassAbility()) {
               String stAbD = stAb.toString();
               if (!stAbD.isEmpty()) {
                  boolean disabled = this.getGame() != null && this.getController() != null && this.game.getAge() != GameStage.Play && !stAb.checkConditions();
                  if (disabled) {
                     sb.append(grayTag);
                  }

                  sb.append(stAbD);
                  if (disabled) {
                     sb.append(endTag);
                  }

                  sb.append("\r\n\r\n");
               }
            }
         }

         List<String> addedManaStrings = Lists.newArrayList();

         for(SpellAbility sa : state.getSpellAbilities()) {
            if (sa != null && !sa.isSecondary() && !sa.isClassAbility() && !sa.isCastFaceDown()) {
               String sAbility = this.formatSpellAbility(sa);
               if (!sa.isSpell() || !sa.isBasicSpell()) {
                  if (sa.hasParam("DescriptionFromChosenName") && !this.getNamedCard().isEmpty()) {
                     String name = this.getNamedCard();
                     ICardFace namedFace = StaticData.instance().getCommonCards().getFaceByName(name);
                     StringBuilder sbSA = new StringBuilder(sAbility);
                     sbSA.append("\r\n\r\n");
                     sbSA.append(Localizer.getInstance().getMessage("lblSpell"));
                     sbSA.append(" — ");
                     if (!namedFace.getManaCost().isNoCost()) {
                        sbSA.append(namedFace.getManaCost().getSimpleString()).append(": ");
                     }

                     sbSA.append(namedFace.getName()).append("\r\n");
                     sbSA.append(namedFace.getType()).append("\r\n");
                     sbSA.append(namedFace.getOracleText().replaceAll("\\\\n", "\r\n"));
                     sbSA.append("\r\n\r\n");
                     sAbility = sbSA.toString();
                  }

                  if (sa.getManaPart() != null) {
                     if (addedManaStrings.contains(sAbility)) {
                        continue;
                     }

                     addedManaStrings.add(sAbility);
                  }

                  boolean alwaysShow = false;
                  if (!sa.isIntrinsic()) {
                     alwaysShow = true;
                  }

                  if (!sAbility.endsWith(state.getName() + "\r\n") || alwaysShow) {
                     sb.append(sAbility);
                     sb.append("\r\n");
                  }
               }
            }
         }

         if (this.game != null && this.isCreature() && this.isInPlay()) {
            for(Card ca : this.game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
               if (!this.equals(ca)) {
                  for(StaticAbility stAb : ca.getStaticAbilities()) {
                     if (stAb.checkConditions()) {
                        boolean found = false;
                        if (stAb.checkMode(StaticAbilityMode.CantBlockBy)) {
                           if (!stAb.hasParam("ValidAttacker") || stAb.hasParam("ValidBlocker") && stAb.getParam("ValidBlocker").equals("Creature.Self")) {
                              continue;
                           }

                           if (stAb.matchesValidParam("ValidAttacker", this)) {
                              found = true;
                           }
                        } else if (stAb.checkMode(StaticAbilityMode.MinMaxBlocker) && stAb.matchesValidParam("ValidCard", this)) {
                           found = true;
                        }

                        if (found) {
                           Card host = stAb.getHostCard();
                           String currentName = host.getName();
                           String desc = TextUtil.fastReplace(stAb.toString(), "CARDNAME", currentName);
                           desc = TextUtil.fastReplace(desc, "NICKNAME", Lang.getInstance().getNickName(currentName));
                           if (host.getEffectSource() != null) {
                              desc = TextUtil.fastReplace(desc, "EFFECTSOURCE", host.getEffectSource().getName());
                           }

                           sb.append(desc);
                           sb.append("\r\n\r\n");
                        }
                     }
                  }
               }
            }
         }

         if (this.isClassCard()) {
            sb.append("\r\n\r\n");

            for(int level = 1; level <= 3; ++level) {
               boolean disabled = level > this.getClassLevel() && this.isInPlay();

               for(StaticAbility st : state.getStaticAbilities()) {
                  if (st.isClassLevelNAbility(level) && !st.isSecondary()) {
                     if (disabled) {
                        sb.append(grayTag);
                     }

                     sb.append(st.toString());
                     if (disabled) {
                        sb.append(endTag);
                     }

                     sb.append("\r\n\r\n");
                  }
               }

               for(SpellAbility sa : state.getSpellAbilities()) {
                  if (sa.isClassLevelNAbility(level) && !sa.isSecondary()) {
                     sb.append(sa.toString()).append("\r\n\r\n");
                  }
               }
            }
         }

         if (sb.toString().contains(" (NOTE: ")) {
            sb.insert(sb.indexOf("(NOTE: "), "\r\n");
         }

         if (sb.toString().contains("(NOTE: ") && sb.toString().contains(".) ")) {
            sb.insert(sb.indexOf(".) ") + 3, "\r\n");
         }

         if (this.isGoaded()) {
            sb.append("is goaded by: ").append(Lang.joinHomogenous(this.getGoaded()));
            sb.append("\r\n");
         }

         String s = "\r\n\r\n\r\n";

         for(int start = sb.lastIndexOf("\r\n\r\n\r\n"); start != -1; start = sb.lastIndexOf("\r\n\r\n\r\n")) {
            sb.replace(start, start + 4, "\r\n");
         }

         String desc = TextUtil.fastReplace(sb.toString(), "CARDNAME", CardTranslation.getTranslatedName(state.getName()));
         if (this.getEffectSource() != null) {
            desc = TextUtil.fastReplace(desc, "EFFECTSOURCE", this.getEffectSource().getName());
         }

         desc = desc.replace("\\r", "\r").replace("\\n", "\n");
         return desc.trim();
      } else {
         sb.append(this.abilityTextInstantSorcery(state));
         if (this.haunting != null) {
            sb.append("Haunting: ").append(this.haunting);
            sb.append("\r\n");
         }

         String result;
         for(result = sb.toString(); result.endsWith("\r\n"); result = result.substring(0, result.length() - 2)) {
         }

         return TextUtil.fastReplace(result, "CARDNAME", CardTranslation.getTranslatedName(state.getName()));
      }
   }

   private StringBuilder abilityTextInstantSorcery(CardState state) {
      StringBuilder sb = new StringBuilder();
      String spellText = this.text.replaceAll("\\\\r\\\\n", "\r\n");
      sb.append(spellText);
      if (spellText.contains(" (NOTE: ")) {
         sb.insert(sb.indexOf("(NOTE: "), "\r\n");
      }

      if (spellText.contains("(NOTE: ") && spellText.endsWith(".)") && !spellText.endsWith("\r\n")) {
         sb.append("\r\n");
      }

      StringBuilder sbSpell = new StringBuilder();

      for(SpellAbility element : state.getSpellAbilities()) {
         if (!element.isSecondary()) {
            sbSpell.append(this.formatSpellAbility(element));
         }
      }

      String strSpell = sbSpell.toString();
      StringBuilder sbBefore = new StringBuilder();
      StringBuilder sbAfter = new StringBuilder();

      for(KeywordInterface inst : this.getKeywords(state)) {
         String keyword = inst.getOriginal();

         try {
            if (!keyword.equals("Ascend") && !keyword.equals("Changeling") && !keyword.equals("Aftermath") && !keyword.equals("Wither") && !keyword.equals("Convoke") && !keyword.equals("Delve") && !keyword.equals("Improvise") && !keyword.equals("Retrace") && !keyword.equals("Undaunted") && !keyword.equals("Cascade") && !keyword.equals("Devoid") && !keyword.equals("Lifelink") && !keyword.equals("Bargain") && !keyword.equals("Spree") && !keyword.equals("Tiered") && !keyword.equals("Split second")) {
               if (!keyword.equals("Conspire") && !keyword.equals("Epic") && !keyword.equals("Suspend") && !keyword.equals("Jump-start") && !keyword.equals("Fuse") && !keyword.equals("Paradigm")) {
                  if (keyword.startsWith("Casualty")) {
                     String[] k = keyword.split(":");
                     sbBefore.append("Casualty ").append(k[1]);
                     if (k.length >= 4) {
                        sbBefore.append(". ").append(k[3]);
                     }

                     sbBefore.append(" (").append(inst.getReminderText()).append(")").append("\r\n\r\n");
                  } else if (!keyword.startsWith("Dredge") && !keyword.startsWith("Ripple")) {
                     if (keyword.startsWith("Starting intensity")) {
                        sbAfter.append(TextUtil.fastReplace(keyword, ":", " ")).append("\r\n");
                     } else if (!keyword.startsWith("Escalate") && !keyword.startsWith("Buyback") && !keyword.startsWith("Freerunning") && !keyword.startsWith("Prowl") && !keyword.startsWith("Sneak") && !keyword.startsWith("Cleave")) {
                        if (keyword.startsWith("Multikicker")) {
                           String[] n = keyword.split(":");
                           Cost cost = new Cost(n[1], false);
                           sbBefore.append("Multikicker ").append(cost.toSimpleString()).append(" (").append(inst.getReminderText()).append(")").append("\r\n");
                        } else if (keyword.startsWith("Kicker")) {
                           sbBefore.append(this.kickerDesc(keyword, inst.getReminderText())).append("\r\n");
                        } else if (keyword.startsWith("AlternateAdditionalCost")) {
                           String[] costs = keyword.split(":", 2)[1].split(":");
                           sbBefore.append("As an additional cost to cast this spell, ");

                           for(int n = 0; n < costs.length; ++n) {
                              Cost cost = new Cost(costs[n], false);
                              if (cost.isOnlyManaCost()) {
                                 sbBefore.append(" pay ");
                              }

                              sbBefore.append(StringUtils.uncapitalize(cost.toSimpleString()));
                              sbBefore.append(n + 1 == costs.length ? ".\r\n\r\n" : (n + 2 == costs.length && costs.length > 2 ? ", or " : (n + 2 == costs.length ? " or " : ", ")));
                           }
                        } else if (keyword.startsWith("MayFlash")) {
                           sbBefore.append(inst.getReminderText());
                           sbBefore.append("\r\n");
                        } else if (!keyword.startsWith("Entwine") && !keyword.startsWith("Madness") && !keyword.startsWith("Miracle") && !keyword.startsWith("Recover") && !keyword.startsWith("Escape") && !keyword.startsWith("Foretell:") && !keyword.startsWith("Disturb") && !keyword.startsWith("Overload") && !keyword.startsWith("Plot") && !keyword.startsWith("Mayhem") && !keyword.startsWith("Splice")) {
                           if (keyword.equals("Gift")) {
                              sbBefore.append(keyword);
                              if (state.getFirstAbility().hasAdditionalAbility("GiftAbility")) {
                                 sbBefore.append(" ").append(state.getFirstAbility().getAdditionalAbility("GiftAbility").getParam("GiftDescription"));
                              }

                              sbBefore.append("\r\n");
                           } else if (keyword.equals("Remove CARDNAME from your deck before playing if you're not playing for ante.")) {
                              sbBefore.append(keyword);
                              sbBefore.append("\r\n");
                           } else if (keyword.startsWith("Haunt")) {
                              sbAfter.append("Haunt (");
                              sbAfter.append("When this spell card is put into a graveyard after resolving, ");
                              sbAfter.append("exile it haunting target creature.");
                              sbAfter.append(")");
                              sbAfter.append("\r\n");
                           } else if (keyword.equals("Storm")) {
                              sbAfter.append("Storm (");
                              sbAfter.append("When you cast this spell, copy it for each spell cast before it this turn.");
                              if (strSpell.contains("Target") || strSpell.contains("target")) {
                                 sbAfter.append(" You may choose new targets for the copies.");
                              }

                              sbAfter.append(")");
                              sbAfter.append("\r\n");
                           } else if (!keyword.startsWith("Replicate")) {
                              if (keyword.equals("Assist")) {
                                 sbBefore.append(keyword).append(" (").append(String.format(inst.getReminderText(), "{" + this.getManaCost().getGenericCost() + "}")).append(")");
                                 sbBefore.append("\r\n\r\n");
                              } else if (keyword.startsWith("DeckLimit")) {
                                 String[] k = keyword.split(":");
                                 sbBefore.append(k[2]).append("\r\n");
                              }
                           } else {
                              String[] n = keyword.split(":");
                              Cost cost = new Cost(n[1], false);
                              sbBefore.append("Replicate ").append(cost.toSimpleString());
                              sbBefore.append(" (When you cast this spell, copy it for each time you paid its replicate cost.");
                              if (strSpell.contains("Target") || strSpell.contains("target")) {
                                 sbBefore.append(" You may choose new targets for the copies.");
                              }

                              sbBefore.append(")\r\n");
                           }
                        } else {
                           sbAfter.append(inst.getTitle()).append(" (").append(inst.getReminderText()).append(")");
                           sbAfter.append("\r\n");
                        }
                     } else {
                        sbBefore.append(inst.getTitle()).append(" (").append(inst.getReminderText()).append(")");
                        sbBefore.append("\r\n");
                     }
                  } else {
                     sbAfter.append(TextUtil.fastReplace(keyword, ":", " "));
                     sbAfter.append(" (").append(inst.getReminderText()).append(")").append("\r\n");
                  }
               } else {
                  sbAfter.append(keyword).append(" (").append(inst.getReminderText()).append(")");
                  sbAfter.append("\r\n");
               }
            } else {
               sbBefore.append(keyword).append(" (").append(inst.getReminderText()).append(")");
               sbBefore.append("\r\n\r\n");
            }
         } catch (Exception e) {
            String msg = "Card:abilityTextInstantSorcery: crash in Keyword parsing";
            Breadcrumb bread = new Breadcrumb(msg);
            bread.setData("Card", this.getName());
            bread.setData("Keyword", keyword);
            Sentry.addBreadcrumb(bread);
            throw new RuntimeException("Error in Card " + this.getName() + " with Keyword " + keyword, e);
         }
      }

      sb.append(CardTranslation.translateMultipleDescriptionText(sbBefore.toString(), state));
      sb.append(strSpell);

      for(Trigger trig : state.getTriggers()) {
         if (!trig.isSecondary()) {
            sb.append(trig.replaceAbilityText(trig.toString(), state)).append("\r\n");
         }
      }

      for(ReplacementEffect replacementEffect : state.getReplacementEffects()) {
         if (!replacementEffect.isSecondary()) {
            sb.append(replacementEffect.getDescription()).append("\r\n");
         }
      }

      for(StaticAbility stAb : state.getStaticAbilities()) {
         if (!stAb.isSecondary()) {
            String stAbD = stAb.toString();
            if (!stAbD.isEmpty()) {
               sb.append(stAbD).append("\r\n");
            }
         }
      }

      sb.append(CardTranslation.translateMultipleDescriptionText(sbAfter.toString(), state));
      return sb;
   }

   private String formatSpellAbility(SpellAbility sa) {
      StringBuilder sb = new StringBuilder();
      sb.append(sa.toString()).append("\r\n\r\n");
      return sb.toString();
   }

   public final boolean canProduceColorMana(Set<String> colors) {
      for(SpellAbility mana : this.getManaAbilities()) {
         if (mana.getApi() == ApiType.ManaReflected) {
            if (!Collections.disjoint(CardUtil.getReflectableManaColors(mana), colors)) {
               return true;
            }
         } else {
            for(String s : colors) {
               if (mana.canProduce(MagicColor.toShortString(s))) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   public final Set<String> getProducibleColors() {
      Set<String> colors = new HashSet();

      for(SpellAbility ab : this.getManaAbilities()) {
         if (ab.getApi() == ApiType.ManaReflected) {
            colors.addAll(CardUtil.getReflectableManaColors(ab));
         } else {
            colors = CardUtil.canProduce(6, ab, colors);
         }

         if (colors.size() == MagicColor.Constant.COLORS_AND_COLORLESS.size()) {
            break;
         }
      }

      return colors;
   }

   public final boolean canProduceSameManaTypeWith(Card c) {
      return this.getManaAbilities().isEmpty() ? false : this.canProduceColorMana(c.getProducibleColors());
   }

   public final int getMaxManaProduced() {
      int max_produced = 0;

      for(SpellAbility m : this.getManaAbilities()) {
         m.setActivatingPlayer(this.getController());
         int mana_cost = m.getPayCosts().getTotalMana().getCMC();
         max_produced = Math.max(max_produced, m.amountOfManaGenerated(true) - mana_cost);
      }

      return max_produced;
   }

   public final SpellAbility getFirstSpellAbility() {
      return (SpellAbility)Iterables.getFirst(this.currentState.getNonManaAbilities(), (Object)null);
   }

   public final SpellPermanent getSpellPermanent() {
      for(SpellAbility sa : this.currentState.getNonManaAbilities()) {
         if (sa instanceof SpellPermanent) {
            return (SpellPermanent)sa;
         }
      }

      return null;
   }

   public final void addSpellAbility(SpellAbility a) {
      this.addSpellAbility(a, true);
   }

   public final void addSpellAbility(SpellAbility a, boolean updateView) {
      a.setHostCard(this);
      if (this.currentState.addSpellAbility(a) && updateView) {
         this.currentState.getView().updateAbilityText(this, this.currentState);
      }

   }

   public final FCollectionView<SpellAbility> getSpellAbilities() {
      return this.currentState.getSpellAbilities();
   }

   public final FCollectionView<SpellAbility> getManaAbilities() {
      return this.currentState.getManaAbilities();
   }

   public final FCollectionView<SpellAbility> getNonManaAbilities() {
      return this.currentState.getNonManaAbilities();
   }

   public final boolean hasSpellAbility(SpellAbility sa) {
      return this.currentState.hasSpellAbility(sa);
   }

   public final boolean hasSpellAbility(int id) {
      return this.currentState.hasSpellAbility(id);
   }

   public boolean hasRemoveIntrinsic() {
      return this.changedCardTypes.isEmpty() ? false : this.changedCardTypes.values().stream().anyMatch(ICardChangedType::isRemoveLandTypes);
   }

   public boolean hasNoAbilities() {
      if (!this.getUnhiddenKeywords().isEmpty()) {
         return false;
      } else if (!this.getStaticAbilities().isEmpty()) {
         return false;
      } else if (!this.currentState.getReplacementEffects(false).isEmpty()) {
         return false;
      } else if (!this.getTriggers().isEmpty()) {
         return false;
      } else {
         for(SpellAbility sa : this.getSpellAbilities()) {
            if (!sa.isLandAbility() && (!sa.isBasicSpell() || !sa.getPayCosts().isOnlyManaCost())) {
               return false;
            }
         }

         return true;
      }
   }

   public void updateSpellAbilities(List<SpellAbility> list, CardState state) {
      for(ICardTraitChanges ck : this.getChangedCardTraitsList(state)) {
         ck.applySpellAbility(list);
      }

      this.getUnhiddenKeywords(state).applySpellAbility(list);
   }

   public final FCollectionView<SpellAbility> getAllSpellAbilities() {
      FCollection<SpellAbility> res = new FCollection<SpellAbility>();

      for(CardStateName key : this.states.keySet()) {
         res.addAll(this.getState(key).getNonManaAbilities());
         res.addAll(this.getState(key).getManaAbilities());
      }

      return res;
   }

   public final FCollectionView<SpellAbility> getSpells() {
      FCollection<SpellAbility> res = new FCollection<SpellAbility>();

      for(SpellAbility sa : this.currentState.getNonManaAbilities()) {
         if (sa.isSpell()) {
            res.add(sa);
         }
      }

      return res;
   }

   public final int getShieldCount() {
      return this.shieldCount;
   }

   public final void incShieldCount() {
      ++this.shieldCount;
      this.view.updateShieldCount(this);
   }

   public final void decShieldCount() {
      --this.shieldCount;
      this.view.updateShieldCount(this);
   }

   public final void resetShieldCount() {
      this.shieldCount = 0;
      this.view.updateShieldCount(this);
   }

   public final void addRegeneratedThisTurn() {
      ++this.regeneratedThisTurn;
   }

   public final int getRegeneratedThisTurn() {
      return this.regeneratedThisTurn;
   }

   public final void setRegeneratedThisTurn(int n) {
      this.regeneratedThisTurn = n;
   }

   public final boolean canBeShielded() {
      return !StaticAbilityCantRegenerate.cantRegenerate(this);
   }

   public final void updateTokenView() {
      this.view.updateToken(this);
   }

   public final boolean isTokenCard() {
      return this.isInPlay() && this.hasMergedCard() ? this.getTopMergedCard().tokenCard : this.tokenCard;
   }

   public final void setTokenCard(boolean tokenC) {
      if (this.tokenCard != tokenC) {
         this.tokenCard = tokenC;
         this.view.updateTokenCard(this);
      }
   }

   public final void setCollectible(boolean collectible) {
      this.collectible = collectible;
   }

   public final boolean isCollectible() {
      return this.collectible;
   }

   public void updateWasDestroyed(boolean value) {
      this.view.updateWasDestroyed(value);
   }

   public final Card getCopiedPermanent() {
      return this.copiedPermanent;
   }

   public final void setCopiedPermanent(Card c) {
      if (this.copiedPermanent != c) {
         this.copiedPermanent = c;
         if (c != null) {
            this.currentState.setOracleText(c.getOracleText());
            this.currentState.setFunctionalVariantName(c.getCurrentState().getFunctionalVariantName());
         }

      }
   }

   public final void addUntapCommand(GameCommand c) {
      this.untapCommandList.add(c);
   }

   public final void addUnattachCommand(GameCommand c) {
      this.unattachCommandList.add(c);
   }

   public final void addFaceupCommand(GameCommand c) {
      this.faceupCommandList.add(c);
   }

   public final void addFacedownCommand(GameCommand c) {
      this.facedownCommandList.add(c);
   }

   public final void addChangeControllerCommand(GameCommand c) {
      this.changeControllerCommandList.add(c);
   }

   public final void addPhaseOutCommand(GameCommand c) {
      this.phaseOutCommandList.add(c);
   }

   public final void addLeavesPlayCommand(GameCommand c) {
      this.leavePlayCommandList.add(c);
   }

   public void addStaticCommandList(Object[] objects) {
      this.staticCommandList.add(objects);
      this.bumpTraitEpoch(); // Forge Nova: GameAction's static scan lists cards with static commands
   }

   public List<Object[]> getStaticCommandList() {
      return this.staticCommandList;
   }

   public final List<GameCommand> getLeavesPlayCommands() {
      return this.leavePlayCommandList;
   }

   public final void setLeavesPlayCommands(List<GameCommand> list) {
      this.leavePlayCommandList = list;
   }

   public final void runLeavesPlayCommands() {
      for(GameCommand c : this.leavePlayCommandList) {
         c.run();
      }

      this.leavePlayCommandList.clear();
   }

   public final void runUntapCommands() {
      for(GameCommand c : this.untapCommandList) {
         c.run();
      }

      this.untapCommandList.clear();
   }

   public final void runUnattachCommands() {
      for(GameCommand c : this.unattachCommandList) {
         c.run();
      }

      this.unattachCommandList.clear();
   }

   public final void runFaceupCommands() {
      for(GameCommand c : this.faceupCommandList) {
         c.run();
      }

      this.faceupCommandList.clear();
   }

   public final void runFacedownCommands() {
      for(GameCommand c : this.facedownCommandList) {
         c.run();
      }

      this.facedownCommandList.clear();
   }

   public final void runChangeControllerCommands() {
      for(GameCommand c : this.changeControllerCommandList) {
         c.run();
      }

      this.changeControllerCommandList.clear();
   }

   public final void runPhaseOutCommands() {
      for(GameCommand c : this.phaseOutCommandList) {
         c.run();
      }

      this.phaseOutCommandList.clear();
   }

   public final void setSickness(boolean sickness0) {
      if (this.sickness != sickness0) {
         this.sickness = sickness0;
         this.view.updateSickness(this);
      }
   }

   public final boolean hasSickness() {
      return this.sickness && !this.hasKeyword(Keyword.HASTE);
   }

   public final boolean isSick() {
      return this.hasSickness() && this.isCreature();
   }

   public final boolean isFirstTurnControlled() {
      return this.sickness;
   }

   public boolean hasBecomeTargetThisTurn() {
      return !this.targetedFromThisTurn.isEmpty();
   }

   public void addTargetFromThisTurn(Player p) {
      this.targetedFromThisTurn.add(p);
   }

   public boolean isValiant(Player p) {
      return this.getController().equals(p) && !this.targetedFromThisTurn.contains(p);
   }

   public boolean hasStartedTheTurnUntapped() {
      return this.startedTheTurnUntapped;
   }

   public void setStartedTheTurnUntapped(boolean untapped) {
      this.startedTheTurnUntapped = untapped;
   }

   public boolean cameUnderControlSinceLastUpkeep() {
      return this.cameUnderControlSinceLastUpkeep;
   }

   public void setCameUnderControlSinceLastUpkeep(boolean underControlSinceLastUpkeep) {
      this.cameUnderControlSinceLastUpkeep = underControlSinceLastUpkeep;
   }

   public final Player getOwner() {
      return this.owner;
   }

   public final void setOwner(Player owner0) {
      if (this.owner != owner0) {
         if (this.owner != null && this.owner.getGame() != this.getGame()) {
            throw new RuntimeException();
         } else {
            this.owner = owner0;
            this.view.updateOwner(this);
            this.view.updateController(this);
         }
      }
   }

   public final Player getController() {
      Map.Entry<Long, Player> lastEntry = this.tempControllers.lastEntry();
      if (lastEntry != null) {
         long lastTimestamp = (Long)lastEntry.getKey();
         if (lastTimestamp > this.controllerTimestamp) {
            return (Player)lastEntry.getValue();
         }
      }

      return this.controller != null ? this.controller : this.owner;
   }

   public final void setController(Player player, long tstamp) {
      this.tempControllers.clear();
      this.controller = player;
      this.controllerTimestamp = tstamp;
      this.view.updateController(this);
   }

   public final void addTempController(Player player, long tstamp) {
      this.tempControllers.put(tstamp, player);
      this.view.updateController(this);
   }

   public final void removeTempController(long tstamp) {
      if (this.tempControllers.remove(tstamp) != null) {
         this.view.updateController(this);
      }

   }

   public final void removeTempController(Player player) {
      boolean changed;
      for(changed = false; this.tempControllers.values().remove(player); changed = true) {
      }

      if (changed) {
         this.view.updateController(this);
      }

   }

   public final void clearTempControllers() {
      if (!this.tempControllers.isEmpty()) {
         this.tempControllers.clear();
         this.view.updateController(this);
      }
   }

   public final void clearControllers() {
      if (!this.tempControllers.isEmpty() || this.controller != null) {
         this.tempControllers.clear();
         this.controller = null;
         this.view.updateController(this);
      }
   }

   public boolean mayPlayerLook(Player player) {
      return this.view.mayPlayerLook(player.getView());
   }

   public final void addMayLookFaceDownExile(Player p) {
      this.mayLookFaceDownExile.add(p);
      this.updateMayLook();
   }

   public final void addMayLookAt(long timestamp, Iterable<Player> list) {
      PlayerCollection plist = new PlayerCollection(list);
      this.mayLook.put(timestamp, plist);
      if (this.isFaceDown() && this.isInZone(ZoneType.Exile)) {
         this.mayLookFaceDownExile.addAll(plist);
      }

      this.updateMayLook();
   }

   public final void removeMayLookAt(long timestamp) {
      if (this.mayLook.remove(timestamp) != null) {
         this.updateMayLook();
      }

   }

   public final void addMayLookTemp(Player player) {
      if (this.mayLookTemp.add(player)) {
         if (this.isFaceDown() && this.isInZone(ZoneType.Exile)) {
            this.mayLookFaceDownExile.add(player);
         }

         this.updateMayLook();
      }

   }

   public final void removeMayLookTemp(Player player) {
      if (this.mayLookTemp.remove(player)) {
         this.updateMayLook();
      }

   }

   public final void updateMayLook() {
      PlayerCollection result = new PlayerCollection();

      for(PlayerCollection v : this.mayLook.values()) {
         result.addAll(v);
      }

      result.addAll(this.mayLookFaceDownExile);
      result.addAll(this.mayLookTemp);
      this.getView().setPlayerMayLook(result);
   }

   public final CardPlayOption mayPlay(StaticAbility sta) {
      return sta == null ? null : (CardPlayOption)this.mayPlay.get(sta);
   }

   public final List<CardPlayOption> mayPlay(Player player) {
      List<CardPlayOption> result = Lists.newArrayList();

      for(CardPlayOption o : this.mayPlay.values()) {
         if (o.getPlayer().equals(player)) {
            result.add(o);
         }
      }

      return result;
   }

   public final void setMayPlay(Player player, boolean withoutManaCost, Cost altManaCost, boolean withFlash, boolean grantZonePermissions, StaticAbility sta) {
      this.mayPlay.put(sta, new CardPlayOption(player, sta, withoutManaCost, altManaCost, withFlash, grantZonePermissions));
   }

   public final void removeMayPlay(StaticAbility sta) {
      this.mayPlay.remove(sta);
   }

   public final Map<StaticAbility, CardPlayOption> getMayPlay() {
      return Maps.newHashMap(this.mayPlay);
   }

   public final Map<StaticAbility, CardPlayOption> setMayPlay(Map<StaticAbility, CardPlayOption> mp) {
      return this.mayPlay = mp;
   }

   public void resetMayPlayTurn() {
      for(StaticAbility sta : this.getStaticAbilities()) {
         sta.resetMayPlayTurn();
      }

   }

   public final CardCollectionView getEquippedBy() {
      return CardLists.filter(this.getAttachedCards(), Card::isEquipment);
   }

   public final boolean isEquipped() {
      return this.getAttachedCards().anyMatch(Card::isEquipment);
   }

   public final boolean isEquippedBy(Card c) {
      return this.hasCardAttachment(c);
   }

   public final boolean isEquippedBy(String cardName) {
      return this.hasCardAttachment(cardName);
   }

   public final CardCollectionView getFortifiedBy() {
      return CardLists.filter(this.getAttachedCards(), CardPredicates.FORTIFICATION);
   }

   public final boolean isFortified() {
      return this.getAttachedCards().anyMatch(CardPredicates.FORTIFICATION);
   }

   public final boolean isFortifiedBy(Card c) {
      return this.hasCardAttachment(c);
   }

   public final boolean isFortifying() {
      return this.isAttachedToEntity();
   }

   public final Card getEquipping() {
      return this.getAttachedTo();
   }

   public final boolean isEquipping() {
      return this.isAttachedToEntity();
   }

   public final GameEntity getEntityAttachedTo() {
      return this.entityAttachedTo;
   }

   public final void setEntityAttachedTo(GameEntity e) {
      if (this.entityAttachedTo != e) {
         this.entityAttachedTo = e;
         this.view.updateAttachedTo(this);
      }
   }

   public final void removeAttachedTo(GameEntity e) {
      if (this.entityAttachedTo == e) {
         this.setEntityAttachedTo((GameEntity)null);
      }

   }

   public final boolean isAttachedToEntity() {
      return this.entityAttachedTo != null;
   }

   public final boolean isAttachedToEntity(GameEntity e) {
      return this.entityAttachedTo == e;
   }

   public final Card getAttachedTo() {
      return this.entityAttachedTo instanceof Card ? (Card)this.entityAttachedTo : null;
   }

   public final Card getEnchantingCard() {
      return this.getAttachedTo();
   }

   public final Player getPlayerAttachedTo() {
      return this.entityAttachedTo instanceof Player ? (Player)this.entityAttachedTo : null;
   }

   public final boolean isEnchanting() {
      return this.isAttachedToEntity();
   }

   public final boolean isEnchantingCard() {
      return this.getEnchantingCard() != null;
   }

   public final void attachToEntity(GameEntity entity, SpellAbility sa) {
      this.attachToEntity(entity, sa, false);
   }

   public final void attachToEntity(GameEntity entity, SpellAbility sa, boolean overwrite) {
      if (overwrite || entity.canBeAttached(this, sa)) {
         GameEntity oldTarget = null;
         if (this.isAttachedToEntity()) {
            oldTarget = this.getEntityAttachedTo();
            if (oldTarget.equals(entity)) {
               return;
            }

            this.unattachFromEntity(oldTarget);
         }

         this.setEntityAttachedTo(entity);
         this.setLayerTimestamp(this.getGame().getNextTimestamp());
         entity.addAttachedCard(this);
         this.getGame().fireEvent(new GameEventCardAttachment(this, oldTarget, entity));
         Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
         repParams.put(AbilityKey.AttachTarget, entity);
         this.getGame().getReplacementHandler().run(ReplacementType.Attached, repParams);
         Map<AbilityKey, Object> runParams = AbilityKey.newMap();
         runParams.put(AbilityKey.AttachSource, this);
         runParams.put(AbilityKey.AttachTarget, entity);
         this.getController().getGame().getTriggerHandler().runTrigger(TriggerType.Attached, runParams, false);
         if (this.hasKeyword(Keyword.RECONFIGURE)) {
            Card eff = SpellAbilityEffect.createEffect(sa, sa.getActivatingPlayer(), "Reconfigure Effect", this.getImageKey());
            eff.setRenderForUI(false);
            eff.addRemembered(this);
            String s = "Mode$ Continuous | AffectedDefined$ RememberedCard | EffectZone$ Command | RemoveType$ Creature";
            eff.addStaticAbility(s);
            GameCommand until = () -> this.game.getAction().exileEffect(eff);
            this.addLeavesPlayCommand(until);
            this.addUnattachCommand(until);
            this.game.getAction().moveToCommand(eff, sa);
         }

      }
   }

   public final void unattachFromEntity(GameEntity entity) {
      this.unattachFromEntity(entity, entity);
   }

   public final void unattachFromEntity(GameEntity entity, GameEntity old) {
      if (this.entityAttachedTo != null && this.entityAttachedTo.equals(entity)) {
         if (!this.isPhasedOut()) {
            this.setEntityAttachedTo((GameEntity)null);
            entity.removeAttachedCard(this);
            this.unanimateBestow();
            this.getGame().fireEvent(new GameEventCardAttachment(this, entity, (GameEntity)null));
            Map<AbilityKey, Object> runParams = AbilityKey.newMap();
            runParams.put(AbilityKey.AttachSource, this);
            runParams.put(AbilityKey.Object, old);
            this.getGame().getTriggerHandler().runTrigger(TriggerType.Unattached, runParams, false);
            this.runUnattachCommands();
         }
      }
   }

   public final boolean isModified() {
      return !this.isEquipped() && !this.hasCounters() ? this.getEnchantedBy().anyMatch(CardPredicates.isController(this.getController())) : true;
   }

   public final void setType(CardType type0) {
      this.currentState.setType(type0);
   }

   public final void addType(String type0) {
      this.currentState.addType(type0);
   }

   public final void addType(Iterable<String> type0) {
      this.currentState.addType(type0);
   }

   public final void removeType(CardType.Supertype st) {
      this.currentState.removeType(st);
   }

   public final void setCreatureTypes(Collection<String> ctypes) {
      this.currentState.setCreatureTypes(ctypes);
   }

   public final CardTypeView getType() {
      return this.currentState.getTypeWithChanges();
   }

   public final CardTypeView getOriginalType() {
      return this.getOriginalType(this.currentState);
   }

   public final CardTypeView getOriginalType(CardState state) {
      return state.getType();
   }

   public Iterable<ICardChangedType> getChangedCardTypes() {
      return this.changedCardTypesByText.isEmpty() && this.changedCardTypesCharacterDefining.isEmpty() && this.changedCardTypes.isEmpty() ? ImmutableList.of() : ImmutableList.copyOf(Iterables.concat(this.changedCardTypesByText.values(), this.changedCardTypesCharacterDefining.values(), this.changedCardTypes.values()));
   }

   public boolean clearChangedCardTypes() {
      boolean changed = false;
      if (!this.changedCardTypesByText.isEmpty()) {
         changed = true;
      }

      this.changedCardTypesByText.clear();
      this.bumpTraitEpoch();
      if (!this.changedCardTypesCharacterDefining.isEmpty()) {
         changed = true;
      }

      this.changedCardTypesCharacterDefining.clear();
      this.bumpTraitEpoch();
      if (!this.changedCardTypes.isEmpty()) {
         changed = true;
      }

      this.changedCardTypes.clear();
      this.bumpTraitEpoch();
      this.updateTypeCache();
      return changed;
   }

   public boolean clearChangedCardColors() {
      boolean changed = this.hasChangedCardColors();
      this.changedCardColorsByText.clear();
      this.changedCardColorsCharacterDefining.clear();
      this.changedCardColors.clear();
      return changed;
   }

   public Table<Long, Long, IKeywordsChange> getChangedCardKeywordsByText() {
      this.bumpTraitEpoch();
      return this.changedCardKeywordsByText;
   }

   public Iterable<? extends IKeywordsChange> getChangedCardKeywordsList(CardState state) {
      return (Iterable<? extends IKeywordsChange>)(this.changedCardKeywordsByText.isEmpty() && this.changedCardKeywordsByWord.isEmpty() && this.changedCardKeywords.isEmpty() ? state.getLandTraitChanges() : Iterables.concat(this.changedCardKeywordsByText.values(), ImmutableList.of(this.changedCardKeywordsByWord), state.getLandTraitChanges(), this.changedCardKeywords.values()));
   }

   public Table<Long, Long, KeywordsChange> getChangedCardKeywords() {
      this.bumpTraitEpoch();
      return this.changedCardKeywords;
   }

   public void setChangedCardKeywords(Table<Long, Long, KeywordsChange> changedCardKeywords) {
      this.changedCardKeywords.clear();
      this.bumpTraitEpoch();

      for(Table.Cell<Long, Long, KeywordsChange> entry : changedCardKeywords.cellSet()) {
         this.changedCardKeywords.put((Long)entry.getRowKey(), (Long)entry.getColumnKey(), ((KeywordsChange)entry.getValue()).copy(this, true));
         this.bumpTraitEpoch();
      }

   }

   public final void addChangedCardTypesByText(CardTypeView addType, long timestamp, long staticId) {
      this.changedCardTypesByText.put(timestamp, staticId, new StateChangedType(addType));
      this.bumpTraitEpoch();
      this.updateTypeCache();
   }

   public final boolean removeChangedCardTypesByText(long timestamp, long staticId) {
      boolean removed = this.changedCardTypesByText.remove(timestamp, staticId) != null;
      this.bumpTraitEpoch();
      if (removed) {
         this.updateTypeCache();
      }

      return removed;
   }

   public final void addChangedCardTypes(CardType addType, CardType removeType, boolean addAllCreatureTypes, Set<RemoveType> remove, long timestamp, long staticId, boolean updateView, boolean cda) {
      (cda ? this.changedCardTypesCharacterDefining : this.changedCardTypes).put(timestamp, staticId, new CardChangedType(addType, removeType, addAllCreatureTypes, remove));
      this.updateTypeCache();
      if (updateView) {
         this.updateTypesForView();
      }

   }

   public final boolean removeChangedCardTypes(long timestamp, long staticId) {
      return this.removeChangedCardTypes(timestamp, staticId, true);
   }

   public final boolean removeChangedCardTypes(long timestamp, long staticId, boolean updateView) {
      boolean removed = false;
      removed |= this.changedCardTypes.remove(timestamp, staticId) != null;
      this.bumpTraitEpoch();
      removed |= this.changedCardTypesCharacterDefining.remove(timestamp, staticId) != null;
      this.bumpTraitEpoch();
      if (removed) {
         this.updateTypeCache();
         if (updateView) {
            this.updateTypesForView();
         }
      }

      return removed;
   }

   public final void updateTypeCache() {
      this.getCurrentState().updateTypes();
   }

   public boolean hasChangedCardColors() {
      return !this.changedCardColorsByText.isEmpty() || !this.changedCardColorsCharacterDefining.isEmpty() || !this.changedCardColors.isEmpty();
   }

   public void addColorByText(ColorSet color, boolean addToColors, long timestamp, StaticAbility stAb) {
      this.changedCardColorsByText.put(timestamp, stAb != null ? (long)stAb.getId() : 0L, new CardColor(color, addToColors));
      this.updateColorForView();
   }

   public final void removeColorByText(long timestampIn, long staticId) {
      if (this.changedCardColorsByText.remove(timestampIn, staticId) != null) {
         this.updateColorForView();
      }

   }

   public final void addColor(ColorSet color, boolean addToColors, long timestamp, StaticAbility stAb) {
      (stAb != null && stAb.isCharacteristicDefining() ? this.changedCardColorsCharacterDefining : this.changedCardColors).put(timestamp, stAb != null ? (long)stAb.getId() : 0L, new CardColor(color, addToColors));
      this.updateColorForView();
   }

   public final void removeColor(long timestampIn, long staticId) {
      boolean removed = false;
      removed |= this.changedCardColors.remove(timestampIn, staticId) != null;
      removed |= this.changedCardColorsCharacterDefining.remove(timestampIn, staticId) != null;
      if (removed) {
         this.updateColorForView();
      }

   }

   public final void setColor(String... color) {
      this.setColor(ColorSet.fromNames(color));
   }

   public final void setColor(ColorSet color) {
      this.currentState.setColor(color);
   }

   public final ColorSet getColor() {
      return this.getColor(this.currentState);
   }

   public final ColorSet getColor(CardState state) {
      byte colors = state.getColor().getColor();

      for(CardColor cc : Iterables.concat(this.changedCardColorsByText.values(), this.changedCardColorsCharacterDefining.values(), this.changedCardColors.values())) {
         if (cc.additional()) {
            colors |= cc.color().getColor();
         } else {
            colors = cc.color().getColor();
         }
      }

      return ColorSet.fromMask(colors);
   }

   public final int getCurrentLoyalty() {
      return this.getCounters(CounterEnumType.LOYALTY);
   }

   public final void setBaseLoyalty(int n) {
      this.currentState.setBaseLoyalty(Integer.toString(n));
   }

   public final int getCurrentDefense() {
      return this.getCounters(CounterEnumType.DEFENSE);
   }

   public final void setBaseDefense(int n) {
      this.currentState.setBaseDefense(Integer.toString(n));
   }

   public final Set<Integer> getAttractionLights() {
      return this.currentState.getAttractionLights();
   }

   public final void setAttractionLights(Set<Integer> attractionLights) {
      this.currentState.setAttractionLights(attractionLights);
   }

   public final int getBasePower() {
      return this.currentState.getBasePower();
   }

   public final int getBaseToughness() {
      return this.currentState.getBaseToughness();
   }

   public final void setBasePower(int n) {
      this.currentState.setBasePower(n);
   }

   public final void setBaseToughness(int n) {
      this.currentState.setBaseToughness(n);
   }

   public final String getBasePowerString() {
      return null == this.currentState.getBasePowerString() ? String.valueOf(this.getBasePower()) : this.currentState.getBasePowerString();
   }

   public final String getBaseToughnessString() {
      return null == this.currentState.getBaseToughnessString() ? String.valueOf(this.getBaseToughness()) : this.currentState.getBaseToughnessString();
   }

   public final void setBasePowerString(String s) {
      this.currentState.setBasePowerString(s);
   }

   public final void setBaseToughnessString(String s) {
      this.currentState.setBaseToughnessString(s);
   }

   public final void addCloneState(CardCloneStates states, long timestamp) {
      this.clonedStates.put(timestamp, states);
      this.updateCloneState(true);
      this.updateWorldTimestamp(timestamp);
   }

   public final boolean removeCloneState(long timestamp) {
      if (this.clonedStates.remove(timestamp) != null) {
         this.updateCloneState(true);
         this.updateWorldTimestamp(timestamp);
         return true;
      } else {
         return false;
      }
   }

   public final boolean removeCloneState(CardTraitBase ctb) {
      boolean changed = false;
      List<Long> toRemove = Lists.newArrayList();

      for(Map.Entry<Long, CardCloneStates> e : this.clonedStates.entrySet()) {
         if (ctb.equals(((CardCloneStates)e.getValue()).getSource())) {
            toRemove.add((Long)e.getKey());
            changed = true;
         }
      }

      for(Long l : toRemove) {
         this.clonedStates.remove(l);
      }

      if (changed) {
         this.updateCloneState(true);
      }

      return changed;
   }

   public final Card getCloner() {
      CardCloneStates clStates = this.getLastClonedState();
      return this.isCloned() && clStates != null ? clStates.getHost() : null;
   }

   public final boolean removeCloneStates() {
      if (this.clonedStates.isEmpty()) {
         return false;
      } else {
         this.clonedStates.clear();
         this.updateCloneState(false);
         return true;
      }
   }

   public final Map<Long, CardCloneStates> getCloneStates() {
      return this.clonedStates;
   }

   public final void setCloneStates(Map<Long, CardCloneStates> val) {
      this.clonedStates.clear();
      this.clonedStates.putAll(val);
      this.updateCloneState(true);
   }

   private void updateCloneState(boolean updateView) {
      if (this.isFaceDown()) {
         this.setState(CardStateName.FaceDown, updateView, true);
      } else {
         this.setState(this.getFaceupCardStateName(), updateView, true);
      }

      this.updateChangedText();
   }

   public final CardStateName getFaceupCardStateName() {
      if (this.isFlipped() && this.hasState(CardStateName.Flipped)) {
         return CardStateName.Flipped;
      } else if (this.isSpecialized()) {
         return this.getCurrentStateName();
      } else {
         if (this.backside && this.isDoubleFaced()) {
            CardStateName stateName = this.getRules().getSplitType().getChangedStateName();
            if (this.hasState(stateName)) {
               return stateName;
            }
         } else if (this.getCurrentStateName() == CardStateName.PreparedSpell) {
            return CardStateName.PreparedSpell;
         }

         return CardStateName.Original;
      }
   }

   private CardCloneStates getLastClonedState() {
      return this.clonedStates.isEmpty() ? null : (CardCloneStates)this.clonedStates.lastEntry().getValue();
   }

   public final Table<Long, Long, Pair<Integer, Integer>> getSetPTTable() {
      return this.newPT;
   }

   public final void setPTTable(Table<Long, Long, Pair<Integer, Integer>> table) {
      this.newPT.clear();
      this.newPT.putAll(table);
   }

   public final Table<Long, Long, Pair<Integer, Integer>> getSetPTCharacterDefiningTable() {
      return this.newPTCharacterDefining;
   }

   public final void setPTCharacterDefiningTable(Table<Long, Long, Pair<Integer, Integer>> table) {
      this.newPTCharacterDefining.clear();
      this.newPTCharacterDefining.putAll(table);
   }

   public final void addNewPTByText(Integer power, Integer toughness, long timestamp, long staticId) {
      this.newPTText.put(timestamp, staticId, Pair.of(power, toughness));
   }

   public final boolean removeNewPTbyText(long timestamp, long staticId) {
      return this.newPTText.remove(timestamp, staticId) != null;
   }

   public final void addNewPT(Integer power, Integer toughness, long timestamp, long staticId) {
      this.addNewPT(power, toughness, timestamp, staticId, false, true);
   }

   public final void addNewPT(Integer power, Integer toughness, long timestamp, long staticId, boolean cda, boolean updateView) {
      (cda ? this.newPTCharacterDefining : this.newPT).put(timestamp, staticId, Pair.of(power, toughness));
      if (updateView) {
         this.updatePTforView();
      }

   }

   public final void removeNewPT(long timestamp, long staticId) {
      this.removeNewPT(timestamp, staticId, true);
   }

   public final boolean removeNewPT(long timestamp, long staticId, boolean updateView) {
      boolean removed = false;
      removed |= this.newPT.remove(timestamp, staticId) != null;
      removed |= this.newPTCharacterDefining.remove(timestamp, staticId) != null;
      if (removed && updateView) {
         this.updatePTforView();
      }

      return removed;
   }

   public Iterable<Pair<Integer, Integer>> getPTIterable() {
      return Iterables.concat(this.newPTText.values(), this.newPTCharacterDefining.values(), this.newPT.values());
   }

   public final boolean clearNewPT() {
      boolean changed = false;
      if (!this.newPTText.isEmpty()) {
         changed = true;
         this.newPTText.clear();
      }

      if (!this.newPTCharacterDefining.isEmpty()) {
         changed = true;
         this.newPTCharacterDefining.clear();
      }

      if (!this.newPT.isEmpty()) {
         changed = true;
         this.newPT.clear();
      }

      return changed;
   }

   public final int getCurrentPower() {
      int total = this.getBasePower();

      for(Pair<Integer, Integer> p : this.getPTIterable()) {
         if (p.getLeft() != null) {
            total = (Integer)p.getLeft();
         }
      }

      return total;
   }

   public final StatBreakdown getUnswitchedPowerBreakdown() {
      return this.isInPlay() && !this.isCreature() ? new StatBreakdown() : new StatBreakdown(this.getCurrentPower(), this.getTempPowerBoost(), this.getPowerBonusFromCounters());
   }

   public final int getUnswitchedPower() {
      return this.getUnswitchedPowerBreakdown().getTotal();
   }

   public final int getPowerBonusFromCounters() {
      // Forge Nova: summed as long and saturated (NovaMath): huge counter counts no longer wrap around
      return !this.hasCounters() ? 0 : forge.game.NovaMath.sat((long)this.getCounters(CounterEnumType.P1P1) + this.getCounters(CounterEnumType.P1P2) + this.getCounters(CounterEnumType.P1P0) - this.getCounters(CounterEnumType.M1M1) + 2L * this.getCounters(CounterEnumType.P2P2) - 2L * this.getCounters(CounterEnumType.M2M1) - 2L * this.getCounters(CounterEnumType.M2M2) - this.getCounters(CounterEnumType.M1M0) + 2L * this.getCounters(CounterEnumType.P2P0));
   }

   public final StatBreakdown getNetPowerBreakdown() {
      return this.getAmountOfKeyword("CARDNAME's power and toughness are switched") % 2 != 0 ? this.getUnswitchedToughnessBreakdown() : this.getUnswitchedPowerBreakdown();
   }

   public final int getNetPower() {
      return this.getAmountOfKeyword("CARDNAME's power and toughness are switched") % 2 != 0 ? this.getUnswitchedToughness() : this.getUnswitchedPower();
   }

   public final int getCurrentToughness() {
      int total = this.getBaseToughness();

      for(Pair<Integer, Integer> p : this.getPTIterable()) {
         if (p.getRight() != null) {
            total = (Integer)p.getRight();
         }
      }

      return total;
   }

   public final StatBreakdown getUnswitchedToughnessBreakdown() {
      return this.isInPlay() && !this.isCreature() ? new StatBreakdown() : new StatBreakdown(this.getCurrentToughness(), this.getTempToughnessBoost(), this.getToughnessBonusFromCounters());
   }

   public final int getUnswitchedToughness() {
      return this.getUnswitchedToughnessBreakdown().getTotal();
   }

   public final int getToughnessBonusFromCounters() {
      // Forge Nova: summed as long and saturated (NovaMath)
      return !this.hasCounters() ? 0 : forge.game.NovaMath.sat((long)this.getCounters(CounterEnumType.P1P1) + 2L * this.getCounters(CounterEnumType.P1P2) - this.getCounters(CounterEnumType.M1M1) + this.getCounters(CounterEnumType.P0P1) - 2L * this.getCounters(CounterEnumType.M0M2) + 2L * this.getCounters(CounterEnumType.P2P2) - this.getCounters(CounterEnumType.M0M1) - this.getCounters(CounterEnumType.M2M1) - 2L * this.getCounters(CounterEnumType.M2M2) + 2L * this.getCounters(CounterEnumType.P0P2));
   }

   public final StatBreakdown getNetToughnessBreakdown() {
      return this.getAmountOfKeyword("CARDNAME's power and toughness are switched") % 2 != 0 ? this.getUnswitchedPowerBreakdown() : this.getUnswitchedToughnessBreakdown();
   }

   public final int getNetToughness() {
      return this.getNetToughnessBreakdown().getTotal();
   }

   public final boolean toughnessAssignsDamage() {
      return StaticAbilityCombatDamageToughness.combatDamageToughness(this);
   }

   public final boolean assignNoCombatDamage() {
      return StaticAbilityAssignNoCombatDamage.assignNoCombatDamage(this);
   }

   public final int getNetCombatDamage() {
      return this.assignNoCombatDamage() ? 0 : (this.toughnessAssignsDamage() ? this.getNetToughnessBreakdown() : this.getNetPowerBreakdown()).getTotal();
   }

   public final int getTempPowerBoost() {
      // Forge Nova: a long sum, saturated (power doubled again and again used to wrap around to 0 or below)
      return forge.game.NovaMath.sat(this.boostPT.values().stream().mapToLong(Pair::getLeft).sum());
   }

   public final int getTempToughnessBoost() {
      return forge.game.NovaMath.sat(this.boostPT.values().stream().mapToLong(Pair::getRight).sum());
   }

   public void addPTBoost(int power, int toughness, long timestamp, long staticId) {
      this.boostPT.put(timestamp, staticId, Pair.of(power, toughness));
   }

   public boolean removePTBoost(long timestamp, long staticId) {
      return this.boostPT.remove(timestamp, staticId) != null;
   }

   public Table<Long, Long, Pair<Integer, Integer>> getPTBoostTable() {
      return this.boostPT;
   }

   public void setPTBoost(Table<Long, Long, Pair<Integer, Integer>> table) {
      this.boostPT.clear();
      this.boostPT.putAll(table);
   }

   public List<String> getDraftActions() {
      return this.draftActions;
   }

   public void addDraftAction(String s) {
      this.draftActions.add(s);
   }

   public final void addIntensity(int n) {
      this.intensity += n;
      this.view.updateIntensity(this);
   }

   public final int getIntensity(boolean total) {
      return total && this.hasKeyword(Keyword.STARTING_INTENSITY) ? this.getKeywordMagnitude(Keyword.STARTING_INTENSITY) + this.intensity : this.intensity;
   }

   public final void setIntensity(int n) {
      this.intensity = n;
   }

   public final boolean hasIntensity() {
      return this.intensity > 0;
   }

   public final boolean hasPerpetual() {
      return !this.perpetual.isEmpty();
   }

   public final List<PerpetualInterface> getPerpetual() {
      return this.perpetual;
   }

   public final void addPerpetual(PerpetualInterface p) {
      this.perpetual.add(p);
   }

   public final void removePerpetual(long timestamp) {
      PerpetualInterface toRemove = null;

      for(PerpetualInterface p : this.perpetual) {
         if (p.getTimestamp() == timestamp) {
            toRemove = p;
            break;
         }
      }

      this.perpetual.remove(toRemove);
   }

   public final void setPerpetual(Card oldCard) {
      this.setPerpetual(oldCard, true);
   }

   public final void setPerpetual(Card oldCard, boolean applyEffects) {
      this.perpetual = oldCard.getPerpetual();
      if (applyEffects) {
         for(PerpetualInterface p : this.perpetual) {
            p.applyEffect(this);
         }
      }

   }

   public final boolean isUntapped() {
      return !this.tapped;
   }

   public final boolean isTapped() {
      return this.tapped;
   }

   public final void setTapped(boolean tapped0) {
      if (this.tapped != tapped0) {
         this.tapped = tapped0;
         this.view.updateTapped(this);
      }
   }

   public final boolean canTap() {
      return this.canTap(false);
   }

   public final boolean canTap(boolean attacker) {
      if (this.tapped) {
         return false;
      } else {
         Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
         repParams.put(AbilityKey.IsCombat, attacker);
         return !this.getGame().getReplacementHandler().cantHappenCheck(ReplacementType.Tap, repParams);
      }
   }

   public final boolean tap(boolean tapAnimation, SpellAbility cause, Player tapper) {
      return this.tap(false, tapAnimation, cause, tapper);
   }

   public final boolean tap(boolean attacker, boolean tapAnimation, SpellAbility cause, Player tapper) {
      if (this.tapped) {
         return false;
      } else {
         Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
         repParams.put(AbilityKey.IsCombat, attacker);
         switch (this.getGame().getReplacementHandler().run(ReplacementType.Tap, repParams)) {
            case NotReplaced:
            case Updated:
               Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(this);
               runParams.put(AbilityKey.Attacker, attacker);
               runParams.put(AbilityKey.Cause, cause);
               runParams.put(AbilityKey.Player, tapper);
               runParams.put(AbilityKey.FirstTime, this.tappedThisTurn == 0);
               runParams.put(AbilityKey.CostStack, this.getGame().costPaymentStack);
               runParams.put(AbilityKey.IndividualCostPaymentInstance, this.getGame().costPaymentStack.peek());
               this.getGame().getTriggerHandler().runTrigger(TriggerType.Taps, runParams, false);
               ++this.tappedThisTurn;
               this.setTapped(true);
               this.view.updateNeedsTapAnimation(tapAnimation);
               this.getGame().fireEvent(new GameEventCardTapped(this, true));
               return true;
            default:
               return false;
         }
      }
   }

   public final boolean canUntap(Player phase, boolean predict) {
      if (!predict && !this.tapped) {
         return false;
      } else if (phase != null && this.isExertedBy(phase)) {
         return false;
      } else if (phase != null && this.hasKeyword("This card doesn't untap during your next untap step.")) {
         return false;
      } else {
         Map<AbilityKey, Object> runParams = AbilityKey.mapFromAffected(this);
         runParams.put(AbilityKey.Player, phase);
         return !this.getGame().getReplacementHandler().cantHappenCheck(ReplacementType.Untap, runParams);
      }
   }

   public final boolean untap() {
      return this.untap((Player)null);
   }

   public final boolean untap(Player phase) {
      if (!this.tapped) {
         return false;
      } else if (phase != null && this.isExertedBy(phase)) {
         return false;
      } else {
         Map<AbilityKey, Object> runParams = AbilityKey.mapFromAffected(this);
         runParams.put(AbilityKey.Player, phase);
         if (this.getGame().getReplacementHandler().run(ReplacementType.Untap, runParams) != ReplacementResult.NotReplaced) {
            return false;
         } else {
            this.getGame().getTriggerHandler().runTrigger(TriggerType.Untaps, AbilityKey.mapFromCard(this), false);
            this.runUntapCommands();
            this.setTapped(false);
            this.view.updateNeedsUntapAnimation(true);
            this.getGame().fireEvent(new GameEventCardTapped(this, false));
            return true;
         }
      }
   }

   public final SpellAbility getSpellAbilityForStaticAbility(String str, StaticAbility stAb) {
      SpellAbility result = (SpellAbility)this.storedSpellAbility.get(stAb, str);
      if (!this.canUseCachedTrait(result, stAb)) {
         result = AbilityFactory.getAbility((String)str, (Card)this, stAb);
         result.changeTextIntrinsic(stAb.getChangedTextColors(), stAb.getChangedTextTypes());
         result.setIntrinsic(false);
         result.setGrantorStatic(stAb);
         this.storedSpellAbility.put(stAb, str, result);
      }

      return result;
   }

   public final Trigger getTriggerForStaticAbility(String str, StaticAbility stAb) {
      Trigger result = (Trigger)this.storedTrigger.get(stAb, str);
      if (!this.canUseCachedTrait(result, stAb)) {
         result = TriggerHandler.parseTrigger(str, this, false, stAb);
         result.changeTextIntrinsic(stAb.getChangedTextColors(), stAb.getChangedTextTypes());
         this.storedTrigger.put(stAb, str, result);
      }

      return result;
   }

   public final Trigger addTriggerForStaticAbility(Trigger trig, StaticAbility stAb) {
      String var10000 = trig.toString();
      String str = var10000 + trig.getId();
      Trigger result = (Trigger)this.storedTrigger.get(stAb, str);
      if (result == null) {
         SpellAbility ab = null;
         if (trig.hasParam("Execute") && trig.getOverridingAbility() != null) {
            ab = (SpellAbility)this.storedAbilityForTrigger.get(stAb, trig.getOverridingAbility());
            if (ab == null) {
               ab = trig.getOverridingAbility().copy(this, false);
               this.storedAbilityForTrigger.put(stAb, trig.getOverridingAbility(), ab);
            }
         }

         result = trig.copy(this, false, false, ab);
         this.storedTrigger.put(stAb, str, result);
      }

      return result;
   }

   public void setStoredReplacements(Table<StaticAbility, String, ReplacementEffect> table) {
      this.storedReplacementEffect.clear();

      for(Table.Cell<StaticAbility, String, ReplacementEffect> c : table.cellSet()) {
         this.storedReplacementEffect.put((StaticAbility)c.getRowKey(), (String)c.getColumnKey(), ((ReplacementEffect)c.getValue()).copy(this, true));
      }

   }

   public final Table<StaticAbility, String, ReplacementEffect> getStoredReplacements() {
      return this.storedReplacementEffect;
   }

   public final ReplacementEffect getReplacementEffectForStaticAbility(String str, StaticAbility stAb) {
      ReplacementEffect result = (ReplacementEffect)this.storedReplacementEffect.get(stAb, str);
      if (!this.canUseCachedTrait(result, stAb)) {
         result = ReplacementHandler.parseReplacement(str, this, false, stAb);
         result.changeTextIntrinsic(stAb.getChangedTextColors(), stAb.getChangedTextTypes());
         this.storedReplacementEffect.put(stAb, str, result);
      }

      return result;
   }

   public final StaticAbility getStaticAbilityForStaticAbility(String str, StaticAbility stAb) {
      StaticAbility result = (StaticAbility)this.storedStaticAbility.get(stAb, str);
      if (!this.canUseCachedTrait(result, stAb)) {
         result = StaticAbility.create(str, this, stAb.getCardState(), false);
         result.changeTextIntrinsic(stAb.getChangedTextColors(), stAb.getChangedTextTypes());
         this.storedStaticAbility.put(stAb, str, result);
      }

      return result;
   }

   public final SpellAbility getSpellAbilityForStaticAbilityByText(SpellAbility sa, StaticAbility stAb) {
      SpellAbility result = (SpellAbility)this.storedSpellAbililityByText.get(stAb, sa);
      if (result == null) {
         result = sa.copy(this, false);
         result.setOriginalAbility(sa);
         result.setGrantorStatic(stAb);
         result.setIntrinsic(true);
         this.storedSpellAbililityByText.put(stAb, sa, result);
      }

      return result;
   }

   public final SpellAbility getSpellAbilityForStaticAbilityGainedByText(String str, StaticAbility stAb) {
      SpellAbility result = (SpellAbility)this.storedSpellAbililityGainedByText.get(stAb, str);
      if (result == null) {
         result = AbilityFactory.getAbility((String)str, (Card)this, stAb);
         result.setIntrinsic(true);
         result.setGrantorStatic(stAb);
         this.storedSpellAbililityGainedByText.put(stAb, str, result);
      }

      return result;
   }

   public final Trigger getTriggerForStaticAbilityByText(Trigger tr, StaticAbility stAb) {
      Trigger result = (Trigger)this.storedTriggerByText.get(stAb, tr);
      if (result == null) {
         result = tr.copy(this, false);
         result.setIntrinsic(true);
         this.storedTriggerByText.put(stAb, tr, result);
      }

      return result;
   }

   public final ReplacementEffect getReplacementEffectForStaticAbilityByText(ReplacementEffect re, StaticAbility stAb) {
      ReplacementEffect result = (ReplacementEffect)this.storedReplacementEffectByText.get(stAb, re);
      if (result == null) {
         result = re.copy(this, false);
         result.setIntrinsic(true);
         this.storedReplacementEffectByText.put(stAb, re, result);
      }

      return result;
   }

   public final StaticAbility getStaticAbilityForStaticAbilityByText(StaticAbility st, StaticAbility stAb) {
      StaticAbility result = (StaticAbility)this.storedStaticAbilityByText.get(stAb, st);
      if (result == null) {
         result = st.copy(this, false);
         result.setIntrinsic(true);
         this.storedStaticAbilityByText.put(stAb, st, result);
      }

      return result;
   }

   public final KeywordInterface getKeywordForStaticAbilityByText(KeywordInterface ki, StaticAbility stAb, long idx) {
      Triple<String, Long, Long> triple = Triple.of(ki.getOriginal(), (long)stAb.getId(), idx);
      KeywordInterface result = (KeywordInterface)this.storedKeywordByText.get(triple);
      if (result == null) {
         result = ki.copy(this, false);
         result.setStatic(stAb);
         result.setIdx(idx);
         result.setIntrinsic(true);
         this.storedKeywordByText.put(triple, result);
      }

      return result;
   }

   private boolean canUseCachedTrait(CardTraitBase cached, CardTraitBase stAb) {
      if (cached == null) {
         return false;
      } else {
         return cached.getChangedTextColors().equals(stAb.getChangedTextColors()) && cached.getChangedTextTypes().equals(stAb.getChangedTextTypes());
      }
   }

   public final Table<Long, Long, CardTraitChanges> getChangedCardTraitsByText() {
      this.bumpTraitEpoch();
      return this.changedCardTraitsByText;
   }

   public final void setChangedCardTraitsByText(Table<Long, Long, CardTraitChanges> changes) {
      this.changedCardTraitsByText.clear();
      this.bumpTraitEpoch();

      for(Table.Cell<Long, Long, CardTraitChanges> e : changes.cellSet()) {
         this.changedCardTraitsByText.put((Long)e.getRowKey(), (Long)e.getColumnKey(), ((CardTraitChanges)e.getValue()).copy(this, true));
         this.bumpTraitEpoch();
      }

   }

   public final void addChangedCardTraitsByText(Collection<SpellAbility> spells, Collection<Trigger> trigger, Collection<ReplacementEffect> replacements, Collection<StaticAbility> statics, long timestamp, long staticId) {
      this.changedCardTraitsByText.put(timestamp, staticId, new CardTraitChanges(spells, trigger, replacements, statics, (e) -> true));
      this.bumpTraitEpoch();
      this.changedTextColors.addEmpty(timestamp, staticId);
      this.changedTextTypes.addEmpty(timestamp, staticId);
      this.updateChangedText();
   }

   public final ICardTraitChanges addChangedCardTraits(Collection<SpellAbility> spells, Collection<Trigger> trigger, Collection<ReplacementEffect> replacements, Collection<StaticAbility> statics, Predicate<CardTraitBase> remove, long timestamp, long staticId) {
      return this.addChangedCardTraits(spells, trigger, replacements, statics, remove, timestamp, staticId, true);
   }

   public final ICardTraitChanges addChangedCardTraits(Collection<SpellAbility> spells, Collection<Trigger> trigger, Collection<ReplacementEffect> replacements, Collection<StaticAbility> statics, Predicate<CardTraitBase> remove, long timestamp, long staticId, boolean updateView) {
      CardTraitChanges result = new CardTraitChanges(spells, trigger, replacements, statics, remove);
      return this.addChangedCardTraits(result, timestamp, staticId, updateView);
   }

   public final ICardTraitChanges addChangedCardTraits(ICardTraitChanges changes, long timestamp, long staticId, boolean updateView) {
      this.changedCardTraits.put(timestamp, staticId, changes);
      this.bumpTraitEpoch();
      if (updateView) {
         this.updateAbilityTextForView();
      }

      return changes;
   }

   public final boolean removeChangedCardTraits(long timestamp, long staticId) {
      this.bumpTraitEpoch();
      final boolean novaRemoved = this.changedCardTraits.remove(timestamp, staticId) != null;
      this.bumpTraitEpoch();
      return novaRemoved;
   }

   public final boolean removeChangedCardTraitsByText(long timestamp, long staticId) {
      this.bumpTraitEpoch();
      final boolean novaRemoved = this.changedCardTraitsByText.remove(timestamp, staticId) != null;
      this.bumpTraitEpoch();
      return novaRemoved;
   }

   public Iterable<? extends ICardTraitChanges> getChangedCardTraitsList(CardState state) {
      return (Iterable<? extends ICardTraitChanges>)(this.changedCardTraitsByText.isEmpty() && this.changedCardTraits.isEmpty() ? state.getLandTraitChanges() : Iterables.concat(this.changedCardTraitsByText.values(), state.getLandTraitChanges(), this.changedCardTraits.values()));
   }

   public final Table<Long, Long, ICardTraitChanges> getChangedCardTraits() {
      this.bumpTraitEpoch();
      return this.changedCardTraits;
   }

   public final void setChangedCardTraits(Table<Long, Long, ICardTraitChanges> changes) {
      this.changedCardTraits.clear();
      this.bumpTraitEpoch();

      for(Table.Cell<Long, Long, ICardTraitChanges> e : changes.cellSet()) {
         this.changedCardTraits.put((Long)e.getRowKey(), (Long)e.getColumnKey(), ((ICardTraitChanges)e.getValue()).copy(this, true));
         this.bumpTraitEpoch();
      }

   }

   public boolean clearChangedCardTraits() {
      boolean changed = false;
      if (!this.changedCardTraitsByText.isEmpty()) {
         changed = true;
      }

      this.changedCardTraitsByText.clear();
      this.bumpTraitEpoch();
      if (!this.changedCardTraits.isEmpty()) {
         changed = true;
      }

      this.changedCardTraits.clear();
      this.bumpTraitEpoch();
      return changed;
   }

   public final List<KeywordInterface> getKeywords() {
      return this.getKeywords(this.currentState);
   }

   public final List<KeywordInterface> getKeywords(CardState state) {
      ListKeywordVisitor visitor = new ListKeywordVisitor();
      this.visitKeywords(state, visitor);
      return visitor.getKeywords();
   }

   public final void visitKeywords(CardState state, Visitor<KeywordInterface> visitor) {
      this.visitUnhiddenKeywords(state, visitor);
   }

   public final boolean hasKeyword(Keyword keyword) {
      return this.hasKeyword(keyword, this.currentState);
   }

   public final boolean hasKeyword(Keyword key, CardState state) {
      return state.hasKeyword(key);
   }

   public final boolean hasKeyword(String keyword) {
      return this.hasKeyword(keyword, this.currentState);
   }

   public final boolean hasKeyword(String keyword, CardState state) {
      if (keyword.startsWith("HIDDEN")) {
         keyword = keyword.substring(7);
      }

      for(List<String> kw : this.hiddenExtrinsicKeywords.values()) {
         if (kw.contains(keyword)) {
            return true;
         }
      }

      HasKeywordVisitor visitor = new HasKeywordVisitor(keyword, false);
      this.visitKeywords(state, visitor);
      return visitor.getResult();
   }

   public final void updateKeywords() {
      this.getCurrentState().getView().updateKeywords(this, this.getCurrentState());
   }

   public final void addChangedCardKeywords(List<String> keywords, List<String> removeKeywords, boolean removeAllKeywords, long timestamp, StaticAbility st) {
      this.addChangedCardKeywords(keywords, removeKeywords, removeAllKeywords, timestamp, st, true);
   }

   public final void addChangedCardKeywords(List<String> keywords, List<String> removeKeywords, boolean removeAllKeywords, long timestamp, StaticAbility st, boolean updateView) {
      List<KeywordInterface> kws = Lists.newArrayList();
      if (keywords != null) {
         long idx = 1L;

         for(String kw : keywords) {
            boolean canHave = true;

            for(Keyword cantKW : this.getCantHaveKeyword()) {
               if (kw.startsWith(cantKW.toString())) {
                  canHave = false;
                  break;
               }
            }

            if (canHave) {
               kws.add(this.getKeywordForStaticAbility(kw, st, idx));
            }

            ++idx;
         }
      }

      KeywordsChange newCks = new KeywordsChange(kws, removeKeywords, removeAllKeywords);
      this.changedCardKeywords.put(timestamp, st == null ? 0L : (long)st.getId(), newCks);
      this.bumpTraitEpoch();
      if (updateView) {
         this.updateKeywords();
      }

   }

   public final KeywordInterface getKeywordForStaticAbility(String kw, StaticAbility st, long idx) {
      long staticId = st == null ? 0L : (long)st.getId();
      Triple<String, Long, Long> triple = Triple.of(kw, staticId, idx);
      KeywordInterface result;
      if (staticId >= 1L && this.storedKeywords.containsKey(triple)) {
         result = (KeywordInterface)this.storedKeywords.get(triple);
      } else {
         result = Keyword.getInstance(kw);
         result.setStatic(st);
         result.setIdx(idx);
         result.createTraits(this, false);
         if (staticId > 0L) {
            this.storedKeywords.put(triple, result);
         }
      }

      return result;
   }

   public final void addKeywordForStaticAbility(KeywordInterface kw) {
      if (kw.getStatic() != null) {
         this.storedKeywords.put(Triple.of(kw.getOriginal(), (long)kw.getStatic().getId(), kw.getIdx()), kw);
      }

   }

   public Map<Triple<String, Long, Long>, KeywordInterface> getStoredKeywords() {
      return this.storedKeywords;
   }

   public void setStoredKeywords(Map<Triple<String, Long, Long>, KeywordInterface> map, boolean lki) {
      this.storedKeywords.clear();

      for(Map.Entry<Triple<String, Long, Long>, KeywordInterface> e : map.entrySet()) {
         this.storedKeywords.put((Triple)e.getKey(), this.getCopyForStoredKeyword(e, lki));
      }

   }

   private KeywordInterface getCopyForStoredKeyword(Map.Entry<Triple<String, Long, Long>, KeywordInterface> e, boolean lki) {
      if (lki) {
         for(KeywordsChange kc : this.changedCardKeywords.column((Long)((Triple)e.getKey()).getMiddle()).values()) {
            for(KeywordInterface kw : kc.getKeywords()) {
               if (kw.getOriginal().equals(((KeywordInterface)e.getValue()).getOriginal())) {
                  return kw;
               }
            }
         }
      }

      return ((KeywordInterface)e.getValue()).copy(this, lki);
   }

   public final void addChangedCardKeywordsByText(List<KeywordInterface> keywords, long timestamp, long staticId, boolean updateView) {
      this.changedCardKeywordsByText.put(timestamp, staticId, new KeywordsChange(keywords, ImmutableList.<KeywordInterface>of(), true));
      this.bumpTraitEpoch();
      if (updateView) {
         this.updateKeywords();
      }

   }

   public void setChangedCardKeywordsByText(Table<Long, Long, IKeywordsChange> changedCardKeywords) {
      this.changedCardKeywordsByText.clear();
      this.bumpTraitEpoch();

      for(Table.Cell<Long, Long, IKeywordsChange> entry : changedCardKeywords.cellSet()) {
         this.changedCardKeywordsByText.put((Long)entry.getRowKey(), (Long)entry.getColumnKey(), ((IKeywordsChange)entry.getValue()).copy(this, true));
         this.bumpTraitEpoch();
      }

   }

   public final void addChangedCardKeywordsInternal(Collection<KeywordInterface> keywords, Collection<KeywordInterface> removeKeywords, boolean removeAllKeywords, long timestamp, StaticAbility st, boolean updateView) {
      KeywordsChange newCks = new KeywordsChange(keywords, removeKeywords, removeAllKeywords);
      long staticId = st == null ? 0L : (long)st.getId();
      this.changedCardKeywords.put(timestamp, staticId, newCks);
      this.bumpTraitEpoch();
      if (updateView) {
         this.updateKeywords();
      }

   }

   public final boolean removeChangedCardKeywords(long timestamp, long staticId) {
      return this.removeChangedCardKeywords(timestamp, staticId, true);
   }

   public final boolean removeChangedCardKeywords(long timestamp, long staticId, boolean updateView) {
      boolean changed = false;
      changed |= this.changedCardKeywords.remove(timestamp, staticId) != null;
      this.bumpTraitEpoch();
      changed |= this.changedCardKeywordsByText.remove(timestamp, staticId) != null;
      this.bumpTraitEpoch();
      if (updateView) {
         this.updateKeywords();
      }

      return changed;
   }

   public final boolean removeChangedCardKeywordsByText(long timestamp, long staticId) {
      this.bumpTraitEpoch();
      final boolean novaRemoved = this.changedCardKeywordsByText.remove(timestamp, staticId) != null;
      this.bumpTraitEpoch();
      return novaRemoved;
   }

   public boolean clearChangedCardKeywords() {
      return this.clearChangedCardKeywords(false);
   }

   public final boolean clearChangedCardKeywords(boolean updateView) {
      boolean changed = false;
      if (!this.changedCardKeywordsByText.isEmpty()) {
         changed = true;
      }

      this.changedCardKeywordsByText.clear();
      this.bumpTraitEpoch();
      if (!this.changedCardKeywords.isEmpty()) {
         changed = true;
      }

      this.changedCardKeywords.clear();
      this.bumpTraitEpoch();
      if (changed && updateView) {
         this.updateKeywords();
      }

      return changed;
   }

   public boolean clearStaticChangedCardKeywords(boolean updateView) {
      boolean changed = this.changedCardKeywords.columnKeySet().retainAll(ImmutableList.of(0L));
      if (changed && updateView) {
         this.updateKeywords();
      }

      return changed;
   }

   public final KeywordCollection getUnhiddenKeywords() {
      return this.getUnhiddenKeywords(this.currentState);
   }

   public final KeywordCollection getUnhiddenKeywords(CardState state) {
      return state.getCachedKeywords();
   }

   public final void updateKeywordsCache() {
      this.updateKeywordsCache(this.getCurrentState());
   }

   public final void updateKeywordsCache(CardState state) {
      this.bumpTraitEpoch();
      KeywordCollection keywords = new KeywordCollection();
      keywords.insertAll(state.getIntrinsicKeywords());
      if (state.getStateName().equals(CardStateName.Original)) {
         if (this.hasState(CardStateName.LeftSplit)) {
            keywords.insertAll(this.getState(CardStateName.LeftSplit).getIntrinsicKeywords());
         }

         if (this.hasState(CardStateName.RightSplit)) {
            keywords.insertAll(this.getState(CardStateName.RightSplit).getIntrinsicKeywords());
         }
      }

      keywords.applyChanges(this.getChangedCardKeywordsList(state));

      for(Keyword k : this.getCantHaveKeyword()) {
         keywords.removeAll(k);
      }

      state.setCachedKeywords(keywords);
   }

   private void visitUnhiddenKeywords(CardState state, Visitor<KeywordInterface> visitor) {
      for(KeywordInterface kw : this.getUnhiddenKeywords(state)) {
         if (!visitor.visit(kw)) {
            return;
         }
      }

   }

   public final KeywordInterface addIntrinsicKeyword(String s) {
      KeywordInterface inst = this.currentState.addIntrinsicKeyword(s, true);
      if (inst != null) {
         this.updateKeywords();
      }

      return inst;
   }

   public final void addIntrinsicKeywords(Iterable<String> s) {
      this.addIntrinsicKeywords(s, true);
   }

   public final void addIntrinsicKeywords(Iterable<String> s, boolean initTraits) {
      if (this.currentState.addIntrinsicKeywords(s, initTraits)) {
         this.updateKeywords();
      }

   }

   public final void removeIntrinsicKeyword(Keyword k) {
      if (this.currentState.removeIntrinsicKeyword(k)) {
         this.updateKeywords();
      }

   }

   public final Iterable<String> getHiddenExtrinsicKeywords() {
      return Iterables.concat(this.hiddenExtrinsicKeywords.values());
   }

   public final void addHiddenExtrinsicKeywords(long timestamp, long staticId, Iterable<String> keywords) {
      this.hiddenExtrinsicKeywords.put(timestamp, staticId, Lists.newArrayList(keywords));
      this.bumpTraitEpoch();
      this.updateNonAbilityTextForView();
      this.updateKeywords();
   }

   public final void removeHiddenExtrinsicKeywords(long timestamp, long staticId) {
      this.bumpTraitEpoch();
      if (this.hiddenExtrinsicKeywords.remove(timestamp, staticId) != null) {
         this.updateNonAbilityTextForView();
         this.updateKeywords();
      }

   }

   public final void removeHiddenExtrinsicKeyword(String s) {
      boolean updated = false;

      for(List<String> list : this.hiddenExtrinsicKeywords.values()) {
         if (list.remove(s)) {
            updated = true;
         }
      }

      if (updated) {
         this.updateNonAbilityTextForView();
         this.updateKeywords();
      }

   }

   public final boolean hasStartOfKeyword(String keyword) {
      return this.hasStartOfKeyword(keyword, this.currentState);
   }

   public final boolean hasStartOfKeyword(String keyword, CardState state) {
      for(String s : this.getHiddenExtrinsicKeywords()) {
         if (s.startsWith(keyword)) {
            return true;
         }
      }

      HasKeywordVisitor visitor = new HasKeywordVisitor(keyword, true);
      this.visitKeywords(state, visitor);
      return visitor.getResult();
   }

   public final boolean hasStartOfUnHiddenKeyword(String keyword) {
      return this.hasStartOfUnHiddenKeyword(keyword, this.currentState);
   }

   public final boolean hasStartOfUnHiddenKeyword(String keyword, CardState state) {
      HasKeywordVisitor visitor = new HasKeywordVisitor(keyword, true);
      this.visitUnhiddenKeywords(state, visitor);
      return visitor.getResult();
   }

   public final boolean hasAnyKeyword(Iterable<String> keywords) {
      return this.hasAnyKeyword(keywords, this.currentState);
   }

   public final boolean hasAnyKeyword(Iterable<String> keywords, CardState state) {
      for(String keyword : keywords) {
         if (this.hasKeyword(keyword, state)) {
            return true;
         }
      }

      return false;
   }

   public final int getAmountOfKeyword(String k) {
      return this.getAmountOfKeyword(k, this.currentState);
   }

   public final int getAmountOfKeyword(String k, CardState state) {
      int count = Iterables.frequency(this.getHiddenExtrinsicKeywords(), k);
      CountKeywordVisitor visitor = new CountKeywordVisitor(k);
      this.visitKeywords(state, visitor);
      return count + visitor.getCount();
   }

   public final int getAmountOfKeyword(Keyword k) {
      return this.getAmountOfKeyword(k, this.currentState);
   }

   public final int getAmountOfKeyword(Keyword k, CardState state) {
      return this.getKeywords(k, state).size();
   }

   public final Collection<KeywordInterface> getKeywords(Keyword k) {
      return this.getKeywords(k, this.currentState);
   }

   public final Collection<KeywordInterface> getKeywords(Keyword k, CardState state) {
      return state.getCachedKeyword(k);
   }

   public final int getKeywordMagnitude(Keyword k) {
      return this.getKeywordMagnitude(k, this.currentState);
   }

   public final int getKeywordMagnitude(Keyword k, CardState state) {
      int count = 0;

      for(KeywordInterface inst : this.getKeywords(k, state)) {
         String kw = inst.getOriginal();
         String[] parse = kw.contains(":") ? kw.split(":") : kw.split(" ");
         if (parse.length < 2) {
            ++count;
         } else {
            String s = parse[1];
            if (StringUtils.isNumeric(s)) {
               count += Integer.parseInt(s);
            } else if (inst.hasSVar(s)) {
               count += AbilityUtils.calculateAmount(this, inst.getSVar(s), (CardTraitBase)null);
            } else {
               String svar = StringUtils.join(parse);
               if (state.hasSVar(svar)) {
                  count += AbilityUtils.calculateAmount(this, state.getSVar(svar), (CardTraitBase)null);
               }
            }
         }
      }

      return count;
   }

   public void addCantHaveKeyword(Keyword keyword, Long timestamp) {
      this.cantHaveKeywords.put(timestamp, keyword);
      this.getView().updateCantHaveKeyword(this);
   }

   public void addCantHaveKeyword(Long timestamp, Iterable<Keyword> keywords) {
      this.cantHaveKeywords.putAll(timestamp, keywords);
      this.getView().updateCantHaveKeyword(this);
   }

   public boolean removeCantHaveKeyword(Long timestamp) {
      return this.removeCantHaveKeyword(timestamp, true);
   }

   public boolean removeCantHaveKeyword(Long timestamp, boolean updateView) {
      boolean change = !this.cantHaveKeywords.removeAll(timestamp).isEmpty();
      if (change && updateView) {
         this.getView().updateCantHaveKeyword(this);
         this.updateKeywords();
      }

      return change;
   }

   public Collection<Keyword> getCantHaveKeyword() {
      return this.cantHaveKeywords.values();
   }

   public final void addChangedTextColorWord(String originalWord, String newWord, Long timestamp, long staticId) {
      if (MagicColor.fromName(newWord) == 0) {
         throw new RuntimeException("Not a color: " + newWord);
      } else {
         this.changedTextColors.add(timestamp, staticId, StringUtils.capitalize(originalWord), StringUtils.capitalize(newWord));
         this.updateChangedText();
      }
   }

   public final void removeChangedTextColorWord(Long timestamp, long staticId) {
      if (this.changedTextColors.remove(timestamp, staticId)) {
         this.updateChangedText();
      }

   }

   public final void addChangedTextTypeWord(String originalWord, String newWord, Long timestamp, long staticId) {
      this.changedTextTypes.add(timestamp, staticId, originalWord, newWord);
      this.changedCardTypesByText.put(timestamp, staticId, new WordChangedType(originalWord, newWord));
      this.bumpTraitEpoch();
      this.updateChangedText();
   }

   public final void removeChangedTextTypeWord(Long timestamp, long staticId) {
      this.bumpTraitEpoch();
      if (this.changedCardTypesByText.remove(timestamp, staticId) != null) {
         this.updateTypeCache();
      }

      if (this.changedTextTypes.remove(timestamp, staticId)) {
         this.updateChangedText();
      }

   }

   public void updateChangedText() {
      this.currentState.updateChangedText();

      for(CardTraitChanges change : this.changedCardTraitsByText.values()) {
         change.changeText();
      }

      KeywordCollection beforeKeywords = new KeywordCollection();
      beforeKeywords.insertAll(this.currentState.getIntrinsicKeywords());
      beforeKeywords.applyChanges(this.changedCardKeywordsByText.values());
      List<KeywordInterface> addKeywords = Lists.newArrayList();
      List<KeywordInterface> removeKeywords = Lists.newArrayList();

      for(KeywordInterface kw : beforeKeywords) {
         String oldtxt = kw.getOriginal();
         if (oldtxt.startsWith("Class")) {
            for(StaticAbility trait : kw.getStaticAbilities()) {
               trait.changeText();
            }
         } else if (oldtxt.startsWith("Chapter")) {
            for(Trigger trait : kw.getTriggers()) {
               trait.changeText();
            }
         } else {
            String newtxt = AbilityUtils.applyKeywordTextChangeEffects(oldtxt, this.getChangedTextColorWords(), this.getChangedTextTypeWords());
            if (!newtxt.equals(oldtxt)) {
               KeywordInterface newKw = Keyword.getInstance(newtxt);
               newKw.createTraits(this, true);
               addKeywords.add(newKw);
               removeKeywords.add(kw);
            }
         }
      }

      this.changedCardKeywordsByWord = new KeywordsChange(addKeywords, removeKeywords, false);
      this.bumpTraitEpoch();
      this.text = AbilityUtils.applyDescriptionTextChangeEffects(this.originalText, this);
      this.getView().updateChangedColorWords(this);
      this.getView().updateChangedTypes(this);
      this.updateManaCostForView();
      this.updateTypeCache();
      this.updateTypesForView();
      this.updateAbilityTextForView();
      this.view.updateNonAbilityText(this);
   }

   public final ImmutableMap<String, String> getChangedTextColorWords() {
      return ImmutableMap.copyOf(this.changedTextColors);
   }

   public final ImmutableMap<String, String> getChangedTextTypeWords() {
      return ImmutableMap.copyOf(this.changedTextTypes);
   }

   public final void copyChangedTextFrom(Card other) {
      this.changedTextColors.copyFrom(other.changedTextColors);
      this.changedTextTypes.copyFrom(other.changedTextTypes);
   }

   public final boolean isPermanent() {
      return !this.isImmutable() && (this.isInPlay() || this.getType().isPermanent());
   }

   public final boolean isSpell() {
      return this.isInstant() || this.isSorcery() || this.isAura() && !this.isInZone(ZoneType.Battlefield);
   }

   public final boolean hasPlayableLandFace() {
      return this.isLand() || this.isModal() && this.getState(CardStateName.Backside).getType().isLand();
   }

   public final boolean isLand() {
      return this.getType().isLand();
   }

   public final boolean isBasicLand() {
      return this.getType().isBasicLand();
   }

   public final boolean isSnow() {
      return this.getType().isSnow();
   }

   public final boolean isKindred() {
      return this.getType().isKindred();
   }

   public final boolean isSorcery() {
      return this.getType().isSorcery();
   }

   public final boolean isInstant() {
      return this.getType().isInstant();
   }

   public final boolean isInstantOrSorcery() {
      return this.getType().isInstant() || this.getType().isSorcery();
   }

   public final boolean isCreature() {
      return this.getType().isCreature();
   }

   public final boolean isArtifact() {
      return this.getType().isArtifact();
   }

   public final boolean isPlaneswalker() {
      return this.getType().isPlaneswalker();
   }

   public final boolean isBattle() {
      return this.getType().isBattle();
   }

   public final boolean isEnchantment() {
      return this.getType().isEnchantment();
   }

   public final boolean isEquipment() {
      return this.getType().isEquipment();
   }

   public final boolean isFortification() {
      return this.getType().isFortification();
   }

   public final boolean isAttraction() {
      return this.getType().isAttraction();
   }

   public final boolean isContraption() {
      return this.getType().isContraption();
   }

   public final boolean isCurse() {
      return this.getType().hasSubtype("Curse");
   }

   public final boolean isAura() {
      return this.getType().isAura();
   }

   public final boolean isShrine() {
      return this.getType().hasSubtype("Shrine");
   }

   public final boolean isSaga() {
      return this.getType().isSaga();
   }

   public final boolean isAttachment() {
      return this.getType().isAttachment();
   }

   public final boolean isHistoric() {
      return this.getType().isHistoric();
   }

   public final boolean isScheme() {
      return this.getType().isScheme();
   }

   public final boolean isPhenomenon() {
      return this.getType().isPhenomenon();
   }

   public final boolean isPlane() {
      return this.getType().isPlane();
   }

   public final boolean isOutlaw() {
      return this.getType().isOutlaw();
   }

   public final boolean isRoom() {
      return this.getType().hasSubtype("Room");
   }

   public final int compareTo(Card that) {
      return that == null ? 1 : Integer.compare(this.id, that.id);
   }

   public final String toString() {
      return this.getView() == null ? this.getPaperCard().getName() : this.getView().toString();
   }

   public final boolean isUnearthed() {
      return this.unearthed;
   }

   public final void setUnearthed(boolean b) {
      this.unearthed = b;
   }

   public final boolean isPhasedOut() {
      return this.phasedOut != null;
   }

   public final boolean isPhasedOut(Player turn) {
      return turn.equals(this.phasedOut);
   }

   public final Player getPhasedOut() {
      return this.phasedOut;
   }

   public final void setPhasedOut(Player phasedOut0) {
      if (this.phasedOut != phasedOut0) {
         this.phasedOut = phasedOut0;
         this.bumpTraitEpoch(); // Forge Nova: phased-out cards drop out of getCardsIn() (and the static ability index)
         this.view.updatePhasedOut(this);
      }
   }

   public final void phase(boolean fromUntapStep) {
      this.phase(fromUntapStep, true);
   }

   public final void phase(boolean fromUntapStep, boolean direct) {
      boolean phasingIn = this.isPhasedOut();
      if (this.switchPhaseState(fromUntapStep)) {
         if (!phasingIn) {
            this.setDirectlyPhasedOut(direct);
         }

         if (!this.getAllAttachedCards().isEmpty()) {
            for(Card eq : this.getAllAttachedCards()) {
               if ((eq.isPhasedOut() || !StaticAbilityCantPhase.cantPhaseOut(eq)) && eq.isPhasedOut() == phasingIn) {
                  eq.phase(fromUntapStep, false);
               }
            }
         }

         GameEntity ge = this.getEntityAttachedTo();
         if (ge != null) {
            ge.updateAttachedCards();
         }

         this.getGame().fireEvent(new GameEventCardPhased(CardView.get(this), this.isPhasedOut()));
      }
   }

   private boolean switchPhaseState(boolean fromUntapStep) {
      if (this.isPhasedOut() && fromUntapStep && this.wontPhaseInNormal) {
         return false;
      } else {
         Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(this);
         if (!this.isPhasedOut()) {
            this.getGame().getTriggerHandler().runTrigger(TriggerType.PhaseOut, runParams, true);
            this.runPhaseOutCommands();
            this.clearEncodedCards();
            if (this.isPaired()) {
               this.getPairedWith().setPairedWith((Card)null);
               this.setPairedWith((Card)null);
            }
         }

         this.setPhasedOut(this.isPhasedOut() ? null : this.getController());
         Combat combat = this.getGame().getCombat();
         if (combat != null && this.isPhasedOut()) {
            combat.saveLKI(this);
            combat.removeFromCombat(this);
         }

         if (!this.isPhasedOut()) {
            if (this.isAttachedToEntity()) {
               GameEntity ge = this.getEntityAttachedTo();
               boolean unattach = false;
               if (ge instanceof Player) {
                  unattach = !((Player)ge).isInGame();
               } else {
                  unattach = !((Card)ge).isInPlay();
               }

               if (unattach) {
                  this.unattachFromEntity(ge);
               }
            }

            this.getGame().getTriggerHandler().registerActiveTrigger(this, false);
            this.getGame().getTriggerHandler().runTrigger(TriggerType.PhaseIn, runParams, true);
         }

         this.game.updateLastStateForCard(this);
         return true;
      }
   }

   public final boolean isDirectlyPhasedOut() {
      return this.directlyPhasedOut;
   }

   public final void setDirectlyPhasedOut(boolean direct) {
      this.directlyPhasedOut = direct;
   }

   public final boolean isWontPhaseInNormal() {
      return this.wontPhaseInNormal;
   }

   public final void setWontPhaseInNormal(boolean phaseFlag) {
      this.wontPhaseInNormal = phaseFlag;
   }

   public final boolean isReflectedLand() {
      for(SpellAbility a : this.currentState.getManaAbilities()) {
         if (a.getApi() == ApiType.ManaReflected) {
            return true;
         }
      }

      return false;
   }

   public final boolean isValid(String restriction, Player sourceController, Card source, CardTraitBase spellAbility) {
      if (TraitEpoch.DISABLED) {
         return this.isValidOriginal(restriction, sourceController, source, spellAbility);
      }
      // Forge Nova: same tests in the same order as isValidOriginal, on a restriction parsed once (NovaRestriction)
      final NovaRestriction r = NovaRestriction.of(restriction);
      final boolean testFailed = r.negated;
      boolean result = !testFailed;
      evaluate: {
         if (this.getCurrentStateName() == CardStateName.PreparedSpell && this.isInZone(ZoneType.Exile)) {
            result = testFailed;
            break evaluate;
         }
         switch (r.kind) {
            case NovaRestriction.SPELL:
               if (!this.isSpell()) {
                  result = testFailed;
                  break evaluate;
               }
               break;
            case NovaRestriction.PERMANENT:
               if (!this.isPermanent()) {
                  result = testFailed;
                  break evaluate;
               }
               break;
            case NovaRestriction.EFFECT:
               if (!this.isImmutable()) {
                  result = testFailed;
                  break evaluate;
               }
               break;
            case NovaRestriction.EMBLEM:
               if (!this.isEmblem()) {
                  result = testFailed;
                  break evaluate;
               }
               break;
            case NovaRestriction.BOON:
               if (!this.isBoon()) {
                  result = testFailed;
                  break evaluate;
               }
               break;
            case NovaRestriction.ANY:
               if (!this.isCreature() && !this.isPlaneswalker() && !this.isBattle()) {
                  result = false;
                  break evaluate;
               }
               break;
            case NovaRestriction.CARD:
               if (this.isImmutable()) {
                  result = testFailed;
                  break evaluate;
               }
               break;
            default: {
               // CardType.hasStringType(type), with its type-name lookups done once per restriction
               final CardTypeView tv = this.getType();
               final boolean has;
               if (r.type.isEmpty()) {
                  has = false;
               } else if (tv.hasSubtype(r.type)) {
                  has = true;
               } else if (r.core != null) {
                  has = tv.hasType(r.core);
               } else {
                  has = r.sup != null && tv.hasSupertype(r.sup);
               }
               if (!has) {
                  result = testFailed;
                  break evaluate;
               }
            }
         }
         if (r.props != null) {
            for (String exR : r.props) {
               if (!this.hasProperty(exR, sourceController, source, spellAbility)) {
                  result = testFailed;
                  break evaluate;
               }
            }
         }
      }
      if (TraitEpoch.VERIFY) {
         boolean fresh = this.isValidOriginal(restriction, sourceController, source, spellAbility);
         if (fresh != result) {
            TraitEpoch.mismatch("isValid:" + restriction, this, java.util.Collections.singletonList(result), java.util.Collections.singletonList(fresh));
         }
      }
      return result;
   }

   private boolean isValidOriginal(String restriction, Player sourceController, Card source, CardTraitBase spellAbility) {
      String[] incR = restriction.split("\\.", 2);
      boolean testFailed = false;
      if (incR[0].startsWith("!")) {
         testFailed = true;
         incR[0] = incR[0].substring(1);
      }

      if (this.getCurrentStateName() == CardStateName.PreparedSpell && this.isInZone(ZoneType.Exile)) {
         return testFailed;
      } else {
         if (incR[0].equals("Spell")) {
            if (!this.isSpell()) {
               return testFailed;
            }
         } else if (incR[0].equals("Permanent")) {
            if (!this.isPermanent()) {
               return testFailed;
            }
         } else if (incR[0].equals("Effect")) {
            if (!this.isImmutable()) {
               return testFailed;
            }
         } else if (incR[0].equals("Emblem")) {
            if (!this.isEmblem()) {
               return testFailed;
            }
         } else if (incR[0].equals("Boon")) {
            if (!this.isBoon()) {
               return testFailed;
            }
         } else if (!incR[0].equals("card") && !incR[0].equals("Card")) {
            if (incR[0].equals("Any")) {
               if (!this.isCreature() && !this.isPlaneswalker() && !this.isBattle()) {
                  return false;
               }
            } else if (!this.getType().hasStringType(incR[0])) {
               return testFailed;
            }
         } else if (this.isImmutable()) {
            return testFailed;
         }

         if (incR.length > 1) {
            String excR = incR[1];
            String[] exRs = excR.split("\\+");

            for(String exR : exRs) {
               if (!this.hasProperty(exR, sourceController, source, spellAbility)) {
                  return testFailed;
               }
            }
         }

         return !testFailed;
      }
   }

   public boolean hasProperty(String property, Player sourceController, Card source, CardTraitBase spellAbility) {
      if (!TraitEpoch.DISABLED && this.getGame() != null) {
         // Forge Nova: common properties answered directly, see NovaProps (derived from CardProperty's chain)
         final boolean negated = property.startsWith("!");
         final String prop = negated ? property.substring(1) : property;
         final int fast = NovaProps.fast(this, prop, sourceController, source, spellAbility);
         if (fast != NovaProps.UNKNOWN) {
            if (TraitEpoch.VERIFY) {
               boolean fresh = CardProperty.cardHasProperty(this, prop, sourceController, source, spellAbility);
               if (fresh != (fast == 1)) {
                  TraitEpoch.mismatch("hasProperty:" + prop, this, java.util.Collections.singletonList(fast == 1), java.util.Collections.singletonList(fresh));
               }
            }
            return negated != (fast == 1);
         }
         return negated != CardProperty.cardHasProperty(this, prop, sourceController, source, spellAbility);
      }
      if (property.startsWith("!")) {
         return !CardProperty.cardHasProperty(this, property.substring(1), sourceController, source, spellAbility);
      } else {
         return CardProperty.cardHasProperty(this, property, sourceController, source, spellAbility);
      }
   }

   public final boolean isEmblem() {
      return this.isEmblem;
   }

   public final void setEmblem(boolean isEmblem0) {
      this.isEmblem = isEmblem0;
      this.view.updateEmblem(this);
   }

   public final boolean isBoon() {
      return this.isBoon;
   }

   public final void setBoon(boolean isBoon0) {
      this.isBoon = isBoon0;
      this.view.updateBoon(this);
   }

   public final boolean isOfColor(String col) {
      return this.getColor().hasAnyColor(MagicColor.fromName(col));
   }

   public final boolean isBlack() {
      return this.getColor().hasBlack();
   }

   public final boolean isBlue() {
      return this.getColor().hasBlue();
   }

   public final boolean isRed() {
      return this.getColor().hasRed();
   }

   public final boolean isGreen() {
      return this.getColor().hasGreen();
   }

   public final boolean isWhite() {
      return this.getColor().hasWhite();
   }

   public final boolean isColorless() {
      return this.getColor().isColorless();
   }

   public final boolean associatedWithColor(String col) {
      Set<String> color = new HashSet();
      if (col != null) {
         color.add(col);
      }

      return this.isOfColor(col) || this.canProduceColorMana(color);
   }

   public final boolean hasNoName() {
      return !this.hasNonLegendaryCreatureNames() && this.getName().isEmpty();
   }

   public final boolean sharesNameWith(Card c1) {
      if (c1 == null) {
         return false;
      } else {
         if (c1.hasNonLegendaryCreatureNames()) {
            if (this.hasNonLegendaryCreatureNames()) {
               return true;
            }

            if (this.getName().isEmpty()) {
               return false;
            }

            if (StaticData.instance().getCommonCards().isNonLegendaryCreatureName(this.getName())) {
               return true;
            }
         }

         return this.sharesNameWith(c1.getName());
      }
   }

   public final boolean sharesNameWith(String name) {
      if (name != null && !name.isEmpty()) {
         boolean shares = this.getName().equals(name);
         if (!shares && !this.hasNameOverwrite()) {
            if (this.isInPlay()) {
               for(String door : this.getUnlockedRoomNames()) {
                  shares |= name.equals(door);
               }
            } else {
               if (this.hasState(CardStateName.LeftSplit)) {
                  shares |= name.equals(this.getState(CardStateName.LeftSplit).getName());
               }

               if (this.hasState(CardStateName.RightSplit)) {
                  shares |= name.equals(this.getState(CardStateName.RightSplit).getName());
               }
            }
         }

         return !shares && this.hasNonLegendaryCreatureNames() ? StaticData.instance().getCommonCards().isNonLegendaryCreatureName(name) : shares;
      } else {
         return false;
      }
   }

   public final boolean sharesColorWith(Card c1) {
      if (!this.isColorless() && !c1.isColorless()) {
         boolean shares = this.isBlack() && c1.isBlack();
         shares |= this.isBlue() && c1.isBlue();
         shares |= this.isGreen() && c1.isGreen();
         shares |= this.isRed() && c1.isRed();
         shares |= this.isWhite() && c1.isWhite();
         return shares;
      } else {
         return false;
      }
   }

   public final boolean sharesCMCWith(int n) {
      Card host = this.game.getCardState(this);
      return host.getCMC() == n;
   }

   public final boolean sharesCMCWith(Card c1) {
      Card host = this.game.getCardState(this);
      Card other = this.game.getCardState(c1);
      return host.getCMC() == other.getCMC();
   }

   public final boolean sharesCreatureTypeWith(Card c1) {
      return c1 == null ? false : this.getType().sharesCreaturetypeWith(c1.getType());
   }

   public final boolean sharesLandTypeWith(Card c1) {
      return c1 == null ? false : this.getType().sharesLandTypeWith(c1.getType());
   }

   public final boolean sharesPermanentTypeWith(Card c1) {
      return c1 == null ? false : this.getType().sharesPermanentTypeWith(c1.getType());
   }

   public final boolean sharesCardTypeWith(Card c1) {
      return c1 == null ? false : this.getType().sharesCardTypeWith(c1.getType());
   }

   public final boolean sharesAllCardTypesWith(Card c1) {
      return c1 == null ? false : this.getType().sharesAllCardTypesWith(c1.getType());
   }

   public final boolean sharesControllerWith(Card c1) {
      return c1 != null && this.getController().equals(c1.getController());
   }

   public final boolean hasABasicLandType() {
      return this.getType().hasABasicLandType();
   }

   public final boolean hasANonBasicLandType() {
      return this.getType().hasANonBasicLandType();
   }

   public final boolean isUsedToPay() {
      return this.usedToPayCost;
   }

   public final void setUsedToPay(boolean b) {
      this.usedToPayCost = b;
   }

   public CardDamageHistory getDamageHistory() {
      return this.damageHistory;
   }

   public void setDamageHistory(CardDamageHistory history) {
      this.damageHistory = history;
   }

   public final boolean hasDealtDamageToOpponentThisTurn() {
      return this.getDamageHistory().getDamageDoneThisTurn((Boolean)null, true, (String)null, "Player.Opponent", this, this.getController(), (CardTraitBase)null) > 0;
   }

   public final int getTotalDamageDoneBy() {
      return this.getDamageHistory().getDamageDoneThisTurn((Boolean)null, false, (String)null, (String)null, this, this.getController(), (CardTraitBase)null);
   }

   public final int getLethal() {
      return this.isLethalDamageByPower() ? this.getNetPower() : this.getNetToughness();
   }

   public final int getLethalDamage() {
      for(Card c : this.getAssignedDamageMap().keySet()) {
         if (c.hasKeyword(Keyword.DEATHTOUCH)) {
            return 0;
         }
      }

      return this.getLethal() - this.getDamage() - this.getTotalAssignedDamage();
   }

   public final int getExcessDamageValue(boolean withDeathtouch) {
      ArrayList<Integer> excessCharacteristics = new ArrayList();
      if (this.isCreature()) {
         int lethal = this.getLethalDamage();
         if (withDeathtouch && lethal > 0) {
            excessCharacteristics.add(1);
         } else {
            excessCharacteristics.add(Math.max(0, lethal));
         }
      }

      if (this.isPlaneswalker()) {
         excessCharacteristics.add(this.getCurrentLoyalty());
      }

      if (this.isBattle()) {
         excessCharacteristics.add(this.getCurrentDefense());
      }

      return excessCharacteristics.isEmpty() ? 0 : (Integer)Collections.min(excessCharacteristics);
   }

   public final int getDamage() {
      long sum = 0L; // Forge Nova: long sum, saturated

      for(int i : this.damage.values()) {
         sum += i;
      }

      return forge.game.NovaMath.sat(sum);
   }

   public final void setDamage(int damage0) {
      if (this.getDamage() != damage0) {
         this.damage.clear();
         if (damage0 != 0) {
            this.damage.put(0, damage0);
         }

         this.view.updateDamage(this);
         this.getGame().fireEvent(new GameEventCardStatsChanged(this));
      }
   }

   public int getMaxDamageFromSource() {
      return this.damage.isEmpty() ? 0 : (Integer)Collections.max(this.damage.values());
   }

   public final boolean hasBeenDealtDeathtouchDamage() {
      return this.hasBeenDealtDeathtouchDamage;
   }

   public final void setHasBeenDealtDeathtouchDamage(boolean hasBeenDealtDeatchtouchDamage) {
      this.hasBeenDealtDeathtouchDamage = hasBeenDealtDeatchtouchDamage;
   }

   public final void healDamage() {
      this.setDamage(0);
      this.setHasBeenDealtDeathtouchDamage(false);
      this.clearAssignedDamage();
   }

   public final boolean hasBeenDealtExcessDamageThisTurn() {
      return this.hasBeenDealtExcessDamageThisTurn;
   }

   public final void setHasBeenDealtExcessDamageThisTurn(boolean bool) {
      this.hasBeenDealtExcessDamageThisTurn = bool;
   }

   public final void logExcessDamage(int n) {
      this.excessDamageThisTurnAmount += n;
   }

   public final int getExcessDamageThisTurn() {
      return this.excessDamageThisTurnAmount;
   }

   public final void setExcessDamageReceivedThisTurn(int n) {
      this.excessDamageThisTurnAmount = n;
   }

   private void resetExcessDamage() {
      this.hasBeenDealtExcessDamageThisTurn = false;
      this.excessDamageThisTurnAmount = 0;
   }

   public final Map<Card, Integer> getAssignedDamageMap() {
      return this.assignedDamageMap;
   }

   public final void addAssignedDamage(int assignedDamage0, Card sourceCard) {
      if (assignedDamage0 > 0) {
         Logger.debug("{} was assigned {} damage by {}", new Object[]{this, assignedDamage0, sourceCard});
         this.assignedDamageMap.merge(sourceCard, assignedDamage0, forge.game.NovaMath::add);
         this.view.updateAssignedDamage(this);
      }
   }

   public final void clearAssignedDamage() {
      if (!this.assignedDamageMap.isEmpty()) {
         this.assignedDamageMap.clear();
         this.view.updateAssignedDamage(this);
      }
   }

   public final int getTotalAssignedDamage() {
      long total = 0L; // Forge Nova: long sum, saturated

      for(Integer assignedDamage : this.assignedDamageMap.values()) {
         total += assignedDamage;
      }

      return forge.game.NovaMath.sat(total);
   }

   public final boolean canDamagePrevented(boolean isCombat) {
      return !StaticAbilityCantPreventDamage.cantPreventDamage(this, isCombat);
   }

   /** Forge Nova: the battlefield card names staticReplaceDamage reacts to. */
   private static final java.util.Set<String> NOVA_REPLACE_DAMAGE_BF = java.util.Set.of("Sulfuric Vapors", "Pyromancer's Swath", "Furnace of Rath", "Dictate of the Twin Gods", "Gratuitous Violence", "Fire Servant", "Gisela, Blade of Goldnight", "Inquisitor's Flail", "Ghosts of the Innocent", "Benevolent Unicorn", "Divine Presence", "Lashknife Barrier");

   public final int staticReplaceDamage(int damage, Card source, boolean isCombat) {
      int restDamage = damage; // Forge Nova: the arithmetic below saturates (NovaMath)

      // Forge Nova: the cards of game.getCardsIn(Battlefield), in order, that have one of the names below (cached
      // per global epoch; name changes bump it): no other card can change restDamage, every branch tests the name
      for(Card c : (TraitEpoch.DISABLED ? this.getGame().getCardsIn(ZoneType.Battlefield) : java.util.Arrays.asList(forge.game.replacement.NovaStaticSourceIndex.namedCards(this.getGame(), ZoneType.Battlefield, NOVA_REPLACE_DAMAGE_BF)))) {

         final String cName = c.getName(); // Forge Nova: one name lookup per card
         if (cName.equals("Sulfuric Vapors")) {
            if (source.isSpell() && source.isRed()) {
               restDamage = forge.game.NovaMath.add(restDamage, 1);
            }
         } else if (cName.equals("Pyromancer's Swath")) {
            if (c.getController().equals(source.getController()) && (source.isInstant() || source.isSorcery()) && this.isCreature()) {
               restDamage = forge.game.NovaMath.add(restDamage, 2);
            }
         } else if (cName.equals("Furnace of Rath")) {
            if (this.isCreature()) {
               restDamage = forge.game.NovaMath.mul(restDamage, 2);
            }
         } else if (cName.equals("Dictate of the Twin Gods")) {
            restDamage = forge.game.NovaMath.add(restDamage, restDamage);
         } else if (cName.equals("Gratuitous Violence")) {
            if (c.getController().equals(source.getController()) && source.isCreature() && this.isCreature()) {
               restDamage = forge.game.NovaMath.mul(restDamage, 2);
            }
         } else if (cName.equals("Fire Servant")) {
            if (c.getController().equals(source.getController()) && source.isRed() && (source.isInstant() || source.isSorcery())) {
               restDamage = forge.game.NovaMath.mul(restDamage, 2);
            }
         } else if (cName.equals("Gisela, Blade of Goldnight")) {
            if (!c.getController().equals(this.getController())) {
               restDamage = forge.game.NovaMath.mul(restDamage, 2);
            }
         } else if (cName.equals("Inquisitor's Flail")) {
            if (isCombat && c.getEquipping() != null && (c.getEquipping().equals(this) || c.getEquipping().equals(source))) {
               restDamage = forge.game.NovaMath.mul(restDamage, 2);
            }
         } else if (cName.equals("Ghosts of the Innocent")) {
            if (this.isCreature()) {
               restDamage /= 2;
            }
         } else if (cName.equals("Benevolent Unicorn")) {
            if (source.isSpell() && this.isCreature()) {
               restDamage = forge.game.NovaMath.sub(restDamage, 1);
            }
         } else if (cName.equals("Divine Presence")) {
            if (restDamage > 3 && this.isCreature()) {
               restDamage = 3;
            }
         } else if (cName.equals("Lashknife Barrier") && c.getController().equals(this.getController()) && this.isCreature()) {
            restDamage = forge.game.NovaMath.sub(restDamage, 1);
         }
      }

      for(Card c : (TraitEpoch.DISABLED ? this.getGame().getCardsIn(ZoneType.Command) : java.util.Arrays.asList(forge.game.replacement.NovaStaticSourceIndex.cardsIn(this.getGame(), ZoneType.Command)))) {
         final String cName = c.getName(); // Forge Nova: one name lookup per card
         if (cName.equals("Insult Effect")) {
            if (c.getController().equals(source.getController())) {
               restDamage = forge.game.NovaMath.mul(restDamage, 2);
            }
         } else if (cName.equals("Mishra") && c.isCreature() && c.getController().equals(source.getController())) {
            restDamage = forge.game.NovaMath.mul(restDamage, 2);
         }
      }

      if (this.getName().equals("Phytohydra")) {
         return 0;
      } else {
         return restDamage;
      }
   }

   public final int addDamageAfterPrevention(int damageIn, Card source, SpellAbility cause, boolean isCombat, GameEntityCounterTable counterTable) {
      if (damageIn <= 0) {
         return 0;
      } else if (!this.isPlaneswalker() && !this.isCreature() && !this.isBattle()) {
         return 0;
      } else if (!this.isInPlay()) {
         return 0;
      } else {
         this.getGame().getReplacementHandler().run(ReplacementType.DealtDamage, AbilityKey.mapFromAffected(this));
         Map<AbilityKey, Object> runParams = AbilityKey.newMap();
         runParams.put(AbilityKey.DamageSource, source);
         runParams.put(AbilityKey.DamageTarget, this);
         runParams.put(AbilityKey.Cause, cause);
         runParams.put(AbilityKey.DamageAmount, damageIn);
         runParams.put(AbilityKey.IsCombatDamage, isCombat);
         runParams.put(AbilityKey.DefendingPlayer, this.game.getCombat() != null ? this.game.getCombat().getDefendingPlayerRelatedTo(source) : null);
         this.getGame().getTriggerHandler().runTrigger(TriggerType.DamageDone, runParams, true);
         GameEventCardDamaged.DamageType damageType = GameEventCardDamaged.DamageType.Normal;
         if (this.isPlaneswalker()) {
            this.subtractCounter(CounterEnumType.LOYALTY, damageIn, (Player)null, true);
         }

         if (this.isBattle()) {
            this.subtractCounter(CounterEnumType.DEFENSE, damageIn, (Player)null, true);
         }

         if (this.isCreature()) {
            if (source.isWitherDamage()) {
               this.addCounter(CounterEnumType.M1M1, damageIn, source.getController(), counterTable);
               damageType = GameEventCardDamaged.DamageType.M1M1Counters;
            } else {
               this.damage.merge(Objects.hash(new Object[]{source.getId(), source.getGameTimestamp()}), damageIn, forge.game.NovaMath::add);
               this.view.updateDamage(this);
            }

            if (source.hasKeyword(Keyword.DEATHTOUCH)) {
               this.setHasBeenDealtDeathtouchDamage(true);
               damageType = GameEventCardDamaged.DamageType.Deathtouch;
            }

            this.game.fireEvent(new GameEventCardDamaged(CardView.get(this), CardView.get(source), damageIn, damageType));
         }

         return damageIn;
      }
   }

   public final void setRandomFoil() {
      this.setFoil(CardEdition.getRandomFoil(this.getSetCode()));
   }

   public final void setFoil(int f) {
      this.currentState.setSVar("Foil", Integer.toString(f));
   }

   public CardEdition.BorderColor borderColor() {
      CardEdition edition = StaticData.instance().getEditions().get(this.getSetCode());
      return edition != null && !this.isBasicLand() ? edition.getBorderColor() : CardEdition.BorderColor.BLACK;
   }

   public final String getMostRecentSet() {
      return StaticData.instance().getCommonCards().getCard(this.getPaperCard().getName()).getEdition();
   }

   public final String getSetCode() {
      return this.currentState.getSetCode();
   }

   public final void setSetCode(String setCode) {
      this.currentState.setSetCode(setCode);
   }

   public final CardRarity getRarity() {
      return this.currentState.getRarity();
   }

   public final void setRarity(CardRarity r) {
      this.currentState.setRarity(r);
   }

   public final String getImageKey() {
      if (!this.getRenderForUI()) {
         return "";
      } else {
         Card uiCard = this.getCardForUi();
         return uiCard == null ? "" : uiCard.currentState.getImageKey();
      }
   }

   public final void setImageKey(String iFN) {
      if (this.getRenderForUI()) {
         Card uiCard = this.getCardForUi();
         if (uiCard != null) {
            uiCard.currentState.setImageKey(iFN);
         }

      }
   }

   public String getImageKey(CardStateName state) {
      if (!this.getRenderForUI()) {
         return "";
      } else {
         Card uiCard = this.getCardForUi();
         if (uiCard == null) {
            return "";
         } else {
            CardState c = (CardState)uiCard.states.get(state);
            return c != null ? c.getImageKey() : "";
         }
      }
   }

   public final String getFacedownImageKey() {
      if (this.isInZone(ZoneType.Exile)) {
         return this.isForetold() ? StaticData.instance().getOtherImageKey("foretell", (String)null) : ImageKeys.getTokenKey("hidden");
      } else if (this.isManifested()) {
         String set = this.getManifestedSA().getCardState().getSetCode();
         return StaticData.instance().getOtherImageKey("manifest", set);
      } else if (this.isCloaked()) {
         String set = this.getCloakedSA().getCardState().getSetCode();
         return StaticData.instance().getOtherImageKey("cloaked", set);
      } else {
         if (this.getCastSA() != null) {
            String set = this.getCastSA().getCardState().getSetCode();
            if (this.getCastSA().isKeyword(Keyword.DISGUISE)) {
               return StaticData.instance().getOtherImageKey("cloaked", set);
            }

            if (this.getCastSA().isKeyword(Keyword.MORPH) || this.getCastSA().isKeyword(Keyword.MEGAMORPH)) {
               return StaticData.instance().getOtherImageKey("morph", set);
            }
         }

         return ImageKeys.getTokenKey("hidden");
      }
   }

   public final boolean isTributed() {
      return this.tributed;
   }

   public final void setTributed(boolean b) {
      this.tributed = b;
   }

   public final SpellAbility getTokenSpawningAbility() {
      return this.tokenSpawningAbility;
   }

   public void setTokenSpawningAbility(SpellAbility sa) {
      this.tokenSpawningAbility = sa;
   }

   public final boolean isEmbalmed() {
      SpellAbility sa = this.getTokenSpawningAbility();
      return sa != null && sa.isEmbalm();
   }

   public final boolean isEternalized() {
      SpellAbility sa = this.getTokenSpawningAbility();
      return sa != null && sa.isEternalize();
   }

   public final int getExertedThisTurn() {
      return this.exertThisTurn;
   }

   public void exert() {
      this.exert(this.getController());
   }

   public void exert(Player p) {
      this.exertedByPlayer.add(p);
      ++this.exertThisTurn;
      this.view.updateExertedThisTurn(this, true);
      Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(this);
      runParams.put(AbilityKey.Player, p);
      this.game.getTriggerHandler().runTrigger(TriggerType.Exerted, runParams, false);
   }

   public boolean isExertedBy(Player player) {
      return this.exertedByPlayer.contains(player);
   }

   public void removeExertedBy(Player player) {
      this.exertedByPlayer.remove(player);
   }

   protected void resetExertedThisTurn() {
      this.exertThisTurn = 0;
      this.view.updateExertedThisTurn(this, false);
   }

   public boolean isDetained() {
      return !this.detainedByPlayer.isEmpty();
   }

   public void detain(Player player) {
      this.detainedByPlayer.add(player);
      this.view.updateDetained(this);
   }

   public void removeDetainedBy(Player player) {
      this.detainedByPlayer.remove(player);
      this.view.updateDetained(this);
   }

   public boolean isMadness() {
      return this.getCastSA() == null ? false : this.getCastSA().isMadness();
   }

   public final boolean getDrawnThisTurn() {
      return this.drawnThisTurn;
   }

   public final void setDrawnThisTurn(boolean b) {
      this.drawnThisTurn = b;
   }

   public final boolean getFoughtThisTurn() {
      return this.foughtThisTurn;
   }

   public final void setFoughtThisTurn(boolean b) {
      this.foughtThisTurn = b;
   }

   public final boolean getEnlistedThisCombat() {
      return this.enlistedThisCombat;
   }

   public final void setEnlistedThisCombat(boolean b) {
      this.enlistedThisCombat = b;
   }

   public boolean wasDiscarded() {
      return this.discarded;
   }

   public void setDiscarded(boolean state) {
      this.discarded = state;
   }

   public boolean wasSurveilled() {
      return this.surveilled;
   }

   public void setSurveilled(boolean value) {
      this.surveilled = value;
   }

   public boolean wasMilled() {
      return this.milled;
   }

   public void setMilled(boolean value) {
      this.milled = value;
   }

   public final boolean isRingBearer() {
      return this.ringbearer;
   }

   public final void setRingBearer(boolean ringbearer0) {
      this.ringbearer = ringbearer0;
      this.view.updateRingBearer(this);
   }

   public final void clearRingBearer() {
      this.setRingBearer(false);
   }

   public final boolean isHarnessed() {
      return this.harnessed;
   }

   public final boolean setHarnessed(boolean harnessed0) {
      this.harnessed = harnessed0;
      return true;
   }

   public final boolean isMonstrous() {
      return this.monstrous;
   }

   public final void setMonstrous(boolean monstrous0) {
      this.monstrous = monstrous0;
   }

   public final boolean isRenowned() {
      return this.renowned;
   }

   public final void setRenowned(boolean renowned0) {
      this.renowned = renowned0;
   }

   public final boolean isSolved() {
      return this.solved;
   }

   public final boolean setSolved(boolean solved) {
      this.solved = solved;
      return true;
   }

   public final int getTimesSaddledThisTurn() {
      return this.timesSaddledThisTurn;
   }

   public final CardCollection getSaddledByThisTurn() {
      return this.saddledByThisTurn;
   }

   public final void addSaddledByThisTurn(CardCollection saddlers) {
      if (this.saddledByThisTurn != null) {
         this.saddledByThisTurn.addAll(saddlers);
      } else {
         this.saddledByThisTurn = saddlers;
      }

   }

   public final void setSaddledByThisTurn(CardCollection saddlers) {
      this.saddledByThisTurn = saddlers;
   }

   public void resetSaddled() {
      boolean changed = this.isSaddled();
      this.setSaddled(false);
      if (this.saddledByThisTurn != null) {
         this.saddledByThisTurn = null;
      }

      this.timesSaddledThisTurn = 0;
      if (changed) {
         this.updateAbilityTextForView();
      }

   }

   public final boolean isSaddled() {
      return this.saddled;
   }

   public final boolean setSaddled(boolean saddled) {
      this.saddled = saddled;
      if (saddled) {
         ++this.timesSaddledThisTurn;
      }

      return true;
   }

   public StaticAbility getSuspectedStatic() {
      return this.suspectedStatic;
   }

   public void setSuspectedStatic(StaticAbility stAb) {
      this.suspectedStatic = stAb;
      this.bumpTraitEpoch(); // Forge Nova: getHiddenStaticAbilities() includes the suspected static
   }

   public final boolean isSuspected() {
      return this.suspectedStatic != null;
   }

   public final boolean setSuspected(boolean suspected) {
      if (suspected && StaticAbilityCantBeSuspected.cantBeSuspected(this)) {
         return false;
      } else {
         if (suspected) {
            if (this.isSuspected()) {
               return true;
            }

            String s = "Mode$ Continuous | AffectedDefined$ Self | AddKeyword$ Menace | AddStaticAbility$ SuspectedCantBlockBy";
            this.suspectedStatic = StaticAbility.create(s, this, this.currentState, true);
            this.suspectedStatic.putParam("Timestamp", String.valueOf(this.getGame().getNextTimestamp()));
            String effect = "Mode$ CantBlock | ValidCard$ Creature.Self | Description$ CARDNAME can't block.";
            this.suspectedStatic.setSVar("SuspectedCantBlockBy", effect);
         } else {
            this.suspectedStatic = null;
         }

         this.bumpTraitEpoch(); // Forge Nova: getHiddenStaticAbilities() includes the suspected static
         return true;
      }
   }

   public boolean isPrepared() {
      return this.preparedEffect != null;
   }

   public Card getPrepared() {
      return this.preparedEffect;
   }

   public Card getPreparedSpell() {
      return this.preparedEffect == null ? null : (Card)this.preparedEffect.getFirstRemembered();
   }

   public void setPrepared(Card eff) {
      if (eff == null && this.preparedEffect != null) {
         Card prepared = (Card)this.preparedEffect.getFirstRemembered();
         if (prepared.isInZone(ZoneType.Exile)) {
            this.game.getAction().ceaseToExist(prepared, true);
         }

         this.game.getAction().exileEffect(this.preparedEffect);
      }

      this.preparedEffect = eff;
      this.view.updatePreparedSpell(this);
   }

   public final boolean isManifested() {
      return this.manifestedSA != null;
   }

   public final SpellAbility getManifestedSA() {
      return this.manifestedSA;
   }

   public final void setManifested(SpellAbility sa) {
      this.manifestedSA = sa;
   }

   public final boolean isCloaked() {
      return this.cloakedSA != null;
   }

   public final SpellAbility getCloakedSA() {
      return this.cloakedSA;
   }

   public final void setCloaked(SpellAbility sa) {
      this.cloakedSA = sa;
   }

   public final boolean isForetold() {
      if (this.isInZone(ZoneType.Exile)) {
         return this.foretold;
      } else {
         return this.getCastSA() != null ? this.getCastSA().isForetold() : false;
      }
   }

   public final void setForetold(boolean foretold) {
      this.foretold = foretold;
   }

   public final boolean isPlotted() {
      return this.plotted;
   }

   public final boolean setPlotted(boolean plotted) {
      this.plotted = plotted;
      if (plotted && !this.isLKI()) {
         Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(this);
         this.game.getTriggerHandler().runTrigger(TriggerType.BecomesPlotted, runParams, false);
      }

      return true;
   }

   public boolean isForetoldCostByEffect() {
      return this.foretoldCostByEffect;
   }

   public void setForetoldCostByEffect(boolean val) {
      this.foretoldCostByEffect = val;
   }

   public boolean isWarped() {
      if (!this.isInZone(ZoneType.Exile)) {
         return false;
      } else {
         return this.exiledSA == null ? false : this.exiledSA.isKeyword(Keyword.WARP);
      }
   }

   public boolean isWebSlinged() {
      return this.getCastSA() != null && this.getCastSA().isAlternativeCost(AlternativeCost.WebSlinging);
   }

   public boolean isSpecialized() {
      return this.specialized;
   }

   public final void setSpecialized(boolean bool) {
      this.specialized = bool;
   }

   public final boolean canSpecialize() {
      return this.getRules() != null && this.getRules().getSplitType() == CardSplitType.Specialize;
   }

   public boolean canCrew() {
      return this.canTap() && !StaticAbilityCantCrew.cantCrew(this);
   }

   public int getTimesCrewedThisTurn() {
      return this.timesCrewedThisTurn;
   }

   public final void setTimesCrewedThisTurn(int t) {
      this.timesCrewedThisTurn = t;
   }

   public void resetTimesCrewedThisTurn() {
      this.timesCrewedThisTurn = 0;
   }

   public void becomesCrewed(SpellAbility sa) {
      ++this.timesCrewedThisTurn;
      CardCollection crew = sa.getPaidList("Tapped", true);
      this.addCrewedByThisTurn(crew);
      Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(this);
      runParams.put(AbilityKey.Crew, crew);
      this.game.getTriggerHandler().runTrigger(TriggerType.BecomesCrewed, runParams, false);
   }

   public void resetCrewed() {
      this.resetTimesCrewedThisTurn();
      if (this.crewedByThisTurn != null) {
         this.crewedByThisTurn = null;
      }

   }

   public final void addCrewedByThisTurn(CardCollection crew) {
      if (this.crewedByThisTurn != null) {
         this.crewedByThisTurn.addAll(crew);
      } else {
         this.crewedByThisTurn = crew;
      }

   }

   public final CardCollectionView getCrewedByThisTurn() {
      return (CardCollectionView)(this.crewedByThisTurn == null ? CardCollection.EMPTY : this.crewedByThisTurn);
   }

   public final void setCrewedByThisTurn(CardCollectionView crew) {
      this.crewedByThisTurn = new CardCollection(crew);
   }

   public final void visitAttraction(Player visitor) {
      this.visitedThisTurn = true;
      Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(this);
      runParams.put(AbilityKey.Player, visitor);
      this.game.getTriggerHandler().runTrigger(TriggerType.VisitAttraction, runParams, false);
   }

   public final boolean wasVisitedThisTurn() {
      return this.visitedThisTurn;
   }

   public final int getClassLevel() {
      return this.classLevel;
   }

   public void setClassLevel(int level) {
      this.classLevel = level;
      this.view.updateClassLevel(this);
      this.updateAbilityTextForView();
   }

   public boolean isClassCard() {
      return this.getType().hasStringType("Class");
   }

   public void setOverlayText(String overlayText) {
      this.overlayText = overlayText;
      this.view.updateMarkerText(this);
   }

   public String getOverlayText() {
      return this.overlayText;
   }

   public final void animateBestow() {
      this.animateBestow(true);
   }

   public final void animateBestow(boolean updateView) {
      if (!this.isBestowed()) {
         this.bestowTimestamp = this.getGame().getNextTimestamp();
         this.addChangedCardTypes(new CardType(Collections.singletonList("Aura"), true), new CardType(Collections.singletonList("Creature"), true), false, EnumSet.of(RemoveType.EnchantmentTypes), this.bestowTimestamp, 0L, updateView, false);
         this.addChangedCardKeywords(Collections.singletonList("Enchant:Creature"), Lists.newArrayList(), false, this.bestowTimestamp, (StaticAbility)null, updateView);
      }
   }

   public final void unanimateBestow() {
      this.unanimateBestow(true);
   }

   public final void unanimateBestow(boolean updateView) {
      if (this.isBestowed()) {
         this.removeChangedCardKeywords(this.bestowTimestamp, 0L, updateView);
         this.removeChangedCardTypes(this.bestowTimestamp, 0L, updateView);
         this.bestowTimestamp = -1L;
      }
   }

   public final boolean isBestowed() {
      return this.bestowTimestamp != -1L;
   }

   public final long getBestowTimestamp() {
      return this.bestowTimestamp;
   }

   public final void setBestowTimestamp(long t) {
      this.bestowTimestamp = t;
   }

   public final long getGameTimestamp() {
      return this.gameTimestamp;
   }

   public final void setGameTimestamp(long t) {
      this.gameTimestamp = t;
      this.layerTimestamp = t;
   }

   public final long getLayerTimestamp() {
      return this.layerTimestamp;
   }

   public final void setLayerTimestamp(long t) {
      this.layerTimestamp = t;
   }

   public boolean equalsWithGameTimestamp(Card c) {
      return this.equals(c) && c.getGameTimestamp() == this.gameTimestamp;
   }

   public long getWorldTimestamp() {
      return this.worldTimestamp;
   }

   public void updateWorldTimestamp(long ts) {
      if (!this.getType().hasSupertype(CardType.Supertype.World)) {
         this.worldTimestamp = -1L;
      } else if (this.worldTimestamp == -1L) {
         this.worldTimestamp = ts;
      }

   }

   public Zone getZone() {
      return this.currentZone;
   }

   public void setZone(Zone zone) {
      if (this.currentZone != zone) {
         this.currentZone = zone;
         this.view.updateZone(this);
      }
   }

   public boolean isInZone(ZoneType zone) {
      Zone z = this.getLastKnownZone();
      return z != null && z.is(zone);
   }

   public boolean isInZones(List<ZoneType> zones) {
      Zone z = this.getLastKnownZone();
      return z != null && zones.contains(z.getZoneType());
   }

   public boolean canBeDiscardedBy(SpellAbility sa, boolean effect) {
      return !this.isInZone(ZoneType.Hand) ? false : this.getOwner().canDiscardBy(sa, effect);
   }

   public final boolean canBeDestroyed() {
      return this.isInPlay() && !this.isPhasedOut() && (!this.hasKeyword(Keyword.INDESTRUCTIBLE) || this.isCreature() && this.getNetToughness() <= 0);
   }

   public final boolean canBeTargetedBy(SpellAbility sa) {
      if (!this.getOwner().isInGame()) {
         return false;
      } else if (sa == null) {
         return true;
      } else if (this.isPhasedOut()) {
         return false;
      } else {
         return StaticAbilityCantTarget.cantTarget(this, sa) == null;
      }
   }

   public final boolean canBeControlledBy(Player newController) {
      return newController.isInGame() && (!StaticAbilityCantGainControl.cantGainControl(this) || this.getController().equals(newController));
   }

   protected final String cantBeEnchantedByMsg(Card aura) {
      if (!aura.hasKeyword(Keyword.ENCHANT)) {
         return "No Enchant Keyword";
      } else {
         for(KeywordInterface ki : aura.getKeywords(Keyword.ENCHANT)) {
            if (ki instanceof KeywordWithType) {
               KeywordWithType kwt = (KeywordWithType)ki;
               String v = kwt.getValidType();
               String desc = kwt.getTypeDescription();
               if (!this.isValid(v.split(","), aura.getController(), aura, (CardTraitBase)null) || !v.contains("inZone") && !this.isInPlay()) {
                  String var10000 = this.getDisplayName();
                  return var10000 + " is not " + Lang.nounWithAmount(1, desc);
               }
            }
         }

         return null;
      }
   }

   protected String cantBeEquippedByMsg(Card equip, SpellAbility sa) {
      if (!this.isInPlay()) {
         return this.getDisplayName() + " is not in play";
      } else if (sa != null && sa.isEquip()) {
         if (!this.isValid(sa.getTargetRestrictions().getValidTgts(), sa.getActivatingPlayer(), equip, sa)) {
            Equip eq = (Equip)sa.getKeyword();
            String var10000 = this.getDisplayName();
            return var10000 + " is not " + Lang.nounWithAmount(1, eq.getValidDescription());
         } else {
            return null;
         }
      } else {
         return !this.isCreature() ? this.getDisplayName() + " is not a creature" : null;
      }
   }

   protected String cantBeFortifiedByMsg(Card fort) {
      if (!this.isLand()) {
         return this.getDisplayName() + " is not a Land";
      } else if (!this.isInPlay()) {
         return this.getDisplayName() + " is not in play";
      } else {
         return fort.isLand() ? fort.getDisplayName() + " is a Land" : null;
      }
   }

   public String cantBeAttachedMsg(Card attach, SpellAbility sa, boolean checkSBA) {
      return this.isPhasedOut() && !attach.isPhasedOut() ? this.getDisplayName() + " is phased out" : super.cantBeAttachedMsg(attach, sa, checkSBA);
   }

   public final boolean canBeSacrificedBy(SpellAbility source, boolean effect) {
      if (this.isImmutable()) {
         System.out.println("Trying to sacrifice immutables: " + String.valueOf(this));
         return false;
      } else if (this.isInPlay() && !this.isPhasedOut()) {
         if (source != null && source.isManaAbility() && this.isUsedToPay()) {
            return false;
         } else {
            Card gameCard = this.game.getCardState(this, (Card)null);
            if (gameCard != null && this.equalsWithGameTimestamp(gameCard)) {
               return !StaticAbilityCantSacrifice.cantSacrifice(this, source, effect);
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   public final boolean canExiledBy(SpellAbility source, boolean effect) {
      return !StaticAbilityCantExile.cantExile(this, source, effect);
   }

   public final FCollectionView<StaticAbility> getStaticAbilities() {
      return this.currentState.getStaticAbilities();
   }

   public final StaticAbility addStaticAbility(String s) {
      if (!s.trim().isEmpty()) {
         StaticAbility stAb = StaticAbility.create(s, this, this.currentState, true);
         this.currentState.addStaticAbility(stAb);
         return stAb;
      } else {
         return null;
      }
   }

   public final StaticAbility addStaticAbility(StaticAbility stAb) {
      this.currentState.addStaticAbility(stAb);
      return stAb;
   }

   public void updateStaticAbilities(List<StaticAbility> list, CardState state) {
      for(ICardTraitChanges ck : this.getChangedCardTraitsList(state)) {
         ck.applyStaticAbility(list);
      }

      this.getUnhiddenKeywords(state).applyStaticAbility(list);
   }

   /** Forge Nova: false when getHiddenStaticAbilities() is empty whatever this card's counters and zone are. */
   public final boolean novaMayHaveHiddenStatics() {
      return !this.counterTypeKeywordStatic.isEmpty() || this.suspectedStatic != null;
   }

   /** Forge Nova: shared empty result; the only callers (GameAction, StaticAbility) just iterate it or call contains */
   private static final FCollectionView<StaticAbility> NOVA_NO_HIDDEN_STATICS = new FCollection<StaticAbility>();

   public final FCollectionView<StaticAbility> getHiddenStaticAbilities() {
      if (this.counterTypeKeywordStatic.isEmpty() && !(this.isInPlay() && this.isSuspected()) && !TraitEpoch.DISABLED) {
         return NOVA_NO_HIDDEN_STATICS; // nothing below would be added
      }
      FCollection<StaticAbility> result = new FCollection<StaticAbility>();
      if (this.isInPlay() && this.isSuspected()) {
         result.add(this.suspectedStatic);
      }

      for(Map.Entry<CounterType, StaticAbility> e : this.counterTypeKeywordStatic.entrySet()) {
         if (this.getCounters((CounterType)e.getKey()) > 0) {
            result.add((StaticAbility)e.getValue());
         }
      }

      return result;
   }

   public final FCollectionView<Trigger> getTriggers() {
      return this.currentState.getTriggers();
   }

   public final Trigger addTrigger(Trigger t) {
      this.currentState.addTrigger(t);
      return t;
   }

   public final boolean hasTrigger(Trigger t) {
      return this.currentState.hasTrigger(t);
   }

   public final boolean hasTrigger(int id) {
      return this.currentState.hasTrigger(id);
   }

   public void updateTriggers(List<Trigger> list, CardState state) {
      for(ICardTraitChanges ck : this.getChangedCardTraitsList(state)) {
         ck.applyTrigger(list);
      }

      this.getUnhiddenKeywords(state).applyTrigger(list);
   }

   public FCollectionView<ReplacementEffect> getReplacementEffects() {
      return this.currentState.getReplacementEffects();
   }

   public ReplacementEffect addReplacementEffect(ReplacementEffect replacementEffect) {
      this.currentState.addReplacementEffect(replacementEffect);
      return replacementEffect;
   }

   public void updateReplacementEffects(List<ReplacementEffect> list, CardState state, boolean rulesHost) {
      for(ICardTraitChanges ck : this.getChangedCardTraitsList(state)) {
         ck.applyReplacementEffect(list);
      }

      this.getUnhiddenKeywords(state).applyReplacementEffect(list);
      if (rulesHost) {
         this.updateCounterReplacementEffects(list);
      }
   }

   /** Forge Nova: true when shield/stun/finality counters add replacement effects. */
   public final boolean hasCounterReplacementEffects() {
      // an empty counter set holds none of the three (most cards have no counters at all)
      return this.hasCounters() && (this.getCounters(CounterEnumType.SHIELD) > 0 || this.getCounters(CounterEnumType.STUN) > 0 || this.getCounters(CounterEnumType.FINALITY) > 0);
   }

   /** Forge Nova: the counter-dependent part of updateReplacementEffects (unchanged logic). */
   public final void updateCounterReplacementEffects(List<ReplacementEffect> list) {
      {
         if (this.getCounters(CounterEnumType.SHIELD) > 0) {
            String sa = "DB$ RemoveCounter | Defined$ Self | CounterType$ Shield | CounterNum$ 1";
            if (this.shieldCounterReplaceDamage == null) {
               String reStr = "Event$ DamageDone | ActiveZones$ Battlefield | ValidTarget$ Card.Self | PreventionEffect$ True | AlwaysReplace$ True | Secondary$ True | Description$ If damage would be dealt to this permanent, prevent that damage and remove a shield counter from it.";
               this.shieldCounterReplaceDamage = ReplacementHandler.parseReplacement(reStr, this, false, (IHasSVars)null);
               this.shieldCounterReplaceDamage.setOverridingAbility(AbilityFactory.getAbility(sa, this));
            }

            if (this.shieldCounterReplaceDestroy == null) {
               String reStr = "Event$ Destroy | ActiveZones$ Battlefield | ValidCard$ Card.Self | ValidCause$ SpellAbility | Secondary$ True | ShieldCounter$ True | Description$ If this permanent would be destroyed as the result of an effect, instead remove a shield counter from it.";
               this.shieldCounterReplaceDestroy = ReplacementHandler.parseReplacement(reStr, this, false, (IHasSVars)null);
               this.shieldCounterReplaceDestroy.setOverridingAbility(AbilityFactory.getAbility(sa, this));
            }

            list.add(this.shieldCounterReplaceDamage);
            list.add(this.shieldCounterReplaceDestroy);
         }

         if (this.getCounters(CounterEnumType.STUN) > 0) {
            String sa = "DB$ RemoveCounter | Defined$ Self | CounterType$ Stun | CounterNum$ 1";
            if (this.stunCounterReplaceUntap == null) {
               String reStr = "Event$ Untap | ActiveZones$ Battlefield | ValidCard$ Card.Self | Secondary$ True | Description$ If this permanent would become untapped, instead remove a stun counter from it.";
               this.stunCounterReplaceUntap = ReplacementHandler.parseReplacement(reStr, this, false, (IHasSVars)null);
               this.stunCounterReplaceUntap.setOverridingAbility(AbilityFactory.getAbility(sa, this));
            }

            list.add(this.stunCounterReplaceUntap);
         }

         if (this.getCounters(CounterEnumType.FINALITY) > 0) {
            if (this.finalityCounterReplaceDying == null) {
               String reStr = "Event$ Moved | ActiveZones$ Battlefield | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Card.Self | Secondary$ True  | Description$ If CARDNAME would die, exile it instead.";
               String sa = "DB$ ChangeZone | Origin$ Battlefield | Destination$ Exile | Defined$ ReplacedCard";
               this.finalityCounterReplaceDying = ReplacementHandler.parseReplacement(reStr, this, false, (IHasSVars)null);
               this.finalityCounterReplaceDying.setOverridingAbility(AbilityFactory.getAbility(sa, this));
            }

            list.add(this.finalityCounterReplaceDying);
         }

      }
   }

   public boolean hasReplacementEffect(ReplacementEffect re) {
      return this.currentState.hasReplacementEffect(re);
   }

   public boolean hasReplacementEffect(int id) {
      return this.currentState.hasReplacementEffect(id);
   }

   public ReplacementEffect getReplacementEffect(int id) {
      return this.currentState.getReplacementEffect(id);
   }

   public Zone getCastFrom() {
      return this.castFrom;
   }

   public void setCastFrom(Zone castFrom0) {
      this.castFrom = castFrom0;
   }

   public boolean wasCast() {
      if (!this.hasMergedCard()) {
         return this.getCastFrom() != null;
      } else {
         boolean wasCast = false;

         for(Card c : this.getMergedCards()) {
            if (null != c.getCastFrom()) {
               wasCast = true;
               break;
            }
         }

         return wasCast;
      }
   }

   public SpellAbility getCastSA() {
      return this.castSA;
   }

   public void setCastSA(SpellAbility castSA) {
      this.castSA = castSA;
   }

   public Card getEffectSource() {
      return this.effectSourceAbility != null ? this.effectSourceAbility.getHostCard() : this.effectSource;
   }

   public SpellAbility getEffectSourceAbility() {
      return this.effectSourceAbility;
   }

   public void setEffectSource(Card src) {
      this.effectSource = src;
   }

   public void setEffectSource(SpellAbility sa) {
      this.effectSourceAbility = sa;
   }

   public boolean isStartsGameInPlay() {
      return this.startsGameInPlay;
   }

   public void setStartsGameInPlay(boolean startsGameInPlay0) {
      this.startsGameInPlay = startsGameInPlay0;
   }

   public boolean isInPlay() {
      return this.isInZone(ZoneType.Battlefield);
   }

   public void onEndOfCombat(Player active) {
      this.setEnlistedThisCombat(false);
      if (this.getController().equals(active)) {
         this.chosenModesYourLastCombat.clear();
         this.chosenModesYourLastCombatStatic.clear();
         this.chosenModesYourLastCombat.putAll(this.chosenModesYourCombat);
         this.chosenModesYourLastCombatStatic.putAll(this.chosenModesYourCombatStatic);
         this.chosenModesYourCombat.clear();
         this.chosenModesYourCombatStatic.clear();
         this.updateAbilityTextForView();
      }

   }

   public void onCleanupPhase(Player turn) {
      this.tappedThisTurn = 0;
      this.setRegeneratedThisTurn(0);
      this.resetShieldCount();
      this.targetedFromThisTurn.clear();
      this.setFoughtThisTurn(false);
      this.turnedFaceUpThisTurn = false;
      this.clearMustBlockCards();
      this.getDamageHistory().setCreatureAttackedLastTurnOf(turn, this.getDamageHistory().getCreatureAttacksThisTurn() > 0);
      this.getDamageHistory().newTurn();
      this.damageReceivedThisTurn.clear();
      this.resetExcessDamage();
      this.clearBlockedByThisTurn();
      this.clearBlockedThisTurn();
      this.resetExertedThisTurn();
      this.resetCrewed();
      this.resetSaddled();
      this.visitedThisTurn = false;
      this.resetMayPlayTurn();
      this.resetChosenModeTurn();
      this.resetAbilityResolvedThisTurn();
   }

   public boolean hasETBTrigger(boolean drawbackOnly) {
      Iterator var2 = this.getTriggers().iterator();

      while(true) {
         if (!var2.hasNext()) {
            return false;
         }

         Trigger tr = (Trigger)var2.next();
         if (tr.getMode() == TriggerType.ChangesZone && ZoneType.Battlefield.toString().equals(tr.getParam("Destination")) && (!tr.hasParam("ValidCard") || tr.getParam("ValidCard").contains("Self"))) {
            if (!drawbackOnly) {
               break;
            }

            SpellAbility sa = tr.ensureAbility();
            if (sa != null && !sa.isActivatedAbility()) {
               break;
            }
         }
      }

      return true;
   }

   public boolean hasETBReplacement() {
      for(ReplacementEffect re : this.getReplacementEffects()) {
         Map<String, String> params = re.getMapParams();
         if (re instanceof ReplaceMoved && ZoneType.Battlefield.toString().equals(params.get("Destination")) && (!params.containsKey("ValidCard") || ((String)params.get("ValidCard")).contains("Self"))) {
            return true;
         }
      }

      return false;
   }

   public int getCMC() {
      return this.getCMC(Card.SplitCMCMode.CurrentSideCMC);
   }

   public int getCMC(SplitCMCMode mode) {
      if (this.lkiCMC >= 0) {
         return this.lkiCMC;
      } else {
         int xPaid = 0;
         if (this.isInZone(ZoneType.Stack) && this.getManaCost() != null) {
            xPaid = this.getXManaCostPaid() * this.getManaCost().countX();
         }

         int requestedCMC = 0;
         if (this.isSplitCard()) {
            switch (mode) {
               case CurrentSideCMC -> requestedCMC = this.getManaCost().getCMC() + xPaid;
               case LeftSplitCMC -> requestedCMC = this.getState(CardStateName.LeftSplit).getManaCost().getCMC() + xPaid;
               case RightSplitCMC -> requestedCMC = this.getState(CardStateName.RightSplit).getManaCost().getCMC() + xPaid;
               default -> System.out.println(TextUtil.concatWithSpace("Illegal Split Card CMC mode", mode.toString(), "passed to getCMC!"));
            }
         } else if (this.currentStateName == CardStateName.Backside && !this.isModal()) {
            if (this.getCopiedPermanent() != null) {
               return 0;
            }

            requestedCMC = this.getState(CardStateName.Original).getManaCost().getCMC();
         } else if (this.currentStateName == CardStateName.Meld) {
            if (this.getCopiedPermanent() != null || this.getMeldedWith() == null) {
               return 0;
            }

            requestedCMC = this.getState(CardStateName.Original).getManaCost().getCMC() + this.getMeldedWith().getManaCost().getCMC();
         } else {
            requestedCMC = this.getManaCost().getCMC() + xPaid;
         }

         return requestedCMC;
      }
   }

   public final void setLKICMC(int cmc) {
      this.lkiCMC = cmc;
   }

   public final boolean isLKI() {
      return this.lkiCMC >= 0;
   }

   public CardRules getRules() {
      return this.getPaperCard() == null ? null : this.getPaperCard().getRules();
   }

   public void updateRulesView() {
      this.currentState.getView().updateRulesText(this.getRules());
   }

   public Game getGame() {
      return this.game;
   }

   public void dangerouslySetGame(Game newGame) {
      this.game = newGame;
   }

   public boolean isCommander() {
      if (this.getMeldedWith() != null && this.getMeldedWith().isCommander()) {
         return true;
      } else {
         if (this.isInPlay() && this.hasMergedCard()) {
            for(Card c : this.getMergedCards()) {
               if (c.isCommander) {
                  return true;
               }
            }
         }

         return this.isCommander;
      }
   }

   public boolean isRealCommander() {
      return this.isCommander;
   }

   public void setCommander(boolean b) {
      if (this.isCommander != b) {
         this.isCommander = b;
         this.view.updateCommander(this);
      }
   }

   public void updateCommanderView() {
      this.view.updateCommander(this);
   }

   public Card getRealCommander() {
      if (this.isCommander) {
         return this;
      } else if (this.getMeldedWith() != null && this.getMeldedWith().isCommander()) {
         return this.getMeldedWith();
      } else {
         if (this.isInPlay() && this.hasMergedCard()) {
            for(Card c : this.getMergedCards()) {
               if (c.isCommander) {
                  return c;
               }
            }
         }

         return null;
      }
   }

   public boolean canMoveToCommandZone() {
      return this.canMoveToCommandZone;
   }

   public void setMoveToCommandZone(boolean b) {
      this.canMoveToCommandZone = b;
   }

   public void setSplitStateToPlayAbility(SpellAbility sa) {
      if (!this.isInPlay()) {
         if (sa.isBestow()) {
            this.animateBestow();
         }

         if (sa.hasParam("Prototype") && this.prototypeTimestamp == -1L) {
            long next = this.game.getNextTimestamp();
            this.addCloneState(CardFactory.getCloneStates(this, this, sa), next);
            this.prototypeTimestamp = next;
         }

         CardStateName stateName = sa.getCardStateName();
         if (stateName != null && this.hasState(stateName) && this.getCurrentStateName() != stateName) {
            this.setState(stateName, true);
            if (this.isDoubleFaced()) {
               this.setBackSide(this.getRules().getSplitType().getChangedStateName().equals(stateName));
            }
         }

         if (sa.isCastFaceDown()) {
            this.turnFaceDown(true);
            CardFactoryUtil.setFaceDownState(this, sa);
         }

      }
   }

   public boolean isOptionalCostPaid(OptionalCost cost) {
      return this.getCastSA() == null ? false : this.getCastSA().isOptionalCostPaid(cost);
   }

   public final int getKickerMagnitude() {
      if (this.getCastSA() != null && this.getCastSA().hasOptionalKeywordAmount(Keyword.MULTIKICKER)) {
         return this.getCastSA().getOptionalKeywordAmount(Keyword.MULTIKICKER);
      } else {
         boolean hasK1 = this.isOptionalCostPaid(OptionalCost.Kicker1);
         return hasK1 == this.isOptionalCostPaid(OptionalCost.Kicker2) ? (hasK1 ? 2 : 0) : 1;
      }
   }

   public List<SpellAbility> getAllPossibleAbilities(Player player, boolean removeUnplayable) {
      return this.getAllPossibleAbilities(player, removeUnplayable, (Multimap)null);
   }

   public List<SpellAbility> getAllPossibleAbilities(Player player, boolean removeUnplayable, Multimap<SpellAbility, SpellAbility> unhiddenAltCost) {
      CardState oState = this.getOriginalState(CardStateName.Original);
      List<SpellAbility> abilities = Lists.newArrayList();
      Consumer<SpellAbility> consumer = (sax) -> {
         abilities.add(sax);
         List<SpellAbility> altCost = GameActionUtil.getAlternativeCosts(sax, player, false);
         abilities.addAll(altCost);
         if (unhiddenAltCost != null) {
            unhiddenAltCost.putAll(sax, altCost);
         }

      };

      for(SpellAbility sa : this.getSpellAbilities()) {
         if (!sa.isAdventure() || !this.isOnAdventure()) {
            consumer.accept(sa);
         }
      }

      if (this.isFaceDown() && this.isInZone(ZoneType.Exile)) {
         for(SpellAbility sa : oState.getSpellAbilities()) {
            abilities.addAll(GameActionUtil.getAlternativeCosts(sa, player, false));
         }
      }

      if (this.isFaceDown() && this.isInZone(ZoneType.Command)) {
         for(KeywordInterface k : oState.getCachedKeyword(Keyword.HIDDEN_AGENDA)) {
            abilities.addAll(k.getAbilities());
         }

         for(KeywordInterface k : oState.getCachedKeyword(Keyword.DOUBLE_AGENDA)) {
            abilities.addAll(k.getAbilities());
         }
      }

      if (this.isModal() && this.hasState(CardStateName.Backside)) {
         for(SpellAbility sa : this.getState(CardStateName.Backside).getSpellAbilities()) {
            if (sa.isSpell() || sa.isLandAbility()) {
               consumer.accept(sa);
            }
         }
      }

      if (!this.isInPlay() && this.hasState(CardStateName.Secondary) && this.getCurrentStateName() == CardStateName.Original) {
         for(SpellAbility sa : this.getState(CardStateName.Secondary).getSpellAbilities()) {
            if ((!sa.isAdventure() || !this.isOnAdventure()) && (sa.isSpell() || sa.isLandAbility())) {
               consumer.accept(sa);
            }
         }
      }

      if (this.isInPlay() && !this.isPhasedOut() && player.canCastSorcery()) {
         if (this.getCurrentStateName() == CardStateName.RightSplit || this.getCurrentStateName() == CardStateName.EmptyRoom) {
            abilities.add(this.getUnlockAbility(CardStateName.LeftSplit));
         }

         if (this.getCurrentStateName() == CardStateName.LeftSplit || this.getCurrentStateName() == CardStateName.EmptyRoom) {
            abilities.add(this.getUnlockAbility(CardStateName.RightSplit));
         }
      }

      if (this.isInPlay() && this.isFaceDown()) {
         if (this.getCurrentStateName() == CardStateName.FaceDown) {
            for(SpellAbility sa : oState.getNonManaAbilities()) {
               if (sa.isTurnFaceUp()) {
                  abilities.add(sa);
               }
            }
         }

         if (oState.getType().isCreature() && oState.getManaCost() != null && !oState.getManaCost().isNoCost()) {
            if (this.isManifested()) {
               abilities.add(oState.getManifestUp());
            }

            if (this.isCloaked()) {
               abilities.add(oState.getCloakUp());
            }
         }
      }

      Collection<SpellAbility> toRemove = Lists.newArrayListWithCapacity(abilities.size());

      for(SpellAbility sa : abilities) {
         sa.setActivatingPlayer(player);
         if (!sa.canPlay(true) && (removeUnplayable || !sa.isPossible())) {
            toRemove.add(sa);
         }
      }

      abilities.removeAll(toRemove);
      return abilities;
   }

   public static Card fromPaperCard(IPaperCard pc, Player owner) {
      return CardFactory.getCard(pc, owner, owner == null ? null : owner.getGame());
   }

   public static Card getCardForUi(IPaperCard pc) {
      if (pc instanceof PaperCard) {
         Card res = (Card)cp2card.get(pc);
         if (res == null) {
            res = fromPaperCard(pc, (Player)null);
            cp2card.put((PaperCard)pc, res);
         }

         return res;
      } else {
         return fromPaperCard(pc, (Player)null);
      }
   }

   public static Card getCardForUi(Card c) {
      return c == null ? null : c.getCardForUi();
   }

   public Card getCardForUi() {
      return this;
   }

   public boolean getRenderForUI() {
      return this.renderForUi;
   }

   public void setRenderForUI(boolean value) {
      this.renderForUi = value;
   }

   /** Forge Nova: {name, set code, art preference, common db size, variant db size, result} of the last lookup. */
   private volatile Object[] novaPaperMemo;

   public IPaperCard getPaperCard() {
      IPaperCard cp = this.paperCard;
      if (cp != null || TraitEpoch.DISABLED) {
         return cp != null ? cp : this.novaLookupPaperCard();
      }
      // Forge Nova: cards without a paper card (tokens...) looked themselves up in the card database (up to five
      // name searches) on every call, and getRules/isDoubleFaced/isSplitCard/getCMC call this constantly. The
      // lookup depends only on the name and set code (same String objects), the art preference and the database,
      // which only grows (card counts change when a card is added).
      final String name = this.getName();
      final String set = this.getSetCode();
      final StaticData sd = StaticData.instance();
      final Object pref = sd.getCardArtPreference();
      final int nCommon = sd.getCommonCards().getAllCards().size();
      final int nVariant = sd.getVariantCards().getAllCards().size();
      final Object[] m = this.novaPaperMemo;
      if (m != null && m[0] == name && m[1] == set && m[2] == pref && (Integer)m[3] == nCommon && (Integer)m[4] == nVariant) {
         IPaperCard r = (IPaperCard)m[5];
         if (TraitEpoch.VERIFY) {
            IPaperCard fresh = this.novaLookupPaperCard();
            if (fresh != r) {
               TraitEpoch.mismatch("getPaperCard", this, java.util.Collections.singletonList(r), java.util.Collections.singletonList(fresh));
            }
         }
         return r;
      }
      IPaperCard r = this.novaLookupPaperCard();
      this.novaPaperMemo = new Object[]{name, set, pref, nCommon, nVariant, r};
      return r;
   }

   /** Forge Nova: the original getPaperCard lookup for a card without a paper card. */
   private IPaperCard novaLookupPaperCard() {
      IPaperCard cp;
      {
         String name = this.getName();
         String set = this.getSetCode();
         if (StringUtils.isNotBlank(set)) {
            cp = StaticData.instance().getVariantCards().getCard(name, set);
            if (cp != null) {
               return cp;
            }

            cp = StaticData.instance().getCommonCards().getCard(name, set);
            if (cp != null) {
               return cp;
            }
         }

         cp = StaticData.instance().getVariantCards().getCard(name);
         if (cp != null) {
            return cp;
         } else {
            CardDb.CardArtPreference cardArtPreference = StaticData.instance().getCardArtPreference();
            if (cardArtPreference == null) {
               cardArtPreference = CardDb.CardArtPreference.ORIGINAL_ART_CORE_EXPANSIONS_REPRINT_ONLY;
            }

            cp = StaticData.instance().getCommonCards().getCardFromEditions(name, cardArtPreference);
            return (IPaperCard)(cp != null ? cp : StaticData.instance().getCommonCards().getCard(name));
         }
      }
   }

   public static void updateCard(PaperCard pc) {
      Card res = (Card)cp2card.get(pc);
      if (res != null) {
         cp2card.put(pc, fromPaperCard(pc, (Player)null));
      }

   }

   public String getOracleText() {
      return this.currentState.getOracleText();
   }

   public void setOracleText(String oracleText) {
      this.currentState.setOracleText(oracleText);
   }

   public String getTranslationKey() {
      return this.currentState.getTranslationKey();
   }

   public String getUntranslatedName() {
      return this.getDisplayName();
   }

   public String getUntranslatedType() {
      return this.currentState.getUntranslatedType();
   }

   public String getTranslatedName() {
      return CardTranslation.getTranslatedName((ITranslatable)this);
   }

   public CardView getView() {
      return this.view;
   }

   public void cleanupCopiedChangesFrom(Card c) {
      for(StaticAbility stAb : c.getStaticAbilities()) {
         this.removeChangedCardTypes(c.getLayerTimestamp(), (long)stAb.getId(), false);
         this.removeColor(c.getLayerTimestamp(), (long)stAb.getId());
         this.removeChangedCardKeywords(c.getLayerTimestamp(), (long)stAb.getId(), false);
         this.removeChangedCardTraits(c.getLayerTimestamp(), (long)stAb.getId());
      }

   }

   public final void addGoad(Long timestamp, Player p) {
      this.goad.put(timestamp, p);
      this.updateAbilityTextForView();
   }

   public final void removeGoad(Long timestamp) {
      if (this.goad.remove(timestamp) != null) {
         this.updateAbilityTextForView();
      }

   }

   public final boolean isGoaded() {
      return !this.goad.isEmpty();
   }

   public final void unGoad() {
      this.goad = Maps.newTreeMap();
      this.updateAbilityTextForView();
   }

   public final boolean isGoadedBy(Player p) {
      return this.goad.containsValue(p);
   }

   public final PlayerCollection getGoaded() {
      return new PlayerCollection(this.goad.values());
   }

   public final Map<Long, Player> getGoadMap() {
      return this.goad;
   }

   public final Zone getLastKnownZone() {
      return this.savedLastKnownZone != null ? this.savedLastKnownZone : this.getZone();
   }

   public final void setLastKnownZone(Zone zone) {
      this.savedLastKnownZone = zone;
   }

   public final boolean hasChapter() {
      return this.getCurrentState().hasChapter();
   }

   public final int getFinalChapterNr() {
      return this.getCurrentState().getFinalChapterNr();
   }

   public boolean activatedThisTurn() {
      return !this.numberTurnActivations.isEmpty();
   }

   public void addAbilityActivated(SpellAbility ability) {
      this.numberTurnActivations.add(ability);
      this.numberGameActivations.add(ability);
      if (ability.isPwAbility()) {
         this.addPlaneswalkerAbilityActivated();
      }

   }

   public ActivationTable getAbilityActivatedThisTurn() {
      return this.numberTurnActivations;
   }

   public ActivationTable getAbilityActivatedThisGame() {
      return this.numberGameActivations;
   }

   public ActivationTable getAbilityResolvedThisTurn() {
      return this.numberAbilityResolved;
   }

   public int getAbilityActivatedThisTurn(SpellAbility ability) {
      return this.numberTurnActivations.get(ability);
   }

   public int getAbilityActivatedThisGame(SpellAbility ability) {
      return this.numberGameActivations.get(ability);
   }

   public int getAbilityResolvedThisTurn(SpellAbility ability) {
      return this.numberAbilityResolved.get(ability);
   }

   public void addAbilityResolved(SpellAbility ability) {
      this.numberAbilityResolved.add(ability);
   }

   public Multiset<Player> getAbilityResolvedThisTurnActivators(SpellAbility ability) {
      return this.numberAbilityResolved.getActivators(ability);
   }

   public void resetAbilityResolvedThisTurn() {
      this.numberAbilityResolved.clear();
   }

   public List<String> getChosenModes(SpellAbility ability, String type) {
      SpellAbility original = null;
      SpellAbility root = ability.getRootAbility();
      if (root.isTrigger()) {
         original = root.getTrigger().getOverridingAbility();
      } else {
         original = ability.getOriginalAbility();
         if (original == null) {
            original = ability;
         }
      }

      if (type.equals("ThisTurn")) {
         return ability.getGrantorStatic() != null ? (List)this.chosenModesTurnStatic.get(original, ability.getGrantorStatic()) : (List)this.chosenModesTurn.get(original);
      } else if (type.equals("ThisGame")) {
         return ability.getGrantorStatic() != null ? (List)this.chosenModesGameStatic.get(original, ability.getGrantorStatic()) : (List)this.chosenModesGame.get(original);
      } else if (type.equals("YourLastCombat")) {
         return ability.getGrantorStatic() != null ? (List)this.chosenModesYourLastCombatStatic.get(original, ability.getGrantorStatic()) : (List)this.chosenModesYourLastCombat.get(original);
      } else {
         return null;
      }
   }

   public void addChosenModes(SpellAbility ability, String mode, boolean yourCombat) {
      SpellAbility original = null;
      SpellAbility root = ability.getRootAbility();
      if (root.isTrigger()) {
         original = root.getTrigger().getOverridingAbility();
      } else {
         original = ability.getOriginalAbility();
         if (original == null) {
            original = ability;
         }
      }

      if (ability.getGrantorStatic() != null) {
         List<String> result = (List)this.chosenModesTurnStatic.get(original, ability.getGrantorStatic());
         if (result == null) {
            result = Lists.newArrayList();
            this.chosenModesTurnStatic.put(original, ability.getGrantorStatic(), result);
         }

         result.add(mode);
         result = (List)this.chosenModesGameStatic.get(original, ability.getGrantorStatic());
         if (result == null) {
            result = Lists.newArrayList();
            this.chosenModesGameStatic.put(original, ability.getGrantorStatic(), result);
         }

         result.add(mode);
         if (yourCombat) {
            result = (List)this.chosenModesYourCombatStatic.get(original, ability.getGrantorStatic());
            if (result == null) {
               List<String> var10 = Lists.newArrayList();
               this.chosenModesYourCombatStatic.put(original, ability.getGrantorStatic(), var10);
            }
         }
      } else {
         List<String> result = (List)this.chosenModesTurn.computeIfAbsent(original, (k) -> Lists.newArrayList());
         result.add(mode);
         result = (List)this.chosenModesGame.computeIfAbsent(original, (k) -> Lists.newArrayList());
         result.add(mode);
         if (yourCombat) {
            result = (List)this.chosenModesYourCombat.computeIfAbsent(original, (k) -> Lists.newArrayList());
            result.add(mode);
         }
      }

   }

   public void resetChosenModeTurn() {
      boolean updateView = !this.chosenModesTurn.isEmpty() || !this.chosenModesTurnStatic.isEmpty();
      this.chosenModesTurn.clear();
      this.chosenModesTurnStatic.clear();
      if (updateView) {
         this.updateAbilityTextForView();
      }

   }

   public int getPlaneswalkerAbilityActivated() {
      return this.planeswalkerAbilityActivated;
   }

   public void addPlaneswalkerAbilityActivated() {
      if (++this.planeswalkerAbilityActivated == 2 && StaticAbilityNumLoyaltyAct.limitIncrease(this)) {
         this.planeswalkerActivationLimitUsed = true;
      }

   }

   public boolean planeswalkerActivationLimitUsed() {
      return this.planeswalkerActivationLimitUsed;
   }

   public void resetActivationsPerTurn() {
      this.planeswalkerAbilityActivated = 0;
      this.planeswalkerActivationLimitUsed = false;
      this.numberTurnActivations.clear();
   }

   public void addCanBlockAdditional(int n, long timestamp) {
      if (n > 0) {
         this.canBlockAdditional.put(timestamp, n);
         this.getView().updateBlockAdditional(this);
      }
   }

   public boolean removeCanBlockAdditional(long timestamp) {
      boolean result = this.canBlockAdditional.remove(timestamp) != null;
      if (result) {
         this.getView().updateBlockAdditional(this);
      }

      return result;
   }

   public int canBlockAdditional() {
      int result = 0;

      for(Integer v : this.canBlockAdditional.values()) {
         result += v;
      }

      return result;
   }

   public void addCanBlockAny(long timestamp) {
      this.canBlockAny.add(timestamp);
      this.getView().updateBlockAdditional(this);
   }

   public boolean removeCanBlockAny(long timestamp) {
      boolean result = this.canBlockAny.remove(timestamp);
      if (result) {
         this.getView().updateBlockAdditional(this);
      }

      return result;
   }

   public boolean canBlockAny() {
      return !this.canBlockAny.isEmpty();
   }

   public void addLethalDamageByPower(long timestamp) {
      if (this.lethalDamageByPower.add(timestamp)) {
         this.getView().updateLethalDamage(this);
      }

   }

   public void removeLethalDamageByPower(long timestamp) {
      if (this.lethalDamageByPower.remove(timestamp)) {
         this.getView().updateLethalDamage(this);
      }

   }

   public boolean isLethalDamageByPower() {
      return !this.lethalDamageByPower.isEmpty();
   }

   public boolean removeChangedState() {
      boolean updateState = false;
      updateState |= this.removeCloneStates();
      updateState |= this.clearChangedCardTypes();
      updateState |= this.clearChangedCardKeywords();
      updateState |= this.clearChangedCardColors();
      updateState |= this.clearChangedCardTraits();
      updateState |= this.clearNewPT();
      updateState |= this.clearChangedName();
      return updateState;
   }

   public CombatLki getCombatLKI() {
      return this.combatLKI;
   }

   public void setCombatLKI(CombatLki combatLKI) {
      this.combatLKI = combatLKI;
   }

   public boolean isAttacking() {
      return this.getCombatLKI() != null ? this.getCombatLKI().isAttacker : this.getGame().getCombat().isAttacking(this);
   }

   public boolean ignoreLegendRule() {
      if (!this.getType().isLegendary()) {
         return true;
      } else {
         return this.getName().isEmpty() && !this.hasNonLegendaryCreatureNames() ? true : StaticAbilityIgnoreLegendRule.ignoreLegendRule(this);
      }
   }

   public boolean ignorePlaneswalkerZeroLoyaltyRule() {
      return !this.getType().isPlaneswalker() ? true : StaticAbilityIgnoreZeroLoyalty.ignorePlaneswalkerZeroLoyaltyRule(this);
   }

   public boolean attackVigilance() {
      return StaticAbilityCantAttackBlock.attackVigilance(this);
   }

   public boolean isAbilitySick() {
      if (!this.isSick()) {
         return false;
      } else {
         return !StaticAbilityActivateAbilityAsIfHaste.canActivate(this);
      }
   }

   public boolean isWitherDamage() {
      return !this.hasKeyword(Keyword.WITHER) && !this.hasKeyword(Keyword.INFECT) ? StaticAbilityWitherDamage.isWitherDamage(this) : true;
   }

   public boolean isInfectDamage(Player target) {
      return this.hasKeyword(Keyword.INFECT) || StaticAbilityInfectDamage.isInfectDamage(target);
   }

   public Set<CardStateName> getUnlockedRooms() {
      return this.unlockedRooms;
   }

   public void setUnlockedRooms(Set<CardStateName> set) {
      this.unlockedRooms = set;
   }

   public List<String> getUnlockedRoomNames() {
      List<String> result = Lists.newArrayList();

      for(CardStateName stateName : this.unlockedRooms) {
         if (this.hasState(stateName)) {
            result.add(this.getState(stateName).getName());
         }
      }

      return result;
   }

   public Set<CardStateName> getLockedRooms() {
      Set<CardStateName> result = Sets.newHashSet(new CardStateName[]{CardStateName.LeftSplit, CardStateName.RightSplit});
      result.removeAll(this.unlockedRooms);
      return result;
   }

   public List<String> getLockedRoomNames() {
      List<String> result = Lists.newArrayList();

      for(CardStateName stateName : this.getLockedRooms()) {
         if (this.hasState(stateName)) {
            result.add(this.getState(stateName).getName());
         }
      }

      return result;
   }

   public boolean unlockRoom(Player p, CardStateName stateName) {
      if (!this.unlockedRooms.contains(stateName) && (stateName == CardStateName.LeftSplit || stateName == CardStateName.RightSplit)) {
         this.unlockedRooms.add(stateName);
         this.updateRooms();
         this.getGame().fireEvent(new GameEventDoorChanged(p, this, stateName, true));
         Map<AbilityKey, Object> unlockParams = AbilityKey.mapFromPlayer(p);
         unlockParams.put(AbilityKey.Card, this);
         unlockParams.put(AbilityKey.CardState, this.getState(stateName));
         this.getGame().getTriggerHandler().runTrigger(TriggerType.UnlockDoor, unlockParams, true);
         if (this.unlockedRooms.size() > 1) {
            Map<AbilityKey, Object> fullyUnlockParams = AbilityKey.mapFromPlayer(p);
            fullyUnlockParams.put(AbilityKey.Card, this);
            this.getGame().getTriggerHandler().runTrigger(TriggerType.FullyUnlock, fullyUnlockParams, true);
         }

         return true;
      } else {
         return false;
      }
   }

   public boolean lockRoom(Player p, CardStateName stateName) {
      if (this.unlockedRooms.contains(stateName) && (stateName == CardStateName.LeftSplit || stateName == CardStateName.RightSplit)) {
         this.unlockedRooms.remove(stateName);
         this.updateRooms();
         this.getGame().fireEvent(new GameEventDoorChanged(p, this, stateName, false));
         return true;
      } else {
         return false;
      }
   }

   public void updateRooms() {
      if (this.isRoom()) {
         if (!this.isFaceDown()) {
            if (this.unlockedRooms.isEmpty()) {
               this.setState(CardStateName.EmptyRoom, true);
            } else if (this.unlockedRooms.size() > 1) {
               this.setState(CardStateName.Original, true);
            } else {
               for(CardStateName name : this.unlockedRooms) {
                  this.setState(name, true);
               }
            }

            this.getGame().getTriggerHandler().clearActiveTriggers(this, (Zone)null);
            this.getGame().getTriggerHandler().registerActiveTrigger(this, false);
         }
      }
   }

   public CardState getEmptyRoomState() {
      if (!this.states.containsKey(CardStateName.EmptyRoom)) {
         this.states.put(CardStateName.EmptyRoom, CardUtil.getEmptyRoomCharacteristic(this));
         this.bumpTraitEpoch(); // Forge Nova: the set of states feeds getAllSpellAbilities()
      }

      return (CardState)this.states.get(CardStateName.EmptyRoom);
   }

   public SpellAbility getUnlockAbility(CardStateName state) {
      if (!this.unlockAbilities.containsKey(state)) {
         this.unlockAbilities.put(state, CardFactoryUtil.abilityUnlockRoom(this.getState(state)));
      }

      return (SpellAbility)this.unlockAbilities.get(state);
   }

   public void copyFrom(Card in) {
      this.changedCardColors.putAll(in.changedCardColors);
      this.changedCardColorsCharacterDefining.putAll(in.changedCardColorsCharacterDefining);
      this.setChangedCardKeywords(in.getChangedCardKeywords());

      for(Table.Cell<Long, Long, List<String>> kw : in.hiddenExtrinsicKeywords.cellSet()) {
         this.hiddenExtrinsicKeywords.put((Long)kw.getRowKey(), (Long)kw.getColumnKey(), (List)kw.getValue());
         this.bumpTraitEpoch();
      }

      this.changedCardTypes.putAll(in.changedCardTypes);
      this.bumpTraitEpoch();
      this.changedCardTypesCharacterDefining.putAll(in.changedCardTypesCharacterDefining);
      this.bumpTraitEpoch();
      this.updateTypeCache();
      this.changedCardNames.putAll(in.changedCardNames);
      this.bumpTraitEpoch(); // Forge Nova: names key NovaStaticSourceIndex.namedCards
      this.setChangedCardTraits(in.getChangedCardTraits());
      this.setChangedCardTraitsByText(in.getChangedCardTraitsByText());
      this.setChangedCardKeywordsByText(in.getChangedCardKeywordsByText());

      for(Map.Entry<CounterType, StaticAbility> e : in.counterTypeKeywordStatic.entrySet()) {
         this.counterTypeKeywordStatic.put((CounterType)e.getKey(), ((StaticAbility)e.getValue()).copy(this, true));
      }
      this.bumpTraitEpoch(); // Forge Nova: getHiddenStaticAbilities() can include the copied statics

   }

   public static enum SplitCMCMode {
      CurrentSideCMC,
      LeftSplitCMC,
      RightSplitCMC;
   }

   private static record CardChangedName(String newName, boolean addNonLegendaryCreatureNames) {
      public boolean isOverwrite() {
         return this.newName != null;
      }
   }

   private static record CardManaCost(ManaCost mana, boolean additional) {
   }

   private static record CardColor(ColorSet color, boolean additional) {
   }

   public static class StatBreakdown {
      public final int currentValue;
      public final int tempBoost;
      public final int bonusFromCounters;

      public StatBreakdown() {
         this.currentValue = 0;
         this.tempBoost = 0;
         this.bonusFromCounters = 0;
      }

      public StatBreakdown(int currentValue, int tempBoost, int bonusFromCounters) {
         this.currentValue = currentValue;
         this.tempBoost = tempBoost;
         this.bonusFromCounters = bonusFromCounters;
      }

      public int getTotal() {
         // Forge Nova: saturated (NovaMath)
         return forge.game.NovaMath.sat((long)this.currentValue + this.tempBoost + this.bonusFromCounters);
      }

      public String toString() {
         return TextUtil.concatWithSpace("c:" + this.currentValue, "tb:" + this.tempBoost, "bfc:" + this.bonusFromCounters);
      }
   }

   private static final class CountKeywordVisitor implements Visitor<KeywordInterface> {
      private String keyword;
      private int count;

      private CountKeywordVisitor(String keyword) {
         this.keyword = keyword;
         this.count = 0;
      }

      public boolean visit(KeywordInterface inst) {
         String kw = inst.getOriginal();
         if (kw.equals(this.keyword)) {
            ++this.count;
         }

         return true;
      }

      public int getCount() {
         return this.count;
      }
   }

   private static final class HasKeywordVisitor implements Visitor<KeywordInterface> {
      private String keyword;
      private final MutableBoolean result = new MutableBoolean(false);
      private boolean startOf;

      private HasKeywordVisitor(String keyword, boolean startOf) {
         this.keyword = keyword;
         this.startOf = startOf;
      }

      public boolean visit(KeywordInterface inst) {
         String kw = inst.getOriginal();
         if (this.startOf && kw.startsWith(this.keyword) || kw.equals(this.keyword)) {
            this.result.setTrue();
         }

         return this.result.isFalse();
      }

      public boolean getResult() {
         return this.result.isTrue();
      }
   }

   private static final class ListKeywordVisitor implements Visitor<KeywordInterface> {
      private List<KeywordInterface> keywords = Lists.newArrayList();

      public boolean visit(KeywordInterface kw) {
         this.keywords.add(kw);
         return true;
      }

      public List<KeywordInterface> getKeywords() {
         return this.keywords;
      }
   }
}
