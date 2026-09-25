package forge.game.staticability;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import java.util.ArrayList;
import java.util.List;

public class StaticAbilityMustAttack {
   public static List<GameEntity> entitiesMustAttack(Card attacker) {
      List<GameEntity> entityList = new ArrayList();
      Game game = attacker.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.MustAttack)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.MustAttack) && stAb.matchesValidParam("ValidCreature", attacker)) {
               if (stAb.hasParam("MustAttack")) {
                  for(GameEntity e : AbilityUtils.getDefinedEntities(stAb.getHostCard(), (String)stAb.getParam("MustAttack"), stAb)) {
                     if (e instanceof Player) {
                        Player attackPl = (Player)e;
                        if (game.getPhaseHandler().isPlayerTurn(attackPl)) {
                           continue;
                        }
                     }

                     if (e instanceof Card) {
                        Card attackPw = (Card)e;
                        if (game.getPhaseHandler().isPlayerTurn(attackPw.getController())) {
                           continue;
                        }
                     }

                     entityList.add(e);
                  }
               } else {
                  entityList.add(attacker);
               }
            }
         }
      }

      return entityList;
   }

   public static Multimap<GameEntity, StaticAbility> mustAttackSpecific(Player attackingPlayer, FCollectionView<GameEntity> possibleDefenders) {
      Multimap<GameEntity, StaticAbility> result = MultimapBuilder.hashKeys(possibleDefenders.size()).arrayListValues().build();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(attackingPlayer.getGame(), StaticAbilityMode.PlayerMustAttack)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.PlayerMustAttack) && stAb.matchesValidParam("ValidPlayer", attackingPlayer)) {
               for(GameEntity ge : possibleDefenders) {
                  if (stAb.matchesValidParam("MustAttack", ge)) {
                     result.put(ge, stAb);
                  }
               }
            }
         }
      }

      return result;
   }

   public static Multimap<Card, StaticAbility> getAttackRequirements(Card card, Iterable<Card> other) {
      Multimap<Card, StaticAbility> result = MultimapBuilder.hashKeys().arrayListValues().build();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(card.getGame(), StaticAbilityMode.AttackRequirement)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.AttackRequirement) && stAb.matchesValidParam("ValidCard", card)) {
               for(Card co : other) {
                  if (stAb.matchesValidParam("ValidAttacker", co)) {
                     result.put(co, stAb);
                  }
               }
            }
         }
      }

      return result;
   }
}
