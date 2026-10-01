package forge.game.staticability;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Lists;
import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameStage;
import forge.game.IIdentifiable;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardState;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.CardTranslation;
import forge.util.Expressions;
import forge.util.FileSection;
import forge.util.ITranslatable;
import forge.util.Lang;
import forge.util.TextUtil;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class StaticAbility extends CardTraitBase implements IIdentifiable, Cloneable, Comparable<StaticAbility> {
   private static int maxId = 0;
   private int id;
   protected EnumSet<ZoneType> validHostZones;
   private Set<StaticAbilityMode> modes;
   private Set<StaticAbilityLayer> layers;
   private CardCollectionView ignoreEffectCards;
   private final List<Player> ignoreEffectPlayers;
   private int mayPlayTurn;
   private SpellAbility payingTrigSA;
   private StaticAbilityView view;

   private static int nextId() {
      return ++maxId;
   }

   public final int getId() {
      return this.id;
   }

   public int hashCode() {
      return Objects.hash(new Object[]{StaticAbility.class, this.getId()});
   }

   public boolean equals(Object obj) {
      return obj instanceof StaticAbility && this.id == ((StaticAbility)obj).id;
   }

   public Set<ZoneType> getActiveZone() {
      return this.validHostZones;
   }

   public void setActiveZone(EnumSet<ZoneType> zones) {
      this.validHostZones = zones;
   }

   public Set<StaticAbilityMode> getMode() {
      return this.modes;
   }

   public void setMode(Set<StaticAbilityMode> modes) {
      this.modes = modes;
   }

   public SpellAbility getPayingTrigSA() {
      if (this.payingTrigSA == null && this.hasParam("Trigger")) {
         this.payingTrigSA = AbilityFactory.getAbility(this.getSVar(this.getParam("Trigger")), this.getHostCard());
         this.payingTrigSA.setIntrinsic(true);
      }

      return this.payingTrigSA;
   }

   private static Map<String, String> parseParams(String abString, Card hostCard) {
      if (abString.length() <= 0) {
         String var10002 = hostCard.getName();
         throw new RuntimeException("StaticEffectFactory : getAbility -- abString too short in " + var10002 + ": [" + abString + "]");
      } else {
         return FileSection.parseToMap(abString, FileSection.DOLLAR_SIGN_KV_SEPARATOR);
      }
   }

   private Set<StaticAbilityLayer> generateLayer() {
      if (!this.checkMode(StaticAbilityMode.Continuous)) {
         return EnumSet.noneOf(StaticAbilityLayer.class);
      } else {
         Set<StaticAbilityLayer> layers = EnumSet.noneOf(StaticAbilityLayer.class);
         if (this.hasParam("GainControl")) {
            layers.add(StaticAbilityLayer.CONTROL);
         }

         if (this.hasParam("ChangeColorWordsTo") || this.hasParam("GainTextOf") || this.hasParam("AddNames") || this.hasParam("SetName") || this.hasParam("Incorporate") || this.hasParam("ManaCost")) {
            layers.add(StaticAbilityLayer.TEXT);
         }

         if (this.hasParam("AddType") || this.hasParam("RemoveType") || this.hasParam("AddAllCreatureTypes") || this.hasParam("RemoveCardTypes") || this.hasParam("RemoveSubTypes") || this.hasParam("RemoveSuperTypes") || this.hasParam("RemoveLandTypes") || this.hasParam("RemoveCreatureTypes") || this.hasParam("RemoveArtifactTypes") || this.hasParam("RemoveEnchantmentTypes")) {
            layers.add(StaticAbilityLayer.TYPE);
         }

         if (this.hasParam("AddColor") || this.hasParam("RemoveColor") || this.hasParam("SetColor")) {
            layers.add(StaticAbilityLayer.COLOR);
         }

         if (this.hasParam("RemoveAllAbilities") || this.hasParam("RemoveNonManaAbilities") || this.hasParam("GainsAbilitiesOf") || this.hasParam("GainsAbilitiesOfDefined") || this.hasParam("GainsTriggerAbsOf") || this.hasParam("AddKeyword") || this.hasParam("AddAbility") || this.hasParam("AddTrigger") || this.hasParam("AddReplacementEffect") || this.hasParam("AddStaticAbility") || this.hasParam("AddSVar") || this.hasParam("CantHaveKeyword") || this.hasParam("ShareRememberedKeywords") || this.hasParam("RemoveKeyword")) {
            layers.add(StaticAbilityLayer.ABILITIES);
         }

         if (this.hasParam("SetPower") || this.hasParam("SetToughness")) {
            layers.add(this.isCharacteristicDefining() ? StaticAbilityLayer.CHARACTERISTIC : StaticAbilityLayer.SETPT);
         }

         if (this.hasParam("AddPower") || this.hasParam("AddToughness")) {
            layers.add(StaticAbilityLayer.MODIFYPT);
         }

         if (this.hasParam("AddHiddenKeyword") || this.hasParam("MayPlay") || this.hasParam("IgnoreEffectCost") || this.hasParam("Goad") || this.hasParam("CanBlockAny") || this.hasParam("CanBlockAmount") || this.hasParam("AdjustLandPlays") || this.hasParam("ControlVote") || this.hasParam("AdditionalVote") || this.hasParam("AdditionalOptionalVote") || this.hasParam("DeclaresAttackers") || this.hasParam("DeclaresBlockers")) {
            layers.add(StaticAbilityLayer.RULES);
         }

         if (layers.isEmpty()) {
            layers.add(StaticAbilityLayer.RULES);
         }

         return layers;
      }
   }

   public boolean isCharacteristicDefining() {
      return this.hasParam("CharacteristicDefining");
   }

   public final String toString() {
      if (this.hasParam("Description") && !this.isSuppressed()) {
         ITranslatable nameSource = this.getHostName(this);
         String desc = CardTranslation.translateSingleDescriptionText(this.getParam("Description"), nameSource);
         String translatedName = nameSource.getTranslatedName();
         desc = TextUtil.fastReplace(desc, "CARDNAME", translatedName);
         desc = TextUtil.fastReplace(desc, "NICKNAME", Lang.getInstance().getNickName(translatedName));
         return desc;
      } else {
         return "";
      }
   }

   public StaticAbility(String params, Card host, CardState state) {
      this(parseParams(params, host), host, state);
   }

   public static StaticAbility create(String params, Card host, CardState state, boolean intrinsic) {
      StaticAbility st = new StaticAbility(params, host, state);
      st.setIntrinsic(intrinsic);
      return st;
   }

   private StaticAbility(Map<String, String> params, Card host, CardState state) {
      this.ignoreEffectCards = new CardCollection();
      this.ignoreEffectPlayers = Lists.newArrayList();
      this.mayPlayTurn = 0;
      this.view = null;
      this.id = nextId();
      this.originalMapParams.putAll(params);
      this.mapParams.putAll(params);
      this.hostCard = host;
      this.setCardState(state);
      if (this.hasParam("EffectZone")) {
         this.setActiveZone(EnumSet.copyOf(ZoneType.listValueOf(this.getParam("EffectZone"))));
      }

      if (this.hasParam("Mode")) {
         this.setMode(StaticAbilityMode.setValueOf(this.getParam("Mode")));
      }

      this.layers = this.generateLayer();
   }

   public StaticAbilityView getView() {
      if (this.view == null) {
         this.view = new StaticAbilityView(this);
      } else {
         this.view.updateHostCard(this);
         this.view.updateDescription(this);
      }

      return this.view;
   }

   public final CardCollectionView applyContinuousAbilityBefore(StaticAbilityLayer layer, CardCollectionView preList) {
      return !this.shouldApplyContinuousAbility(layer, false) ? null : StaticAbilityContinuous.applyContinuousAbility(this, layer, preList);
   }

   public final CardCollectionView applyContinuousAbility(StaticAbilityLayer layer, CardCollectionView affected) {
      return !this.shouldApplyContinuousAbility(layer, true) ? null : StaticAbilityContinuous.applyContinuousAbility(this, affected, layer);
   }

   private boolean shouldApplyContinuousAbility(StaticAbilityLayer layer, boolean previousRun) {
      return this.layers.contains(layer) && this.checkConditions(StaticAbilityMode.Continuous) && (previousRun || this.getHostCard().getStaticAbilities().contains(this) || this.getHostCard().getHiddenStaticAbilities().contains(this));
   }

   public final Cost getAttackCost(Card attacker, GameEntity target, List<Card> attackersWithOptionalCost) {
      if (this.checkMode(StaticAbilityMode.CantAttackUnless) || this.checkMode(StaticAbilityMode.OptionalAttackCost) && attackersWithOptionalCost.contains(attacker)) {
         return !this.checkConditions() ? null : StaticAbilityCantAttackBlock.getAttackCost(this, attacker, target);
      } else {
         return null;
      }
   }

   public final boolean hasAttackCost(Card attacker, Class<? extends CostPart> costType) {
      return !this.checkConditions(StaticAbilityMode.OptionalAttackCost) ? false : StaticAbilityCantAttackBlock.getAttackCost(this, attacker, (GameEntity)null).hasSpecificCostType(costType);
   }

   public final Cost getBlockCost(Card blocker, Card attacker) {
      return !this.checkConditions(StaticAbilityMode.CantBlockUnless) ? null : StaticAbilityCantAttackBlock.getBlockCost(this, blocker, attacker);
   }

   public final boolean checkMode(StaticAbilityMode mode) {
      if (forge.game.card.TraitEpoch.VERIFY && this.modes.contains(mode)) {
         StaticAbilityModeRegistry.verifySeen(mode, this); // Forge Nova: the registry must know every mode in use
      }
      return this.modes.contains(mode);
   }

   public final boolean checkConditions(StaticAbilityMode mode) {
      return this.checkMode(mode) && this.checkConditions();
   }

   public final boolean zonesCheck() {
      if (this.isSuppressed()) {
         return false;
      } else if (this.getHostCard().isPhasedOut()) {
         return false;
      } else {
         if (!this.isCharacteristicDefining()) {
            if (this.validHostZones != null) {
               Zone zone = this.getHostCard().getGame().getZoneOf(this.getHostCard());
               if (zone == null || !this.validHostZones.contains(zone.getZoneType())) {
                  return false;
               }
            } else if (!this.getHostCard().isInPlay()) {
               return false;
            }
         }

         return true;
      }
   }

   public final boolean checkConditions() {
      Player controller = this.getHostCard().getController();
      Game game = this.getHostCard().getGame();
      PhaseHandler ph = game.getPhaseHandler();
      if (!this.zonesCheck()) {
         return false;
      } else {
         String condition = this.getParam("Condition");
         if (null != condition) {
            if (condition.equals("Threshold") && !controller.hasThreshold()) {
               return false;
            }

            if (condition.equals("Hellbent") && !controller.hasHellbent()) {
               return false;
            }

            if (condition.equals("Metalcraft") && !controller.hasMetalcraft()) {
               return false;
            }

            if (condition.equals("Delirium") && !controller.hasDelirium()) {
               return false;
            }

            if (condition.equals("Ferocious") && !controller.hasFerocious()) {
               return false;
            }

            if (condition.equals("Blessing") && !controller.hasBlessing()) {
               return false;
            }

            if (condition.equals("EnduringStory") && !controller.hasEnduringStory()) {
               return false;
            }

            if (condition.equals("Monarch") & !controller.isMonarch()) {
               return false;
            }

            if (condition.equals("Night") & !game.isNight()) {
               return false;
            }

            if (condition.equals("MaxSpeed") && !controller.maxSpeed()) {
               return false;
            }

            if (condition.equals("PlayerTurn")) {
               if (!ph.isPlayerTurn(controller)) {
                  return false;
               }
            } else if (condition.equals("NotPlayerTurn")) {
               if (ph.isPlayerTurn(controller)) {
                  return false;
               }
            } else if (condition.equals("ExtraTurn")) {
               if (!game.getPhaseHandler().getPlayerTurn().isExtraTurn()) {
                  return false;
               }
            } else if (condition.equals("FatefulHour") && controller.getLife() > 5) {
               return false;
            }
         }

         if (this.hasParam("Phases") && !PhaseType.parseRange(this.getParam("Phases")).contains(ph.getPhase())) {
            return false;
         } else {
            if (this.hasParam("PlayerTurn")) {
               List<Player> players = AbilityUtils.getDefinedPlayers(this.hostCard, this.getParam("PlayerTurn"), this);
               if (!players.contains(ph.getPlayerTurn())) {
                  return false;
               }
            }

            if (this.hasParam("TopCardOfLibraryIs")) {
               if (controller.getCardsIn(ZoneType.Library).isEmpty()) {
                  return false;
               }

               Card topCard = (Card)controller.getCardsIn(ZoneType.Library).get(0);
               if (!topCard.isValid(this.getParam("TopCardOfLibraryIs").split(","), controller, this.hostCard, this)) {
                  return false;
               }
            }

            if (this.hasParam("IsPresent")) {
               List<ZoneType> zone = this.hasParam("PresentZone") ? ZoneType.listValueOf(this.getParam("PresentZone")) : List.of(ZoneType.Battlefield);
               String compare = this.getParamOrDefault("PresentCompare", "GE1");
               CardCollectionView list = game.getCardsIn((Iterable)zone);
               String present = this.getParam("IsPresent");
               list = CardLists.getValidCards(list, (String)present, controller, this.hostCard, this);
               int right = 1;
               String rightString = compare.substring(2);
               right = AbilityUtils.calculateAmount(this.hostCard, rightString, this);
               int left = list.size();
               if (!Expressions.compare(left, compare, right)) {
                  return false;
               }
            }

            if (this.hasParam("GameStage")) {
               String[] stageDefs = TextUtil.split(this.getParam("GameStage"), ',');
               boolean isRelevantStage = false;

               for(String stage : stageDefs) {
                  isRelevantStage |= game.getAge() == GameStage.valueOf(stage);
               }

               return isRelevantStage;
            } else {
               if (this.hasParam("ClassLevel")) {
                  int level = this.hostCard.getClassLevel();
                  int levelMin = Integer.parseInt(this.getParam("ClassLevel"));
                  if (level < levelMin) {
                     return false;
                  }
               }

               if (this.hasParam("CheckSVar")) {
                  int sVar = AbilityUtils.calculateAmount(this.hostCard, this.getParam("CheckSVar"), this);
                  String comparator = this.getParamOrDefault("SVarCompare", "GE1");
                  String svarOperator = comparator.substring(0, 2);
                  String svarOperand = comparator.substring(2);
                  int operandValue = AbilityUtils.calculateAmount(this.hostCard, svarOperand, this);
                  if (!Expressions.compare(sVar, svarOperator, operandValue)) {
                     return false;
                  } else if (this.hasParam("CheckSecondSVar")) {
                     sVar = AbilityUtils.calculateAmount(this.hostCard, this.getParam("CheckSecondSVar"), this);
                     comparator = this.getParamOrDefault("SecondSVarCompare", "GE1");
                     svarOperator = comparator.substring(0, 2);
                     svarOperand = comparator.substring(2);
                     operandValue = AbilityUtils.calculateAmount(this.hostCard, svarOperand, this);
                     if (!Expressions.compare(sVar, svarOperator, operandValue)) {
                        return false;
                     } else if (this.hasParam("CheckThirdSVar")) {
                        sVar = AbilityUtils.calculateAmount(this.hostCard, this.getParam("CheckThirdSVar"), this);
                        comparator = this.getParamOrDefault("ThirdSVarCompare", "GE1");
                        svarOperator = comparator.substring(0, 2);
                        svarOperand = comparator.substring(2);
                        operandValue = AbilityUtils.calculateAmount(this.hostCard, svarOperand, this);
                        if (!Expressions.compare(sVar, svarOperator, operandValue)) {
                           return false;
                        } else {
                           if (this.hasParam("CheckFourthSVar")) {
                              sVar = AbilityUtils.calculateAmount(this.hostCard, this.getParam("CheckFourthSVar"), this);
                              comparator = this.getParamOrDefault("FourthSVarCompare", "GE1");
                              svarOperator = comparator.substring(0, 2);
                              svarOperand = comparator.substring(2);
                              operandValue = AbilityUtils.calculateAmount(this.hostCard, svarOperand, this);
                              if (!Expressions.compare(sVar, svarOperator, operandValue)) {
                                 return false;
                              }
                           }

                           return true;
                        }
                     } else {
                        return true;
                     }
                  } else {
                     return true;
                  }
               } else {
                  return true;
               }
            }
         }
      }
   }

   public CardCollectionView getIgnoreEffectCards() {
      return this.ignoreEffectCards;
   }

   public void setIgnoreEffectCards(CardCollectionView cards) {
      this.ignoreEffectCards = cards;
   }

   public List<Player> getIgnoreEffectPlayers() {
      return this.ignoreEffectPlayers;
   }

   public void addIgnoreEffectPlayers(Player p) {
      this.ignoreEffectPlayers.add(p);
   }

   public void clearIgnoreEffects() {
      this.ignoreEffectPlayers.clear();
      this.ignoreEffectCards = new CardCollection();
   }

   public Set<StaticAbilityLayer> getLayers() {
      return this.layers;
   }

   public int getMayPlayTurn() {
      return this.mayPlayTurn + (int)this.hostCard.getGame().getStack().getSpellsCastThisTurn().stream().filter((sp) -> this.equals(sp.getMayPlay())).count();
   }

   public void incMayPlayTurn() {
      ++this.mayPlayTurn;
   }

   public void resetMayPlayTurn() {
      this.mayPlayTurn = 0;
   }

   public int compareTo(StaticAbility arg0) {
      return ComparisonChain.start().compare(this.getHostCard(), arg0.getHostCard()).compare(this.getId(), arg0.getId()).result();
   }

   public long getTimestamp() {
      return this.hasParam("Timestamp") ? Long.valueOf(this.getParam("Timestamp")) : this.getHostCard().getLayerTimestamp();
   }

   public void setHostCard(Card host) {
      super.setHostCard(host);
      if (this.payingTrigSA != null) {
         this.payingTrigSA.setHostCard(host);
      }

   }

   public final StaticAbility copy(Card newHost, boolean lki) {
      return this.copy(newHost, lki, false);
   }

   public StaticAbility copy(Card host, boolean lki, boolean keepTextChanges) {
      StaticAbility clone = null;

      try {
         clone = (StaticAbility)this.clone();
         clone.id = lki ? this.id : nextId();
         this.copyHelper(clone, host, lki || keepTextChanges);
         clone.payingTrigSA = null;
         if (!lki) {
            clone.mayPlayTurn = 0;
         }

         clone.layers = this.generateLayer();
         if (this.validHostZones != null) {
            clone.setActiveZone(EnumSet.copyOf(this.validHostZones));
         }

         if (this.modes != null) {
            clone.setMode(EnumSet.copyOf(this.modes));
         }
      } catch (CloneNotSupportedException e) {
         System.err.println(e);
      }

      return clone;
   }
}
