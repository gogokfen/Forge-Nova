package forge.game.combat;

import com.google.common.collect.Lists;
import forge.card.mana.ManaCost;
import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityBlockRestrict;
import forge.game.staticability.StaticAbilityCantAttackBlock;
import forge.game.staticability.StaticAbilityMustBlock;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.TextUtil;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;

public class CombatUtil {
   public static FCollectionView<GameEntity> getAllPossibleDefenders(Player playerWhoAttacks) {
      FCollection<GameEntity> defenders = new FCollection<GameEntity>();

      for(Player defender : playerWhoAttacks.getOpponents()) {
         defenders.add(defender);
         defenders.addAll(defender.getPlaneswalkersInPlay());
      }

      Game game = playerWhoAttacks.getGame();

      for(Card battle : CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.BATTLES)) {
         if (battle.getProtectingPlayer().isOpponentOf(playerWhoAttacks)) {
            defenders.add(battle);
         }
      }

      return defenders;
   }

   public static boolean validateAttackers(Combat combat) {
      AttackConstraints constraints = combat.getAttackConstraints();
      int myViolations = constraints.countViolations(combat.getAttackersAndDefenders());
      if (myViolations == -1) {
         return false;
      } else {
         Pair<Map<Card, GameEntity>, Integer> bestAttack = constraints.getLegalAttackers();
         return myViolations <= (Integer)bestAttack.getRight();
      }
   }

   public static boolean couldAttackButNotAttacking(Combat combat, Card attacker) {
      if (combat == null) {
         combat = new Combat(attacker.getController());
      } else if (combat.isAttacking(attacker)) {
         return false;
      }

      AttackConstraints constraints = combat.getAttackConstraints();
      Pair<Map<Card, GameEntity>, Integer> bestAttack = constraints.getLegalAttackers();
      Map<Card, GameEntity> attackers = new HashMap(combat.getAttackersAndDefenders());
      Game game = attacker.getGame();
      return getAllPossibleDefenders(attacker.getController()).anyMatch((defender) -> {
         if (canAttack(attacker, defender) && getAttackCost(game, attacker, defender) == null) {
            attackers.put(attacker, defender);
            int myViolations = constraints.countViolations(attackers);
            if (myViolations == -1) {
               return false;
            } else {
               return myViolations <= (Integer)bestAttack.getRight();
            }
         } else {
            return false;
         }
      });
   }

   public static boolean canAttack(Player p) {
      CardCollection possibleAttackers = getPossibleAttackers(p);
      return !possibleAttackers.isEmpty();
   }

   public static CardCollection getPossibleAttackers(Player p) {
      return CardLists.filter(p.getCreaturesInPlay(), CombatUtil::canAttack);
   }

   public static boolean canAttack(Card attacker) {
      return getAllPossibleDefenders(attacker.getController()).anyMatch((defender) -> canAttack(attacker, defender));
   }

   public static boolean canAttack(Card attacker, GameEntity defender) {
      return canAttack(attacker, defender, false);
   }

   public static boolean canAttackNextTurn(Card attacker, GameEntity defender) {
      return canAttack(attacker, defender, true);
   }

   private static boolean canAttack(Card attacker, GameEntity defender, boolean forNextTurn) {
      Game game = attacker.getGame();
      if (attacker.isBattle()) {
         return false;
      } else if (forNextTurn || attacker.isCreature() && !attacker.isTapped() && !attacker.isPhasedOut() && !isAttackerSick(attacker, defender) && !game.getPhaseHandler().getPhase().isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
         if (attacker.isGoaded()) {
            boolean goadedByDefender = defender instanceof Player && attacker.isGoadedBy((Player)defender);
            if (goadedByDefender || !(defender instanceof Player)) {
               for(GameEntity ge : getAllPossibleDefenders(attacker.getController())) {
                  if (!ge.equals(defender) && ge instanceof Player && !attacker.isGoadedBy((Player)ge) && canAttack(attacker, ge)) {
                     return false;
                  }
               }
            }
         }

         return !StaticAbilityCantAttackBlock.cantAttack(attacker, defender);
      } else {
         return false;
      }
   }

   public static boolean isAttackerSick(Card attacker, GameEntity defender) {
      return !StaticAbilityCantAttackBlock.canAttackHaste(attacker, defender);
   }

   public static boolean checkPropagandaEffects(Game game, Card attacker, Combat combat, List<Card> attackersWithOptionalCost) {
      Cost attackCost = getAttackCost(game, attacker, combat.getDefenderByAttacker(attacker), attackersWithOptionalCost);
      if (attackCost == null) {
         return true;
      } else {
         SpellAbility fakeSA = new SpellAbility.EmptySa(attacker, attacker.getController());
         fakeSA.setCardState(attacker.getCurrentState());
         fakeSA.setPayCosts(attackCost);
         fakeSA.setSVar("X", "0");
         return attacker.getController().getController().payCombatCost(attacker, attackCost, fakeSA, "Pay additional cost to declare " + String.valueOf(attacker) + " an attacker");
      }
   }

   public static Cost getAttackCost(Game game, Card attacker, GameEntity defender) {
      return getAttackCost(game, attacker, defender, List.of());
   }

   public static Cost getAttackCost(Game game, Card attacker, GameEntity defender, List<Card> attackersWithOptionalCost) {
      Cost attackCost = new Cost(ManaCost.ZERO, true);
      boolean hasCost = false;

      for(Card card : game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
         for(StaticAbility stAb : card.getStaticAbilities()) {
            Cost additionalCost = stAb.getAttackCost(attacker, defender, attackersWithOptionalCost);
            if (null != additionalCost) {
               attackCost.add(additionalCost);
               hasCost = true;
            }
         }
      }

      if (!hasCost) {
         return null;
      } else {
         return attackCost;
      }
   }

   public static CardCollection getOptionalAttackCostCreatures(CardCollection attackers, Class<? extends CostPart> costType) {
      CardCollection attackersWithCost = new CardCollection();

      for(Card card : attackers) {
         for(StaticAbility stAb : card.getStaticAbilities()) {
            if (stAb.hasAttackCost(card, costType)) {
               attackersWithCost.add(card);
            }
         }
      }

      return attackersWithCost;
   }

   public static boolean payRequiredBlockCosts(Game game, Card blocker, Card attacker) {
      Cost blockCost = getBlockCost(game, blocker, attacker);
      if (blockCost == null) {
         return true;
      } else {
         SpellAbility fakeSA = new SpellAbility.EmptySa(blocker, blocker.getController());
         fakeSA.setCardState(blocker.getCurrentState());
         fakeSA.setPayCosts(blockCost);
         fakeSA.setSVar("X", "0");
         return blocker.getController().getController().payCombatCost(blocker, blockCost, fakeSA, "Pay cost to declare " + String.valueOf(blocker) + " a blocker. ");
      }
   }

   public static Cost getBlockCost(Game game, Card blocker, Card attacker) {
      if (forge.game.card.TraitEpoch.DISABLED) {
         return getBlockCostScan(game, blocker, attacker);
      }
      // Forge Nova: only static abilities with the CantBlockUnless mode can produce a block cost;
      // the index lists exactly those, in the original scan order.
      java.util.List<StaticAbility> candidates = forge.game.staticability.StaticAbilityIndex.forMode(game, forge.game.staticability.StaticAbilityMode.CantBlockUnless);
      Cost result = null;
      if (!candidates.isEmpty()) {
         Cost blockCost = new Cost(ManaCost.ZERO, true);
         boolean noCost = true;
         for(StaticAbility stAb : candidates) {
            Cost c1 = stAb.getBlockCost(blocker, attacker);
            if (c1 != null) {
               blockCost.add(c1);
               noCost = false;
            }
         }
         result = noCost ? null : blockCost;
      }
      if (forge.game.card.TraitEpoch.VERIFY) {
         Cost fresh = getBlockCostScan(game, blocker, attacker);
         if (!String.valueOf(fresh).equals(String.valueOf(result))) {
            forge.game.card.TraitEpoch.mismatch("blockCost", blocker, java.util.List.of(String.valueOf(result)), java.util.List.of(String.valueOf(fresh)));
         }
      }
      return result;
   }

   /** The original implementation (full scan), kept for verification and A/B runs. */
   private static Cost getBlockCostScan(Game game, Card blocker, Card attacker) {
      Cost blockCost = new Cost(ManaCost.ZERO, true);
      boolean noCost = true;

      for(Card card : game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
         for(StaticAbility stAb : card.getStaticAbilities()) {
            Cost c1 = stAb.getBlockCost(blocker, attacker);
            if (c1 != null) {
               blockCost.add(c1);
               noCost = false;
            }
         }
      }

      if (noCost) {
         return null;
      } else {
         return blockCost;
      }
   }

   public static void checkDeclaredAttacker(Game game, Card c, Combat combat, boolean triggers) {
      GameEntity defender = combat.getDefenderByAttacker(c);
      List<Card> otherAttackers = combat.getAttackers();
      if (triggers) {
         Map<AbilityKey, Object> runParams = AbilityKey.newMap();
         runParams.put(AbilityKey.Attacker, c);
         otherAttackers.remove(c);
         runParams.put(AbilityKey.OtherAttackers, otherAttackers);
         runParams.put(AbilityKey.Attacked, defender);
         runParams.put(AbilityKey.DefendingPlayer, combat.getDefenderPlayerByAttacker(c));
         FCollection<GameEntity> defenders = new FCollection<GameEntity>();

         for(GameEntity e : combat.getDefenders()) {
            if (!combat.getAttackersOf(e).isEmpty()) {
               defenders.add(e);
            }
         }

         runParams.put(AbilityKey.Defenders, defenders);
         game.getTriggerHandler().runTrigger(TriggerType.Attacks, runParams, false);
      }

      c.getDamageHistory().setCreatureAttackedThisCombat(defender, otherAttackers.size());
      c.getDamageHistory().clearNotAttackedSinceLastUpkeepOf();
      c.getController().addCreaturesAttackedThisTurn(CardCopyService.getLKICopy(c), defender);
   }

   public static AttackConstraints getAllRequirements(Combat combat) {
      return new AttackConstraints(combat);
   }

   public static boolean canBlock(Card blocker, Combat combat) {
      if (blocker == null) {
         return false;
      } else if (combat == null) {
         return canBlock(blocker);
      } else if (!canBlockMoreCreatures(blocker, combat.getAttackersBlockedBy(blocker))) {
         return false;
      } else {
         CardCollection allOtherBlockers = combat.getAllBlockers();
         allOtherBlockers.remove(blocker);
         int blockersFromOnePlayer = CardLists.count(allOtherBlockers, CardPredicates.isController(blocker.getController()));
         return blockersFromOnePlayer >= StaticAbilityBlockRestrict.blockRestrictNum(blocker.getController()) ? false : canBlock(blocker);
      }
   }

   public static boolean canBlock(Card blocker) {
      return canBlock(blocker, false);
   }

   public static boolean canBlock(Card blocker, boolean nextTurn) {
      if (blocker != null && blocker.isCreature()) {
         if (blocker.isBattle()) {
            return false;
         } else if (!nextTurn && blocker.isPhasedOut()) {
            return false;
         } else if (!nextTurn && blocker.isTapped() && !StaticAbilityCantAttackBlock.canBlockTapped(blocker)) {
            return false;
         } else if (!blocker.hasKeyword("CARDNAME can't block.") && !blocker.hasKeyword("CARDNAME can't attack or block.")) {
            if (StaticAbilityCantAttackBlock.cantBlock(blocker)) {
               return false;
            } else {
               boolean cantBlockAlone = blocker.hasKeyword("CARDNAME can't attack or block alone.") || blocker.hasKeyword("CARDNAME can't block alone.");
               List<Card> list = blocker.getController().getCreaturesInPlay();
               return list.size() >= 2 || !cantBlockAlone;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public static boolean canBlockMoreCreatures(Card blocker, CardCollectionView blockedBy) {
      if (!blockedBy.isEmpty() && !blocker.canBlockAny()) {
         int canBlockMore = blocker.canBlockAdditional();
         return canBlockMore >= blockedBy.size();
      } else {
         return true;
      }
   }

   public static boolean canBeBlocked(Card attacker, Combat combat, Player defendingPlayer) {
      if (attacker == null) {
         return true;
      } else {
         if (combat != null) {
            if ((Integer)StaticAbilityCantAttackBlock.getMinMaxBlocker(attacker, defendingPlayer).getRight() == combat.getBlockers(attacker).size()) {
               return false;
            }

            Player attacked = combat.getDefendingPlayerRelatedTo(attacker);
            if (attacked != null && attacked != defendingPlayer) {
               return false;
            }
         }

         return !StaticAbilityCantAttackBlock.cantBlockBy(attacker, (Card)null);
      }
   }

   public static boolean canBlockAtLeastOne(Card blocker, Iterable<Card> attackers) {
      for(Card attacker : attackers) {
         if (canBlock(attacker, blocker)) {
            return true;
         }
      }

      return false;
   }

   public static boolean canBeBlocked(Card attacker, List<Card> blockers, Combat combat) {
      int blocks = 0;

      for(Card blocker : blockers) {
         if (canBlock(attacker, blocker)) {
            ++blocks;
         }
      }

      return canAttackerBeBlockedWithAmount(attacker, blocks, combat);
   }

   public static List<Card> getPotentialBestBlockers(Card attacker, List<Card> blockers, Combat combat) {
      List<Card> potentialBlockers = Lists.newArrayList();
      if (!blockers.isEmpty() && attacker != null) {
         for(Card blocker : blockers) {
            if (canBlock(attacker, blocker)) {
               potentialBlockers.add(blocker);
            }
         }

         int minBlockers = getMinNumBlockersForAttacker(attacker, ((Card)blockers.get(0)).getController());
         CardLists.sortByPowerDesc(potentialBlockers);
         List<Card> minBlockerList = Lists.newArrayList();

         for(int i = 0; i < minBlockers && i < potentialBlockers.size(); ++i) {
            minBlockerList.add((Card)potentialBlockers.get(i));
         }

         return minBlockerList;
      } else {
         return potentialBlockers;
      }
   }

   public static List<Card> findFreeBlockers(List<Card> defendersArmy, Combat combat) {
      CardCollection freeBlockers = new CardCollection();

      for(Card blocker : defendersArmy) {
         if (canBlock(blocker) && !mustBlockAnAttacker(blocker, combat, (List)null)) {
            CardCollection blockedAttackers = combat.getAttackersBlockedBy(blocker);
            boolean blockChange = blockedAttackers.isEmpty();

            for(Card attacker : blockedAttackers) {
               List<Card> blockersReduced = combat.getBlockers(attacker);
               blockersReduced.remove(blocker);
               if (canBlockMoreCreatures(blocker, blockedAttackers) || canBeBlocked(attacker, blockersReduced, combat)) {
                  blockChange = true;
                  break;
               }
            }

            if (blockChange) {
               freeBlockers.add(blocker);
            }
         }
      }

      return freeBlockers;
   }

   public static String validateBlocks(Combat combat, Player defending) {
      List<Card> defendersArmy = defending.getCreaturesInPlay();
      List<Card> attackers = combat.getAttackers();
      List<Card> blockers = CardLists.filterControlledBy(combat.getAllBlockers(), defending);
      List<Card> freeBlockers = findFreeBlockers(defendersArmy, combat);

      for(Card blocker : defendersArmy) {
         if (!blocker.getMustBlockCards().isEmpty()) {
            CardCollectionView blockedSoFar = combat.getAttackersBlockedBy(blocker);

            for(Card cardToBeBlocked : blocker.getMustBlockCards()) {
               if (getBlockCost(blocker.getGame(), blocker, cardToBeBlocked) == null) {
                  int additionalBlockers = getMinNumBlockersForAttacker(cardToBeBlocked, defending) - 1;
                  int potentialBlockers = 0;

                  for(int i = 0; i < additionalBlockers; ++i) {
                     for(Card freeBlocker : new CardCollection(freeBlockers)) {
                        if (freeBlocker != blocker && canBlock(cardToBeBlocked, freeBlocker)) {
                           freeBlockers.remove(freeBlocker);
                           ++potentialBlockers;
                        }
                     }
                  }

                  if (potentialBlockers >= additionalBlockers && !blockedSoFar.contains(cardToBeBlocked) && (canBlockMoreCreatures(blocker, blockedSoFar) || freeBlockers.contains(blocker)) && combat.isAttacking(cardToBeBlocked) && canBlock(cardToBeBlocked, blocker)) {
                     return TextUtil.concatWithSpace(blocker.toString(), "must still block", TextUtil.addSuffix(cardToBeBlocked.toString(), "."));
                  }
               }
            }
         }

         if (mustBlockAnAttacker(blocker, combat, freeBlockers)) {
            return TextUtil.concatWithSpace(blocker.toString(), "must block an attacker, but has not been assigned to block", blockers.contains(blocker) ? "the right ones." : "any.");
         }

         if (!blockers.contains(blocker) && StaticAbilityMustBlock.blocksEachCombatIfAble(blocker)) {
            for(Card attacker : attackers) {
               if (getBlockCost(blocker.getGame(), blocker, attacker) == null && canBlock(attacker, blocker, combat)) {
                  boolean must = true;
                  if (getMinNumBlockersForAttacker(attacker, defending) > 1) {
                     List<Card> possibleBlockers = Lists.newArrayList(freeBlockers);
                     possibleBlockers.addAll(combat.getBlockers(attacker));
                     if (!canBeBlocked(attacker, possibleBlockers, combat)) {
                        must = false;
                     }
                  }

                  if (must) {
                     return TextUtil.concatWithSpace(blocker.toString(), "must block each combat but was not assigned to block any attacker now.");
                  }
               }
            }
         }
      }

      for(Card blocker : blockers) {
         boolean cantBlockAlone = blocker.hasKeyword("CARDNAME can't attack or block alone.") || blocker.hasKeyword("CARDNAME can't block alone.");
         if (blockers.size() < 2 && cantBlockAlone) {
            return TextUtil.concatWithSpace(blocker.toString(), "can't block alone.");
         }

         if (blockers.size() < 3 && blocker.hasKeyword("CARDNAME can't block unless at least two other creatures block.")) {
            return TextUtil.concatWithSpace(blocker.toString(), "can't block unless at least two other creatures block.");
         }

         if (blocker.hasKeyword("CARDNAME can't block unless a creature with greater power also blocks.")) {
            boolean found = false;
            int power = blocker.getNetPower();

            for(Card blocker2 : blockers) {
               if (blocker2.getNetPower() > power) {
                  found = true;
                  break;
               }
            }

            if (!found) {
               return TextUtil.concatWithSpace(blocker.toString(), "can't block unless a creature with greater power also blocks.");
            }
         }
      }

      for(Card attacker : attackers) {
         int cntBlockers = combat.getBlockers(attacker).size();
         if (cntBlockers > 0 && !canAttackerBeBlockedWithAmount(attacker, cntBlockers, combat)) {
            return TextUtil.concatWithSpace(attacker.toString(), "cannot be blocked with", String.valueOf(cntBlockers), "creatures you've assigned");
         }
      }

      return null;
   }

   public static boolean mustBlockAnAttacker(Card blocker, Combat combat, List<Card> freeBlockers) {
      if (blocker != null && combat != null) {
         CardCollectionView attackers = combat.getAttackers();
         CardCollection requirementCards = new CardCollection();
         Player defender = blocker.getController();

         for(Card attacker : attackers) {
            if (getBlockCost(blocker.getGame(), blocker, attacker) == null && !attackerLureSatisfied(attacker, blocker, combat.getBlockers(attacker)) && canBeBlocked(attacker, combat, defender) && canBlock(attacker, blocker)) {
               boolean canBe = true;
               Player defendingPlayer = combat.getDefenderPlayerByAttacker(attacker);
               if (getMinNumBlockersForAttacker(attacker, defendingPlayer) > 1) {
                  List<Card> blockers = defendingPlayer.getCreaturesInPlay();
                  blockers.remove(blocker);
                  if (!canBeBlocked(attacker, blockers, combat)) {
                     canBe = false;
                  }
               }

               if (canBe) {
                  requirementCards.add(attacker);
               }
            }
         }

         for(Card attacker : blocker.getMustBlockCards()) {
            if (getBlockCost(blocker.getGame(), blocker, attacker) == null && canBeBlocked(attacker, combat, defender) && canBlock(attacker, blocker) && combat.isAttacking(attacker)) {
               boolean canBe = true;
               Player defendingPlayer = combat.getDefenderPlayerByAttacker(attacker);
               if (getMinNumBlockersForAttacker(attacker, defendingPlayer) > 1) {
                  List<Card> blockers = freeBlockers != null ? new CardCollection(freeBlockers) : defendingPlayer.getCreaturesInPlay();
                  blockers.remove(blocker);
                  if (!canBeBlocked(attacker, blockers, combat)) {
                     canBe = false;
                  }
               }

               if (canBe) {
                  requirementCards.add(attacker);
               }
            }
         }

         if (requirementCards.isEmpty()) {
            return false;
         } else if (combat.getAttackersBlockedBy(blocker).containsAll(requirementCards)) {
            return false;
         } else {
            if (!canBlock(blocker, combat)) {
               for(Card attacker : attackers) {
                  boolean requirementSatisfied = attackerLureSatisfied(attacker, blocker, combat.getBlockers(attacker));
                  CardCollection reducedBlockers = combat.getBlockers(attacker);
                  if (requirementSatisfied && reducedBlockers.contains(blocker)) {
                     reducedBlockers.remove(blocker);
                     if (!attackerLureSatisfied(attacker, blocker, reducedBlockers)) {
                        return false;
                     }
                  }
               }
            }

            return Collections.disjoint(combat.getAttackersBlockedBy(blocker), requirementCards);
         }
      } else {
         return false;
      }
   }

   private static boolean attackerLureSatisfied(Card attacker, Card blocker, CardCollection blockers) {
      if (!attacker.hasStartOfKeyword("All creatures able to block CARDNAME do so.") && (!attacker.hasStartOfKeyword("CARDNAME must be blocked if able.") || !blockers.isEmpty()) && (!attacker.hasStartOfKeyword("CARDNAME must be blocked by exactly one creature if able.") || blockers.size() == 1) && (!attacker.hasStartOfKeyword("CARDNAME must be blocked by two or more creatures if able.") || blockers.size() >= 2)) {
         for(KeywordInterface inst : attacker.getKeywords()) {
            String keyword = inst.getOriginal();
            if (keyword.startsWith("MustBeBlockedBy ")) {
               String valid = keyword.substring("MustBeBlockedBy ".length());
               if (blocker.isValid(valid, (Player)null, (Card)null, (CardTraitBase)null) && CardLists.getValidCardCount(blockers, valid, (Player)null, (Card)null, (CardTraitBase)null) == 0) {
                  return false;
               }
            }

            if (keyword.startsWith("MustBeBlockedByAll")) {
               String valid = keyword.split(":")[1];
               if (blocker.isValid(valid, (Player)null, (Card)null, (CardTraitBase)null)) {
                  return false;
               }
            }
         }

         return true;
      } else {
         return false;
      }
   }

   public static boolean canBlock(Player p, Combat combat) {
      List<Card> creatures = p.getCreaturesInPlay();
      if (creatures.isEmpty()) {
         return false;
      } else {
         List<Card> attackers = combat.getAttackers();
         if (attackers.isEmpty()) {
            return false;
         } else {
            for(Card c : creatures) {
               for(Card a : attackers) {
                  if (canBlock(a, c, combat)) {
                     return true;
                  }
               }
            }

            return false;
         }
      }
   }

   public static boolean canBlock(Card attacker, Card blocker, Combat combat) {
      if (attacker != null && blocker != null) {
         if (!canBlock(blocker, combat)) {
            return false;
         } else if (!canBeBlocked(attacker, combat, blocker.getController())) {
            return false;
         } else if (combat != null && combat.isBlocking(blocker, attacker)) {
            return false;
         } else {
            boolean mustBeBlockedBy = false;

            for(KeywordInterface inst : attacker.getKeywords()) {
               String keyword = inst.getOriginal();
               if (keyword.startsWith("MustBeBlockedBy ")) {
                  String valid = keyword.substring("MustBeBlockedBy ".length());
                  if (blocker.isValid(valid, (Player)null, (Card)null, (CardTraitBase)null) && CardLists.getValidCardCount(combat.getBlockers(attacker), valid, (Player)null, (Card)null, (CardTraitBase)null) == 0) {
                     mustBeBlockedBy = true;
                     break;
                  }
               }

               if (keyword.startsWith("MustBeBlockedByAll")) {
                  String valid = keyword.split(":")[1];
                  if (blocker.isValid(valid, (Player)null, (Card)null, (CardTraitBase)null)) {
                     mustBeBlockedBy = true;
                     break;
                  }
               }
            }

            return !attacker.hasKeyword("All creatures able to block CARDNAME do so.") && (!attacker.hasKeyword("CARDNAME must be blocked if able.") || !combat.getBlockers(attacker).isEmpty()) && (!attacker.hasKeyword("CARDNAME must be blocked by exactly one creature if able.") || combat.getBlockers(attacker).size() == 1) && (!attacker.hasKeyword("CARDNAME must be blocked by two or more creatures if able.") || combat.getBlockers(attacker).size() >= 2) && !blocker.getMustBlockCards().contains(attacker) && !mustBeBlockedBy && mustBlockAnAttacker(blocker, combat, (List)null) ? false : canBlock(attacker, blocker);
         }
      } else {
         return false;
      }
   }

   public static boolean canBlock(Card attacker, Card blocker) {
      return canBlock(attacker, blocker, false);
   }

   public static boolean canBlock(Card attacker, Card blocker, boolean nextTurn) {
      if (attacker != null && blocker != null && blocker.isCreature()) {
         if (!canBlock(blocker, nextTurn)) {
            return false;
         } else {
            return !StaticAbilityCantAttackBlock.cantBlockBy(attacker, blocker);
         }
      } else {
         return false;
      }
   }

   public static boolean canAttackerBeBlockedWithAmount(Card attacker, int amount, Combat combat) {
      return canAttackerBeBlockedWithAmount(attacker, amount, combat != null ? combat.getDefenderPlayerByAttacker(attacker) : null);
   }

   public static boolean canAttackerBeBlockedWithAmount(Card attacker, int amount, Player defender) {
      if (amount == 0) {
         return false;
      } else {
         Pair<Integer, Integer> minMaxBlock = StaticAbilityCantAttackBlock.getMinMaxBlocker(attacker, defender);
         return (Integer)minMaxBlock.getLeft() <= amount && (Integer)minMaxBlock.getRight() >= amount;
      }
   }

   public static int getMinNumBlockersForAttacker(Card attacker, Player defender) {
      return (Integer)StaticAbilityCantAttackBlock.getMinMaxBlocker(attacker, defender).getLeft();
   }
}
