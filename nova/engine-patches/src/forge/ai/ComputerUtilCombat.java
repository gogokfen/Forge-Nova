package forge.ai;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CounterEnumType;
import forge.game.combat.AttackingBand;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.CostPayment;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityAssignCombatDamageAsUnblocked;
import forge.game.staticability.StaticAbilityMode;
import forge.game.staticability.StaticAbilityMustAttack;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.IterableUtil;
import forge.util.MyRandom;
import forge.util.TextUtil;
import forge.util.collect.FCollection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class ComputerUtilCombat {
   public static boolean canAttackNextTurn(Card attacker) {
      Iterable<GameEntity> defenders = CombatUtil.getAllPossibleDefenders(attacker.getController());
      return IterableUtil.any(defenders, (input) -> canAttackNextTurn(attacker, input));
   }

   public static boolean canAttackNextTurn(Card attacker, GameEntity defender) {
      if (!attacker.isCreature()) {
         return false;
      } else if (!CombatUtil.canAttackNextTurn(attacker, defender)) {
         return false;
      } else if (attacker.getGame().getReplacementHandler().wouldPhaseBeSkipped(attacker.getController(), PhaseType.COMBAT_BEGIN)) {
         return false;
      } else {
         List<GameEntity> mustAttack = StaticAbilityMustAttack.entitiesMustAttack(attacker);
         mustAttack.removeAll(new CardCollection(attacker));
         if (!mustAttack.isEmpty() && !mustAttack.contains(defender)) {
            return false;
         } else {
            return !attacker.isTapped() || attacker.getCounters(CounterEnumType.STUN) == 0 && attacker.canUntap(attacker.getController(), true);
         }
      }
   }

   public static int getTotalFirstStrikeBlockPower(Card attacker, Player player) {
      List<Card> list = player.getCreaturesInPlay();
      List<Card> var3 = CardLists.filter(list, ((c) -> (c.hasFirstStrike() || c.hasDoubleStrike()) && CombatUtil.canBlock(attacker, c)));
      return totalFirstStrikeDamageOfBlockers(attacker, var3);
   }

   public static int getAttack(Card c) {
      int n = c.getNetCombatDamage();
      if (c.hasDoubleStrike()) {
         n *= 2;
      }

      return n;
   }

   public static int damageIfUnblocked(Card attacker, GameEntity attacked, Combat combat, boolean withoutAbilities) {
      int damage = attacker.getNetCombatDamage();
      int sum = 0;
      if (attacked instanceof Player p) {
         if (!p.canLoseLife()) {
            return 0;
         }
      }

      if (!attacker.hasKeyword(Keyword.INFECT)) {
         if (isCombatDamagePrevented(attacker, attacked, damage)) {
            return 0;
         }

         damage += predictPowerBonusOfAttacker(attacker, (Card)null, combat, withoutAbilities);
         sum = predictDamageTo(attacked, damage, attacker, true);
         if (attacker.hasDoubleStrike()) {
            sum *= 2;
         }
      }

      return sum;
   }

   public static int poisonIfUnblocked(Card attacker, Player attacked) {
      if (!attacked.canReceiveCounters(CounterEnumType.POISON)) {
         return 0;
      } else {
         int damage = attacker.getNetCombatDamage() + predictPowerBonusOfAttacker(attacker, (Card)null, (Combat)null, false);
         int poison = 0;
         if (attacker.isInfectDamage(attacked)) {
            int pd = predictDamageTo(attacked, damage, attacker, true);
            if (pd > 1 || !attacker.getController().getOpponents().getCardsIn(ZoneType.Battlefield).anyMatch(CardPredicates.nameEquals("Vorinclex, Monstrous Raider"))) {
               poison = pd;
               if (attacker.hasDoubleStrike()) {
                  poison = pd * 2;
               }
            }
         }

         if (damage > 0) {
            poison += predictExtraPoisonWithDamage(attacker, attacked, damage);
         }

         return poison;
      }
   }

   public static int sumDamageIfUnblocked(Iterable<Card> attackers, Player attacked) {
      return sumDamageIfUnblocked(attackers, attacked, false);
   }

   public static int sumDamageIfUnblocked(Iterable<Card> attackers, Player attacked, boolean onlyPreventable) {
      int sum = 0;

      for(Card attacker : attackers) {
         if (!onlyPreventable || attacker.canDamagePrevented(true)) {
            sum += damageIfUnblocked(attacker, attacked, (Combat)null, false);
         }
      }

      return sum;
   }

   public static int sumPoisonIfUnblocked(List<Card> attackers, Player attacked) {
      int sum = 0;

      for(Card attacker : attackers) {
         sum += poisonIfUnblocked(attacker, attacked);
      }

      return sum;
   }

   public static boolean wouldLoseLife(Player ai, Combat combat) {
      return lifeThatWouldRemain(ai, combat) < ai.getLife();
   }

   public static int lifeThatWouldRemain(Player ai, Combat combat) {
      int damage = 0;
      if (ai.canLoseLife()) {
         List<Card> attackers = combat.getAttackersOf(ai);
         List<Card> unblocked = Lists.newArrayList();

         for(Card attacker : attackers) {
            List<Card> blockers = combat.getBlockers(attacker);
            if (blockers.size() != 0 && !StaticAbilityAssignCombatDamageAsUnblocked.assignCombatDamageAsUnblocked(attacker)) {
               if (attacker.hasKeyword(Keyword.TRAMPLE) && !attacker.hasKeyword(Keyword.INFECT)) {
                  int dmgAfterShielding = getAttack(attacker) - totalShieldDamage(attacker, blockers);
                  if (dmgAfterShielding > 0) {
                     damage += dmgAfterShielding;
                  }
               }
            } else {
               unblocked.add(attacker);
            }
         }

         damage += sumDamageIfUnblocked(unblocked, ai);
      }

      return ai.getLife() - damage;
   }

   public static int resultingPoison(Player ai, Combat combat) {
      if (!ai.canReceiveCounters(CounterEnumType.POISON)) {
         return ai.getPoisonCounters();
      } else {
         int poison = 0;
         List<Card> attackers = combat.getAttackersOf(ai);
         List<Card> unblocked = Lists.newArrayList();

         for(Card attacker : attackers) {
            List<Card> blockers = combat.getBlockers(attacker);
            if (!blockers.isEmpty() && !StaticAbilityAssignCombatDamageAsUnblocked.assignCombatDamageAsUnblocked(attacker)) {
               if (attacker.hasKeyword(Keyword.TRAMPLE)) {
                  int trampleDamage = getAttack(attacker) - totalShieldDamage(attacker, blockers);
                  if (trampleDamage > 0) {
                     if (attacker.isInfectDamage(ai)) {
                        poison += trampleDamage;
                     }

                     poison += predictExtraPoisonWithDamage(attacker, ai, trampleDamage);
                  }
               }
            } else {
               unblocked.add(attacker);
            }
         }

         poison += sumPoisonIfUnblocked(unblocked, ai);
         return ai.getPoisonCounters() + poison;
      }
   }

   public static List<Card> getLifeThreateningCommanders(Player ai, Combat combat) {
      List<Card> res = Lists.newArrayList();

      for(Card c : combat.getAttackers()) {
         if (c.isCommander() && combat.isAttacking(c, ai)) {
            int currentCommanderDamage = ai.getCommanderDamage(c);
            if (damageIfUnblocked(c, ai, combat, false) + currentCommanderDamage >= 21) {
               res.add(c);
            }
         }
      }

      return res;
   }

   public static boolean lifeInDanger(Player ai, Combat combat) {
      return lifeInDanger(ai, combat, 0);
   }

   public static boolean lifeInDanger(Player ai, Combat combat, int payment) {
      if (!ai.cantLose() && combat != null && combat.getAttackingPlayer() != ai) {
         CardCollectionView otb = ai.getCardsIn(ZoneType.Battlefield);
         if (otb.anyMatch(CardPredicates.nameEquals("Worship")) && !ai.getCreaturesInPlay().isEmpty()) {
            return false;
         } else if (otb.anyMatch(CardPredicates.nameEquals("Elderscale Wurm")) && ai.getLife() >= 7) {
            return false;
         } else {
            List<Card> attackers = combat.getAttackersOf(ai);
            List<Card> threateningCommanders = getLifeThreateningCommanders(ai, combat);

            for(Card attacker : attackers) {
               List<Card> blockers = combat.getBlockers(attacker);
               if (blockers.isEmpty() && !attacker.getSVar("MustBeBlocked").isEmpty()) {
                  boolean cond = false;
                  String condVal = attacker.getSVar("MustBeBlocked");
                  boolean isAttackingPlayer = combat.getDefenderByAttacker(attacker) instanceof Player;
                  cond |= "true".equalsIgnoreCase(condVal);
                  cond |= "attackingplayer".equalsIgnoreCase(condVal) && isAttackingPlayer;
                  cond |= "attackingplayerconservative".equalsIgnoreCase(condVal) && isAttackingPlayer && ai.getCreaturesInPlay().size() >= 3 && ai.getCreaturesInPlay().size() > attacker.getController().getCreaturesInPlay().size();
                  if (cond) {
                     return true;
                  }
               }

               if (threateningCommanders.contains(attacker)) {
                  return true;
               }
            }

            if (resultingPoison(ai, combat) > Math.max(7, ai.getPoisonCounters())) {
               return true;
            } else {
               int threshold = AiProfileUtil.getIntProperty(ai, AiProps.AI_IN_DANGER_THRESHOLD);
               int maxTreshold = AiProfileUtil.getIntProperty(ai, AiProps.AI_IN_DANGER_MAX_THRESHOLD) - threshold;

               for(int chance = MyRandom.getRandom().nextInt(80) + 5; maxTreshold > 0; --maxTreshold) {
                  if (MyRandom.getRandom().nextInt(100) < chance) {
                     ++threshold;
                  }
               }

               return !ai.cantLoseForZeroOrLessLife() && lifeThatWouldRemain(ai, combat) - payment < Math.min(threshold, ai.getLife());
            }
         }
      } else {
         return false;
      }
   }

   public static boolean lifeInSeriousDanger(Player ai, Combat combat) {
      return lifeInSeriousDanger(ai, combat, 0);
   }

   public static boolean lifeInSeriousDanger(Player ai, Combat combat, int payment) {
      if (!ai.cantLose() && combat != null) {
         List<Card> threateningCommanders = getLifeThreateningCommanders(ai, combat);

         for(Card attacker : combat.getAttackersOf(ai)) {
            List<Card> blockers = combat.getBlockers(attacker);
            if (blockers.isEmpty() && !attacker.getSVar("MustBeBlocked").isEmpty()) {
               return true;
            }

            if (threateningCommanders.contains(attacker)) {
               return true;
            }
         }

         if (resultingPoison(ai, combat) >= ai.getGame().getRules().getPoisonCountersToLose()) {
            return true;
         } else {
            return !ai.cantLoseForZeroOrLessLife() && lifeThatWouldRemain(ai, combat) - payment < 1;
         }
      } else {
         return false;
      }
   }

   public static int totalDamageOfBlockers(Card attacker, List<Card> defenders) {
      int damage = 0;
      if (attacker.isEquippedBy("Godsend") && !defenders.isEmpty()) {
         defenders.remove(0);
      }

      for(Card defender : defenders) {
         damage += dealsDamageAsBlocker(attacker, defender);
      }

      return damage;
   }

   public static int totalFirstStrikeDamageOfBlockers(Card attacker, List<Card> defenders) {
      int damage = 0;
      if (attacker.isEquippedBy("Godsend") && !defenders.isEmpty()) {
         defenders.remove(0);
      }

      for(Card defender : defenders) {
         damage += predictDamageByBlockerWithoutDoubleStrike(attacker, defender);
      }

      return damage;
   }

   public static int dealsDamageAsBlocker(Card attacker, Card defender) {
      int defenderDamage = predictDamageByBlockerWithoutDoubleStrike(attacker, defender);
      if (defender.hasDoubleStrike()) {
         defenderDamage += predictDamageTo(attacker, defenderDamage, defender, true);
      }

      return defenderDamage;
   }

   private static int predictDamageByBlockerWithoutDoubleStrike(Card attacker, Card defender) {
      if (attacker.getName().equals("Sylvan Basilisk") && !defender.hasKeyword(Keyword.INDESTRUCTIBLE)) {
         return 0;
      } else {
         int flankingMagnitude = 0;
         if (attacker.hasKeyword(Keyword.FLANKING) && !defender.hasKeyword(Keyword.FLANKING)) {
            flankingMagnitude = attacker.getAmountOfKeyword(Keyword.FLANKING);
            if (flankingMagnitude >= defender.getNetToughness()) {
               return 0;
            }

            if (flankingMagnitude >= defender.getNetToughness() - defender.getDamage() && !defender.hasKeyword(Keyword.INDESTRUCTIBLE)) {
               return 0;
            }
         }

         if (attacker.hasKeyword(Keyword.INDESTRUCTIBLE) && !defender.isWitherDamage()) {
            return 0;
         } else {
            int defenderDamage;
            if (defender.toughnessAssignsDamage()) {
               defenderDamage = defender.getNetToughness() + predictToughnessBonusOfBlocker(attacker, defender, true);
            } else {
               defenderDamage = defender.getNetPower() + predictPowerBonusOfBlocker(attacker, defender, true);
            }

            defenderDamage = predictDamageTo(attacker, defenderDamage, defender, true);
            return defenderDamage;
         }
      }
   }

   public static int totalShieldDamage(Card attacker, List<Card> defenders) {
      int defenderDefense = 0;

      for(Card defender : defenders) {
         defenderDefense += shieldDamage(attacker, defender);
      }

      return defenderDefense;
   }

   public static int shieldDamage(Card attacker, Card blocker) {
      if (canDestroyBlockerBeforeFirstStrike(blocker, attacker, false)) {
         return 0;
      } else {
         int flankingMagnitude = 0;
         if (attacker.hasKeyword(Keyword.FLANKING) && !blocker.hasKeyword(Keyword.FLANKING)) {
            flankingMagnitude = attacker.getAmountOfKeyword(Keyword.FLANKING);
            if (flankingMagnitude >= blocker.getNetToughness()) {
               return 0;
            }

            if (flankingMagnitude >= blocker.getNetToughness() - blocker.getDamage() && !blocker.hasKeyword(Keyword.INDESTRUCTIBLE)) {
               return 0;
            }
         }

         int defBushidoMagnitude = blocker.getKeywordMagnitude(Keyword.BUSHIDO);
         int defenderDefense = blocker.getLethalDamage() - flankingMagnitude + defBushidoMagnitude;
         return defenderDefense;
      }
   }

   public static boolean combatantWouldBeDestroyed(Player ai, Card combatant, Combat combat) {
      if (combat.isAttacking(combatant)) {
         return attackerWouldBeDestroyed(ai, combatant, combat);
      } else {
         return combat.isBlocking(combatant) ? blockerWouldBeDestroyed(ai, combatant, combat) : false;
      }
   }

   public static boolean attackerWouldBeDestroyed(Player ai, Card attacker, Combat combat) {
      List<Card> blockers = combat.getBlockers(attacker);
      int firstStrikeBlockerDmg = 0;

      for(Card defender : blockers) {
         if (!defender.isWitherDamage() && canDestroyAttacker(ai, attacker, defender, combat, true)) {
            return true;
         }

         if (defender.hasFirstStrike() || defender.hasDoubleStrike()) {
            firstStrikeBlockerDmg += defender.getNetCombatDamage();
         }
      }

      if (!attacker.hasFirstStrike() && !attacker.hasDoubleStrike()) {
         return totalDamageOfBlockers(attacker, blockers) >= getDamageToKill(attacker, false);
      } else {
         return firstStrikeBlockerDmg >= getDamageToKill(attacker, true);
      }
   }

   public static boolean combatTriggerWillTrigger(Card attacker, Card defender, Trigger trigger, Combat combat) {
      return combatTriggerWillTrigger(attacker, defender, trigger, combat, (List)null);
   }

   public static boolean combatTriggerWillTrigger(Card attacker, Card defender, Trigger trigger, Combat combat, List<Card> plannedAttackers) {
      Game game = attacker.getGame();
      boolean willTrigger = false;
      Card source = trigger.getHostCard();
      if (combat == null) {
         combat = game.getCombat();
         if (combat == null) {
            return false;
         }
      }

      TriggerType mode = trigger.getMode();
      if (mode != TriggerType.Attacks && mode != TriggerType.AttackerUnblocked && mode != TriggerType.Blocks && mode != TriggerType.AttackerBlocked && mode != TriggerType.AttackerBlockedByCreature && mode != TriggerType.DamageDone) {
         return false;
      } else if (!trigger.zonesCheck(game.getZoneOf(trigger.getHostCard()))) {
         return false;
      } else if (!trigger.requirementsCheck(game)) {
         return false;
      } else {
         if (mode == TriggerType.Attacks) {
            willTrigger = true;
            if (combat.isAttacking(attacker)) {
               return false;
            }

            if (trigger.hasParam("ValidCard") && !trigger.matchesValidParam("ValidCard", attacker) && (!combat.isAttacking(source) || !trigger.matchesValidParam("ValidCard", source) || trigger.hasParam("Alone"))) {
               return false;
            }

            if (trigger.hasParam("Attacked")) {
               if (combat.isAttacking(attacker)) {
                  if (!trigger.matchesValidParam("Attacked", combat.getDefenderByAttacker(attacker))) {
                     return false;
                  }
               } else if ("You,Planeswalker.YouCtrl".equals(trigger.getParam("Attacked")) && source.getController() == attacker.getController()) {
                  return false;
               }
            }

            if (trigger.hasParam("Alone") && plannedAttackers != null && plannedAttackers.size() != 1) {
               return false;
            }
         }

         if (defender == null && mode == TriggerType.AttackerUnblocked) {
            willTrigger = true;
            if (!trigger.matchesValidParam("ValidCard", attacker)) {
               return false;
            }
         }

         if (defender == null) {
            return willTrigger;
         } else {
            if (mode == TriggerType.Blocks) {
               willTrigger = true;
               if (trigger.hasParam("ValidBlocked")) {
                  String validBlocked = trigger.getParam("ValidBlocked");
                  if (validBlocked.contains(".withLesserPower")) {
                     validBlocked = TextUtil.fastReplace(validBlocked, ".withLesserPower", "");
                     if (defender.getCurrentPower() <= attacker.getCurrentPower()) {
                        return false;
                     }
                  }

                  if (!trigger.matchesValid(attacker, validBlocked.split(","))) {
                     return false;
                  }
               }

               if (trigger.hasParam("ValidCard")) {
                  String validBlocker = trigger.getParam("ValidCard");
                  if (validBlocker.contains(".withLesserPower")) {
                     validBlocker = TextUtil.fastReplace(validBlocker, ".withLesserPower", "");
                     if (defender.getCurrentPower() >= attacker.getCurrentPower()) {
                        return false;
                     }
                  }

                  if (!trigger.matchesValid(defender, validBlocker.split(","))) {
                     return false;
                  }
               }
            } else if (mode != TriggerType.AttackerBlocked && mode != TriggerType.AttackerBlockedByCreature) {
               if (mode == TriggerType.DamageDone) {
                  willTrigger = true;
                  if (trigger.hasParam("ValidSource") && !"False".equals(trigger.getParam("CombatDamage"))) {
                     if (!trigger.matchesValidParam("ValidSource", defender) || defender.getNetCombatDamage() <= 0 || !trigger.matchesValidParam("ValidTarget", attacker)) {
                        return false;
                     }

                     if (!trigger.matchesValidParam("ValidSource", attacker) || attacker.getNetCombatDamage() <= 0 || !trigger.matchesValidParam("ValidTarget", defender)) {
                        return false;
                     }
                  }
               }
            } else {
               willTrigger = true;
               if (!trigger.matchesValidParam("ValidBlocker", defender)) {
                  return false;
               }

               if (!trigger.matchesValidParam("ValidCard", attacker)) {
                  return false;
               }
            }

            return willTrigger;
         }
      }
   }

   public static int predictPowerBonusOfBlocker(Card attacker, Card blocker, boolean withoutAbilities) {
      int power = 0;
      if (blocker.getName().equals("Serene Master")) {
         power += attacker.getNetPower() - blocker.getNetPower();
      } else if (blocker.getName().equals("Shape Stealer")) {
         power += attacker.getNetPower() - blocker.getNetPower();
      }

      if (dealsFirstStrikeDamage(attacker, withoutAbilities, (Combat)null) && attacker.isWitherDamage() && !dealsFirstStrikeDamage(blocker, withoutAbilities, (Combat)null) && blocker.canReceiveCounters(CounterEnumType.M1M1)) {
         power -= attacker.getNetCombatDamage();
      }

      Game game = attacker.getGame();

      // Forge Nova: only the Continuous static abilities, in the same order (see NovaCombatTriggers)
      NovaCombatTriggers.Statics novaStatics = NovaCombatTriggers.continuousBattlefieldAndCommand(game);
      for(int novaI = 0; novaI < novaStatics.size(); ++novaI) {
         Card card = novaStatics.host(novaI);
         StaticAbility stAb = novaStatics.stAb(novaI);
         {
            if (stAb.checkMode(StaticAbilityMode.Continuous) && stAb.hasParam("Affected") && stAb.getParam("Affected").contains("blocking")) {
               String valid = TextUtil.fastReplace(stAb.getParam("Affected"), "blocking", "Creature");
               if (blocker.isValid(valid, card.getController(), card, stAb) && stAb.hasParam("AddPower")) {
                  power += AbilityUtils.calculateAmount(card, stAb.getParam("AddPower"), stAb);
               }
            }
         }
      }

      // Forge Nova: the same collection minus modes combatTriggerWillTrigger rejects (see NovaCombatTriggers)
      FCollection<Trigger> theTriggers = NovaCombatTriggers.battlefieldAndCommand(game);

      theTriggers = NovaCombatTriggers.plus(theTriggers, attacker);

      for(Trigger trigger : theTriggers) {
         Card source = trigger.getHostCard();
         if (combatTriggerWillTrigger(attacker, blocker, trigger, (Combat)null)) {
            SpellAbility sa = trigger.ensureAbility();
            if (sa != null && ApiType.Pump.equals(sa.getApi()) && !sa.usesTargeting() && sa.hasParam("NumAtt")) {
               String defined = sa.getParam("Defined");
               List<Card> list = AbilityUtils.getDefinedCards(source, defined, sa);
               if (defined != null && defined.startsWith("TriggeredBlocker")) {
                  list.add(blocker);
               }

               if (list.contains(blocker)) {
                  power += AbilityUtils.calculateAmount(source, sa.getParam("NumAtt"), sa, true);
               }
            }
         }
      }

      if (withoutAbilities) {
         return power;
      } else {
         for(SpellAbility ability : blocker.getAllSpellAbilities()) {
            if (ability.isActivatedAbility() && !ability.hasParam("ActivationPhases") && !ability.hasParam("SorcerySpeed") && !ability.hasParam("ActivationZone") && (!ability.usesTargeting() || ability.canTarget(blocker))) {
               int pBonus = 0;
               if (ability.getApi() == ApiType.Pump) {
                  if (!ability.hasParam("NumAtt")) {
                     continue;
                  }

                  pBonus = AbilityUtils.calculateAmount(ability.getHostCard(), ability.getParam("NumAtt"), ability);
               } else if (ability.getApi() == ApiType.PutCounter) {
                  if (!ability.hasParam("CounterType") || !ability.getParam("CounterType").equals("P1P1") || ability.hasParam("Monstrosity") && blocker.isMonstrous() || ability.hasParam("Adapt") && blocker.getCounters(CounterEnumType.P1P1) > 0) {
                     continue;
                  }

                  pBonus = AbilityUtils.calculateAmount(ability.getHostCard(), ability.getParamOrDefault("CounterNum", "1"), ability);
               }

               if (pBonus > 0 && ComputerUtilCost.canPayCost(ability, blocker.getController(), false)) {
                  power += pBonus;
               }
            }
         }

         return power;
      }
   }

   public static int predictToughnessBonusOfBlocker(Card attacker, Card blocker, boolean withoutAbilities) {
      int toughness = 0;
      if (blocker.getName().equals("Shape Stealer")) {
         toughness += attacker.getNetToughness() - blocker.getNetToughness();
      }

      Game game = attacker.getGame();
      // Forge Nova: the same collection minus modes combatTriggerWillTrigger rejects (see NovaCombatTriggers)
      FCollection<Trigger> theTriggers = NovaCombatTriggers.battlefieldAndCommand(game);

      theTriggers = NovaCombatTriggers.plus(theTriggers, attacker);

      for(Trigger trigger : theTriggers) {
         Card source = trigger.getHostCard();
         if (combatTriggerWillTrigger(attacker, blocker, trigger, (Combat)null)) {
            SpellAbility sa = trigger.ensureAbility();
            if (sa != null) {
               String defined = sa.getParam("Defined");
               if (ApiType.DealDamage.equals(sa.getApi())) {
                  if (defined != null && defined.startsWith("TriggeredBlocker")) {
                     int damage = AbilityUtils.calculateAmount(source, sa.getParam("NumDmg"), sa);
                     toughness -= predictDamageTo(blocker, damage, source, false);
                  }
               } else if (ApiType.PutCounter.equals(sa.getApi())) {
                  if (defined != null && defined.startsWith("TriggeredBlocker") && "M1M1".equals(sa.getParam("CounterType"))) {
                     toughness -= AbilityUtils.calculateAmount(source, sa.getParamOrDefault("CounterNum", "1"), sa);
                  }
               } else if (ApiType.Pump.equals(sa.getApi()) && !sa.usesTargeting()) {
                  List<Card> list = AbilityUtils.getDefinedCards(source, defined, (CardTraitBase)null);
                  if (defined != null && defined.startsWith("TriggeredBlocker")) {
                     list.add(blocker);
                  }

                  if (!list.isEmpty() && list.contains(blocker) && sa.hasParam("NumDef")) {
                     toughness += AbilityUtils.calculateAmount(source, sa.getParam("NumDef"), sa, true);
                  }
               }
            }
         }
      }

      if (withoutAbilities) {
         return toughness;
      } else {
         for(SpellAbility ability : blocker.getAllSpellAbilities()) {
            if (ability.isActivatedAbility() && !ability.hasParam("ActivationPhases") && !ability.hasParam("SorcerySpeed") && !ability.hasParam("ActivationZone") && (!ability.usesTargeting() || ability.canTarget(blocker))) {
               int tBonus = 0;
               if (ability.getApi() == ApiType.Pump) {
                  if (!ability.hasParam("NumDef")) {
                     continue;
                  }

                  tBonus = AbilityUtils.calculateAmount(ability.getHostCard(), ability.getParam("NumDef"), ability);
               } else if (ability.getApi() == ApiType.PutCounter) {
                  if (!ability.hasParam("CounterType") || !ability.getParam("CounterType").equals("P1P1") || ability.hasParam("Monstrosity") && blocker.isMonstrous() || ability.hasParam("Adapt") && blocker.getCounters(CounterEnumType.P1P1) > 0) {
                     continue;
                  }

                  tBonus = AbilityUtils.calculateAmount(ability.getHostCard(), ability.getParamOrDefault("CounterNum", "1"), ability);
               }

               if (tBonus > 0 && ComputerUtilCost.canPayCost(ability, blocker.getController(), false)) {
                  toughness += tBonus;
               }
            }
         }

         return toughness;
      }
   }

   public static int predictPowerBonusOfAttacker(Card attacker, Card blocker, Combat combat, boolean withoutAbilities) {
      return predictPowerBonusOfAttacker(attacker, blocker, combat, withoutAbilities, false);
   }

   public static int predictPowerBonusOfAttacker(Card attacker, Card blocker, Combat combat, boolean withoutAbilities, boolean withoutCombatStaticAbilities) {
      int power = 0;
      if (blocker != null && blocker.getName().equals("Serene Master")) {
         power += blocker.getNetPower() - attacker.getNetPower();
      } else if (blocker != null && attacker.getName().equals("Shape Stealer")) {
         power += blocker.getNetPower() - attacker.getNetPower();
      }

      Game game = attacker.getGame();
      // Forge Nova: the same collection minus modes combatTriggerWillTrigger rejects (see NovaCombatTriggers)
      FCollection<Trigger> theTriggers = NovaCombatTriggers.battlefieldAndCommand(game);

      if (null != blocker) {
         if (dealsFirstStrikeDamage(blocker, withoutAbilities, combat) && blocker.isWitherDamage() && !dealsFirstStrikeDamage(attacker, withoutAbilities, combat) && attacker.canReceiveCounters(CounterEnumType.M1M1)) {
            power -= blocker.getNetCombatDamage();
         }

         theTriggers = NovaCombatTriggers.plus(theTriggers, blocker);
      }

      if (!withoutCombatStaticAbilities) {
         // Forge Nova: only the Continuous static abilities, in the same order (see NovaCombatTriggers)
         NovaCombatTriggers.Statics novaStatics = NovaCombatTriggers.continuousBattlefieldAndCommand(game);
         for(int novaI = 0; novaI < novaStatics.size(); ++novaI) {
            Card card = novaStatics.host(novaI);
            StaticAbility stAb = novaStatics.stAb(novaI);
            {
               if (stAb.checkMode(StaticAbilityMode.Continuous) && stAb.hasParam("Affected") && stAb.getParam("Affected").contains("attacking")) {
                  String valid = TextUtil.fastReplace(stAb.getParam("Affected"), "attacking", "Creature");
                  if (attacker.isValid(valid, card.getController(), card, stAb) && stAb.hasParam("AddPower")) {
                     power += AbilityUtils.calculateAmount(card, stAb.getParam("AddPower"), stAb);
                  }
               }
            }
         }
      }

      for(Trigger trigger : theTriggers) {
         Card source = trigger.getHostCard();
         if (combatTriggerWillTrigger(attacker, blocker, trigger, combat) && (combat == null || !trigger.isKeyword(Keyword.EXALTED) || combat.getAttackers().isEmpty() || combat.getAttackers().contains(attacker))) {
            SpellAbility sa = trigger.ensureAbility();
            if (sa != null && !sa.usesTargeting() && (ApiType.Pump.equals(sa.getApi()) || ApiType.PumpAll.equals(sa.getApi())) && sa.hasParam("NumAtt")) {
               sa.setActivatingPlayer(source.getController());
               if (!sa.hasParam("Cost") || CostPayment.canPayAdditionalCosts(sa.getPayCosts(), sa, true)) {
                  List<Card> list = Lists.newArrayList();
                  if (sa.hasParam("ValidCards")) {
                     if (attacker.isValid(sa.getParam("ValidCards").split(","), source.getController(), source, (CardTraitBase)null) || attacker.isValid(sa.getParam("ValidCards").replace("attacking+", "").split(","), source.getController(), source, (CardTraitBase)null)) {
                        list.add(attacker);
                     }
                  } else {
                     list = AbilityUtils.getDefinedCards(source, sa.getParam("Defined"), (CardTraitBase)null);
                  }

                  if (sa.hasParam("Defined") && sa.getParam("Defined").startsWith("TriggeredAttacker")) {
                     list.add(attacker);
                  }

                  if (list.contains(attacker)) {
                     String att = sa.getParam("NumAtt");
                     if (att.startsWith("+")) {
                        att = att.substring(1);
                     }

                     if (!att.matches("[0-9][0-9]?") && !att.matches("-[0-9][0-9]?")) {
                        String bonus = AbilityUtils.getSVar(sa, att);
                        if (bonus.contains("Count$Valid Creature.blockingTriggeredAttacker")) {
                           bonus = TextUtil.fastReplace(bonus, "Count$Valid Creature.blockingTriggeredAttacker", "Number$1");
                        } else if (bonus.contains("TriggeredPlayersDefenders$Amount")) {
                           bonus = TextUtil.fastReplace(bonus, "TriggeredPlayersDefenders$Amount", "Number$1");
                        } else if (bonus.contains("TriggeredAttacker$CardPower")) {
                           bonus = TextUtil.fastReplace(bonus, "TriggeredAttacker$CardPower", TextUtil.concatNoSpace(new String[]{"Number$", String.valueOf(attacker.getNetPower())}));
                        } else if (bonus.contains("TriggeredAttacker$CardToughness")) {
                           bonus = TextUtil.fastReplace(bonus, "TriggeredAttacker$CardToughness", TextUtil.concatNoSpace(new String[]{"Number$", String.valueOf(attacker.getNetToughness())}));
                        }

                        power += AbilityUtils.calculateAmount(source, bonus, sa);
                     } else {
                        power += Integer.parseInt(att);
                     }
                  }
               }
            }
         }
      }

      if (withoutAbilities) {
         return power;
      } else {
         for(SpellAbility ability : attacker.getAllSpellAbilities()) {
            if (ability.isActivatedAbility() && !ability.hasParam("ActivationPhases") && !ability.hasParam("SorcerySpeed") && !ability.hasParam("ActivationZone") && (!ability.usesTargeting() || ability.canTarget(attacker))) {
               int pBonus = 0;
               if (ability.getApi() == ApiType.Pump) {
                  if (!ability.hasParam("NumAtt") || ComputerUtilCost.isSacrificeSelfCost(ability.getPayCosts())) {
                     continue;
                  }

                  if (!ability.getPayCosts().hasTapCost()) {
                     pBonus = AbilityUtils.calculateAmount(ability.getHostCard(), ability.getParam("NumAtt"), ability);
                  }
               } else if (ability.getApi() == ApiType.PutCounter) {
                  if (!ability.hasParam("CounterType") || !ability.getParam("CounterType").equals("P1P1") || ability.hasParam("Monstrosity") && attacker.isMonstrous() || ability.hasParam("Adapt") && attacker.getCounters(CounterEnumType.P1P1) > 0) {
                     continue;
                  }

                  if (!ability.getPayCosts().hasTapCost()) {
                     pBonus = AbilityUtils.calculateAmount(ability.getHostCard(), ability.getParamOrDefault("CounterNum", "1"), ability);
                  }
               }

               if (pBonus > 0 && ComputerUtilCost.canPayCost(ability, attacker.getController(), false)) {
                  power += pBonus;
               }
            }
         }

         return power;
      }
   }

   public static int predictToughnessBonusOfAttacker(Card attacker, Card blocker, Combat combat, boolean withoutAbilities) {
      return predictToughnessBonusOfAttacker(attacker, blocker, combat, withoutAbilities, false);
   }

   public static int predictToughnessBonusOfAttacker(Card attacker, Card blocker, Combat combat, boolean withoutAbilities, boolean withoutCombatStaticAbilities) {
      int toughness = 0;
      if (blocker != null && attacker.getName().equals("Shape Stealer")) {
         toughness += blocker.getNetToughness() - attacker.getNetToughness();
      }

      Game game = attacker.getGame();
      // Forge Nova: the same collection minus modes combatTriggerWillTrigger rejects (see NovaCombatTriggers)
      FCollection<Trigger> theTriggers = NovaCombatTriggers.battlefieldAndCommand(game);

      if (blocker != null) {
         theTriggers = NovaCombatTriggers.plus(theTriggers, blocker);
      }

      if (!withoutCombatStaticAbilities) {
         // Forge Nova: only the Continuous static abilities, in the same order (see NovaCombatTriggers)
         NovaCombatTriggers.Statics novaStatics = NovaCombatTriggers.continuousBattlefield(game);
         for(int novaI = 0; novaI < novaStatics.size(); ++novaI) {
            Card card = novaStatics.host(novaI);
            StaticAbility stAb = novaStatics.stAb(novaI);
            {
               if (stAb.checkMode(StaticAbilityMode.Continuous) && stAb.hasParam("Affected") && stAb.hasParam("AddToughness")) {
                  String affected = stAb.getParam("Affected");
                  String addT = stAb.getParam("AddToughness");
                  if (affected.contains("attacking")) {
                     String valid = TextUtil.fastReplace(affected, "attacking", "Creature");
                     if (attacker.isValid(valid, card.getController(), card, (CardTraitBase)null)) {
                        toughness += AbilityUtils.calculateAmount(card, addT, stAb, true);
                     }
                  } else if (affected.contains("untapped")) {
                     String valid = TextUtil.fastReplace(affected, "untapped", "Creature");
                     if (attacker.isValid(valid, card.getController(), card, (CardTraitBase)null) && !attacker.hasKeyword(Keyword.VIGILANCE)) {
                        toughness -= AbilityUtils.calculateAmount(card, addT, stAb, true);
                     }
                  }
               }
            }
         }
      }

      for(Trigger trigger : theTriggers) {
         Card source = trigger.getHostCard();
         if (combatTriggerWillTrigger(attacker, blocker, trigger, combat)) {
            SpellAbility sa = trigger.ensureAbility();
            if (sa != null && !sa.usesTargeting()) {
               sa.setActivatingPlayer(source.getController());
               if (ApiType.DealDamage.equals(sa.getApi())) {
                  if (sa.hasParam("Defined") && sa.getParam("Defined").startsWith("TriggeredAttacker")) {
                     int damage = AbilityUtils.calculateAmount(source, sa.getParam("NumDmg"), sa);
                     toughness -= predictDamageTo(attacker, damage, source, false);
                  }
               } else if (sa.getApi() == ApiType.EachDamage && "TriggeredAttackerLKICopy".equals(sa.getParam("Defined"))) {
                  List<Card> valid = CardLists.getValidCards(source.getController().getCreaturesInPlay(), (String)sa.getParam("ValidCards"), source.getController(), source, sa);
                  toughness -= valid.size();
               } else if (ApiType.Pump.equals(sa.getApi())) {
                  if (sa.hasParam("NumDef") && (!sa.hasParam("Cost") || CostPayment.canPayAdditionalCosts(sa.getPayCosts(), sa, true))) {
                     String defined = sa.getParam("Defined");
                     CardCollection list = AbilityUtils.getDefinedCards(source, defined, sa);
                     if (defined != null && defined.startsWith("TriggeredAttacker")) {
                        list.add(attacker);
                     }

                     if (list.contains(attacker)) {
                        String def = sa.getParam("NumDef");
                        if (def.startsWith("+")) {
                           def = def.substring(1);
                        }

                        if (!def.matches("[0-9][0-9]?") && !def.matches("-[0-9][0-9]?")) {
                           String bonus = AbilityUtils.getSVar(sa, def);
                           if (bonus.contains("Count$Valid Creature.blockingTriggeredAttacker")) {
                              bonus = TextUtil.fastReplace(bonus, "Count$Valid Creature.blockingTriggeredAttacker", "Number$1");
                           } else if (bonus.contains("TriggeredPlayersDefenders$Amount")) {
                              bonus = TextUtil.fastReplace(bonus, "TriggeredPlayersDefenders$Amount", "Number$1");
                           }

                           toughness += AbilityUtils.calculateAmount(source, bonus, sa);
                        } else {
                           toughness += Integer.parseInt(def);
                        }
                     }
                  }
               } else if (ApiType.PumpAll.equals(sa.getApi()) && sa.hasParam("NumDef") && (!sa.hasParam("Cost") || CostPayment.canPayAdditionalCosts(sa.getPayCosts(), sa, true)) && sa.hasParam("ValidCards") && attacker.isValid(sa.getParam("ValidCards").replace("attacking+", "").split(","), source.getController(), source, sa)) {
                  String def = sa.getParam("NumDef");
                  if (def.startsWith("+")) {
                     def = def.substring(1);
                  }

                  if (!def.matches("[0-9][0-9]?") && !def.matches("-[0-9][0-9]?")) {
                     String bonus = AbilityUtils.getSVar(sa, def);
                     if (bonus.contains("Count$Valid Creature.blockingTriggeredAttacker")) {
                        bonus = TextUtil.fastReplace(bonus, "Count$Valid Creature.blockingTriggeredAttacker", "Number$1");
                     } else if (bonus.contains("TriggeredPlayersDefenders$Amount")) {
                        bonus = TextUtil.fastReplace(bonus, "TriggeredPlayersDefenders$Amount", "Number$1");
                     }

                     toughness += AbilityUtils.calculateAmount(source, bonus, sa);
                  } else {
                     toughness += Integer.parseInt(def);
                  }
               }
            }
         }
      }

      if (withoutAbilities) {
         return toughness;
      } else {
         for(SpellAbility ability : attacker.getAllSpellAbilities()) {
            if (ability.isActivatedAbility() && !ability.hasParam("ActivationPhases") && !ability.hasParam("SorcerySpeed") && !ability.hasParam("ActivationZone") && (!ability.usesTargeting() || ability.canTarget(attacker)) && (!ability.getPayCosts().hasTapCost() || attacker.hasKeyword(Keyword.VIGILANCE))) {
               int tBonus = 0;
               if (ability.getApi() == ApiType.Pump) {
                  if (!ability.hasParam("NumDef")) {
                     continue;
                  }

                  tBonus = AbilityUtils.calculateAmount(ability.getHostCard(), ability.getParam("NumDef"), ability, true);
               } else if (ability.getApi() == ApiType.PutCounter) {
                  if (!ability.hasParam("CounterType") || !ability.getParam("CounterType").equals("P1P1") || ability.hasParam("Monstrosity") && attacker.isMonstrous() || ability.hasParam("Adapt") && attacker.getCounters(CounterEnumType.P1P1) > 0) {
                     continue;
                  }

                  tBonus = AbilityUtils.calculateAmount(ability.getHostCard(), ability.getParamOrDefault("CounterNum", "1"), ability);
               }

               if (tBonus > 0 && ComputerUtilCost.canPayCost(ability, attacker.getController(), false)) {
                  toughness += tBonus;
               }
            }
         }

         return toughness;
      }
   }

   public static boolean canDestroyAttackerBeforeFirstStrike(Card attacker, Card blocker, Combat combat, boolean withoutAbilities) {
      if (blocker.isEquippedBy("Godsend")) {
         return true;
      } else if (combatantCantBeDestroyed(attacker.getController(), attacker)) {
         return false;
      } else if (getDamageToKill(attacker, false) + predictToughnessBonusOfAttacker(attacker, blocker, combat, withoutAbilities) <= 0) {
         return true;
      } else {
         // Forge Nova: the same collection minus modes combatTriggerWillTrigger rejects (see NovaCombatTriggers)
         FCollection<Trigger> theTriggers = NovaCombatTriggers.battlefield(attacker.getGame());

         for(Trigger trigger : theTriggers) {
            Card source = trigger.getHostCard();
            if (combatTriggerWillTrigger(attacker, blocker, trigger, (Combat)null)) {
               SpellAbility sa = trigger.ensureAbility();
               if (sa != null && ApiType.Destroy.equals(sa.getApi()) && sa.hasParam("Defined")) {
                  if (sa.getParam("Defined").startsWith("TriggeredAttacker")) {
                     return true;
                  }

                  if (sa.getParam("Defined").equals("Self") && source.equals(attacker)) {
                     return true;
                  }

                  if (sa.getParam("Defined").equals("TriggeredTarget") && source.equals(blocker)) {
                     return true;
                  }
               }
            }
         }

         return false;
      }
   }

   public static boolean combatantCantBeDestroyed(Player ai, Card combatant) {
      if (combatant.getCounters(CounterEnumType.SHIELD) > 0) {
         return true;
      } else if (combatant.getShieldCount() > 0 && combatant.canBeShielded()) {
         return true;
      } else {
         return combatant.hasKeyword(Keyword.INDESTRUCTIBLE) || ComputerUtil.canRegenerate(ai, combatant);
      }
   }

   public static boolean canDestroyAttacker(Player ai, Card attacker, Card blocker, Combat combat, boolean withoutAbilities) {
      return canDestroyAttacker(ai, attacker, blocker, combat, withoutAbilities, false);
   }

   public static boolean canDestroyAttacker(Player ai, Card attacker, Card blocker, Combat combat, boolean withoutAbilities, boolean withoutAttackerStaticAbilities) {
      if (!withoutAbilities) {
         attacker = canTransform(attacker);
         blocker = canTransform(blocker);
      }

      if (canDestroyAttackerBeforeFirstStrike(attacker, blocker, combat, withoutAbilities)) {
         return true;
      } else if (canDestroyBlockerBeforeFirstStrike(blocker, attacker, withoutAbilities)) {
         return false;
      } else {
         int flankingMagnitude = 0;
         if (attacker.hasKeyword(Keyword.FLANKING) && !blocker.hasKeyword(Keyword.FLANKING)) {
            flankingMagnitude = attacker.getAmountOfKeyword(Keyword.FLANKING);
            if (flankingMagnitude >= blocker.getNetToughness()) {
               return false;
            }

            if (flankingMagnitude >= blocker.getNetToughness() - blocker.getDamage() && !blocker.hasKeyword(Keyword.INDESTRUCTIBLE)) {
               return false;
            }
         }

         if ((!attacker.hasKeyword(Keyword.INDESTRUCTIBLE) && (withoutAbilities || !ComputerUtil.canRegenerate(ai, attacker)) || blocker.isWitherDamage()) && (!attacker.hasKeyword(Keyword.PERSIST) || attacker.canReceiveCounters(CounterEnumType.M1M1) || attacker.getCounters(CounterEnumType.M1M1) != 0) && (!attacker.hasKeyword(Keyword.UNDYING) || attacker.canReceiveCounters(CounterEnumType.P1P1) || attacker.getCounters(CounterEnumType.P1P1) != 0)) {
            int defenderDamage;
            if (blocker.toughnessAssignsDamage()) {
               defenderDamage = blocker.getNetToughness() + predictToughnessBonusOfBlocker(attacker, blocker, withoutAbilities);
            } else {
               defenderDamage = blocker.getNetPower() + predictPowerBonusOfBlocker(attacker, blocker, withoutAbilities);
            }

            int possibleDefenderPrevention = 0;
            int possibleAttackerPrevention = 0;
            if (!withoutAbilities) {
               possibleDefenderPrevention = ComputerUtil.possibleDamagePrevention(blocker);
               possibleAttackerPrevention = ComputerUtil.possibleDamagePrevention(attacker);
            }

            defenderDamage = predictDamageTo(attacker, defenderDamage, possibleAttackerPrevention, blocker, true);
            if (defenderDamage > 0 && isCombatDamagePrevented(blocker, attacker, defenderDamage)) {
               return false;
            } else {
               int attackerDamage;
               if (attacker.toughnessAssignsDamage()) {
                  attackerDamage = attacker.getNetToughness() + predictToughnessBonusOfAttacker(attacker, blocker, combat, withoutAbilities, withoutAttackerStaticAbilities);
               } else {
                  attackerDamage = attacker.getNetPower() + predictPowerBonusOfAttacker(attacker, blocker, combat, withoutAbilities, withoutAttackerStaticAbilities);
               }

               attackerDamage = predictDamageTo(blocker, attackerDamage, possibleDefenderPrevention, attacker, true);
               int defenderLife = getDamageToKill(blocker, false) + predictToughnessBonusOfBlocker(attacker, blocker, withoutAbilities);
               int attackerLife = getDamageToKill(attacker, false) + predictToughnessBonusOfAttacker(attacker, blocker, combat, withoutAbilities, withoutAttackerStaticAbilities);
               if (blocker.hasDoubleStrike()) {
                  if (defenderDamage <= 0 || !hasKeyword(blocker, "Deathtouch", withoutAbilities, combat) && !attacker.hasSVar("DestroyWhenDamaged")) {
                     if (defenderDamage >= attackerLife) {
                        return true;
                     } else {
                        if (dealsFirstStrikeDamage(attacker, withoutAbilities, combat) && !blocker.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                           if (attackerDamage >= defenderLife) {
                              return false;
                           }

                           if (attackerDamage > 0 && (hasKeyword(attacker, "Deathtouch", withoutAbilities, combat) || blocker.hasSVar("DestroyWhenDamaged"))) {
                              return false;
                           }
                        }

                        if (attackerLife <= 2 * defenderDamage) {
                           return true;
                        } else {
                           return false;
                        }
                     }
                  } else {
                     return true;
                  }
               } else {
                  if (dealsFirstStrikeDamage(attacker, withoutAbilities, combat) && !blocker.hasKeyword(Keyword.INDESTRUCTIBLE) && !dealsFirstStrikeDamage(blocker, withoutAbilities, combat)) {
                     if (attackerDamage >= defenderLife) {
                        return false;
                     }

                     if (attackerDamage > 0 && (hasKeyword(attacker, "Deathtouch", withoutAbilities, combat) || blocker.hasSVar("DestroyWhenDamaged"))) {
                        return false;
                     }
                  }

                  if (defenderDamage <= 0 || !hasKeyword(blocker, "Deathtouch", withoutAbilities, combat) && !attacker.hasSVar("DestroyWhenDamaged")) {
                     return defenderDamage >= attackerLife;
                  } else {
                     return true;
                  }
               }
            }
         } else {
            return false;
         }
      }
   }

   public static boolean blockerWouldBeDestroyed(Player ai, Card blocker, Combat combat) {
      for(Card attacker : combat.getAttackersBlockedBy(blocker)) {
         if (!attacker.isWitherDamage() && canDestroyBlocker(ai, blocker, attacker, combat, true)) {
            return true;
         }
      }

      return false;
   }

   public static boolean canDestroyBlockerBeforeFirstStrike(Card blocker, Card attacker, boolean withoutAbilities) {
      if (attacker.isEquippedBy("Godsend")) {
         return true;
      } else if (attacker.getName().equals("Elven Warhounds")) {
         return true;
      } else {
         int flankingMagnitude = 0;
         if (attacker.hasKeyword(Keyword.FLANKING) && !blocker.hasKeyword(Keyword.FLANKING)) {
            flankingMagnitude = attacker.getAmountOfKeyword(Keyword.FLANKING);
            if (flankingMagnitude >= blocker.getNetToughness()) {
               return true;
            }

            if (flankingMagnitude >= getDamageToKill(blocker, false) && !blocker.hasKeyword(Keyword.INDESTRUCTIBLE)) {
               return true;
            }
         }

         if (!blocker.hasKeyword(Keyword.INDESTRUCTIBLE) && !ComputerUtil.canRegenerate(blocker.getController(), blocker)) {
            if (getDamageToKill(blocker, false) + predictToughnessBonusOfBlocker(attacker, blocker, withoutAbilities) <= 0) {
               return true;
            } else {
               Game game = blocker.getGame();
               // Forge Nova: the same collection minus modes combatTriggerWillTrigger rejects (see NovaCombatTriggers)
               FCollection<Trigger> theTriggers = NovaCombatTriggers.battlefield(game);

               for(Trigger trigger : theTriggers) {
                  Card source = trigger.getHostCard();
                  if (combatTriggerWillTrigger(attacker, blocker, trigger, (Combat)null)) {
                     SpellAbility sa = trigger.ensureAbility();
                     if (sa != null && ApiType.Destroy.equals(sa.getApi()) && sa.hasParam("Defined")) {
                        if (sa.getParam("Defined").startsWith("TriggeredBlocker")) {
                           return true;
                        }

                        if (sa.getParam("Defined").equals("Self") && source.equals(blocker)) {
                           return true;
                        }

                        if (sa.getParam("Defined").equals("TriggeredTarget") && source.equals(attacker)) {
                           return true;
                        }
                     }
                  }
               }

               return false;
            }
         } else {
            return false;
         }
      }
   }

   public static boolean canDestroyBlocker(Player ai, Card blocker, Card attacker, Combat combat, boolean withoutAbilities) {
      return canDestroyBlocker(ai, blocker, attacker, combat, withoutAbilities, false);
   }

   public static boolean canDestroyBlocker(Player ai, Card blocker, Card attacker, Combat combat, boolean withoutAbilities, boolean withoutAttackerStaticAbilities) {
      if (!withoutAbilities) {
         attacker = canTransform(attacker);
         blocker = canTransform(blocker);
      }

      if (canDestroyBlockerBeforeFirstStrike(blocker, attacker, withoutAbilities)) {
         return true;
      } else if ((!blocker.hasKeyword(Keyword.INDESTRUCTIBLE) && (withoutAbilities || !ComputerUtil.canRegenerate(ai, blocker)) || attacker.isWitherDamage()) && (!blocker.hasKeyword(Keyword.PERSIST) || blocker.canReceiveCounters(CounterEnumType.M1M1) || blocker.getCounters(CounterEnumType.M1M1) != 0) && (!blocker.hasKeyword(Keyword.UNDYING) || blocker.canReceiveCounters(CounterEnumType.P1P1) || blocker.getCounters(CounterEnumType.P1P1) != 0)) {
         if (canDestroyAttackerBeforeFirstStrike(attacker, blocker, combat, withoutAbilities)) {
            return false;
         } else {
            int defenderDamage;
            if (blocker.toughnessAssignsDamage()) {
               defenderDamage = blocker.getNetToughness() + predictToughnessBonusOfBlocker(attacker, blocker, withoutAbilities);
            } else {
               defenderDamage = blocker.getNetPower() + predictPowerBonusOfBlocker(attacker, blocker, withoutAbilities);
            }

            int attackerDamage;
            if (attacker.toughnessAssignsDamage()) {
               attackerDamage = attacker.getNetToughness() + predictToughnessBonusOfAttacker(attacker, blocker, combat, withoutAbilities, withoutAttackerStaticAbilities);
            } else {
               attackerDamage = attacker.getNetPower() + predictPowerBonusOfAttacker(attacker, blocker, combat, withoutAbilities, withoutAttackerStaticAbilities);
            }

            int possibleDefenderPrevention = 0;
            int possibleAttackerPrevention = 0;
            if (!withoutAbilities) {
               possibleDefenderPrevention = ComputerUtil.possibleDamagePrevention(blocker);
               possibleAttackerPrevention = ComputerUtil.possibleDamagePrevention(attacker);
            }

            defenderDamage = predictDamageTo(attacker, defenderDamage, possibleAttackerPrevention, blocker, true);
            attackerDamage = predictDamageTo(blocker, attackerDamage, possibleDefenderPrevention, attacker, true);
            if (isCombatDamagePrevented(attacker, blocker, attackerDamage)) {
               attackerDamage = 0;
            }

            if (isCombatDamagePrevented(blocker, attacker, defenderDamage)) {
               defenderDamage = 0;
            }

            if (combat != null) {
               for(Card atkr : combat.getAttackersBlockedBy(blocker)) {
                  if (!atkr.equals(attacker)) {
                     attackerDamage += predictDamageTo(blocker, atkr.getNetCombatDamage(), atkr, true);
                  }
               }
            }

            int defenderLife = getDamageToKill(blocker, false) + predictToughnessBonusOfBlocker(attacker, blocker, withoutAbilities);
            int attackerLife = getDamageToKill(attacker, false) + predictToughnessBonusOfAttacker(attacker, blocker, combat, withoutAbilities, withoutAttackerStaticAbilities);
            if (attacker.hasDoubleStrike()) {
               if (attackerDamage >= defenderLife) {
                  return true;
               } else if (attackerDamage <= 0 || !hasKeyword(attacker, "Deathtouch", withoutAbilities, combat) && !blocker.hasSVar("DestroyWhenDamaged")) {
                  if (dealsFirstStrikeDamage(blocker, withoutAbilities, combat) && !attacker.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                     if (defenderDamage >= attackerLife) {
                        return false;
                     }

                     if (defenderDamage > 0 && (hasKeyword(blocker, "Deathtouch", withoutAbilities, combat) || attacker.hasSVar("DestroyWhenDamaged"))) {
                        return false;
                     }
                  }

                  if (defenderLife <= 2 * attackerDamage) {
                     return true;
                  } else {
                     return false;
                  }
               } else {
                  return true;
               }
            } else {
               if (dealsFirstStrikeDamage(blocker, withoutAbilities, combat) && !attacker.hasKeyword(Keyword.INDESTRUCTIBLE) && !dealsFirstStrikeDamage(attacker, withoutAbilities, combat)) {
                  if (defenderDamage >= attackerLife) {
                     return false;
                  }

                  if (defenderDamage > 0 && (hasKeyword(blocker, "Deathtouch", withoutAbilities, combat) || attacker.hasSVar("DestroyWhenDamaged"))) {
                     return false;
                  }
               }

               if (attackerDamage <= 0 || !hasKeyword(attacker, "Deathtouch", withoutAbilities, combat) && !blocker.hasSVar("DestroyWhenDamaged")) {
                  return attackerDamage >= defenderLife;
               } else {
                  return true;
               }
            }
         }
      } else {
         return false;
      }
   }

   public static Map<Card, Integer> distributeAIDamage(Player self, Card combatant, CardCollectionView opposedCombatants, CardCollectionView remaining, int dmgCanDeal, GameEntity defender, boolean overrideOrder) {
      Map<Card, Integer> damageMap = Maps.newHashMap();
      Combat combat = combatant.getGame().getCombat();
      boolean isAttacking = defender != null;
      boolean isAttackingMe = isAttacking && combat.getDefenderPlayerByAttacker(combatant).equals(self);
      boolean isBlockingMyBand = combatant.getController().isOpponentOf(self) && AttackingBand.isValidBand(opposedCombatants, true);
      boolean aiDistributesBandingDmg = isAttackingMe || isBlockingMyBand;
      boolean hasTrample = combatant.hasKeyword(Keyword.TRAMPLE);
      if (combat != null && remaining != null && hasTrample && combatant.isAttacking() && !aiDistributesBandingDmg) {
         for(Card c : remaining) {
            if (c != combatant && !c.hasKeyword(Keyword.TRAMPLE)) {
               CardCollection sharedBlockers = new CardCollection(opposedCombatants);
               sharedBlockers.retainAll(combat.getBlockers(c));
               if (!sharedBlockers.isEmpty()) {
                  return null;
               }
            }
         }
      }

      if (isAttacking && overrideOrder) {
         if (combatant.isAttacking()) {
            opposedCombatants = AiBlockController.orderBlockers(combatant, new CardCollection(opposedCombatants));
         } else {
            opposedCombatants = AiBlockController.orderAttackers(combatant, new CardCollection(opposedCombatants));
         }
      }

      if (opposedCombatants.size() == 1) {
         Card blocker = (Card)opposedCombatants.getFirst();
         int dmgToBlocker = dmgCanDeal;
         if (hasTrample && isAttacking && !aiDistributesBandingDmg) {
            dmgToBlocker = getEnoughDamageToKill(blocker, dmgCanDeal, combatant, true);
            if (dmgCanDeal < dmgToBlocker) {
               dmgToBlocker = Math.min(blocker.getLethalDamage(), dmgCanDeal);
            }

            int remainingDmg = dmgCanDeal - dmgToBlocker;
            if (remainingDmg > 0) {
               damageMap.put(null, remainingDmg);
            }
         }

         damageMap.put(blocker, dmgToBlocker);
      } else if (!aiDistributesBandingDmg) {
         Card lastBlocker = null;

         for(Card b : opposedCombatants) {
            lastBlocker = b;
            int dmgToKill = getEnoughDamageToKill(b, dmgCanDeal, combatant, true);
            if (dmgToKill <= dmgCanDeal) {
               damageMap.put(b, dmgToKill);
               dmgCanDeal -= dmgToKill;
            } else {
               int dmg = Math.min(b.getLethalDamage(), dmgCanDeal);
               damageMap.put(b, dmg);
               dmgCanDeal -= dmg;
               if (dmgCanDeal <= 0) {
                  break;
               }
            }
         }

         if (dmgCanDeal > 0) {
            if (hasTrample && isAttacking) {
               damageMap.put(null, dmgCanDeal);
            } else if (lastBlocker != null) {
               damageMap.merge(lastBlocker, dmgCanDeal, Integer::sum);
            }
         }
      } else {
         for(Card b : opposedCombatants) {
            int dmgToKill = getEnoughDamageToKill(b, dmgCanDeal, combatant, true);
            if (dmgToKill > dmgCanDeal) {
               damageMap.put(b, dmgCanDeal);
               break;
            }
         }

         if (damageMap.isEmpty()) {
            damageMap.put(ComputerUtilCard.getWorstCreatureAI(opposedCombatants), dmgCanDeal);
         }
      }

      return damageMap;
   }

   public static final int getEnoughDamageToKill(Card c, int maxDamage, Card source, boolean isCombat) {
      return getEnoughDamageToKill(c, maxDamage, source, isCombat, false);
   }

   public static final int getEnoughDamageToKill(Card c, int maxDamage, Card source, boolean isCombat, boolean noPrevention) {
      int killDamage = getDamageToKill(c, false);
      if (c.hasKeyword(Keyword.INDESTRUCTIBLE) || c.getCounters(CounterEnumType.SHIELD) > 0 || c.getShieldCount() > 0 && c.canBeShielded()) {
         if (!source.isWitherDamage()) {
            return maxDamage + 1;
         }
      } else if (source.hasKeyword(Keyword.DEATHTOUCH) && c.isCreature()) {
         killDamage = 1;
      }

      for(int i = 1; i <= maxDamage; ++i) {
         if (noPrevention) {
            if (c.staticReplaceDamage(i, source, isCombat) >= killDamage) {
               return i;
            }
         } else if (predictDamageTo(c, i, source, isCombat) >= killDamage) {
            return i;
         }
      }

      return maxDamage + 1;
   }

   public static final int getDamageToKill(Card c, boolean withShields) {
      int damageShield = withShields ? c.getPreventNextDamageTotalShields() : 0;
      int killDamage = c.getExcessDamageValue(false);
      if (killDamage > damageShield && c.hasSVar("DestroyWhenDamaged")) {
         killDamage = 1;
      }

      return killDamage + damageShield;
   }

   public static final int predictDamageTo(GameEntity target, int damage, Card source, boolean isCombat) {
      return predictDamageTo(target, damage, 0, source, isCombat);
   }

   public static final int predictDamageTo(GameEntity target, int damage, int possiblePrevention, Card source, boolean isCombat) {
      int restDamage = target.staticReplaceDamage(damage, source, isCombat);
      restDamage = target.staticDamagePrevention(restDamage, possiblePrevention, source, isCombat, isCombat ? isCombatDamagePreventedThisTurnCached(target.getGame()) : null);
      return restDamage;
   }

   private static Boolean isCombatDamagePreventedThisTurnCached(Game game) {
      return (Boolean)AiCache.getCached("isPreventCombatDamageThisTurn", () -> game.getReplacementHandler().isPreventCombatDamageThisTurn(), List.of(AiCache::identity), game);
   }

   public static final boolean dealsFirstStrikeDamage(Card combatant, boolean withoutAbilities, Combat combat) {
      if (!combatant.hasFirstStrike() && !combatant.hasDoubleStrike()) {
         return !withoutAbilities ? canGainKeyword(combatant, Lists.newArrayList(new String[]{"Double Strike", "First Strike"}), combat) : false;
      } else {
         return true;
      }
   }

   public static final boolean hasKeyword(Card combatant, String keyword, boolean withoutAbilities, Combat combat) {
      if (combatant.hasKeyword(keyword)) {
         return true;
      } else {
         return !withoutAbilities ? canGainKeyword(combatant, Lists.newArrayList(new String[]{keyword}), combat) : false;
      }
   }

   public static final boolean canGainKeyword(Card combatant, List<String> keywords, Combat combat) {
      Player controller = combatant.getController();

      // Forge Nova: only the Pump abilities of the controller's permanents, in the same order (NovaAbilityIndex);
      // every other ability fails "ability.getApi() == ApiType.Pump" below without side effects
      NovaAbilityIndex.Scan novaPumps = NovaAbilityIndex.battlefieldPumps(controller);
      for(int novaI = 0; novaI < novaPumps.size(); ++novaI) {
         Card c = novaPumps.card(novaI);
         for(SpellAbility ability : novaPumps.abilities(novaI)) {
            if (ability.isActivatedAbility() && ability.getApi() == ApiType.Pump && !ability.hasParam("ActivationPhases") && !ability.hasParam("SorcerySpeed") && ability.hasParam("KW")) {
               boolean grants = false;

               for(String keyword : keywords) {
                  if (ability.getParam("KW").contains(keyword)) {
                     grants = true;
                     break;
                  }
               }

               if (grants && (c == combatant || ability.usesTargeting() && ability.canTarget(combatant) && (!controller.getGame().getPhaseHandler().isPlayerTurn(controller) || combat != null && combat.isAttacking(combatant) && !combat.isAttacking(c))) && ComputerUtilCost.canPayCost(ability, controller, false)) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   public static final Card canTransform(Card original) {
      if (original.isTransformable() && !original.isInAlternateState()) {
         for(SpellAbility sa : original.getSpellAbilities()) {
            if (sa.getApi() == ApiType.SetState && ComputerUtilCost.canPayCost(sa, original.getController(), false)) {
               Card transformed = CardCopyService.getLKICopy(original);
               transformed.getCurrentState().copyFrom(original.getAlternateState(), true);
               transformed.updateStateForView();
               return transformed;
            }
         }
      }

      return original;
   }

   public static boolean isCombatDamagePrevented(Card attacker, GameEntity target, int damage) {
      if (!attacker.canDamagePrevented(true)) {
         return false;
      } else {
         Game game = attacker.getGame();
         Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(target);
         repParams.put(AbilityKey.DamageSource, attacker);
         repParams.put(AbilityKey.DamageAmount, damage);
         repParams.put(AbilityKey.IsCombat, true);

         for(ReplacementEffect re : game.getReplacementHandler().getReplacementList(ReplacementType.DamageDone, repParams, ReplacementLayer.Other)) {
            Map<String, String> params = re.getMapParams();
            if (params.containsKey("Prevent") || re.getOverridingAbility() != null && re.getOverridingAbility().getApi() != ApiType.ReplaceDamage && re.getOverridingAbility().getApi() != ApiType.ReplaceEffect) {
               return true;
            }
         }

         return false;
      }
   }

   public static boolean attackerHasThreateningAfflict(Card attacker, Player aiDefender) {
      int afflictDmg = attacker.getKeywordMagnitude(Keyword.AFFLICT);
      return afflictDmg > attacker.getNetPower() || afflictDmg >= aiDefender.getLife();
   }

   public static List<Card> categorizeAttackersByEvasion(List<Card> attackers) {
      List<Card> categorizedAttackers = Lists.newArrayList();
      CardCollection withEvasion = new CardCollection();
      CardCollection withoutEvasion = new CardCollection();

      for(Card atk : attackers) {
         if (!atk.hasKeyword(Keyword.FLYING) && !atk.hasKeyword(Keyword.SHADOW) && !atk.hasKeyword(Keyword.HORSEMANSHIP) && !atk.hasKeyword(Keyword.FEAR) && !atk.hasKeyword(Keyword.INTIMIDATE) && !atk.hasKeyword(Keyword.SKULK) && !atk.hasKeyword(Keyword.PROTECTION)) {
            withoutEvasion.add(atk);
         } else {
            withEvasion.add(atk);
         }
      }

      categorizedAttackers.addAll(withEvasion);
      categorizedAttackers.addAll(withoutEvasion);
      return categorizedAttackers;
   }

   public static Card mostDangerousAttacker(CardCollection list, Player ai, Combat combat, boolean withAbilities) {
      Card damageCard = null;
      Card poisonCard = null;
      int damageScore = 0;
      int poisonScore = 0;
      Iterator var8 = list.iterator();

      while(true) {
         Card c;
         int estimatedDmg;
         int estimatedPoison;
         while(true) {
            if (!var8.hasNext()) {
               if (damageCard == null && poisonCard == null) {
                  return null;
               }

               if (damageCard == null) {
                  return poisonCard;
               }

               if (poisonCard == null) {
                  return damageCard;
               }

               int life = ai.getLife();
               int poisonLife = 10 - ai.getPoisonCounters();
               double percentLife = (double)life * (double)1.0F / (double)damageScore;
               double percentPoison = (double)poisonLife * (double)1.0F / (double)poisonScore;
               if (percentLife >= percentPoison) {
                  return damageCard;
               }

               return poisonCard;
            }

            c = (Card)var8.next();
            estimatedDmg = damageIfUnblocked(c, ai, combat, withAbilities);
            estimatedPoison = poisonIfUnblocked(c, ai);
            if (!combat.isBlocked(c)) {
               break;
            }

            if (c.hasKeyword(Keyword.TRAMPLE)) {
               int absorbedByToughness = 0;

               for(Card blocker : combat.getBlockers(c)) {
                  absorbedByToughness += blocker.getNetToughness();
               }

               estimatedPoison -= absorbedByToughness;
               estimatedDmg -= absorbedByToughness;
               break;
            }
         }

         if (estimatedDmg > damageScore) {
            damageScore = estimatedDmg;
            damageCard = c;
         }

         if (estimatedPoison > poisonScore) {
            poisonScore = estimatedPoison;
            poisonCard = c;
         }
      }
   }

   public static Card applyPotentialAttackCloneTriggers(Card attacker) {
      Card attackerAfterTrigs = attacker;

      for(Trigger t : attacker.getTriggers()) {
         if (t.getMode() == TriggerType.Attacks) {
            SpellAbility exec = t.ensureAbility();
            if (exec != null && exec.getApi() == ApiType.Clone && "Self".equals(exec.getParam("CloneTarget")) && exec.hasParam("ValidTgts") && exec.getParam("ValidTgts").contains("Creature") && exec.getParam("ValidTgts").contains("attacking") && (!exec.getParam("ValidTgts").contains("nonLegendary") || !attacker.getType().isLegendary())) {
               int maxPwr = 0;

               for(Card c : attacker.getController().getCreaturesInPlay()) {
                  if (c.getNetPower() > maxPwr || c.getNetPower() == maxPwr && ComputerUtilCard.evaluateCreature(c) > ComputerUtilCard.evaluateCreature(attackerAfterTrigs)) {
                     maxPwr = c.getNetPower();
                     attackerAfterTrigs = c;
                  }
               }
            }
         }
      }

      return attackerAfterTrigs;
   }

   public static boolean willKillAtLeastOne(Player ai, Card c, Combat combat) {
      if (combat == null) {
         return false;
      } else {
         if (combat.isBlocked(c)) {
            for(Card blk : combat.getBlockers(c)) {
               if (blockerWouldBeDestroyed(ai, blk, combat)) {
                  return true;
               }
            }
         } else if (combat.isBlocking(c)) {
            for(Card atk : combat.getAttackersBlockedBy(c)) {
               if (attackerWouldBeDestroyed(ai, atk, combat)) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   public static int predictExtraPoisonWithDamage(Card attacker, Player attacked, int damage) {
      int poison = 0;
      int damageAfterRepl = predictDamageTo(attacked, damage, attacker, true);
      if (damageAfterRepl > 0) {
         for(Card c : attacker.getController().getCardsIn(ZoneType.Battlefield)) {
            for(Trigger t : c.getTriggers()) {
               if (t.getMode() == TriggerType.DamageDone && !"False".equals(t.getParam("CombatDamage")) && t.matchesValidParam("ValidSource", attacker)) {
                  SpellAbility ab = t.getOverridingAbility();
                  if (ab.getApi() == ApiType.Poison && "TriggeredTarget".equals(ab.getParam("Defined"))) {
                     poison += AbilityUtils.calculateAmount(attacker, ab.getParam("Num"), ab);
                  }
               }
            }
         }

         poison += attacker.getKeywordMagnitude(Keyword.TOXIC);
      }

      if (attacker.hasDoubleStrike()) {
         poison *= 2;
      }

      return poison;
   }

   public static GameEntity addAttackerToCombat(SpellAbility sa, Card attacker, Iterable<? extends GameEntity> defenders) {
      Combat combat = sa.getHostCard().getGame().getCombat();
      if (combat != null) {
         GameEntity def = combat.getDefenderByAttacker(sa.getHostCard());
         if (def instanceof Card) {
            Card card = (Card)def;
            if (Iterables.contains(defenders, def)) {
               if (card.isPlaneswalker()) {
                  return def;
               }

               if (card.isBattle()) {
                  return def;
               }
            }
         }

         for(GameEntity p : defenders) {
            if (p instanceof Player) {
               Player p1 = (Player)p;
               if (!ComputerUtilCard.canBeBlockedProfitably(p1, attacker, true)) {
                  return p;
               }
            }

            if (p instanceof Card) {
               Card card = (Card)p;
               if (!ComputerUtilCard.canBeBlockedProfitably(card.getController(), attacker, true)) {
                  return p;
               }
            }
         }
      }

      return (GameEntity)Iterables.getFirst(defenders, null);
   }

   public static int checkAttackerLifelinkDamage(Combat combat) {
      if (combat == null) {
         return 0;
      } else {
         int totalLifeLinkDamage = 0;

         for(Card attacker : combat.getAttackers()) {
            int netDamage = attacker.getNetCombatDamage();
            if ((attacker.hasKeyword(Keyword.LIFELINK) || attacker.hasSVar("LikeLifeLink")) && netDamage > 0) {
               int damage = predictDamageTo(combat.getDefenderByAttacker(attacker), netDamage, attacker, true);
               boolean prevented = isCombatDamagePrevented(attacker, combat.getDefenderByAttacker(attacker), damage);
               if (!prevented) {
                  totalLifeLinkDamage += damage;
               }
            }
         }

         return totalLifeLinkDamage;
      }
   }

   public static boolean willOpposingCreatureDieInCombat(Player ai, Card combatant, Combat combat) {
      if (combat != null) {
         if (combat.isBlocking(combatant)) {
            for(Card atk : combat.getAttackersBlockedBy(combatant)) {
               if (combatantWouldBeDestroyed(ai, atk, combat)) {
                  return true;
               }
            }
         } else if (combat.isBlocked(combatant)) {
            for(Card blk : combat.getBlockers(combatant)) {
               if (combatantWouldBeDestroyed(ai, blk, combat)) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   public static boolean isDangerousToSacInCombat(Player ai, Card combatant, Combat combat) {
      if (combat != null) {
         if (combat.isBlocking(combatant)) {
            if (combatant.hasKeyword(Keyword.BANDING)) {
               return true;
            }

            for(Card atk : combat.getAttackersBlockedBy(combatant)) {
               if (atk.hasKeyword(Keyword.TRAMPLE)) {
                  return true;
               }
            }
         } else if (combat.isBlocked(combatant) && combatant.hasKeyword(Keyword.BANDING)) {
            return true;
         }
      }

      return false;
   }
}
