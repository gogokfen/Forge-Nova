package forge.game.card;

import com.google.common.collect.Lists;
import forge.card.mana.ManaCostShard;
import forge.game.CardTraitBase;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.staticability.StaticAbilityTapPowerValue;
import forge.util.IterableUtil;
import forge.util.MyRandom;
import forge.util.StreamUtil;
import forge.util.collect.FCollectionView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class CardLists {
   public static final Comparator<Card> ToughnessComparator = Comparator.comparingInt(Card::getNetToughness);
   public static final Comparator<Card> ToughnessComparatorInv = Comparator.<Card>comparingInt(Card::getNetToughness).reversed();
   public static final Comparator<Card> PowerComparator = Comparator.comparingInt(Card::getNetCombatDamage);
   public static final Comparator<Card> CmcComparator = Comparator.comparingInt(Card::getCMC);
   public static final Comparator<Card> CmcComparatorInv = Comparator.<Card>comparingInt(Card::getCMC).reversed();
   public static final Comparator<Card> TextLenComparator = Comparator.comparingInt((a) -> a.getView().getText().length());

   public static CardCollection filterToughness(Iterable<Card> in, int atLeastToughness) {
      return filter(in, (Predicate<Card>)((c) -> c.getNetToughness() <= atLeastToughness));
   }

   public static CardCollection filterPower(Iterable<Card> in, int atLeastPower) {
      return filter(in, (Predicate<Card>)((c) -> c.getNetPower() >= atLeastPower));
   }

   public static CardCollection filterLEPower(Iterable<Card> in, int lessthanPower) {
      return filter(in, (Predicate<Card>)((c) -> c.getNetPower() <= lessthanPower));
   }

   public static CardCollection filterAnyCounters(Iterable<Card> in, int atLeastCounters) {
      return filter(in, (Predicate<Card>)((c) -> c.getNumAllCounters() >= atLeastCounters));
   }

   public static void sortByCmcDesc(List<Card> list) {
      list.sort(CmcComparatorInv);
   }

   public static void sortByToughnessAsc(List<Card> list) {
      list.sort(ToughnessComparator);
   }

   public static void sortByToughnessDesc(List<Card> list) {
      list.sort(ToughnessComparatorInv);
   }

   public static void sortByPowerAsc(List<Card> list) {
      list.sort(PowerComparator);
   }

   public static void sortByPowerDesc(List<Card> list) {
      list.sort(Collections.reverseOrder(PowerComparator));
   }

   public static CardCollection getRandomSubList(List<Card> c, int amount) {
      if (c.size() < amount) {
         return null;
      } else {
         CardCollection cs = new CardCollection(c);
         CardCollection subList = new CardCollection();

         while(subList.size() < amount) {
            shuffle(cs);
            subList.add((Card)cs.remove(0));
         }

         return subList;
      }
   }

   public static void shuffle(List<Card> list) {
      Collections.shuffle(list, MyRandom.getRandom());
   }

   public static CardCollection filterControlledBy(Iterable<Card> cardList, Player player) {
      return filter(cardList, CardPredicates.isController(player));
   }

   public static CardCollection filterControlledBy(Iterable<Card> cardList, FCollectionView<Player> player) {
      return filter(cardList, CardPredicates.isControlledByAnyOf(player));
   }

   public static List<Card> filterControlledByAsList(Iterable<Card> cardList, Player player) {
      return filterAsList(cardList, CardPredicates.isController(player));
   }

   public static List<Card> filterControlledByAsList(Iterable<Card> cardList, FCollectionView<Player> player) {
      return filterAsList(cardList, CardPredicates.isControlledByAnyOf(player));
   }

   public static CardCollection getValidCards(Iterable<Card> cardList, String[] restrictions, Player sourceController, Card source, CardTraitBase spellAbility) {
      return filter(cardList, CardPredicates.restriction(restrictions, sourceController, source, spellAbility));
   }

   public static CardCollection getValidCards(Iterable<Card> cardList, String restriction, Player sourceController, Card source, CardTraitBase sa) {
      if (novaMemo(cardList, restriction)) {
         Card[] in = novaArray(cardList);
         boolean[] mask = novaMask(in, restriction, sourceController, source, sa);
         CardCollection out = new CardCollection();
         for(int i = 0; i < in.length; ++i) {
            if (mask[i]) {
               out.add(in[i]);
            }
         }
         if (TraitEpoch.VERIFY) {
            CardCollection fresh = filter(cardList, CardPredicates.restriction(restriction.split(","), sourceController, source, sa));
            if (!TraitEpoch.sameElements(out, fresh)) {
               TraitEpoch.mismatch("getValidCards:" + restriction, source, TraitEpoch.toList(out), TraitEpoch.toList(fresh));
            }
         }
         return out;
      }
      return filter(cardList, CardPredicates.restriction(restriction.split(","), sourceController, source, sa));
   }

   public static List<Card> getValidCardsAsList(Iterable<Card> cardList, String restriction, Player sourceController, Card source, CardTraitBase sa) {
      if (novaMemo(cardList, restriction)) {
         Card[] in = novaArray(cardList);
         boolean[] mask = novaMask(in, restriction, sourceController, source, sa);
         List<Card> out = Lists.newArrayList();
         for(int i = 0; i < in.length; ++i) {
            if (mask[i]) {
               out.add(in[i]);
            }
         }
         if (TraitEpoch.VERIFY) {
            List<Card> fresh = filterAsList(cardList, CardPredicates.restriction(restriction.split(","), sourceController, source, sa));
            if (!TraitEpoch.sameElements(out, fresh)) {
               TraitEpoch.mismatch("getValidCardsAsList:" + restriction, source, TraitEpoch.toList(out), TraitEpoch.toList(fresh));
            }
         }
         return out;
      }
      return filterAsList(cardList, CardPredicates.restriction(restriction.split(","), sourceController, source, sa));
   }

   public static int getValidCardCount(Iterable<Card> cardList, String restriction, Player sourceController, Card source, CardTraitBase sa) {
      if (novaMemo(cardList, restriction)) {
         Card[] in = novaArray(cardList);
         boolean[] mask = novaMask(in, restriction, sourceController, source, sa);
         int n = 0;
         for(boolean b : mask) {
            if (b) {
               ++n;
            }
         }
         if (TraitEpoch.VERIFY) {
            int fresh = count(cardList, CardPredicates.restriction(restriction.split(","), sourceController, source, sa));
            if (fresh != n) {
               TraitEpoch.mismatch("getValidCardCount:" + restriction, source, java.util.Collections.singletonList(n), java.util.Collections.singletonList(fresh));
            }
         }
         return n;
      }
      return count(cardList, CardPredicates.restriction(restriction.split(","), sourceController, source, sa));
   }

   /**
    * Forge Nova: inside a static-ability layer from COLOR on, a layer-stable restriction gives every card the same
    * answer for the rest of the layer (see forge.game.staticability.NovaLayerMemo), so the per-card answers are
    * remembered and reused for the same cards; the results are built exactly as below (same cards, same order).
    */
   private static boolean novaMemo(Iterable<Card> cardList, String restriction) {
      return cardList != null && !TraitEpoch.DISABLED && forge.game.staticability.NovaLayerMemo.active() && NovaLayerStable.isStable(restriction);
   }

   private static Card[] novaArray(Iterable<Card> cardList) {
      if (cardList instanceof java.util.Collection<Card> coll) {
         return coll.toArray(new Card[0]);
      }
      List<Card> l = Lists.newArrayList(cardList);
      return l.toArray(new Card[0]);
   }

   private static boolean[] novaMask(Card[] in, String restriction, Player sourceController, Card source, CardTraitBase sa) {
      boolean[] mask = forge.game.staticability.NovaLayerMemo.validMask(restriction, sourceController, source, in);
      if (mask == null) {
         Predicate<Card> p = CardPredicates.restriction(restriction.split(","), sourceController, source, sa);
         mask = new boolean[in.length];
         for(int i = 0; i < in.length; ++i) {
            mask[i] = p.test(in[i]);
         }
         forge.game.staticability.NovaLayerMemo.putValidMask(restriction, sourceController, source, in, mask);
      }
      return mask;
   }

   public static CardCollection getTargetableCards(Iterable<Card> cardList, SpellAbility source) {
      CardCollection result = filter(cardList, CardPredicates.isTargetableBy(source));
      if (source.getTargets().isEmpty() && source.usesTargeting() && source.getMinTargets() >= 2) {
         CardCollection removeList = new CardCollection();
         TargetRestrictions tr = source.getTargetRestrictions();

         for(Card card : result) {
            if (tr.isSameController()) {
               boolean found = false;

               for(Card card2 : result) {
                  if (card != card2 && card.getController() == card2.getController()) {
                     found = true;
                     break;
                  }
               }

               if (!found) {
                  removeList.add(card);
               }
            }

            if (tr.isWithoutSameCreatureType()) {
               boolean found = false;

               for(Card card2 : result) {
                  if (card != card2 && !card.sharesCreatureTypeWith(card2)) {
                     found = true;
                     break;
                  }
               }

               if (!found) {
                  removeList.add(card);
               }
            }

            if (tr.isWithSameCreatureType()) {
               boolean found = false;

               for(Card card2 : result) {
                  if (card != card2 && card.sharesCreatureTypeWith(card2)) {
                     found = true;
                     break;
                  }
               }

               if (!found) {
                  removeList.add(card);
               }
            }

            if (tr.isWithSameCardType()) {
               boolean found = false;

               for(Card card2 : result) {
                  if (card != card2 && card.sharesCardTypeWith(card2)) {
                     found = true;
                     break;
                  }
               }

               if (!found) {
                  removeList.add(card);
               }
            }
         }

         result.removeAll(removeList);
      }

      return result;
   }

   public static CardCollection canSubsequentlyTarget(CardCollection list, SpellAbility source) {
      if (source.getTargets().isEmpty()) {
         return list;
      } else {
         Objects.requireNonNull(source);
         return filter(list, source::canTarget);
      }
   }

   public static CardCollection getKeyword(Iterable<Card> cardList, String keyword) {
      return filter(cardList, CardPredicates.hasKeyword(keyword));
   }

   public static CardCollection getKeyword(Iterable<Card> cardList, Keyword keyword) {
      return filter(cardList, CardPredicates.hasKeyword(keyword));
   }

   public static CardCollection getNotKeyword(Iterable<Card> cardList, String keyword) {
      return filter(cardList, CardPredicates.hasKeyword(keyword).negate());
   }

   public static CardCollection getNotKeyword(Iterable<Card> cardList, Keyword keyword) {
      return filter(cardList, CardPredicates.hasKeyword(keyword).negate());
   }

   public static int getAmountOfKeyword(Iterable<Card> cardList, String keyword) {
      int nKeyword = 0;

      for(Card c : cardList) {
         nKeyword += c.getAmountOfKeyword(keyword);
      }

      return nKeyword;
   }

   public static int getAmountOfKeyword(Iterable<Card> cardList, Keyword keyword) {
      int nKeyword = 0;

      for(Card c : cardList) {
         nKeyword += c.getAmountOfKeyword(keyword);
      }

      return nKeyword;
   }

   public static CardCollection getNotType(Iterable<Card> cardList, String cardType) {
      return filter(cardList, CardPredicates.isType(cardType).negate());
   }

   public static CardCollection getType(Iterable<Card> cardList, String cardType) {
      return filter(cardList, CardPredicates.isType(cardType));
   }

   public static CardCollection getNotColor(Iterable<Card> cardList, byte color) {
      return filter(cardList, CardPredicates.isColor(color).negate());
   }

   public static CardCollection getColor(Iterable<Card> cardList, byte color) {
      return filter(cardList, CardPredicates.isColor(color));
   }

   public static CardCollection filter(Iterable<Card> cardList, Predicate<Card> filt) {
      return new CardCollection(IterableUtil.filter(cardList, filt));
   }

   public static CardCollection filter(Iterable<Card> cardList, Predicate<Card> f1, Predicate<Card> f2) {
      return new CardCollection(IterableUtil.filter(cardList, f1.and(f2)));
   }

   public static CardCollection filter(Iterable<Card> cardList, Iterable<Predicate<Card>> filt) {
      return new CardCollection(IterableUtil.filter(cardList, IterableUtil.and(filt)));
   }

   public static List<Card> filterAsList(Iterable<Card> cardList, Predicate<Card> filt) {
      return Lists.newArrayList(IterableUtil.filter(cardList, filt));
   }

   public static List<Card> filterAsList(Iterable<Card> cardList, Predicate<Card> f1, Predicate<Card> f2) {
      return Lists.newArrayList(IterableUtil.filter(cardList, f1.and(f2)));
   }

   public static List<Card> filterAsList(Iterable<Card> cardList, Iterable<Predicate<Card>> filt) {
      return Lists.newArrayList(IterableUtil.filter(cardList, IterableUtil.and(filt)));
   }

   public static int count(Iterable<Card> cardList, Predicate<Card> filt) {
      if (cardList == null) {
         return 0;
      } else {
         int count = 0;

         for(Card c : cardList) {
            if (filt.test(c)) {
               ++count;
            }
         }

         return count;
      }
   }

   public static CardCollection getCardsWithHighestCMC(Iterable<Card> cardList) {
      CardCollection tiedForHighest = new CardCollection();
      int highest = 0;

      for(Card crd : cardList) {
         int curCmc = crd.getCMC();
         if (curCmc > highest) {
            highest = curCmc;
            tiedForHighest.clear();
         }

         if (curCmc >= highest) {
            tiedForHighest.add(crd);
         }
      }

      return tiedForHighest;
   }

   public static CardCollection getCardsWithLowestCMC(Iterable<Card> cardList) {
      CardCollection tiedForLowest = new CardCollection();
      int lowest = 25;

      for(Card crd : cardList) {
         int curCmc = crd.getCMC();
         if (curCmc < lowest) {
            lowest = curCmc;
            tiedForLowest.clear();
         }

         if (curCmc <= lowest) {
            tiedForLowest.add(crd);
         }
      }

      return tiedForLowest;
   }

   public static int getTotalPower(Iterable<Card> cardList, CardTraitBase ctb) {
      int total = 0;

      for(Card crd : cardList) {
         if (StaticAbilityTapPowerValue.withToughness(crd, ctb)) {
            total += Math.max(0, crd.getNetToughness());
         } else {
            int m = StaticAbilityTapPowerValue.getMod(crd, ctb);
            total += Math.max(0, crd.getNetPower() + m);
         }
      }

      return total;
   }

   public static int getTotalChroma(Iterable<Card> cardList, byte colorCode) {
      int colorOcurrencices = 0;

      for(Card c0 : cardList) {
         for(ManaCostShard sh : c0.getManaCost()) {
            if (sh.isColor(colorCode)) {
               ++colorOcurrencices;
            }
         }
      }

      return colorOcurrencices;
   }

   public static int getTotalCMC(Iterable<Card> cardList) {
      int total = 0;

      for(Card crd : cardList) {
         total += Math.max(0, crd.getCMC());
      }

      return total;
   }

   public static boolean cmcCanSumTo(int sum, Iterable<Card> cardList) {
      List<Integer> numList = Lists.newArrayList();

      for(Card c : cardList) {
         int num = c.getCMC();
         if (num == sum) {
            return true;
         }

         if (num < sum) {
            numList.add(num);
         }
      }

      if (numList.isEmpty()) {
         return false;
      } else {
         numList.sort((Comparator)null);
         return isSubsetSum(numList, sum);
      }
   }

   public static boolean isSubsetSum(List<Integer> numList, int sum) {
      if (sum == 0) {
         return true;
      } else {
         int size = numList.size();
         if (size == 0) {
            return false;
         } else {
            Integer last = (Integer)numList.get(size - 1);
            numList.remove(last);
            if (last > sum) {
               return isSubsetSum(numList, sum);
            } else {
               return isSubsetSum(numList, sum) || isSubsetSum(numList, sum - last);
            }
         }
      }
   }

   public static int getDifferentNamesCount(Iterable<Card> cardList) {
      Map<Boolean, List<Card>> parted = (Map)StreamUtil.stream(cardList).collect(Collectors.partitioningBy(Card::hasNonLegendaryCreatureNames, Collector.<Card, List<Card>>of(ArrayList::new, (list, cx) -> {
         if (!cx.hasNoName() && list.stream().noneMatch((c2) -> cx.sharesNameWith(c2))) {
            list.add(cx);
         }

      }, (l1, l2) -> {
         l1.addAll(l2);
         return l1;
      })));
      List<Card> preList = (List)parted.get(Boolean.FALSE);

      for(Card c : (List<Card>)parted.get(Boolean.TRUE)) {
         if (preList.stream().noneMatch((c2) -> c.sharesNameWith(c2))) {
            preList.add(c);
         }
      }

      return preList.size();
   }
}
