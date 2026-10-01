package forge.game.staticability;

import java.util.EnumSet;
import java.util.Set;

public enum StaticAbilityMode {
   Continuous,
   CantAttackUnless,
   CantBlockUnless,
   OptionalAttackCost,
   OptionalCost,
   AlternativeCost,
   CantBeCast,
   CantBeActivated,
   CantPlayLand,
   DisableTriggers,
   Panharmonicon,
   MustTarget,
   CantAttack,
   CanAttackDefender,
   CantBlock,
   CantBlockBy,
   CanAttackIfHaste,
   CanBlockIfReach,
   CanBlockIfShadow,
   MinMaxBlocker,
   BlockTapped,
   AttackVigilance,
   MustAttack,
   PlayerMustAttack,
   MustBlock,
   AssignCombatDamageAsUnblocked,
   CombatDamageToughness,
   ColorlessDamageSource,
   NoCleanupDamage,
   BlockRestrict,
   CantGainLife,
   CantLoseLife,
   CantChangeLife,
   CantPayLife,
   RaiseCost,
   ReduceCost,
   SetCost,
   IgnoreHexproof,
   IgnoreShroud,
   AttackRestrict,
   AssignNoCombatDamage,
   CanAdapt,
   CantBeCopied,
   CantBeBeamedUp,
   CantBeSuspected,
   CantBecomeMonarch,
   CantGainControl,
   CantAttach,
   CantCrew,
   CantDraw,
   CantDiscard,
   CantExile,
   CantPhaseIn,
   CantPhaseOut,
   CantPreventDamage,
   CantPutCounter,
   CantRegenerate,
   CantSacrifice,
   CantTarget,
   CantTransform,
   CantVenture,
   CantChangeDayTime,
   ActivateAbilityAsIfHaste,
   CastWithFlash,
   IgnoreLandwalk,
   IgnoreLegendRule,
   IgnorePlaneswalkerZeroLoyaltyRule,
   MaxCounter,
   InfectDamage,
   WitherDamage,
   FlipCoinMod,
   FlipCoinDoubler,
   PlotZone,
   NumLoyaltyAct,
   Activations,
   Devotion,
   GainLifeRadiation,
   SurveilNum,
   TapPowerValue,
   UnspentMana,
   ManaBurn,
   ManaConvert,
   UntapOtherPlayer,
   TurnReversed,
   PhaseReversed,
   AttackRequirement,
   CountersRemain,
   ManaRestriction;

   public static StaticAbilityMode smartValueOf(String value) {
      if (value == null) {
         return null;
      } else {
         String valToCompate = value.trim();

         for(StaticAbilityMode v : values()) {
            if (v.name().compareToIgnoreCase(valToCompate) == 0) {
               return v;
            }
         }

         throw new IllegalArgumentException("No element named " + value + " in enum StaticAbilityMode");
      }
   }

   public static Set<StaticAbilityMode> setValueOf(String values) {
      Set<StaticAbilityMode> result = EnumSet.noneOf(StaticAbilityMode.class);

      for(String s : values.split("[, ]+")) {
         StaticAbilityMode zt = smartValueOf(s);
         if (zt != null) {
            result.add(zt);
         }
      }

      StaticAbilityModeRegistry.note(result); // Forge Nova: every static ability's modes come from here
      return result;
   }
}
