package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.List;

public class StaticAbilityCantBeCast {
   public static boolean cantBeCastAbility(SpellAbility spell, Card card, Player activator) {
      card.setCastSA(spell);
      Game game = activator.getGame();
      CardCollection allp = new CardCollection(game.getCardsIn((Iterable)ZoneType.STATIC_ABILITIES_SOURCE_ZONES));
      allp.add(card);

      for(Card ca : allp) {
         for(StaticAbility stAb : ca.getStaticAbilities()) {
            if (stAb.checkConditions(StaticAbilityMode.CantBeCast) && applyCantBeCastAbility(stAb, spell, card, activator)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean cantBeActivatedAbility(SpellAbility spell, Card card, Player activator) {
      if (spell.isTrigger()) {
         return false;
      } else {
         Game game = activator.getGame();

         for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantBeActivated)) { // Forge Nova: indexed scan
            {
               if (stAb.checkConditions(StaticAbilityMode.CantBeActivated) && applyCantBeActivatedAbility(stAb, spell, card, activator)) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   public static boolean cantPlayLandAbility(SpellAbility spell, Card card, Player activator) {
      Game game = activator.getGame();

      for(StaticAbility stAb : forge.game.staticability.StaticAbilityIndex.forMode(game, StaticAbilityMode.CantPlayLand)) { // Forge Nova: indexed scan
         {
            if (stAb.checkConditions(StaticAbilityMode.CantPlayLand) && applyCantPlayLandAbility(stAb, card, activator)) {
               return true;
            }
         }
      }

      return false;
   }

   public static boolean applyCantBeCastAbility(StaticAbility stAb, SpellAbility spell, Card card, Player activator) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else if (!stAb.matchesValidParam("Caster", activator)) {
         return false;
      } else if (stAb.getIgnoreEffectPlayers().contains(activator)) {
         return false;
      } else if (stAb.hasParam("OnlySorcerySpeed") && activator != null && activator.canCastSorcery()) {
         return false;
      } else {
         if (stAb.hasParam("Origin")) {
            List<ZoneType> src = ZoneType.listValueOf(stAb.getParam("Origin"));
            if (card.getCastFrom() == null || !src.contains(card.getCastFrom().getZoneType())) {
               return false;
            }
         }

         if (stAb.hasParam("cmcGT") && activator != null) {
            if (stAb.getParam("cmcGT").equals("Turns")) {
               if (card.getCMC() <= activator.getTurn()) {
                  return false;
               }
            } else if (card.getCMC() <= CardLists.getType(activator.getCardsIn(ZoneType.Battlefield), stAb.getParam("cmcGT")).size()) {
               return false;
            }
         }

         if (stAb.hasParam("NumLimitEachTurn") && activator != null) {
            int limit = Integer.parseInt(stAb.getParam("NumLimitEachTurn"));
            String valid = stAb.getParamOrDefault("ValidCard", "Card");
            List<Card> thisTurnCast = CardUtil.getThisTurnCast(valid, card, stAb, activator);
            if (CardLists.filterControlledByAsList(thisTurnCast, activator).size() < limit) {
               return false;
            }
         }

         return true;
      }
   }

   public static boolean applyCantBeActivatedAbility(StaticAbility stAb, SpellAbility spellAbility, Card card, Player activator) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else if (stAb.getIgnoreEffectCards().contains(card)) {
         return false;
      } else if (!stAb.matchesValidParam("ValidSA", spellAbility)) {
         return false;
      } else if (stAb.hasParam("AffectedZone") && !card.isInZone(ZoneType.smartValueOf(stAb.getParam("AffectedZone")))) {
         return false;
      } else {
         return stAb.matchesValidParam("Activator", activator);
      }
   }

   public static boolean applyCantPlayLandAbility(StaticAbility stAb, Card card, Player player) {
      if (!stAb.matchesValidParam("ValidCard", card)) {
         return false;
      } else {
         if (stAb.hasParam("Origin")) {
            List<ZoneType> src = ZoneType.listValueOf(stAb.getParam("Origin"));
            if (!src.contains(card.getLastKnownZone().getZoneType())) {
               return false;
            }
         }

         if (!stAb.matchesValidParam("Player", player)) {
            return false;
         } else {
            return !stAb.getIgnoreEffectPlayers().contains(player);
         }
      }
   }
}
