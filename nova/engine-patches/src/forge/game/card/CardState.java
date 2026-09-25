package forge.game.card;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.UnmodifiableIterator;
import forge.card.CardRarity;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.CardTypeView;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.CardTraitBase;
import forge.game.GameObject;
import forge.game.IHasSVars;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.cost.Cost;
import forge.game.keyword.IKeywordsChange;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordCollection;
import forge.game.keyword.KeywordInterface;
import forge.game.keyword.KeywordWithType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.LandAbility;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellPermanent;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.util.CardTranslation;
import forge.util.ITranslatable;
import forge.util.IterableUtil;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;

public class CardState implements GameObject, IHasSVars, ITranslatable {
   private String name;
   private CardType type;
   private CardTypeView changedType;
   private ManaCost manaCost;
   private ManaCost perpetualAdjustedManaCost;
   private ColorSet color;
   private String oracleText;
   private String functionalVariantName;
   private String flavorName;
   private int basePower;
   private int baseToughness;
   private String basePowerString;
   private String baseToughnessString;
   private String baseLoyalty;
   private String baseDefense;
   private KeywordCollection intrinsicKeywords;
   private Set<Integer> attractionLights;
   private final FCollection<SpellAbility> abilities;
   private FCollection<Trigger> triggers;
   private FCollection<ReplacementEffect> replacementEffects;
   private FCollection<StaticAbility> staticAbilities;
   private String imageKey;
   private Map<String, String> sVars;
   private Map<String, SpellAbility> abilityForTrigger;
   private KeywordCollection cachedKeywords;
   private CardRarity rarity;
   private String setCode;
   private final CardView.CardStateView view;
   private final Card card;
   private SpellAbility landAbility;
   private SpellAbility auraAbility;
   private SpellAbility permanentAbility;
   private Map<MagicColor.Color, SpellAbility> landManaAbilities;
   private ReplacementEffect loyaltyRep;
   private ReplacementEffect defenseRep;
   private ReplacementEffect sagaRep;
   private ReplacementEffect adventureRep;
   private ReplacementEffect omenRep;
   private SpellAbility manifestUp;
   private SpellAbility cloakUp;
   private List<LandTraitChanges> landTraitChanges;

   public CardState(Card card, CardStateName name) {
      this(card.getView().createAlternateState(name), card);
   }

   public CardState(CardView.CardStateView view0, Card card0) {
      this.name = "";
      this.type = new CardType(false);
      this.changedType = null;
      this.manaCost = ManaCost.NO_COST;
      this.perpetualAdjustedManaCost = null;
      this.color = ColorSet.C;
      this.oracleText = "";
      this.functionalVariantName = null;
      this.flavorName = null;
      this.basePower = 0;
      this.baseToughness = 0;
      this.basePowerString = null;
      this.baseToughnessString = null;
      this.baseLoyalty = "";
      this.baseDefense = "";
      this.intrinsicKeywords = new KeywordCollection();
      this.attractionLights = null;
      this.abilities = new FCollection<SpellAbility>();
      this.triggers = new FCollection<Trigger>();
      this.replacementEffects = new FCollection<ReplacementEffect>();
      this.staticAbilities = new FCollection<StaticAbility>();
      this.imageKey = "";
      this.sVars = Maps.newTreeMap();
      this.abilityForTrigger = Maps.newHashMap();
      this.cachedKeywords = new KeywordCollection();
      this.rarity = CardRarity.Unknown;
      this.setCode = "???";
      this.landManaAbilities = Maps.newEnumMap(MagicColor.Color.class);
      this.landTraitChanges = List.of(new LandTraitChanges(this));
      this.view = view0;
      this.card = card0;
      this.view.updateRarity(this);
      this.view.updateSetCode(this);
   }

   public CardView.CardStateView getView() {
      return this.view;
   }

   public Card getCard() {
      return this.card;
   }

   public final String getName() {
      return this.name;
   }

   public final void setName(String name0) {
      this.name = name0;
      this.view.updateName(this);
   }

   public CardStateName getStateName() {
      return this.getView().getState();
   }

   public String toString() {
      String var10000 = this.name;
      return var10000 + " (" + String.valueOf(this.view.getState()) + ")";
   }

   public CardTypeView getTypeWithChanges() {
      return (CardTypeView)Objects.requireNonNullElse(this.changedType, this.getType());
   }

   public void updateTypes() {
      this.bumpEpoch();
      this.changedType = this.getType().getTypeWithChanges(this.card.getChangedCardTypes());
   }

   public void updateTypesForView() {
      this.view.updateType(this);
   }

   public final CardTypeView getType() {
      return this.type;
   }

   public final void addType(String type0) {
      this.bumpEpoch();
      if (this.type.add(type0)) {
         this.updateTypes();
         this.updateTypesForView();
      }

   }

   public final void addType(Iterable<String> type0) {
      this.bumpEpoch();
      if (this.type.addAll(type0)) {
         this.updateTypes();
         this.updateTypesForView();
      }

   }

   public final void setType(CardType type0) {
      this.bumpEpoch();
      if (type0 != this.type) {
         if (!type0.isEmpty() || !this.type.isEmpty()) {
            this.type.clear();
            this.type.addAll(type0);
            this.updateTypes();
            this.updateTypesForView();
         }
      }
   }

   public final void removeType(CardType.Supertype st) {
      this.bumpEpoch();
      if (this.type.remove(st)) {
         this.updateTypes();
         this.updateTypesForView();
      }

   }

   public final void removeCardTypes(boolean sanisfy) {
      this.bumpEpoch();
      this.type.removeCardTypes();
      if (sanisfy) {
         this.type.sanisfySubtypes();
      }

      this.updateTypes();
      this.updateTypesForView();
   }

   public final void setCreatureTypes(Collection<String> ctypes) {
      this.bumpEpoch();
      if (this.type.setCreatureTypes(ctypes)) {
         this.updateTypes();
         this.updateTypesForView();
      }

   }

   public final ManaCost getManaCost() {
      return this.manaCost;
   }

   public final void setManaCost(ManaCost manaCost0) {
      this.manaCost = manaCost0;
      this.view.updateManaCost(this);
   }

   public void calculatePerpetualAdjustedManaCost() {
      if (this.getCard() != null && this.getCard().getGame() != null && (this.manaCost.getGenericCost() != 0 || this.manaCost.getShardCount(ManaCostShard.X) != 0)) {
         int genericCostAdjustment = 0;

         for(StaticAbility stAb : this.getStaticAbilities()) {
            if ("Card.Self".equals(stAb.getParam("ValidCard"))) {
               int reduceOrRaise = 0;
               if (stAb.checkMode(StaticAbilityMode.ReduceCost)) {
                  reduceOrRaise = 1;
               } else if (stAb.checkMode(StaticAbilityMode.RaiseCost)) {
                  reduceOrRaise = -1;
               }

               if (reduceOrRaise != 0) {
                  try {
                     genericCostAdjustment += Integer.parseInt(stAb.getParamOrDefault("Amount", "1")) * reduceOrRaise;
                  } catch (NumberFormatException var6) {
                  }
               }
            }
         }

         if (genericCostAdjustment != 0) {
            int newGeneric = this.manaCost.getGenericCost() - genericCostAdjustment;
            if (this.manaCost.getShardCount(ManaCostShard.X) == 0) {
               newGeneric = Math.max(0, newGeneric);
            }

            String var10003 = newGeneric != 0 ? newGeneric + " " : "";
            this.perpetualAdjustedManaCost = new ManaCost(var10003 + this.manaCost.getShortString().replace("" + this.manaCost.getGenericCost(), ""));
            this.view.updateManaCost(this);
         }
      }
   }

   public ManaCost getPerpetualAdjustedManaCost() {
      return this.perpetualAdjustedManaCost == null ? this.getManaCost() : this.perpetualAdjustedManaCost;
   }

   public final ColorSet getColor() {
      return this.color;
   }

   public final void addColor(ColorSet color) {
      this.color = ColorSet.combine(this.color, color);
      this.view.updateColors(this.card);
   }

   public final void setColor(ColorSet color) {
      this.color = color;
      this.view.updateColors(this.card);
   }

   public String getOracleText() {
      return this.oracleText;
   }

   public void setOracleText(String oracleText) {
      this.oracleText = oracleText;
      this.view.setOracleText(oracleText);
   }

   public String getFunctionalVariantName() {
      return this.functionalVariantName;
   }

   public void setFunctionalVariantName(String functionalVariantName) {
      if (functionalVariantName != null && functionalVariantName.isEmpty()) {
         functionalVariantName = null;
      }

      this.functionalVariantName = functionalVariantName;
      this.view.setFunctionalVariantName(functionalVariantName);
   }

   public String getFlavorName() {
      return this.flavorName;
   }

   public void setFlavorName(String flavorName) {
      this.flavorName = flavorName;
      this.view.updateName(this);
   }

   public final int getBasePower() {
      return this.basePower;
   }

   public final void setBasePower(int basePower0) {
      if (this.basePower != basePower0) {
         this.basePower = basePower0;
         this.view.updatePower(this);
      }
   }

   public final int getBaseToughness() {
      return this.baseToughness;
   }

   public final void setBaseToughness(int baseToughness0) {
      if (this.baseToughness != baseToughness0) {
         this.baseToughness = baseToughness0;
         this.view.updateToughness(this);
      }
   }

   public final String getBasePowerString() {
      return this.basePowerString;
   }

   public final String getBaseToughnessString() {
      return this.baseToughnessString;
   }

   public final void setBasePowerString(String s) {
      this.basePowerString = s;
   }

   public final void setBaseToughnessString(String s) {
      this.baseToughnessString = s;
   }

   public final boolean hasPrintedPT() {
      return !StringUtils.isEmpty(this.basePowerString) || !StringUtils.isEmpty(this.baseToughnessString);
   }

   public String getBaseLoyalty() {
      return this.baseLoyalty;
   }

   public final void setBaseLoyalty(String string) {
      this.baseLoyalty = string;
      this.view.updateLoyalty(this);
   }

   public String getBaseDefense() {
      return this.baseDefense;
   }

   public final void setBaseDefense(String string) {
      this.baseDefense = string;
      this.view.updateDefense(this);
   }

   public Set<Integer> getAttractionLights() {
      return this.attractionLights;
   }

   public final void setAttractionLights(Set<Integer> attractionLights) {
      this.attractionLights = attractionLights;
      this.view.updateAttractionLights(this);
   }

   public final KeywordCollection getCachedKeywords() {
      return this.cachedKeywords;
   }

   public final Collection<KeywordInterface> getCachedKeyword(Keyword keyword) {
      return this.cachedKeywords.getValues(keyword);
   }

   public final void setCachedKeywords(KeywordCollection col) {
      this.bumpEpoch();
      this.cachedKeywords = col;
   }

   public final boolean hasKeyword(Keyword key) {
      return this.cachedKeywords.contains(key);
   }

   public final Collection<KeywordInterface> getIntrinsicKeywords() {
      return this.intrinsicKeywords.getValues();
   }

   public final boolean hasIntrinsicKeyword(String k) {
      return this.intrinsicKeywords.contains(k);
   }

   public final boolean hasIntrinsicKeyword(Keyword k) {
      return this.intrinsicKeywords.contains(k);
   }

   public final void setIntrinsicKeywords(Iterable<KeywordInterface> intrinsicKeyword0, boolean lki) {
      this.bumpEpoch();
      this.intrinsicKeywords.clear();

      for(KeywordInterface k : intrinsicKeyword0) {
         this.intrinsicKeywords.insert(k.copy(this.card, lki));
      }

      this.card.updateKeywordsCache(this);
   }

   public final KeywordInterface addIntrinsicKeyword(String s, boolean initTraits) {
      this.bumpEpoch();
      if (s.trim().length() == 0) {
         return null;
      } else {
         KeywordInterface inst = null;

         try {
            inst = this.intrinsicKeywords.add(s);
         } catch (Exception e) {
            String msg = "CardState:addIntrinsicKeyword: failed to parse Keyword";
            Breadcrumb bread = new Breadcrumb(msg);
            bread.setData("Card", this.card.getName());
            bread.setData("Keyword", s);
            Sentry.addBreadcrumb(bread);
            throw new RuntimeException("Error in Keyword " + s + " for card " + this.card.getName(), e);
         }

         if (inst != null && initTraits) {
            inst.createTraits(this.card, true);
         }

         return inst;
      }
   }

   public final boolean addIntrinsicKeywords(Iterable<String> keywords) {
      this.bumpEpoch();
      return this.addIntrinsicKeywords(keywords, true);
   }

   public final boolean addIntrinsicKeywords(Iterable<String> keywords, boolean initTraits) {
      this.bumpEpoch();
      boolean changed = false;

      for(String k : keywords) {
         if (this.addIntrinsicKeyword(k, initTraits) != null) {
            changed = true;
         }
      }

      return changed;
   }

   public final boolean removeIntrinsicKeyword(String s) {
      this.bumpEpoch();
      return this.intrinsicKeywords.remove(s);
   }

   public final boolean removeIntrinsicKeyword(KeywordInterface s) {
      this.bumpEpoch();
      return this.intrinsicKeywords.remove(s);
   }

   public final boolean removeIntrinsicKeyword(Keyword k) {
      this.bumpEpoch();
      return this.intrinsicKeywords.removeAll(k);
   }


   // ------------------------------------------------------------------------------------------
   // Forge Nova: epoch-keyed caches for ability lists (see forge.game.card.TraitEpoch).
   // Each cache holds {epoch, list}; the epoch is read BEFORE computing, so a concurrent change
   // always invalidates the entry. With -Dnova.verifyCaches=true every hit is re-validated.
   // ------------------------------------------------------------------------------------------
   private volatile Object[] novaSpellAbilities;
   private volatile Object[] novaManaAbilities;
   private volatile Object[] novaNonManaAbilities;
   private volatile Object[] novaTriggers;
   private volatile Object[] novaStatics;
   private volatile Object[] novaReplacements;

   private void bumpEpoch() {
      if (this.card != null) {
         this.card.bumpTraitEpoch();
      }
   }

   private static void novaVerify(String what, CardState st, FCollectionView<?> cached, FCollectionView<?> fresh) {
      if (!TraitEpoch.sameElements(cached, fresh)) {
         TraitEpoch.mismatch(what, st.getCard(), TraitEpoch.toList(cached), TraitEpoch.toList(fresh));
      }
   }

   @SuppressWarnings("unchecked")
   private static <T> FCollectionView<T> novaHit(Object[] c, long epoch) {
      if (c != null && ((Long) c[0]) == epoch) {
         TraitEpoch.HITS.incrementAndGet();
         return (FCollectionView<T>) c[1];
      }
      return null;
   }

   public final FCollectionView<SpellAbility> getSpellAbilities() {
      if (TraitEpoch.DISABLED || this.card == null) return this.computeSpellAbilities();
      long ep = this.card.getTraitEpoch();
      FCollectionView<SpellAbility> r = novaHit(this.novaSpellAbilities, ep);
      if (r != null) {
         if (TraitEpoch.VERIFY) { FCollectionView<?> fresh = this.computeSpellAbilities(); if (this.card.getTraitEpoch() == ep) novaVerify("getSpellAbilities", this, r, fresh); }
         return r;
      }
      TraitEpoch.MISSES.incrementAndGet();
      r = this.computeSpellAbilities();
      this.novaSpellAbilities = new Object[]{ep, r};
      return r;
   }

   public final FCollectionView<SpellAbility> getManaAbilities() {
      if (TraitEpoch.DISABLED || this.card == null) return this.computeManaAbilities();
      long ep = this.card.getTraitEpoch();
      FCollectionView<SpellAbility> r = novaHit(this.novaManaAbilities, ep);
      if (r != null) {
         if (TraitEpoch.VERIFY) { FCollectionView<?> fresh = this.computeManaAbilities(); if (this.card.getTraitEpoch() == ep) novaVerify("getManaAbilities", this, r, fresh); }
         return r;
      }
      TraitEpoch.MISSES.incrementAndGet();
      r = this.computeManaAbilities();
      this.novaManaAbilities = new Object[]{ep, r};
      return r;
   }

   public final FCollectionView<SpellAbility> getNonManaAbilities() {
      if (TraitEpoch.DISABLED || this.card == null) return this.computeNonManaAbilities();
      long ep = this.card.getTraitEpoch();
      FCollectionView<SpellAbility> r = novaHit(this.novaNonManaAbilities, ep);
      if (r != null) {
         if (TraitEpoch.VERIFY) { FCollectionView<?> fresh = this.computeNonManaAbilities(); if (this.card.getTraitEpoch() == ep) novaVerify("getNonManaAbilities", this, r, fresh); }
         return r;
      }
      TraitEpoch.MISSES.incrementAndGet();
      r = this.computeNonManaAbilities();
      this.novaNonManaAbilities = new Object[]{ep, r};
      return r;
   }

   public final FCollectionView<Trigger> getTriggers() {
      if (TraitEpoch.DISABLED || this.card == null) return this.computeTriggers();
      long ep = this.card.getTraitEpoch();
      FCollectionView<Trigger> r = novaHit(this.novaTriggers, ep);
      if (r != null) {
         if (TraitEpoch.VERIFY) { FCollectionView<?> fresh = this.computeTriggers(); if (this.card.getTraitEpoch() == ep) novaVerify("getTriggers", this, r, fresh); }
         return r;
      }
      TraitEpoch.MISSES.incrementAndGet();
      r = this.computeTriggers();
      this.novaTriggers = new Object[]{ep, r};
      return r;
   }

   public final FCollectionView<StaticAbility> getStaticAbilities() {
      if (TraitEpoch.DISABLED || this.card == null) return this.computeStaticAbilities();
      long ep = this.card.getTraitEpoch();
      FCollectionView<StaticAbility> r = novaHit(this.novaStatics, ep);
      if (r != null) {
         if (TraitEpoch.VERIFY) { FCollectionView<?> fresh = this.computeStaticAbilities(); if (this.card.getTraitEpoch() == ep) novaVerify("getStaticAbilities", this, r, fresh); }
         return r;
      }
      TraitEpoch.MISSES.incrementAndGet();
      r = this.computeStaticAbilities();
      this.novaStatics = new Object[]{ep, r};
      return r;
   }

   public FCollectionView<ReplacementEffect> getReplacementEffects(boolean rulesHost) {
      if (TraitEpoch.DISABLED || this.card == null) return this.computeReplacementEffects(rulesHost);
      long ep = this.card.getTraitEpoch();
      FCollectionView<ReplacementEffect> base = novaHit(this.novaReplacements, ep);
      if (base == null) {
         TraitEpoch.MISSES.incrementAndGet();
         base = this.computeReplacementEffects(false);
         this.novaReplacements = new Object[]{ep, base};
      }
      FCollectionView<ReplacementEffect> r = base;
      if (rulesHost) {
         // counter- and type-dependent extras are cheap; append them exactly as the original does
         boolean counters = this.card.hasCounterReplacementEffects();
         boolean adventure = this.type.hasSubtype("Adventure");
         boolean omen = this.type.hasSubtype("Omen");
         if (counters || adventure || omen) {
            FCollection<ReplacementEffect> result = new FCollection<ReplacementEffect>(base);
            if (counters) {
               this.card.updateCounterReplacementEffects(result);
            }
            if (adventure) {
               if (this.adventureRep == null) {
                  this.adventureRep = CardFactoryUtil.setupAdventureAbility(this);
               }
               result.add(this.adventureRep);
            }
            if (omen) {
               if (this.omenRep == null) {
                  this.omenRep = CardFactoryUtil.setupOmenAbility(this);
               }
               result.add(this.omenRep);
            }
            r = result;
         }
      }
      if (TraitEpoch.VERIFY) {
         FCollectionView<ReplacementEffect> fresh = this.computeReplacementEffects(rulesHost);
         if (this.card.getTraitEpoch() == ep) {
            novaVerify(rulesHost ? "replacementEffects(host)" : "replacementEffects", this, r, fresh);
         }
      }
      return r;
   }

   private FCollectionView<SpellAbility> computeSpellAbilities() {
      FCollection<SpellAbility> newCol = new FCollection<SpellAbility>();
      this.updateSpellAbilities(newCol);
      newCol.addAll(this.abilities);
      this.card.updateSpellAbilities(newCol, this);
      return newCol;
   }

   private FCollectionView<SpellAbility> computeManaAbilities() {
      FCollection<SpellAbility> newCol = new FCollection<SpellAbility>();
      this.updateSpellAbilities(newCol);
      newCol.addAll(this.abilities);
      this.card.updateSpellAbilities(newCol, this);
      newCol.removeIf(Predicate.not(SpellAbility::isManaAbility));
      return newCol;
   }

   private FCollectionView<SpellAbility> computeNonManaAbilities() {
      FCollection<SpellAbility> newCol = new FCollection<SpellAbility>();
      this.updateSpellAbilities(newCol);
      newCol.addAll(this.abilities);
      this.card.updateSpellAbilities(newCol, this);
      newCol.removeIf(SpellAbility::isManaAbility);
      return newCol;
   }

   protected final void updateSpellAbilities(FCollection<SpellAbility> newCol) {
      if (this.getStateName().equals(CardStateName.Original)) {
         if (this.getCard().hasState(CardStateName.LeftSplit)) {
            CardState leftState = this.getCard().getState(CardStateName.LeftSplit);
            newCol.addAll(leftState.abilities);
            leftState.updateSpellAbilities(newCol);
         }

         if (this.getCard().hasState(CardStateName.RightSplit)) {
            CardState rightState = this.getCard().getState(CardStateName.RightSplit);
            newCol.addAll(rightState.abilities);
            rightState.updateSpellAbilities(newCol);
         }
      }

      switch (this.getStateName()) {
         case Backside:
            if (!this.getCard().isModal()) {
               return;
            }
         case Original:
         case LeftSplit:
         case RightSplit:
         case SpecializeB:
         case SpecializeG:
         case SpecializeR:
         case SpecializeU:
         case SpecializeW:
            break;
         default:
            return;
      }

      if (!this.getStateName().equals(CardStateName.Original) || !this.getCard().hasState(CardStateName.LeftSplit) && !this.getCard().hasState(CardStateName.RightSplit)) {
         CardTypeView type = this.getTypeWithChanges();
         if (type.isLand()) {
            if (this.landAbility == null) {
               this.landAbility = new LandAbility(this.card, this);
            }

            newCol.add(this.landAbility);
         } else if (type.isAura()) {
            newCol.add(this.getAuraSpell());
         } else if (type.isPermanent()) {
            if (this.abilities.anyMatch((s) -> s.isBasicSpell() && s.getSubAbility() == null && (ApiType.PermanentCreature.equals(s.getApi()) || ApiType.PermanentNoncreature.equals(s.getApi())))) {
               return;
            }

            if (this.permanentAbility == null) {
               this.permanentAbility = new SpellPermanent(this.card, this);
            }

            newCol.add(this.permanentAbility);
         }

      }
   }

   public SpellAbility getLandManaForColor(MagicColor.Color c) {
      return (SpellAbility)this.landManaAbilities.computeIfAbsent(c, (a) -> {
         String var10000 = a.getShortName();
         String abString = "AB$ Mana | Cost$ T | Produced$ " + var10000 + " | Secondary$ True | SpellDescription$ Add " + a.getSymbol() + ".";
         SpellAbility sa = AbilityFactory.getAbility(abString, this);
         sa.setIntrinsic(true);
         return sa;
      });
   }

   public List<LandTraitChanges> getLandTraitChanges() {
      return this.landTraitChanges;
   }

   public final Iterable<SpellAbility> getIntrinsicSpellAbilities() {
      return IterableUtil.filter(this.getSpellAbilities(), CardTraitBase::isIntrinsic);
   }

   public final SpellAbility getFirstAbility() {
      return (SpellAbility)Iterables.getFirst(this.getIntrinsicSpellAbilities(), (Object)null);
   }

   public final SpellAbility getFirstSpellAbility() {
      return this.card.getCastSA() != null ? this.card.getCastSA() : (SpellAbility)Iterables.getFirst(this.getNonManaAbilities(), (Object)null);
   }

   public final SpellAbility getFirstSpellAbilityWithFallback() {
      SpellAbility sa = this.getFirstSpellAbility();
      CardTypeView type = this.getTypeWithChanges();
      if (sa == null && !type.isLand()) {
         if (type.isAura()) {
            return this.getAuraSpell();
         } else {
            if (this.permanentAbility == null) {
               this.permanentAbility = new SpellPermanent(this.card, this);
            }

            return this.permanentAbility;
         }
      } else {
         return sa;
      }
   }

   public final SpellAbility getAuraSpell() {
      CardTypeView type = this.getTypeWithChanges();
      if (!type.isAura()) {
         return null;
      } else {
         if (this.auraAbility == null) {
            String desc = "";
            String extra = "";
            Iterator var4 = this.getCachedKeyword(Keyword.ENCHANT).iterator();
            if (var4.hasNext()) {
               KeywordInterface ki = (KeywordInterface)var4.next();
               if (ki instanceof KeywordWithType) {
                  KeywordWithType kwt = (KeywordWithType)ki;
                  desc = kwt.getTypeDescription();
               }
            }

            if (this.hasSVar("AttachAITgts")) {
               extra = extra + " | AITgts$ " + this.getSVar("AttachAITgts");
            }

            if (this.hasSVar("AttachAILogic")) {
               extra = extra + " | AILogic$ " + this.getSVar("AttachAILogic");
            }

            if (this.hasSVar("AttachAIValid")) {
               extra = extra + " | AIValid$ " + this.getSVar("AttachAIValid");
            }

            String st = "SP$ Attach | ValidTgts$ Card.CanBeEnchantedBy,Player.CanBeEnchantedBy | TgtZone$ Battlefield,Graveyard | ValidTgtsDesc$ " + desc + extra;
            this.auraAbility = AbilityFactory.getAbility(st, this);
            this.auraAbility.setIntrinsic(true);
         }

         return this.auraAbility;
      }
   }

   public final boolean hasSpellAbility(SpellAbility sa) {
      return this.getSpellAbilities().contains(sa);
   }

   public final boolean hasSpellAbility(int id) {
      for(SpellAbility sa : this.getSpellAbilities()) {
         if (id == sa.getId()) {
            return true;
         }
      }

      return false;
   }

   public final boolean addSpellAbility(SpellAbility a) {
      this.bumpEpoch();
      return this.abilities.add(a);
   }

   private FCollectionView<Trigger> computeTriggers() {
      FCollection<Trigger> result = new FCollection<Trigger>(this.triggers);
      if (this.getStateName().equals(CardStateName.Original)) {
         if (this.getCard().hasState(CardStateName.LeftSplit)) {
            result.addAll(this.getCard().getState(CardStateName.LeftSplit).triggers);
         }

         if (this.getCard().hasState(CardStateName.RightSplit)) {
            result.addAll(this.getCard().getState(CardStateName.RightSplit).triggers);
         }
      }

      this.card.updateTriggers(result, this);
      return result;
   }

   public final boolean hasTrigger(Trigger t) {
      return this.getTriggers().contains(t);
   }

   public final boolean hasTrigger(int id) {
      for(Trigger t : this.getTriggers()) {
         if (id == t.getId()) {
            return true;
         }
      }

      return false;
   }

   public final boolean addTrigger(Trigger t) {
      this.bumpEpoch();
      return this.triggers.add(t);
   }

   private FCollectionView<StaticAbility> computeStaticAbilities() {
      FCollection<StaticAbility> result = new FCollection<StaticAbility>(this.staticAbilities);
      if (this.getStateName().equals(CardStateName.Original)) {
         if (this.getCard().hasState(CardStateName.LeftSplit)) {
            result.addAll(this.getCard().getState(CardStateName.LeftSplit).staticAbilities);
         }

         if (this.getCard().hasState(CardStateName.RightSplit)) {
            result.addAll(this.getCard().getState(CardStateName.RightSplit).staticAbilities);
         }
      }

      this.card.updateStaticAbilities(result, this);
      return result;
   }

   public final boolean addStaticAbility(StaticAbility stab) {
      this.bumpEpoch();
      return this.staticAbilities.add(stab);
   }

   public final boolean removeStaticAbility(StaticAbility stab) {
      this.bumpEpoch();
      return this.staticAbilities.remove(stab);
   }

   public FCollectionView<ReplacementEffect> getReplacementEffects() {
      return this.getReplacementEffects(true);
   }

   private FCollectionView<ReplacementEffect> computeReplacementEffects(boolean rulesHost) {
      FCollection<ReplacementEffect> result = new FCollection<ReplacementEffect>(this.replacementEffects);
      if (this.getStateName().equals(CardStateName.Original)) {
         if (this.getCard().hasState(CardStateName.LeftSplit)) {
            result.addAll(this.getCard().getState(CardStateName.LeftSplit).replacementEffects);
         }

         if (this.getCard().hasState(CardStateName.RightSplit)) {
            result.addAll(this.getCard().getState(CardStateName.RightSplit).replacementEffects);
         }
      }

      this.card.updateReplacementEffects(result, this, rulesHost);
      if (!rulesHost) {
         return result;
      } else {
         if (this.type.hasSubtype("Adventure")) {
            if (this.adventureRep == null) {
               this.adventureRep = CardFactoryUtil.setupAdventureAbility(this);
            }

            result.add(this.adventureRep);
         }

         if (this.type.hasSubtype("Omen")) {
            if (this.omenRep == null) {
               this.omenRep = CardFactoryUtil.setupOmenAbility(this);
            }

            result.add(this.omenRep);
         }

         return result;
      }
   }

   public boolean addReplacementEffect(ReplacementEffect replacementEffect) {
      this.bumpEpoch();
      return this.replacementEffects.add(replacementEffect);
   }

   public final boolean hasReplacementEffect(ReplacementEffect re) {
      return this.getReplacementEffects().contains(re);
   }

   public final boolean hasReplacementEffect(int id) {
      return this.getReplacementEffect(id) != null;
   }

   public final ReplacementEffect getReplacementEffect(int id) {
      for(ReplacementEffect r : this.getReplacementEffects()) {
         if (id == r.getId()) {
            return r;
         }
      }

      return null;
   }

   public ReplacementEffect getLoyaltyRep() {
      if (this.loyaltyRep == null) {
         this.loyaltyRep = CardFactoryUtil.makeEtbCounter("etbCounter:LOYALTY:" + this.baseLoyalty, this, true);
      }

      return this.loyaltyRep;
   }

   public ReplacementEffect getDefenseRep() {
      if (this.defenseRep == null) {
         this.defenseRep = CardFactoryUtil.makeEtbCounter("etbCounter:DEFENSE:" + this.baseDefense, this, true);
      }

      return this.defenseRep;
   }

   public ReplacementEffect getSagaRep() {
      if (this.sagaRep == null) {
         this.sagaRep = CardFactoryUtil.makeEtbCounter("etbCounter:LORE:1", this, true);
      }

      return this.sagaRep;
   }

   public final Map<String, String> getSVars() {
      return this.sVars;
   }

   public final String getSVar(String var) {
      return this.sVars.containsKey(var) ? (String)this.sVars.get(var) : "";
   }

   public final boolean hasSVar(String var) {
      return var == null ? false : this.sVars.containsKey(var);
   }

   public final void setSVar(String var, String str) {
      this.sVars.put(var, str);
      this.view.updateFoilIndex(this.card.getState(CardStateName.Original));
   }

   public final void setSVars(Map<String, String> newSVars) {
      this.sVars = Maps.newTreeMap();
      this.sVars.putAll(newSVars);
      this.view.updateFoilIndex(this.card.getState(CardStateName.Original));
   }

   public final void removeSVar(String var) {
      this.sVars.remove(var);
   }

   public final int getFoil() {
      String foil = this.getSVar("Foil");
      return !foil.isEmpty() ? Integer.parseInt(foil) : 0;
   }

   public final void copyFrom(CardState source, boolean lki) {
      this.bumpEpoch();
      this.copyFrom(source, lki, (CardTraitBase)null);
   }

   public final void copyFrom(CardState source, boolean lki, CardTraitBase ctb) {
      this.bumpEpoch();
      this.setName(source.getName());
      this.setType(source.type);
      this.setManaCost(source.getManaCost());
      this.setColor(source.getColor());
      this.setOracleText(source.getOracleText());
      this.setFunctionalVariantName(source.getFunctionalVariantName());
      this.setBasePower(source.getBasePower());
      this.setBaseToughness(source.getBaseToughness());
      this.setBasePowerString(source.getBasePowerString());
      this.setBaseToughnessString(source.getBaseToughnessString());
      this.setBaseLoyalty(source.getBaseLoyalty());
      this.setBaseDefense(source.getBaseDefense());
      this.setAttractionLights(source.getAttractionLights());
      this.setFlavorName(source.getFlavorName());
      this.setSVars(source.getSVars());
      this.abilityForTrigger.clear();

      for(Map.Entry<String, SpellAbility> e : source.abilityForTrigger.entrySet()) {
         this.abilityForTrigger.put((String)e.getKey(), ((SpellAbility)e.getValue()).copy(this.card, lki));
      }

      this.abilities.clear();

      for(SpellAbility sa : source.abilities) {
         if (sa.isIntrinsic()) {
            this.abilities.add(sa.copy(this.card, lki));
         }
      }

      this.setIntrinsicKeywords(source.intrinsicKeywords.getValues(), lki);
      this.setImageKey(source.getImageKey());
      this.setRarity(source.rarity);
      this.setSetCode(source.setCode);
      Trigger dontCopyTr = null;
      if (ctb != null && ctb.hasParam("DoesntHaveThisAbility")) {
         SpellAbility root = ((SpellAbility)ctb).getRootAbility();
         if (root.isTrigger()) {
            dontCopyTr = root.getTrigger();
         }
      }

      this.triggers.clear();

      for(Trigger tr : source.triggers) {
         if (!tr.equals(dontCopyTr) && tr.isIntrinsic()) {
            this.triggers.add(tr.copy(this.card, lki, false, tr.hasParam("Execute") ? (SpellAbility)this.abilityForTrigger.get(tr.getParam("Execute")) : null));
         }
      }

      ReplacementEffect runRE = null;
      if (ctb instanceof SpellAbility sp) {
         if (sp.isReplacementAbility() && source.getCard().equals(ctb.getHostCard())) {
            runRE = sp.getReplacementEffect();
         }
      }

      this.replacementEffects.clear();

      for(ReplacementEffect re : source.replacementEffects) {
         if (re.isIntrinsic()) {
            ReplacementEffect reCopy = re.copy(this.card, lki);
            if (re.equals(runRE) && runRE.hasRun()) {
               reCopy.setHasRun(true);
            }

            this.replacementEffects.add(reCopy);
         }
      }

      this.staticAbilities.clear();

      for(StaticAbility sa : source.staticAbilities) {
         if (sa.isIntrinsic()) {
            this.staticAbilities.add(sa.copy(this.card, lki));
         }
      }

      if (lki) {
         this.changedType = source.changedType;
         if (source.landAbility != null) {
            this.landAbility = source.landAbility.copy(this.card, true);
         }

         if (source.auraAbility != null) {
            this.auraAbility = source.auraAbility.copy(this.card, true);
         }

         if (source.permanentAbility != null) {
            this.permanentAbility = source.permanentAbility.copy(this.card, true);
         }

         if (source.loyaltyRep != null) {
            this.loyaltyRep = source.loyaltyRep.copy(this.card, true);
         }

         if (source.defenseRep != null) {
            this.defenseRep = source.defenseRep.copy(this.card, true);
         }

         if (source.sagaRep != null) {
            this.sagaRep = source.sagaRep.copy(this.card, true);
         }

         if (source.adventureRep != null) {
            this.adventureRep = source.adventureRep.copy(this.card, true);
         }

         if (source.omenRep != null) {
            this.omenRep = source.omenRep.copy(this.card, true);
         }

         for(Map.Entry<MagicColor.Color, SpellAbility> e : source.landManaAbilities.entrySet()) {
            this.landManaAbilities.put((MagicColor.Color)e.getKey(), ((SpellAbility)e.getValue()).copy(this.card, true));
         }
      }

   }

   public final void addAbilitiesFrom(CardState source, boolean lki) {
      this.bumpEpoch();
      for(SpellAbility sa : source.abilities) {
         if (sa.isIntrinsic() && sa.getApi() != ApiType.PermanentCreature && sa.getApi() != ApiType.PermanentNoncreature) {
            this.abilities.add(sa.copy(this.card, lki));
         }
      }

      for(KeywordInterface k : source.intrinsicKeywords) {
         this.intrinsicKeywords.insert(k.copy(this.card, lki));
      }

      for(Trigger tr : source.triggers) {
         if (tr.isIntrinsic()) {
            this.triggers.add(tr.copy(this.card, lki));
         }
      }

      for(ReplacementEffect re : source.replacementEffects) {
         if (re.isIntrinsic()) {
            this.replacementEffects.add(re.copy(this.card, lki));
         }
      }

      for(StaticAbility sa : source.staticAbilities) {
         if (sa.isIntrinsic()) {
            this.staticAbilities.add(sa.copy(this.card, lki));
         }
      }

   }

   public CardState copy(Card host, CardStateName name, boolean lki) {
      return this.copy(host, name, lki, (CardTraitBase)null);
   }

   public CardState copy(Card host, CardTraitBase ctb) {
      return this.copy(host, this.getStateName(), false, ctb);
   }

   public CardState copy(Card host, CardStateName name, CardTraitBase ctb) {
      return this.copy(host, name, false, ctb);
   }

   public CardState copy(Card host, CardStateName name, boolean lki, CardTraitBase ctb) {
      CardState result = new CardState(host, name);
      result.copyFrom(this, lki, ctb);
      return result;
   }

   public CardRarity getRarity() {
      return this.rarity;
   }

   public void setRarity(CardRarity rarity0) {
      this.rarity = rarity0;
      this.view.updateRarity(this);
   }

   public String getSetCode() {
      return this.setCode;
   }

   public void setSetCode(String setCode0) {
      this.setCode = setCode0;
      this.view.updateSetCode(this);
   }

   public final String getImageKey() {
      return this.imageKey;
   }

   public final void setImageKey(String imageFilename0) {
      this.imageKey = imageFilename0;
      this.view.updateImageKey(this);
   }

   public boolean hasProperty(String property, Player sourceController, Card source, CardTraitBase spellAbility) {
      return CardStateProperty.hasProperty(this, sourceController, source, property, spellAbility);
   }

   public ImmutableList<CardTraitBase> getTraits() {
      return ImmutableList.<CardTraitBase>builder().addAll(this.abilities).addAll(this.triggers).addAll(this.replacementEffects).addAll(this.staticAbilities).build();
   }

   public void resetOriginalHost(Card oldHost) {
      this.bumpEpoch();
      UnmodifiableIterator var2 = this.getTraits().iterator();

      while(var2.hasNext()) {
         CardTraitBase ctb = (CardTraitBase)var2.next();
         if (ctb.isIntrinsic() && oldHost.equals(ctb.getOriginalHost())) {
            ctb.setCardState(this);
         }
      }

   }

   public void updateChangedText() {
      this.bumpEpoch();
      UnmodifiableIterator var1 = this.getTraits().iterator();

      while(var1.hasNext()) {
         CardTraitBase ctb = (CardTraitBase)var1.next();
         if (ctb.isIntrinsic()) {
            ctb.changeText();
         }
      }

   }

   public void changeTextIntrinsic(Map<String, String> colorMap, Map<String, String> typeMap) {
      UnmodifiableIterator var3 = this.getTraits().iterator();

      while(var3.hasNext()) {
         CardTraitBase ctb = (CardTraitBase)var3.next();
         if (ctb.isIntrinsic()) {
            ctb.changeTextIntrinsic(colorMap, typeMap);
         }
      }

   }

   public final boolean hasChapter() {
      return this.getTriggers().anyMatch(Trigger::isChapter);
   }

   public final int getFinalChapterNr() {
      int n = 0;

      for(Trigger t : this.getTriggers()) {
         if (t.isChapter()) {
            n = Math.max(n, t.getChapter());
         }
      }

      return n;
   }

   public SpellAbility getManifestUp() {
      if (this.manifestUp == null) {
         this.manifestUp = CardFactoryUtil.abilityTurnFaceUp(this, new Cost(this.getManaCost(), true), "ManifestUp", "Unmanifest", "manacost");
      }

      return this.manifestUp;
   }

   public SpellAbility getCloakUp() {
      if (this.cloakUp == null) {
         this.cloakUp = CardFactoryUtil.abilityTurnFaceUp(this, new Cost(this.getManaCost(), true), "CloakUp", "Uncloak", "manacost");
      }

      return this.cloakUp;
   }

   public SpellAbility getAbilityForTrigger(String svar) {
      return (SpellAbility)this.abilityForTrigger.computeIfAbsent(svar, (s) -> AbilityFactory.getAbility((Card)this.getCard(), (String)s, this));
   }

   public String getTranslationKey() {
      String displayName = this.flavorName == null ? this.name : this.flavorName;
      return StringUtils.isNotEmpty(this.functionalVariantName) ? displayName + " $" + this.functionalVariantName : displayName;
   }

   public String getUntranslatedType() {
      return this.getType().toString();
   }

   public String getTranslatedName() {
      return CardTranslation.getTranslatedName((ITranslatable)this);
   }

   public boolean isWorthy() {
      CardTypeView type = this.getTypeWithChanges();
      if (type.isCreature() && type.isLegendary() && !type.hasSubtype("Villain")) {
         ColorSet color = this.getCard().getColor(this);
         return color.hasRed() || color.hasWhite();
      } else {
         return false;
      }
   }

   static record LandTraitChanges(CardState state) implements ICardTraitChanges, IKeywordsChange {
      public List<SpellAbility> applySpellAbility(List<SpellAbility> list) {
         if (this.state.getCard().hasRemoveIntrinsic()) {
            list.clear();
         }

         CardTypeView type = this.state.getTypeWithChanges();
         if (!type.isLand()) {
            return list;
         } else {
            for(MagicColor.Color c : MagicColor.Color.values()) {
               if (c.getBasicLandType() != null && type.hasSubtype(c.getBasicLandType())) {
                  list.add(this.state.getLandManaForColor(c));
               }
            }

            return list;
         }
      }

      public List<Trigger> applyTrigger(List<Trigger> list) {
         if (this.state.getCard().hasRemoveIntrinsic()) {
            list.clear();
         }

         return list;
      }

      public List<ReplacementEffect> applyReplacementEffect(List<ReplacementEffect> list) {
         if (this.state.getCard().hasRemoveIntrinsic()) {
            list.clear();
         }

         CardTypeView type = this.state.getTypeWithChanges();
         if (type.isPlaneswalker()) {
            list.add(this.state.getLoyaltyRep());
         }

         if (type.isBattle()) {
            list.add(this.state.getDefenseRep());
         }

         if (type.isSaga() && !this.state.hasKeyword(Keyword.READ_AHEAD)) {
            list.add(this.state.getSagaRep());
         }

         return list;
      }

      public List<StaticAbility> applyStaticAbility(List<StaticAbility> list) {
         if (this.state.getCard().hasRemoveIntrinsic()) {
            list.clear();
         }

         return list;
      }

      public void applyKeywords(KeywordCollection list) {
         if (this.state.getCard().hasRemoveIntrinsic()) {
            list.clear();
         }

      }

      public LandTraitChanges copy(Card host, boolean lki) {
         return this;
      }
   }
}
