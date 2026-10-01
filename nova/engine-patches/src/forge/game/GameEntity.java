package forge.game;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CounterType;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.keyword.KeywordWithType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantAttach;
import forge.game.zone.ZoneType;
import forge.trackable.TrackableProperty;
import forge.util.Lang;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;

public abstract class GameEntity implements GameObject, IIdentifiable {
   protected int id;
   private String name = "";
   protected CardCollection attachedCards = new CardCollection();
   protected Multiset<CounterType> counters = HashMultiset.create();
   protected List<Pair<Integer, Boolean>> damageReceivedThisTurn = Lists.newArrayList();

   protected GameEntity(int id0) {
      this.id = id0;
   }

   public int getId() {
      return this.id;
   }

   public void dangerouslySetId(int i) {
      this.id = i;
   }

   public String getName() {
      return this.name;
   }

   public void setName(String s) {
      this.name = s;
      this.getView().updateName(this);
   }

   public abstract int addDamageAfterPrevention(int var1, Card var2, SpellAbility var3, boolean var4, GameEntityCounterTable var5);

   public int staticDamagePrevention(int damage, int possiblePrevention, Card source, boolean isCombat) {
      return this.staticDamagePrevention(damage, possiblePrevention, source, isCombat, (Boolean)null);
   }

   public int staticDamagePrevention(int damage, int possiblePrevention, Card source, boolean isCombat, Boolean combatDamagePreventedThisTurn) {
      if (damage <= 0) {
         return 0;
      } else if (!source.canDamagePrevented(isCombat)) {
         return damage;
      } else {
         if (isCombat) {
            boolean prevented = combatDamagePreventedThisTurn != null ? combatDamagePreventedThisTurn : this.getGame().getReplacementHandler().isPreventCombatDamageThisTurn();
            if (prevented) {
               return 0;
            }
         }

         if (!forge.game.card.TraitEpoch.DISABLED) {
            // Forge Nova: visit only the cards whose visit can matter, in the same order (NovaStaticSourceIndex)
            final Card[] novaCards = forge.game.replacement.NovaStaticSourceIndex.damageDone(this.getGame());
            if (forge.game.card.TraitEpoch.VERIFY) {
               int fresh = this.novaStaticDamagePreventionScan(this.getGame().getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES), damage, possiblePrevention, source, isCombat);
               int indexed = this.novaStaticDamagePreventionScan(java.util.Arrays.asList(novaCards), damage, possiblePrevention, source, isCombat);
               if (fresh != indexed) {
                  forge.game.card.TraitEpoch.mismatch("staticDamagePrevention", this, java.util.Collections.singletonList(indexed), java.util.Collections.singletonList(fresh));
               }
            }
            return this.novaStaticDamagePreventionScan(java.util.Arrays.asList(novaCards), damage, possiblePrevention, source, isCombat);
         }
         return this.novaStaticDamagePreventionScan(this.getGame().getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES), damage, possiblePrevention, source, isCombat);
      }
   }

   /** Forge Nova: the card loop of staticDamagePrevention, unchanged, over the given cards. */
   private int novaStaticDamagePreventionScan(Iterable<Card> cards, int damage, int possiblePrevention, Card source, boolean isCombat) {
      {
         for(Card ca : cards) {
            for(ReplacementEffect re : ca.getReplacementEffects()) {
               if (re.getMode().equals(ReplacementType.DamageDone) && (re.hasParam("PreventionEffect") || re.hasParam("Prevent")) && re.zonesCheck(this.getGame().getZoneOf(ca)) && re.requirementsCheck(this.getGame()) && !"Immortal Coil".equals(ca.getName()) && re.matchesValidParam("ValidSource", source) && re.matchesValidParam("ValidTarget", this) && (!re.hasParam("IsCombat") || re.getParam("IsCombat").equals("True") == isCombat)) {
                  if (re.hasParam("Prevent")) {
                     return 0;
                  }

                  if (re.getOverridingAbility() == null) {
                     return 0;
                  }

                  SpellAbility repSA = re.getOverridingAbility();
                  if (repSA.getApi() == ApiType.ReplaceDamage) {
                     damage = Math.max(0, damage - AbilityUtils.calculateAmount(ca, repSA.getParam("Amount"), repSA));
                  }
               }
            }
         }

         return Math.max(0, damage - possiblePrevention);
      }
   }

   public abstract int staticReplaceDamage(int var1, Card var2, boolean var3);

   public int getPreventNextDamageTotalShields() {
      return this.getGame().getReplacementHandler().getTotalPreventionShieldAmount(this);
   }

   public abstract boolean hasKeyword(String var1);

   public abstract boolean hasKeyword(Keyword var1);

   public final CardCollectionView getEnchantedBy() {
      return CardLists.filter(this.getAttachedCards(), Card::isAura);
   }

   public final CardCollectionView getAttachedCards() {
      return CardLists.filter(this.getAllAttachedCards(), CardPredicates.phasedIn());
   }

   public final CardCollectionView getAllAttachedCards() {
      return CardCollection.getView(this.attachedCards);
   }

   public final void setAttachedCards(Iterable<Card> cards) {
      this.attachedCards = this.getView().setCards(this.attachedCards, cards, TrackableProperty.AttachedCards);
   }

   public final void clearAttachedCards() {
      this.attachedCards = this.getView().clearCards(this.attachedCards, TrackableProperty.AttachedCards);
   }

   public final boolean hasCardAttachments() {
      return !this.getAttachedCards().isEmpty();
   }

   public final boolean isEnchanted() {
      return this.getAttachedCards().anyMatch(Card::isAura);
   }

   public final boolean hasCardAttachment(Card c) {
      return this.getAttachedCards().contains(c);
   }

   public final boolean isEnchantedBy(Card c) {
      return this.hasCardAttachment(c);
   }

   public final boolean hasCardAttachment(String cardName) {
      return this.getAttachedCards().anyMatch(CardPredicates.nameEquals(cardName));
   }

   public final boolean isEnchantedBy(String cardName) {
      return this.hasCardAttachment(cardName);
   }

   public final void addAttachedCard(Card c) {
      this.attachedCards = this.getView().addCard(this.attachedCards, c, TrackableProperty.AttachedCards);
   }

   public final void removeAttachedCard(Card c) {
      this.attachedCards = this.getView().removeCard(this.attachedCards, c, TrackableProperty.AttachedCards);
   }

   public final void updateAttachedCards() {
      this.getView().setCards((CardCollection)null, this.attachedCards, TrackableProperty.AttachedCards);
   }

   public final void unAttachAllCards(Card old) {
      for(Card c : this.getAttachedCards()) {
         c.unattachFromEntity(this, old);
      }

   }

   public boolean canBeAttached(Card attach, SpellAbility sa) {
      return this.canBeAttached(attach, sa, false);
   }

   public boolean canBeAttached(Card attach, SpellAbility sa, boolean checkSBA) {
      return this.cantBeAttachedMsg(attach, sa, checkSBA) == null;
   }

   public String cantBeAttachedMsg(Card attach, SpellAbility sa) {
      return this.cantBeAttachedMsg(attach, sa, false);
   }

   public String cantBeAttachedMsg(Card attach, SpellAbility sa, boolean checkSBA) {
      if (!attach.isAttachment()) {
         return attach.getDisplayName() + " is not an attachment";
      } else if (this.equals(attach)) {
         return attach.getDisplayName() + " can't attach to itself";
      } else if (attach.isCreature() && !attach.hasKeyword(Keyword.RECONFIGURE)) {
         return attach.getDisplayName() + " is a creature without reconfigure";
      } else if (attach.isPhasedOut()) {
         return attach.getDisplayName() + " is phased out";
      } else {
         if (attach.isAura()) {
            String msg = this.cantBeEnchantedByMsg(attach);
            if (msg != null) {
               return msg;
            }
         }

         if (attach.isEquipment()) {
            String msg = this.cantBeEquippedByMsg(attach, sa);
            if (msg != null) {
               return msg;
            }
         }

         if (attach.isFortification()) {
            String msg = this.cantBeFortifiedByMsg(attach);
            if (msg != null) {
               return msg;
            }
         }

         StaticAbility stAb = StaticAbilityCantAttach.cantAttach(this, attach, checkSBA);
         return stAb != null ? stAb.toString() : null;
      }
   }

   protected String cantBeEquippedByMsg(Card aura, SpellAbility sa) {
      return this.getName() + " is not a Creature";
   }

   protected String cantBeFortifiedByMsg(Card fort) {
      return this.getName() + " is not a Land";
   }

   protected String cantBeEnchantedByMsg(Card aura) {
      if (!aura.hasKeyword(Keyword.ENCHANT)) {
         return "No Enchant Keyword";
      } else {
         for(KeywordInterface ki : aura.getKeywords(Keyword.ENCHANT)) {
            if (ki instanceof KeywordWithType) {
               KeywordWithType kwt = (KeywordWithType)ki;
               String v = kwt.getValidType();
               String desc = kwt.getTypeDescription();
               if (!this.isValid(v.split(","), aura.getController(), aura, (CardTraitBase)null)) {
                  String var10000 = this.getName();
                  return var10000 + " is not " + Lang.nounWithAmount(1, desc);
               }
            }
         }

         return null;
      }
   }

   public boolean hasCounters() {
      return !this.counters.isEmpty();
   }

   public final Multiset<CounterType> getCounters() {
      return this.counters;
   }

   public final int getNumAllCounters() {
      return this.counters.size();
   }

   public final int getCounters(CounterType counterName) {
      return this.counters.count(counterName);
   }

   public void setCounters(CounterType counterType, Integer num) {
      this.counters.setCount(counterType, num);
   }

   public abstract void setCounters(Multiset<CounterType> var1);

   public abstract boolean canRemoveCounters(CounterType var1);

   public abstract boolean canReceiveCounters(CounterType var1);

   public abstract int subtractCounter(CounterType var1, int var2, Player var3);

   public abstract void clearCounters();

   public final void addCounter(CounterType counterType, int n, Player source, GameEntityCounterTable table) {
      if (n > 0 && this.canReceiveCounters(counterType)) {
         Integer max = this.getCounterMax(counterType);
         if (max != null) {
            n = Math.min(n, max - this.getCounters(counterType));
            if (n <= 0) {
               return;
            }
         }

         table.put(source, this, counterType, n);
      }
   }

   public abstract void addCounterInternal(CounterType var1, int var2, Player var3, boolean var4, GameEntityCounterTable var5, Map<AbilityKey, Object> var6);

   public Integer getCounterMax(CounterType counterType) {
      return null;
   }

   public List<Pair<Integer, Boolean>> getDamageReceivedThisTurn() {
      return this.damageReceivedThisTurn;
   }

   public void setDamageReceivedThisTurn(List<Pair<Integer, Boolean>> dmg) {
      this.damageReceivedThisTurn.addAll(dmg);
   }

   public void receiveDamage(Pair<Integer, Boolean> dmg) {
      this.damageReceivedThisTurn.add(dmg);
   }

   public final int getAssignedDamage() {
      return this.getAssignedDamage((Boolean)null, (Card)null);
   }

   public final int getAssignedCombatDamage() {
      return this.getAssignedDamage(true, (Card)null);
   }

   public final int getAssignedDamage(Boolean isCombat, Card source) {
      int num = 0;

      for(Pair<Integer, Boolean> dmg : this.damageReceivedThisTurn) {
         if ((isCombat == null || dmg.getRight() == isCombat) && (source == null || ((Card)this.getGame().getDamageLKI(dmg).getLeft()).equalsWithGameTimestamp(source))) {
            num += (Integer)dmg.getLeft();
         }
      }

      return num;
   }

   public final boolean equals(Object o) {
      if (o == null) {
         return false;
      } else {
         return o.hashCode() == this.id && o.getClass().equals(this.getClass());
      }
   }

   public final int hashCode() {
      return this.id;
   }

   public String toString() {
      return this.name;
   }

   public abstract Game getGame();

   public abstract GameEntityView getView();
}
