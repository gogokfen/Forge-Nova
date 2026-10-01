package forge.game.staticability;

import com.google.common.collect.Lists;
import forge.card.mana.ManaCostParser;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.List;
import java.util.Set;

public class StaticAbilityAlternativeCost {
   public static List<SpellAbility> alternativeCosts(SpellAbility sa, Card source, Player pl) {
      List<SpellAbility> result = Lists.newArrayList();
      if (!StaticAbilityModeRegistry.mayExist(StaticAbilityMode.AlternativeCost)) {
         return result; // Forge Nova: no static ability of this mode exists anywhere
      }
      // Forge Nova: Forge visits the source card first, then every card in the static ability zones except
      // cards equal to the source (CardCollection dedup); the index lists the zone cards' AlternativeCost
      // abilities in that order, so only the abilities that can pass checkMode are visited
      for(StaticAbility stAb : source.getStaticAbilities()) {
         addAlternativeCost(stAb, sa, source, pl, result);
      }
      StaticAbilityIndex.Entries entries = StaticAbilityIndex.entries(source.getGame(), StaticAbilityMode.AlternativeCost);
      for(int i = 0; i < entries.statics.size(); ++i) {
         if (!entries.hosts.get(i).equals(source)) {
            addAlternativeCost(entries.statics.get(i), sa, source, pl, result);
         }
      }

      return result;
   }

   private static void addAlternativeCost(StaticAbility stAb, SpellAbility sa, Card source, Player pl, List<SpellAbility> result) {
            if (stAb.checkMode(StaticAbilityMode.AlternativeCost) && apply(stAb, sa, source, pl) && stAb.checkConditions()) {
               String costTemplate = stAb.getParam("Cost");
               costTemplate = costTemplate.replace("ConvertedManaCost", Integer.toString(source.getCMC()));
               Cost cost = new Cost(costTemplate, sa.isAbility());
               SpellAbility newSA = sa.isAbility() ? sa.copyWithDefinedCost(cost) : sa.copyWithManaCostReplaced(pl, cost);
               newSA.setActivatingPlayer(pl);
               newSA.setBasicSpell(false);
               if (stAb.hasParam("XAlternative")) {
                  newSA.putParam("XAlternative", stAb.getParam("XAlternative"));
               }

               if (stAb.hasParam("Announce")) {
                  newSA.putParam("Announce", stAb.getParam("Announce"));
               }

               if (stAb.hasParam("ManaRestriction")) {
                  newSA.putParam("ManaRestriction", stAb.getParam("ManaRestriction"));
               }

               if (!stAb.getHostCard().isImmutable()) {
                  Set<ZoneType> zones = stAb.getActiveZone();
                  if (zones != null && zones.size() == 1) {
                     newSA.getRestrictions().setZone((ZoneType)zones.stream().findFirst().get());
                  }
               }

               if (stAb.hasParam("StackDescription")) {
                  newSA.putParam("StackDescription", stAb.getParam("StackDescription"));
               }

               StringBuilder sb = new StringBuilder();
               if (sa.isAbility()) {
                  newSA.putParam("CostDesc", stAb.hasParam("CostDesc") ? ManaCostParser.parse(stAb.getParam("CostDesc")) : cost.toSimpleString());
                  sb.append(newSA.getCostDescription());
               }

               if (sa.isSpell()) {
                  sb.append(sa.getDescription());
                  if (source.equals(stAb.getHostCard())) {
                     newSA.addOptionalCost(OptionalCost.AltCost);
                     sb.append(" (" + stAb.getParam("Description") + ") ");
                  } else {
                     sb.append(" (by paying " + cost.toSimpleString() + " instead of its mana cost)");
                  }
               }

               newSA.setDescription(sb.toString());
               if (stAb.hasParam("Named")) {
                  newSA.setName(stAb.getParam("Named"));
               }

               result.add(newSA);
            }
   }

   private static boolean apply(StaticAbility stAb, SpellAbility sa, Card source, Player pl) {
      if (!stAb.matchesValidParam("ValidSA", sa)) {
         return false;
      } else if (!stAb.matchesValidParam("ValidCard", source)) {
         return false;
      } else {
         return stAb.matchesValidParam("ValidPlayer", pl);
      }
   }
}
