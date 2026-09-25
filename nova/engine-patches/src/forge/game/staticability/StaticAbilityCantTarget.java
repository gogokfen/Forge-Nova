package forge.game.staticability;

import com.google.common.collect.Lists;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class StaticAbilityCantTarget {
   public static StaticAbility cantTarget(GameEntity entity, SpellAbility spellAbility) {
      Game game = entity.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantTarget)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantTarget) && applyCantTargetAbility(stAb, entity, spellAbility)) {
               return stAb;
            }
         }
      }

      return null;
   }

   public static boolean applyCantTargetAbility(StaticAbility stAb, GameEntity entity, SpellAbility spellAbility) {
      if (entity instanceof Card card) {
         if (stAb.hasParam("AffectedZone")) {
            if (ZoneType.listValueOf(stAb.getParam("AffectedZone")).stream().noneMatch((zt) -> card.isInZone(zt))) {
               return false;
            }
         } else if (!card.isInPlay()) {
            return false;
         }

         Set<ZoneType> zones = stAb.getActiveZone();
         if (zones != null && zones.contains(ZoneType.Stack) && card.getGame().getStack().getSpellMatchingHost(spellAbility.getHostCard()) != null) {
            return false;
         }
      } else if (stAb.hasParam("AffectedZone")) {
         return false;
      }

      Card source = spellAbility.getHostCard();
      Player activator = spellAbility.getActivatingPlayer();
      if ((stAb.isKeyword(Keyword.HEXPROOF) || stAb.isKeyword(Keyword.SHROUD)) && StaticAbilityIgnoreHexproofShroud.ignore(entity, spellAbility, stAb)) {
         return false;
      } else if (!stAb.matchesValidParam("ValidTarget", entity)) {
         return false;
      } else if (!stAb.matchesValidParam("ValidSA", spellAbility)) {
         return false;
      } else if (!stAb.matchesValidParam("ValidSource", source)) {
         return false;
      } else if (!stAb.matchesValidParam("Activator", activator)) {
         return false;
      } else {
         if (stAb.hasParam("SourceCanOnlyTarget")) {
            SpellAbility root = spellAbility.getRootAbility();
            List<SpellAbility> choices = null;
            if (root.getApi() == ApiType.Charm) {
               choices = Lists.newArrayList(root.getAdditionalAbilityList("Choices"));
            } else {
               choices = Lists.newArrayList(new SpellAbility[]{root});
            }

            Iterator<SpellAbility> it = choices.iterator();
            SpellAbility next = (SpellAbility)it.next();

            while(next != null) {
               if (next.usesTargeting() && (!next.getParam("ValidTgts").contains(stAb.getParam("SourceCanOnlyTarget")) || next.getParam("ValidTgts").contains(",") || next.getParam("ValidTgts").contains("non" + stAb.getParam("SourceCanOnlyTarget")))) {
                  return false;
               }

               next = next.getSubAbility();
               if (next == null && it.hasNext()) {
                  next = (SpellAbility)it.next();
               }
            }
         }

         return true;
      }
   }
}
