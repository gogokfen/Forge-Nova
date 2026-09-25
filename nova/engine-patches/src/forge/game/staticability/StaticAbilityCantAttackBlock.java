package forge.game.staticability;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;

public class StaticAbilityCantAttackBlock {
   public static boolean cantAttack(Card attacker, GameEntity defender) {
      if (!attacker.hasKeyword("CARDNAME can't attack.") && !attacker.hasKeyword("CARDNAME can't attack or block.")) {
         if (attacker.isDetained()) {
            return true;
         } else {
            for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(attacker.getGame(), StaticAbilityMode.CantAttack)) { // Forge Nova: indexed scan
               {
                  if (stAb.checkConditions(StaticAbilityMode.CantAttack) && applyCantAttackAbility(stAb, attacker, defender)) {
                     return true;
                  }
               }
            }

            return false;
         }
      } else {
         return true;
      }
   }

   public static boolean applyCantAttackAbility(StaticAbility stAb, Card card, GameEntity target) {
      Card hostCard = stAb.getHostCard();
      Game game = hostCard.getGame();
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else if (stAb.getIgnoreEffectCards().contains(card)) {
         return false;
      } else if (!stAb.matchesValidParam("Target", target)) {
         return false;
      } else if (stAb.isKeyword(Keyword.DEFENDER) && canAttackDefender(card, target)) {
         return false;
      } else {
         Player defender;
         if (target instanceof Player) {
            defender = (Player)target;
         } else {
            Card c = (Card)target;
            if (c.isBattle()) {
               defender = c.getProtectingPlayer();
            } else {
               defender = c.getController();
            }
         }

         if (stAb.hasParam("DefenderNotNearestToYouInChosenDirection")) {
            if (hostCard.getChosenDirection() == null) {
               return false;
            }

            if (target instanceof Card && ((Card)target).isBattle()) {
               return false;
            }

            Player next;
            for(next = card.getController(); !next.isOpponentOf(card.getController()); next = game.getNextPlayerAfter(next, hostCard.getChosenDirection())) {
            }

            if (defender.equals(next)) {
               return false;
            }
         }

         if (stAb.hasParam("UnlessDefender")) {
            String type = stAb.getParam("UnlessDefender");
            if (defender.hasProperty(type, hostCard.getController(), hostCard, stAb)) {
               return false;
            }
         }

         return true;
      }
   }

   public static boolean canAttackDefender(Card card, GameEntity target) {
      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(card.getGame(), StaticAbilityMode.CanAttackDefender)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CanAttackDefender) && applyCanAttackDefenderAbility(stAb, card, target)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCanAttackDefenderAbility(StaticAbility stAb, Card card, GameEntity target) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidAttacked", target);
      }
   }

   public static boolean cantBlock(Card blocker) {
      if (blocker.isDetained()) {
         return true;
      } else {
         // Forge Nova: blocker first, then the indexed static zones (minus the blocker itself)
         for(StaticAbility stAb : blocker.getStaticAbilities()) {
            if (stAb.checkConditions(StaticAbilityMode.CantBlock) && applyCantBlockAbility(stAb, blocker)) {
               return true;
            }
         }

         StaticAbilityIndex.Entries entries = StaticAbilityIndex.entries(blocker.getGame(), StaticAbilityMode.CantBlock);
         for(int i = 0; i < entries.statics.size(); ++i) {
            if (entries.hosts.get(i).equals(blocker)) {
               continue;
            }
            StaticAbility stAb = entries.statics.get(i);
            if (stAb.checkConditions(StaticAbilityMode.CantBlock) && applyCantBlockAbility(stAb, blocker)) {
               return true;
            }
         }

         return false;
      }
   }

   public static boolean applyCantBlockAbility(StaticAbility stAb, Card blocker) {
      if (!stAb.matchesValidParam("ValidCard", blocker)) {
         return false;
      } else {
         return !stAb.getIgnoreEffectCards().contains(blocker);
      }
   }

   public static boolean canBlockTapped(Card card) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.BlockTapped)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.BlockTapped) && applyBlockTapped(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean applyBlockTapped(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }

   public static boolean cantBlockBy(Card attacker, Card blocker) {
      // Forge Nova: attacker, then blocker (unless equal), then the indexed static zones minus both
      for(StaticAbility stAb : attacker.getStaticAbilities()) {
         if (stAb.checkConditions(StaticAbilityMode.CantBlockBy) && applyCantBlockByAbility(stAb, attacker, blocker)) {
            return true;
         }
      }

      boolean distinctBlocker = blocker != null && !blocker.equals(attacker);
      if (distinctBlocker) {
         for(StaticAbility stAb : blocker.getStaticAbilities()) {
            if (stAb.checkConditions(StaticAbilityMode.CantBlockBy) && applyCantBlockByAbility(stAb, attacker, blocker)) {
               return true;
            }
         }
      }

      StaticAbilityIndex.Entries entries = StaticAbilityIndex.entries(attacker.getGame(), StaticAbilityMode.CantBlockBy);
      for(int i = 0; i < entries.statics.size(); ++i) {
         Card host = entries.hosts.get(i);
         if (host.equals(attacker) || distinctBlocker && host.equals(blocker)) {
            continue;
         }
         StaticAbility stAb = entries.statics.get(i);
         // Evasion keywords (Flying, Fear, Intimidate, Shadow, landwalk...) are CantBlockBy statics with
         // "ValidAttacker$ Creature.Self": they can only ever apply to their own host card.
         if (!attacker.equals(stAb.getHostCard()) && "Creature.Self".equals(stAb.getParam("ValidAttacker")) && !stAb.hasParam("InvertValidAttacker")) {
            continue;
         }
         if (stAb.checkConditions(StaticAbilityMode.CantBlockBy) && applyCantBlockByAbility(stAb, attacker, blocker)) {
            return true;
         }
      }

      return false;
   }

   public static boolean applyCantBlockByAbility(StaticAbility stAb, Card attacker, Card blocker) {
      Card host = stAb.getHostCard();
      if (!stAb.matchesValidParam("ValidAttacker", attacker)) {
         return false;
      } else {
         if (stAb.hasParam("ValidBlocker")) {
            boolean stillblock = true;

            for(String v : stAb.getParam("ValidBlocker").split(",")) {
               if (blocker != null && blocker.isValid(v, host.getController(), host, stAb)) {
                  stillblock = false;
                  if (v.contains("withoutReach") && canBlockIfReach(attacker, blocker)) {
                     stillblock = true;
                  }

                  if (v.contains("withoutShadow") && canBlockIfShadow(attacker, blocker)) {
                     stillblock = true;
                  }

                  if (!stillblock) {
                     break;
                  }
               }
            }

            if (stillblock) {
               return false;
            }
         }

         if (!stAb.matchesValidParam("ValidAttackerRelative", attacker, blocker)) {
            return false;
         } else if (!stAb.matchesValidParam("ValidBlockerRelative", blocker, attacker)) {
            return false;
         } else if (blocker != null && stAb.matchesValidParam("ValidDefender", blocker.getController())) {
            return !stAb.isKeyword(Keyword.LANDWALK) || !StaticAbilityIgnoreLandwalk.ignoreLandWalk(attacker, blocker, stAb.getKeyword());
         } else {
            return false;
         }
      }
   }

   public static boolean canBlockIfReach(Card attacker, Card blocker) {
      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(attacker.getGame(), StaticAbilityMode.CanBlockIfReach)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CanBlockIfReach) && applyCanBlockIfReachAbility(stAb, attacker, blocker)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCanBlockIfReachAbility(StaticAbility stAb, Card attacker, Card blocker) {
      if (!stAb.matchesValidParam("ValidAttacker", attacker)) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidBlocker", blocker);
      }
   }

   public static boolean canBlockIfShadow(Card attacker, Card blocker) {
      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(attacker.getGame(), StaticAbilityMode.CanBlockIfShadow)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CanBlockIfShadow) && applyCanBlockIfShadowAbility(stAb, attacker, blocker)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCanBlockIfShadowAbility(StaticAbility stAb, Card attacker, Card blocker) {
      if (!stAb.matchesValidParam("ValidAttacker", attacker)) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidBlocker", blocker);
      }
   }

   public static Cost getAttackCost(StaticAbility stAb, Card attacker, GameEntity target) {
      Card hostCard = stAb.getHostCard();
      if (!stAb.matchesValidParam("ValidCard", attacker)) {
         return null;
      } else if (!stAb.matchesValidParam("Target", target)) {
         return null;
      } else {
         String costString = stAb.getParam("Cost");
         if (stAb.hasSVar(costString)) {
            boolean remember = stAb.hasParam("RememberingAttacker");
            if (remember) {
               hostCard.addRemembered(attacker);
            }

            boolean addX = costString.startsWith("X");
            costString = Integer.toString(AbilityUtils.calculateAmount(hostCard, stAb.getSVar(costString), stAb));
            if (addX) {
               costString = costString + " X";
            }

            if (remember) {
               hostCard.removeRemembered(attacker);
            }
         }

         Cost cost = new Cost(costString, true);
         if (stAb.hasParam("Trigger")) {
            ((CostPart)cost.getCostParts().get(0)).setTrigger(stAb.getPayingTrigSA());
         }

         return cost;
      }
   }

   public static Cost getBlockCost(StaticAbility stAb, Card blocker, GameEntity attacker) {
      Card hostCard = stAb.getHostCard();
      if (!stAb.matchesValidParam("ValidCard", blocker)) {
         return null;
      } else if (!stAb.matchesValidParam("Attacker", attacker)) {
         return null;
      } else {
         String costString = stAb.getParam("Cost");
         if (stAb.hasSVar(costString)) {
            boolean addX = costString.startsWith("X");
            costString = Integer.toString(AbilityUtils.calculateAmount(hostCard, stAb.getSVar(costString), stAb));
            if (addX) {
               costString = costString + " X";
            }
         }

         return new Cost(costString, true);
      }
   }

   public static boolean canAttackHaste(Card attacker, GameEntity defender) {
      Game game = attacker.getGame();
      if (!attacker.isSick()) {
         return true;
      } else {
         for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CanAttackIfHaste)) { // Forge Nova: indexed scan
            {
               if (stAb.checkConditions(StaticAbilityMode.CanAttackIfHaste) && applyCanAttackHasteAbility(stAb, attacker, defender)) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   public static boolean applyCanAttackHasteAbility(StaticAbility stAb, Card card, GameEntity target) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidTarget", target);
      }
   }

   public static Pair<Integer, Integer> getMinMaxBlocker(Card attacker, Player defender) {
      MutablePair<Integer, Integer> result = MutablePair.of(1, Integer.MAX_VALUE);
      if (attacker.hasKeyword(Keyword.MENACE)) {
         result.setLeft(2);
      }

      Game game = attacker.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.MinMaxBlocker)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.MinMaxBlocker)) {
               applyMinMaxBlockerAbility(stAb, attacker, defender, result);
            }
         }
      }

      return result;
   }

   public static void applyMinMaxBlockerAbility(StaticAbility stAb, Card attacker, Player defender, MutablePair<Integer, Integer> result) {
      if (stAb.matchesValidParam("ValidCard", attacker)) {
         if (stAb.hasParam("Min")) {
            if ("All".equals(stAb.getParam("Min"))) {
               if (defender != null) {
                  result.setLeft(defender.getCreaturesInPlay().size());
               }
            } else {
               result.setLeft(AbilityUtils.calculateAmount(stAb.getHostCard(), stAb.getParam("Min"), stAb));
            }
         }

         if (stAb.hasParam("Max")) {
            result.setRight(AbilityUtils.calculateAmount(stAb.getHostCard(), stAb.getParam("Max"), stAb));
         }

      }
   }

   public static boolean attackVigilance(Card card) {
      Game game = card.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.AttackVigilance)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.AttackVigilance) && applyAttackVigilanceAbility(stAb, card)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyAttackVigilanceAbility(StaticAbility stAb, Card card) {
      return stAb.matchesValidParam("ValidCard", card);
   }
}
