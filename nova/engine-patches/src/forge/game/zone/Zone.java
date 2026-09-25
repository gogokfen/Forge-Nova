package forge.game.zone;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.MultimapBuilder;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.event.EventValueChangeType;
import forge.game.event.GameEventZone;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.util.MyRandom;
import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class Zone implements Serializable, Iterable<Card> {
   private static final long serialVersionUID = -5687652485777639176L;
   private final CardCollection cardList = new CardCollection();
   protected final ZoneType zoneType;
   protected final Game game;
   protected final transient ListMultimap<ZoneType, Card> cardsAddedThisTurn = MultimapBuilder.enumKeys(ZoneType.class).arrayListValues().build();
   protected final transient ListMultimap<ZoneType, Card> cardsAddedLastTurn = MultimapBuilder.enumKeys(ZoneType.class).arrayListValues().build();
   protected final transient Map<Card, ZoneType> enteredFromThisTurn = Maps.newHashMap();
   private static final Comparator<Card> COMPARATOR = Comparator.comparingInt((Card c) -> c.getCMC()).thenComparing((Card c) -> c.getColor().getOrderWeight()).thenComparing(Comparator.comparing((Card c) -> c.getName())).thenComparing((Card c) -> c.hasPerpetual());

   protected void sort() {
      this.cardList.sort(COMPARATOR);
      forge.game.card.TraitEpoch.bumpGlobal();
   }

   public Zone(ZoneType zone0, Game game0) {
      this.zoneType = zone0;
      this.game = game0;
   }

   protected void onChanged() {
   }

   public Player getPlayer() {
      return null;
   }

   public ZoneView getView() {
      return new ZoneView(PlayerView.get(this.getPlayer()), this.zoneType);
   }

   public final void reorder(Card c, int index) {
      this.cardList.remove(c);
      this.cardList.add(index, c);
      forge.game.card.TraitEpoch.bumpGlobal();
   }

   public final void add(Card c) {
      this.add(c, (Integer)null);
   }

   public final void add(Card c, Integer index) {
      this.add(c, index, (Card)null);
   }

   public void add(Card c, Integer index, Card latestState) {
      this.add(c, index, latestState, false);
   }

   public void add(Card c, Integer index, Card latestState, boolean rollback) {
      if (index != null && this.cardList.isEmpty() && index > 0) {
         System.out.println("Warning: tried to add a card to zone with a specific non-zero index, but the zone was empty! Canceling Zone#add to avoid a crash.");
      } else {
         if (index == null && this.zoneType == ZoneType.Command && c.isCommander()) {
            index = 0;
            if (this.game.getRules().hasAppliedVariant(GameType.Oathbreaker) && c.getRules().canBeSignatureSpell() && !this.cardList.isEmpty() && ((Card)this.cardList.get(0)).isCommander()) {
               index = 1;
            }
         }

         if (!rollback) {
            if (!c.isImmutable()) {
               Zone oldZone = this.game.getZoneOf(c);
               ZoneType zt = oldZone == null ? ZoneType.Stack : oldZone.getZoneType();
               if (zt != this.zoneType) {
                  c.setTurnInController(this.getPlayer());
                  c.setTurnInZone(this.game.getPhaseHandler().getTurn());
                  if (latestState != null) {
                     this.cardsAddedThisTurn.put(zt, latestState);
                     this.enteredFromThisTurn.put(latestState, zt);
                  }
               }
            }

            if (this.zoneType != ZoneType.Battlefield) {
               c.setTapped(false);
            }

            if (this.zoneType == ZoneType.Graveyard && c.isPermanent() && !c.isToken()) {
               c.getOwner().descend();
            }
         }

         c.setZone(this);
         if (this.zoneType == ZoneType.Battlefield || !c.isToken() || c.getCurrentStateName() == CardStateName.PreparedSpell || this.zoneType == ZoneType.Stack && c.getCopiedPermanent() != null) {
            if (index == null) {
               this.cardList.add(c);
               forge.game.card.TraitEpoch.bumpGlobal();
            } else {
               this.cardList.add(index, c);
               forge.game.card.TraitEpoch.bumpGlobal();
            }
         }

         this.onChanged();
         this.game.fireEvent(new GameEventZone(this.zoneType, this.getPlayer(), EventValueChangeType.Added, c));
      }
   }

   public final boolean contains(Card c) {
      return this.cardList.contains(c);
   }

   public final boolean contains(Predicate<Card> condition) {
      return this.cardList.anyMatch(condition);
   }

   public void remove(Card c) {
      if (this.cardList.remove(c)) {
         forge.game.card.TraitEpoch.bumpGlobal();
         this.onChanged();
         this.game.fireEvent(new GameEventZone(this.zoneType, this.getPlayer(), EventValueChangeType.Removed, c));
      }

   }

   public final void setCards(Iterable<Card> cards) {
      this.cardList.clear();
      forge.game.card.TraitEpoch.bumpGlobal();

      for(Card c : cards) {
         c.setZone(this);
         this.cardList.add(c);
         forge.game.card.TraitEpoch.bumpGlobal();
      }

      this.onChanged();
      this.game.fireEvent(new GameEventZone(this.zoneType, this.getPlayer(), EventValueChangeType.ComplexUpdate, (Card)null));
   }

   public final void removeAllCards(boolean forcedWithoutEvents) {
      if (forcedWithoutEvents) {
         this.cardList.clear();
         forge.game.card.TraitEpoch.bumpGlobal();
      } else {
         for(Card c : this.cardList) {
            this.remove(c);
         }
      }

   }

   public final boolean is(ZoneType zone) {
      return zone == this.zoneType;
   }

   public final boolean is(ZoneType zone, Player player) {
      return this.zoneType == zone && player == this.getPlayer();
   }

   public final ZoneType getZoneType() {
      return this.zoneType;
   }

   public final int size() {
      return this.cardList.size();
   }

   public final Card get(int index) {
      return (Card)this.cardList.get(index);
   }

   public final CardCollectionView getCards() {
      return this.getCards(true);
   }

   public CardCollectionView getCards(boolean filter) {
      return this.cardList;
   }

   public final boolean isEmpty() {
      return this.cardList.isEmpty();
   }

   public final List<Card> getCardsAddedThisTurn(ZoneType origin) {
      return getCardsAdded(this.cardsAddedThisTurn, origin);
   }

   public final List<Card> getCardsAddedLastTurn(ZoneType origin) {
      return getCardsAdded(this.cardsAddedLastTurn, origin);
   }

   public final boolean isCardAddedThisTurn(Card card, ZoneType origin) {
      return this.cardsAddedThisTurn.containsEntry(origin, card) ? origin.equals(this.enteredFromThisTurn.get(card)) : false;
   }

   private static List<Card> getCardsAdded(ListMultimap<ZoneType, Card> cardsAdded, ZoneType origin) {
      if (origin != null) {
         return Lists.newArrayList(cardsAdded.get(origin));
      } else {
         return (List<Card>)(cardsAdded.isEmpty() ? List.of() : Lists.newArrayList(cardsAdded.values()));
      }
   }

   public final void resetCardsAddedThisTurn() {
      this.cardsAddedLastTurn.clear();
      this.cardsAddedLastTurn.putAll(this.cardsAddedThisTurn);
      this.cardsAddedThisTurn.clear();
      this.enteredFromThisTurn.clear();
   }

   public Iterator<Card> iterator() {
      return this.cardList.iterator();
   }

   public void shuffle() {
      Collections.shuffle(this.cardList, MyRandom.getRandom());
      forge.game.card.TraitEpoch.bumpGlobal();
      this.onChanged();
   }

   public String toString() {
      return this.zoneType.toString();
   }

   public Zone getLKICopy(Map<Integer, Card> cachedMap) {
      Zone result = new Zone(this.zoneType, this.game);
      result.setCards(CardCopyService.getLKICopyList(this.getCards(), cachedMap));
      return result;
   }

   public void saveLKI(Card c, Card old) {
      Zone oldZone = this.game.getZoneOf(old);
      ZoneType zt = oldZone == null ? ZoneType.Stack : oldZone.getZoneType();
      if (zt != this.zoneType) {
         Card lki = CardCopyService.getLKICopy(c);
         this.cardsAddedThisTurn.put(zt, lki);
         this.enteredFromThisTurn.put(lki, zt);
      }
   }
}
