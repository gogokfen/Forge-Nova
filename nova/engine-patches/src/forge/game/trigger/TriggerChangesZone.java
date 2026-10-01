package forge.game.trigger;

import com.google.common.collect.Sets;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.Expressions;
import forge.util.IterableUtil;
import forge.util.Localizer;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.apache.commons.lang3.ArrayUtils;

public class TriggerChangesZone extends Trigger {
   public TriggerChangesZone(Map<String, String> params, Card host, boolean intrinsic) {
      super(params, host, intrinsic);
      this.correctZones();
   }

   public final boolean performTest(Map<AbilityKey, Object> runParams) {
      if (this.hasParam("Origin") && !this.getParam("Origin").equals("Any")) {
         if (this.getParam("Origin") == null) {
            return false;
         }

         if (!ArrayUtils.contains(this.getParam("Origin").split(","), runParams.get(AbilityKey.Origin))) {
            return false;
         }
      }

      if (this.hasParam("Destination") && !this.getParam("Destination").equals("Any") && !ArrayUtils.contains(this.getParam("Destination").split(","), runParams.get(AbilityKey.Destination))) {
         return false;
      } else if (this.hasParam("ExcludedOrigins") && ArrayUtils.contains(this.getParam("ExcludedOrigins").split(","), runParams.get(AbilityKey.Origin))) {
         return false;
      } else if (this.hasParam("ExcludedDestinations") && ArrayUtils.contains(this.getParam("ExcludedDestinations").split(","), runParams.get(AbilityKey.Destination))) {
         return false;
      } else {
         if ("Battlefield".equals(this.getParam("Origin")) && this.getActiveZone() != null && this.getActiveZone().contains(ZoneType.Graveyard)) {
            CardCollectionView lastState = (CardCollectionView)runParams.get(AbilityKey.LastStateGraveyard);
            if (!lastState.contains(this.getHostCard())) {
               return false;
            }
         }

         Card moved = (Card)runParams.get(AbilityKey.Card);
         if (this.hasParam("ValidCard")) {
            if (!"Battlefield".equals(this.getParam("Origin")) && (!"Graveyard".equals(this.getParam("Origin")) || "Battlefield".equals(this.getParam("Destination")))) {
               if ("Battlefield".equals(runParams.get(AbilityKey.Destination))) {
                  // Forge Nova: the entry the sort below would put last, found in one pass (Zone.novaLastAddedThisTurn);
                  // this ran for every "enters" trigger on every card entering, copying and sorting everything that
                  // entered this turn
                  forge.game.zone.Zone novaBf = moved.getController().getZone(ZoneType.Battlefield);
                  Card novaFound = forge.game.card.TraitEpoch.DISABLED ? null : novaBf.novaLastAddedThisTurn(moved);
                  if (novaFound != null && !forge.game.card.TraitEpoch.VERIFY) {
                     moved = novaFound;
                  } else {
                     List<Card> etbLKI = novaBf.getCardsAddedThisTurn((ZoneType)null);
                     etbLKI.sort(CardPredicates.compareByGameTimestamp());
                     Card novaOriginal = (Card)etbLKI.get(etbLKI.lastIndexOf(moved));
                     if (novaFound != null && novaFound != novaOriginal) {
                        forge.game.card.TraitEpoch.mismatch("lastAddedThisTurn", moved, java.util.Collections.singletonList(novaFound), java.util.Collections.singletonList(novaOriginal));
                     }
                     moved = novaOriginal;
                  }
               }
            } else {
               moved = (Card)runParams.get(AbilityKey.CardLKI);
            }

            if (!this.matchesValidParam("ValidCard", moved)) {
               return false;
            }
         }

         if (this.hasParam("CheckOnTriggeredCard")) {
            String[] condition = this.getParam("CheckOnTriggeredCard").split(" ", 2);
            String comparator = condition.length < 2 ? "GE1" : condition[1];
            int referenceValue = AbilityUtils.calculateAmount(this.getHostCard(), comparator.substring(2), this);
            int actualValue = AbilityUtils.calculateAmount(moved, condition[0], this);
            if (!Expressions.compare(actualValue, comparator.substring(0, 2), referenceValue)) {
               return false;
            }
         }

         if (!this.matchesValidParam("ValidCause", runParams.get(AbilityKey.Cause))) {
            return false;
         } else {
            if (this.hasParam("Fizzle")) {
               if (!runParams.containsKey(AbilityKey.Fizzle)) {
                  return false;
               }

               Boolean val = (Boolean)runParams.get(AbilityKey.Fizzle);
               if ("True".equals(this.getParam("Fizzle")) != val) {
                  return false;
               }
            }

            if (this.hasParam("NotThisAbility") && runParams.containsKey(AbilityKey.Cause)) {
               SpellAbility cause = (SpellAbility)runParams.get(AbilityKey.Cause);
               if (cause != null && this.equals(cause.getRootAbility().getTrigger())) {
                  return false;
               }
            }

            if (this.hasParam("ConditionYouCastThisTurn")) {
               String compare = this.getParam("ConditionYouCastThisTurn");
               List<Card> thisTurnCast = this.getHostCard().getGame().getStack().getSpellCardsCastThisTurn();
               thisTurnCast = CardLists.filterControlledByAsList(thisTurnCast, this.getHostCard().getController());
               SpellAbility castSA = this.getHostCard().getCastSA();
               int left = IterableUtil.indexOf(thisTurnCast, CardPredicates.castSA(Predicate.isEqual(castSA)));
               int right = Integer.parseInt(compare.substring(2));
               if (!Expressions.compare(left + 1, compare, right)) {
                  return false;
               }
            }

            return true;
         }
      }
   }

   public final void setTriggeringObjects(SpellAbility sa, Map<AbilityKey, Object> runParams) {
      if ("Battlefield".equals(this.getParam("Origin"))) {
         sa.setTriggeringObject(AbilityKey.Card, runParams.get(AbilityKey.CardLKI));
         sa.setTriggeringObject(AbilityKey.NewCard, runParams.get(AbilityKey.Card));
      } else {
         sa.setTriggeringObjectsFrom(runParams, AbilityKey.Card, AbilityKey.CardLKI);
      }

   }

   public String getImportantStackObjects(SpellAbility sa) {
      StringBuilder sb = new StringBuilder();
      sb.append(Localizer.getInstance().getMessage("lblZoneChanger", new Object[0])).append(": ").append(sa.getTriggeringObject(AbilityKey.Card));
      return sb.toString();
   }

   protected void correctZones() {
      if (this.validHostZones == null) {
         if (this.getHostCard().getGame() != null) {
            if (this.hasParam("ValidCard")) {
               if (this.hasParam("Origin")) {
                  boolean leavesBattlefield = ArrayUtils.contains(this.getParam("Origin").split(","), ZoneType.Battlefield.toString());
                  if (leavesBattlefield && !this.isStatic()) {
                     this.setActiveZone(EnumSet.of(ZoneType.Battlefield));
                  }
               }

               if (this.getParam("ValidCard").contains("Self") && (!this.hasParam("Origin") || "Any".equals(this.getParam("Origin")))) {
                  this.setActiveZone(Sets.newEnumSet(ZoneType.listValueOf(this.getParam("Destination")), ZoneType.class));
               }

            }
         }
      }
   }
}
