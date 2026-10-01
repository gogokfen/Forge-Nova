package forge.game.staticability;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.GameCommand;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.RemoveType;
import forge.card.MagicColor.Constant;
import forge.card.mana.ManaCost;
import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.StaticEffect;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardFactoryUtil;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CardState;
import forge.game.card.CardUtil;
import forge.game.cost.Cost;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilityStatic;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;
import forge.util.TextUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public final class StaticAbilityContinuous {
   private StaticAbilityContinuous() {
   }

   public static CardCollectionView applyContinuousAbility(StaticAbility stAb, StaticAbilityLayer layer, CardCollectionView preList) {
      CardCollectionView affectedCards = getAffectedCards(stAb, preList);
      return applyContinuousAbility(stAb, affectedCards, layer);
   }

   public static CardCollectionView applyContinuousAbility(StaticAbility stAb, CardCollectionView affectedCards, StaticAbilityLayer layer) {
      Map<String, String> params = stAb.getMapParams();
      Card hostCard = stAb.getHostCard();
      Player controller = hostCard.getController();
      List<Player> affectedPlayers = getAffectedPlayers(stAb);
      if (stAb.hasParam("Affected") && affectedPlayers.isEmpty() && affectedCards.isEmpty()) {
         return affectedCards;
      } else {
         Game game = hostCard.getGame();
         StaticEffect se = game.getStaticEffects().getStaticEffect(stAb);
         se.setAffectedCards(affectedCards);
         se.setAffectedPlayers(affectedPlayers);
         se.setParams(params);
         se.setTimestamp(stAb.getTimestamp());
         String addP = "";
         int powerBonus = 0;
         String addT = "";
         int toughnessBonus = 0;
         String setP = "";
         Integer setPower = null;
         String setT = "";
         Integer setToughness = null;
         List<String> addKeywords = null;
         List<String> addHiddenKeywords = Lists.newArrayList();
         List<String> removeKeywords = null;
         String[] addAbilities = null;
         String[] addReplacements = null;
         String[] addSVars = null;
         List<String> addTypes = null;
         List<String> removeTypes = null;
         ColorSet addColors = null;
         String[] addTriggers = null;
         String[] addStatics = null;
         Predicate<CardTraitBase> removeAbilities = null;
         boolean addAllCreatureTypes = false;
         Set<RemoveType> remove = EnumSet.noneOf(RemoveType.class);
         boolean overwriteColors = false;
         Set<Keyword> cantHaveKeyword = null;
         List<Player> mayLookAt = null;
         boolean controllerMayPlay = false;
         boolean mayPlayWithoutManaCost = false;
         boolean mayPlayWithFlash = false;
         String mayPlayAltManaCost = null;
         boolean mayPlayGrantZonePermissions = true;
         Integer mayPlayLimit = null;
         if (layer == StaticAbilityLayer.SETPT || layer == StaticAbilityLayer.CHARACTERISTIC) {
            if (params.containsKey("SetPower")) {
               setP = (String)params.get("SetPower");
               setPower = AbilityUtils.calculateAmount(hostCard, setP, stAb);
            }

            if (params.containsKey("SetToughness")) {
               setT = (String)params.get("SetToughness");
               setToughness = AbilityUtils.calculateAmount(hostCard, setT, stAb);
            }
         }

         if (layer == StaticAbilityLayer.MODIFYPT) {
            if (params.containsKey("AddPower")) {
               addP = (String)params.get("AddPower");
               powerBonus = AbilityUtils.calculateAmount(hostCard, addP, stAb, true);
            }

            if (params.containsKey("AddToughness")) {
               addT = (String)params.get("AddToughness");
               toughnessBonus = AbilityUtils.calculateAmount(hostCard, addT, stAb, true);
            }
         }

         if (layer == StaticAbilityLayer.ABILITIES) {
            if (params.containsKey("AddKeyword")) {
               List<String> var54 = Lists.newArrayList(Arrays.asList(((String)params.get("AddKeyword")).split(" & ")));
               List<String> newKeywords = Lists.newArrayList();
               String hostCardUID = Integer.toString(hostCard.getId());
               String hostCardControllerUID = Integer.toString(hostCard.getController().getId());
               var54.removeIf((input) -> {
                  if (!hostCard.hasChosenColor() && input.contains("ChosenColor")) {
                     return true;
                  } else if (!hostCard.hasChosenType() && input.contains("ChosenType")) {
                     return true;
                  } else if (!hostCard.hasChosenNumber() && input.contains("ChosenNumber")) {
                     return true;
                  } else if (!hostCard.hasChosenPlayer() && input.contains("ChosenPlayer")) {
                     return true;
                  } else if (!hostCard.hasNamedCard() && input.contains("ChosenName")) {
                     return true;
                  } else if (hostCard.hasChosenEvenOdd() || !input.contains("ChosenEvenOdd") && !input.contains("chosenEvenOdd")) {
                     if (!input.contains("AllColors") && !input.contains("allColors")) {
                        if (input.contains("CommanderColorID")) {
                           if (hostCard.getController().getCommanders().isEmpty()) {
                              return true;
                           } else if (input.contains("NotCommanderColorID")) {
                              for(MagicColor.Color color : hostCard.getController().getCommanderColorID().inverse()) {
                                 newKeywords.add(input.replace("NotCommanderColorID", color.getName()));
                              }

                              return true;
                           } else {
                              for(MagicColor.Color color : hostCard.getController().getCommanderColorID()) {
                                 newKeywords.add(input.replace("CommanderColorID", color.getName()));
                              }

                              return true;
                           }
                        } else if (!input.contains("ColorsYouCtrl") && !input.contains("colorsYouCtrl")) {
                           if (input.contains("YourBasic")) {
                              CardCollectionView lands = hostCard.getController().getLandsInPlay();

                              for(String type : Constant.BASIC_LANDS) {
                                 if (lands.anyMatch(CardPredicates.isType(type))) {
                                    String y = input.replaceAll("YourBasic", type);
                                    newKeywords.add(y);
                                 }
                              }

                              return true;
                           } else if (input.contains("EachCMCAmongDefined")) {
                              String keywordDefined = (String)params.get("KeywordDefined");
                              CardCollectionView definedCards = game.getCardsIn(ZoneType.Battlefield);

                              for(Card c : CardLists.getValidCards(definedCards, (String)keywordDefined, hostCard.getController(), hostCard, stAb)) {
                                 int cmc = c.getCMC();
                                 String y = input.replace(" from EachCMCAmongDefined", ":Card.cmcEQ" + cmc + ":Protection from mana value " + cmc);
                                 if (!newKeywords.contains(y)) {
                                    newKeywords.add(y);
                                 }
                              }

                              return true;
                           } else {
                              return false;
                           }
                        } else {
                           for(MagicColor.Color color : CardUtil.getColorsFromCards(controller.getCardsIn(ZoneType.Battlefield))) {
                              String y = input.replaceAll("ColorsYouCtrl", StringUtils.capitalize(color.getName()));
                              y = y.replaceAll("colorsYouCtrl", color.getName());
                              newKeywords.add(y);
                           }

                           return true;
                        }
                     } else {
                        for(byte color : MagicColor.WUBRG) {
                           String colorWord = MagicColor.toLongString(color);
                           String y = input.replaceAll("AllColors", StringUtils.capitalize(colorWord));
                           y = y.replaceAll("allColors", colorWord);
                           newKeywords.add(y);
                        }

                        return true;
                     }
                  } else {
                     return true;
                  }
               });
               var54.addAll(newKeywords);
               addKeywords = (List)var54.stream().map((input) -> {
                  if (hostCard.hasChosenColor()) {
                     input = input.replaceAll("ChosenColor", StringUtils.capitalize(hostCard.getChosenColor()));
                     input = input.replaceAll("chosenColor", hostCard.getChosenColor().toLowerCase());
                  }

                  if (hostCard.hasChosenType()) {
                     input = input.replaceAll("ChosenType", hostCard.getChosenType());
                  }

                  if (hostCard.hasChosenNumber()) {
                     input = input.replaceAll("ChosenNumber", String.valueOf(hostCard.getChosenNumber()));
                  }

                  if (hostCard.hasChosenPlayer()) {
                     Player cp = hostCard.getChosenPlayer();
                     input = input.replaceAll("ChosenPlayerUID", String.valueOf(cp.getId()));
                     input = input.replaceAll("ChosenPlayerName", Matcher.quoteReplacement(cp.getName()));
                  }

                  if (hostCard.hasNamedCard()) {
                     String chosenName = hostCard.getNamedCard().replace(",", ";");
                     input = input.replaceAll("ChosenName", "Card.named" + chosenName);
                  }

                  if (hostCard.hasChosenEvenOdd()) {
                     input = input.replaceAll("ChosenEvenOdd", hostCard.getChosenEvenOdd().toString());
                     input = input.replaceAll("chosenEvenOdd", hostCard.getChosenEvenOdd().toString().toLowerCase());
                  }

                  input = input.replace("HostCardUID", hostCardUID);
                  input = input.replace("HostCardControllerUID", hostCardControllerUID);
                  if (params.containsKey("CalcKeywordN")) {
                     input = input.replace("N", String.valueOf(AbilityUtils.calculateAmount(hostCard, (String)params.get("CalcKeywordN"), stAb)));
                  }

                  return input;
               }).collect(Collectors.toList());
               if (params.containsKey("SharedKeywordsZone")) {
                  List<ZoneType> zones = ZoneType.listValueOf((String)params.get("SharedKeywordsZone"));
                  String[] restrictions = params.containsKey("SharedRestrictions") ? ((String)params.get("SharedRestrictions")).split(",") : new String[]{"Card"};
                  addKeywords = CardFactoryUtil.sharedKeywords(addKeywords, restrictions, zones, hostCard, stAb);
               }

               if (params.containsKey("FromDraftNotes")) {
                  addKeywords = Lists.newArrayList(((String)hostCard.getController().getDraftNotes().getOrDefault(params.get("FromDraftNotes"), "")).split(","));
               }
            } else if (params.containsKey("ShareRememberedKeywords")) {
               List<String> kwToShare = Lists.newArrayList();

               for(Object o : hostCard.getRemembered()) {
                  String k = (String)o;
                  kwToShare.add(k);
               }

               if (!kwToShare.isEmpty()) {
                  addKeywords = kwToShare;
               }
            }

            if (params.containsKey("CantHaveKeyword")) {
               cantHaveKeyword = Keyword.setValueOf((String)params.get("CantHaveKeyword"));
            }

            if (params.containsKey("RemoveKeyword")) {
               removeKeywords = Arrays.asList(((String)params.get("RemoveKeyword")).split(" & "));
            }
         }

         if (layer == StaticAbilityLayer.RULES && params.containsKey("AddHiddenKeyword")) {
            addHiddenKeywords.addAll(Arrays.asList(((String)params.get("AddHiddenKeyword")).split(" & ")));
         }

         if (layer == StaticAbilityLayer.ABILITIES) {
            if (params.containsKey("RemoveAllAbilities")) {
               removeAbilities = (e) -> true;
            } else if (params.containsKey("RemoveNonManaAbilities")) {
               removeAbilities = Predicate.not(CardTraitBase::isManaAbility);
            }

            if (params.containsKey("AddAbility")) {
               String[] sVars = ((String)params.get("AddAbility")).split(" & ");

               for(int i = 0; i < sVars.length; ++i) {
                  sVars[i] = AbilityUtils.getSVar(stAb, sVars[i]);
               }

               addAbilities = sVars;
            }

            if (params.containsKey("AddReplacementEffect")) {
               String[] sVars = ((String)params.get("AddReplacementEffect")).split(" & ");

               for(int i = 0; i < sVars.length; ++i) {
                  sVars[i] = AbilityUtils.getSVar(stAb, sVars[i]);
               }

               addReplacements = sVars;
            }

            if (params.containsKey("AddTrigger")) {
               String[] sVars = ((String)params.get("AddTrigger")).split(" & ");

               for(int i = 0; i < sVars.length; ++i) {
                  sVars[i] = AbilityUtils.getSVar(stAb, sVars[i]);
               }

               addTriggers = sVars;
            }

            if (params.containsKey("AddStaticAbility")) {
               String[] sVars = ((String)params.get("AddStaticAbility")).split(" & ");

               for(int i = 0; i < sVars.length; ++i) {
                  sVars[i] = AbilityUtils.getSVar(stAb, sVars[i]);
               }

               addStatics = sVars;
            }

            if (params.containsKey("AddSVar")) {
               addSVars = ((String)params.get("AddSVar")).split(" & ");
            }
         }

         if (layer == StaticAbilityLayer.TYPE) {
            if (params.containsKey("AddType")) {
               addTypes = Lists.newArrayList(Arrays.asList(((String)params.get("AddType")).split(" & ")));
               List<String> newTypes = Lists.newArrayList();
               addTypes.removeIf((input) -> {
                  if (input.equals("ChosenType") && !hostCard.hasChosenType()) {
                     return true;
                  } else if (input.equals("ChosenType2") && !hostCard.hasChosenType2()) {
                     return true;
                  } else if (input.equals("ImprintedCreatureType")) {
                     if (hostCard.hasImprintedCard()) {
                        newTypes.addAll(((Card)hostCard.getImprintedCards().getLast()).getType().getCreatureTypes());
                     }

                     return true;
                  } else if (input.equals("AllBasicLandType")) {
                     newTypes.addAll(CardType.getBasicTypes());
                     return true;
                  } else if (input.equals("AllNonBasicLandType")) {
                     newTypes.addAll(CardType.getNonBasicTypes());
                     return true;
                  } else {
                     return false;
                  }
               });
               addTypes.addAll(newTypes);
               addTypes = (List)addTypes.stream().map((input) -> {
                  if (hostCard.hasChosenType2()) {
                     input = input.replaceAll("ChosenType2", hostCard.getChosenType2());
                  }

                  if (hostCard.hasChosenType()) {
                     input = input.replaceAll("ChosenType", hostCard.getChosenType());
                  }

                  return input;
               }).collect(Collectors.toList());
            }

            if (params.containsKey("RemoveType")) {
               removeTypes = Lists.newArrayList(Arrays.asList(((String)params.get("RemoveType")).split(" & ")));
               removeTypes.removeIf((input) -> input.equals("ChosenType") && !hostCard.hasChosenType());
            }

            if (params.containsKey("AddAllCreatureTypes")) {
               addAllCreatureTypes = true;
            }

            if (addTypes == null || !addTypes.isEmpty()) {
               if (params.containsKey("RemoveSuperTypes")) {
                  remove.add(RemoveType.SuperTypes);
               }

               if (params.containsKey("RemoveCardTypes")) {
                  remove.add(RemoveType.CardTypes);
               }

               if (params.containsKey("RemoveSubTypes")) {
                  remove.add(RemoveType.SubTypes);
               }

               if (params.containsKey("RemoveLandTypes")) {
                  remove.add(RemoveType.LandTypes);
               }

               if (params.containsKey("RemoveCreatureTypes")) {
                  remove.add(RemoveType.CreatureTypes);
               }

               if (params.containsKey("RemoveArtifactTypes")) {
                  remove.add(RemoveType.ArtifactTypes);
               }

               if (params.containsKey("RemoveEnchantmentTypes")) {
                  remove.add(RemoveType.EnchantmentTypes);
               }
            }
         }

         if (layer == StaticAbilityLayer.COLOR) {
            if (params.containsKey("AddColor")) {
               addColors = getColorsFromParam(stAb, (String)params.get("AddColor"));
            }

            if (params.containsKey("SetColor")) {
               addColors = getColorsFromParam(stAb, (String)params.get("SetColor"));
               overwriteColors = true;
            }
         }

         if (layer == StaticAbilityLayer.RULES) {
            if (params.containsKey("MayLookAt")) {
               String look = (String)params.get("MayLookAt");
               if ("True".equals(look)) {
                  mayLookAt = new PlayerCollection();
               } else {
                  mayLookAt = AbilityUtils.getDefinedPlayers(hostCard, look, stAb);
               }
            }

            if (params.containsKey("MayPlay")) {
               controllerMayPlay = true;
               if (params.containsKey("MayPlayWithoutManaCost")) {
                  mayPlayWithoutManaCost = true;
               } else if (params.containsKey("MayPlayAltManaCost")) {
                  mayPlayAltManaCost = (String)params.get("MayPlayAltManaCost");
               }

               if (params.containsKey("MayPlayWithFlash")) {
                  mayPlayWithFlash = true;
               }

               if (params.containsKey("MayPlayLimit")) {
                  mayPlayLimit = Integer.parseInt((String)params.get("MayPlayLimit"));
               }

               if (params.containsKey("MayPlayDontGrantZonePermissions")) {
                  mayPlayGrantZonePermissions = false;
               }
            }

            if (params.containsKey("IgnoreEffectCost")) {
               String cost = (String)params.get("IgnoreEffectCost");
               buildIgnoreEffectAbility(stAb, cost, affectedPlayers, affectedCards);
            }
         }

         for(Player p : affectedPlayers) {
            if (addKeywords != null && !addKeywords.isEmpty()) {
               p.addChangedKeywords(addKeywords, removeKeywords, se.getTimestamp(), (long)stAb.getId());
            }

            if (layer == StaticAbilityLayer.RULES) {
               if (params.containsKey("SetMaxHandSize")) {
                  String mhs = (String)params.get("SetMaxHandSize");
                  if (mhs.equals("Unlimited")) {
                     p.setUnlimitedHandSize(true);
                  } else {
                     p.setUnlimitedHandSize(false);
                     int max = AbilityUtils.calculateAmount(hostCard, mhs, stAb);
                     p.setMaxHandSize(max);
                  }
               }

               if (params.containsKey("RaiseMaxHandSize")) {
                  String rmhs = (String)params.get("RaiseMaxHandSize");
                  int rmax = AbilityUtils.calculateAmount(hostCard, rmhs, stAb);
                  p.setMaxHandSize(p.getMaxHandSize() + rmax);
               }

               if (params.containsKey("AdjustLandPlays")) {
                  String mhs = (String)params.get("AdjustLandPlays");
                  if (mhs.equals("Unlimited")) {
                     p.addMaxLandPlaysInfinite(se.getTimestamp());
                  } else {
                     int add = AbilityUtils.calculateAmount(hostCard, mhs, stAb);
                     p.addMaxLandPlays(se.getTimestamp(), add);
                  }
               }

               if (params.containsKey("ControlOpponentsSearchingLibrary")) {
                  Player cntl = (Player)Iterables.getFirst(AbilityUtils.getDefinedPlayers(hostCard, (String)params.get("ControlOpponentsSearchingLibrary"), stAb), (Object)null);
                  p.addControlledWhileSearching(se.getTimestamp(), cntl);
               }

               if (params.containsKey("ControlVote")) {
                  p.addControlVote(se.getTimestamp());
               }

               if (params.containsKey("AdditionalVote")) {
                  String mhs = (String)params.get("AdditionalVote");
                  int add = AbilityUtils.calculateAmount(hostCard, mhs, stAb);
                  p.addAdditionalVote(se.getTimestamp(), add);
               }

               if (params.containsKey("AdditionalOptionalVote")) {
                  String mhs = (String)params.get("AdditionalOptionalVote");
                  int add = AbilityUtils.calculateAmount(hostCard, mhs, stAb);
                  p.addAdditionalOptionalVote(se.getTimestamp(), add);
               }

               if (params.containsKey("AdditionalVillainousChoice")) {
                  String mhs = (String)params.get("AdditionalVillainousChoice");
                  int add = AbilityUtils.calculateAmount(hostCard, mhs, stAb);
                  p.addAdditionalVillainousChoices(se.getTimestamp(), add);
               }

               if (params.containsKey("DeclaresAttackers")) {
                  PlayerCollection players = AbilityUtils.getDefinedPlayers(hostCard, (String)params.get("DeclaresAttackers"), stAb);
                  if (!players.isEmpty()) {
                     p.addDeclaresAttackers(se.getTimestamp(), (Player)players.getFirst());
                  }
               }

               if (params.containsKey("DeclaresBlockers")) {
                  PlayerCollection players = AbilityUtils.getDefinedPlayers(hostCard, (String)params.get("DeclaresBlockers"), stAb);
                  if (!players.isEmpty()) {
                     p.addDeclaresBlockers(se.getTimestamp(), (Player)players.getFirst());
                  }
               }
            }
         }

         for(Card affectedCard : affectedCards) {
            if (layer == StaticAbilityLayer.CONTROL && params.containsKey("GainControl")) {
               PlayerCollection gain = AbilityUtils.getDefinedPlayers(hostCard, (String)params.get("GainControl"), stAb);
               if (!gain.isEmpty()) {
                  affectedCard.addTempController((Player)gain.get(0), se.getTimestamp());
               }
            }

            if (layer == StaticAbilityLayer.TEXT) {
               if (params.containsKey("GainTextOf")) {
                  CardCollection allValid = AbilityUtils.getDefinedCards(hostCard, (String)params.get("GainTextOf"), stAb);
                  if (!allValid.isEmpty()) {
                     Card first = (Card)allValid.getFirst();
                     CardState state = first.getState(affectedCard.isFlipped() && first.isFlipCard() ? CardStateName.Flipped : first.getCurrentStateName());
                     List<SpellAbility> spellAbilities = Lists.newArrayList();
                     List<Trigger> trigger = Lists.newArrayList();
                     List<ReplacementEffect> replacementEffects = Lists.newArrayList();
                     List<StaticAbility> staticAbilities = Lists.newArrayList();
                     List<KeywordInterface> keywords = Lists.newArrayList();

                     for(SpellAbility sa : state.getSpellAbilities()) {
                        spellAbilities.add(affectedCard.getSpellAbilityForStaticAbilityByText(sa, stAb));
                     }

                     if (params.containsKey("GainTextAbilities")) {
                        for(String ability : ((String)params.get("GainTextAbilities")).split(" & ")) {
                           spellAbilities.add(affectedCard.getSpellAbilityForStaticAbilityGainedByText(AbilityUtils.getSVar(stAb, ability), stAb));
                        }
                     }

                     for(Trigger tr : state.getTriggers()) {
                        trigger.add(affectedCard.getTriggerForStaticAbilityByText(tr, stAb));
                     }

                     for(ReplacementEffect re : state.getReplacementEffects()) {
                        replacementEffects.add(affectedCard.getReplacementEffectForStaticAbilityByText(re, stAb));
                     }

                     for(StaticAbility st : state.getStaticAbilities()) {
                        staticAbilities.add(affectedCard.getStaticAbilityForStaticAbilityByText(st, stAb));
                     }

                     long kwIdx = 1L;

                     for(KeywordInterface ki : state.getIntrinsicKeywords()) {
                        keywords.add(affectedCard.getKeywordForStaticAbilityByText(ki, stAb, kwIdx));
                        ++kwIdx;
                     }

                     affectedCard.addChangedName(state.getName(), false, se.getTimestamp(), (long)stAb.getId());
                     affectedCard.addChangedManaCost(state.getManaCost(), false, se.getTimestamp(), (long)stAb.getId());
                     affectedCard.addColorByText(state.getColor(), false, se.getTimestamp(), stAb);
                     affectedCard.addChangedCardTypesByText(state.getType(), se.getTimestamp(), (long)stAb.getId());
                     affectedCard.addChangedCardTraitsByText(spellAbilities, trigger, replacementEffects, staticAbilities, se.getTimestamp(), (long)stAb.getId());
                     affectedCard.addChangedCardKeywordsByText(keywords, se.getTimestamp(), (long)stAb.getId(), false);
                     affectedCard.addNewPTByText(state.getBasePower(), state.getBaseToughness(), se.getTimestamp(), (long)stAb.getId());
                  }
               }

               if (stAb.hasParam("Incorporate")) {
                  ManaCost manaCost = new ManaCost(stAb.getParam("Incorporate"));
                  affectedCard.addChangedManaCost(manaCost, true, se.getTimestamp(), (long)stAb.getId());
                  affectedCard.addColorByText(ColorSet.fromMask(manaCost.getColorProfile()), true, se.getTimestamp(), stAb);
               }

               if (stAb.hasParam("ManaCost")) {
                  ManaCost manaCost = new ManaCost(stAb.getParam("ManaCost"));
                  affectedCard.addChangedManaCost(manaCost, false, se.getTimestamp(), (long)stAb.getId());
               }

               if (stAb.hasParam("AddNames")) {
                  affectedCard.addChangedName((String)null, true, se.getTimestamp(), (long)stAb.getId());
               }

               if (stAb.hasParam("SetName")) {
                  String newName = stAb.getParam("SetName");
                  if (newName.equals("ChosenName")) {
                     newName = hostCard.getNamedCard();
                  }

                  if (!newName.isEmpty()) {
                     affectedCard.addChangedName(newName, false, se.getTimestamp(), (long)stAb.getId());
                  }
               }

               if (params.containsKey("ChangeColorWordsTo")) {
                  String changeColorWordsTo = (String)params.get("ChangeColorWordsTo");
                  byte color;
                  if (changeColorWordsTo.equals("ChosenColor")) {
                     if (hostCard.hasChosenColor()) {
                        color = MagicColor.fromName((String)Iterables.getFirst(hostCard.getChosenColors(), (Object)null));
                     } else {
                        color = 0;
                     }
                  } else {
                     color = MagicColor.fromName(changeColorWordsTo);
                  }

                  if (color != 0) {
                     String colorName = MagicColor.toLongString(color);
                     affectedCard.addChangedTextColorWord(stAb.getParamOrDefault("ChangeColorWordsFrom", "Any"), colorName, se.getTimestamp(), (long)stAb.getId());
                  }
               }
            }

            if ((layer == StaticAbilityLayer.SETPT || layer == StaticAbilityLayer.CHARACTERISTIC) && (setPower != null || setToughness != null)) {
               if (setP.contains("Affected")) {
                  setPower = AbilityUtils.calculateAmount(affectedCard, setP, stAb, true);
               }

               if (setT.contains("Affected")) {
                  setToughness = AbilityUtils.calculateAmount(affectedCard, setT, stAb, true);
               }

               affectedCard.addNewPT(setPower, setToughness, se.getTimestamp(), (long)stAb.getId(), layer == StaticAbilityLayer.CHARACTERISTIC, false);
            }

            if (layer == StaticAbilityLayer.MODIFYPT) {
               if (addP.contains("Affected")) {
                  powerBonus = AbilityUtils.calculateAmount(affectedCard, addP, stAb, true);
               }

               if (addT.contains("Affected")) {
                  toughnessBonus = AbilityUtils.calculateAmount(affectedCard, addT, stAb, true);
               }

               affectedCard.addPTBoost(powerBonus, toughnessBonus, se.getTimestamp(), (long)stAb.getId());
            }

            if (addKeywords != null && !addKeywords.isEmpty() || removeKeywords != null || removeAbilities != null) {
               List<String> newKeywords = null;
               if (addKeywords != null) {
                  newKeywords = Lists.newArrayList(addKeywords);
                  List<String> extraKeywords = Lists.newArrayList();
                  newKeywords.removeIf((input) -> {
                     if (!input.contains("CardColors") && !input.contains("cardColors")) {
                        return false;
                     } else {
                        if (!affectedCard.getColor().isColorless()) {
                           for(MagicColor.Color color : affectedCard.getColor()) {
                              extraKeywords.add(input.replaceAll("CardColors", StringUtils.capitalize(color.getName())).replaceAll("cardColors", color.getName()));
                           }
                        }

                        return true;
                     }
                  });
                  newKeywords.addAll(extraKeywords);
                  newKeywords = (List)newKeywords.stream().map((input) -> {
                     if (input.contains("CardManaCost")) {
                        input = input.replace("CardManaCost", affectedCard.getManaCost().getShortString());
                     } else if (input.contains("ConvertedManaCost")) {
                        String costcmc = Integer.toString(affectedCard.getCMC());
                        input = input.replace("ConvertedManaCost", costcmc);
                     }

                     return input;
                  }).collect(Collectors.toList());
               }

               if (newKeywords != null && !newKeywords.isEmpty() && params.containsKey("KeywordMultiplier")) {
                  newKeywords = (List)newKeywords.stream().flatMap((s) -> Collections.nCopies(Integer.valueOf((String)params.get("KeywordMultiplier")), s).stream()).collect(Collectors.toList());
               }

               affectedCard.addChangedCardKeywords(newKeywords, removeKeywords, removeAbilities != null, se.getTimestamp(), stAb, false);
               affectedCard.updateKeywordsCache();
            }

            if (!addHiddenKeywords.isEmpty()) {
               affectedCard.addHiddenExtrinsicKeywords(se.getTimestamp(), (long)stAb.getId(), addHiddenKeywords);
            }

            if (addSVars != null) {
               Map<String, String> map = Maps.newHashMap();

               for(String sVar : addSVars) {
                  String actualSVar = AbilityUtils.getSVar(stAb, sVar);
                  String name = sVar;
                  if (actualSVar.startsWith("SVar:")) {
                     actualSVar = actualSVar.split("SVar:")[1];
                     name = actualSVar.split(":")[0];
                     actualSVar = actualSVar.split(":")[1];
                  }

                  map.put(name, actualSVar);
               }

               affectedCard.addChangedSVars(map, se.getTimestamp(), (long)stAb.getId());
            }

            if (layer == StaticAbilityLayer.ABILITIES) {
               List<SpellAbility> addedAbilities = Lists.newArrayList();
               List<ReplacementEffect> addedReplacementEffects = Lists.newArrayList();
               List<Trigger> addedTrigger = Lists.newArrayList();
               List<StaticAbility> addedStaticAbility = Lists.newArrayList();
               if (addAbilities != null) {
                  for(String ability : addAbilities) {
                     if (ability.contains("CardManaCost")) {
                        ability = TextUtil.fastReplace(ability, "CardManaCost", affectedCard.getManaCost().getShortString());
                     } else if (ability.contains("ConvertedManaCost")) {
                        String costcmc = Integer.toString(affectedCard.getCMC());
                        ability = TextUtil.fastReplace(ability, "ConvertedManaCost", costcmc);
                     }

                     addedAbilities.add(affectedCard.getSpellAbilityForStaticAbility(ability, stAb));
                  }
               }

               if (params.containsKey("GainsAbilitiesOf") || params.containsKey("GainsAbilitiesOfDefined")) {
                  for(Card c : cardsGainedFrom(params.containsKey("GainsAbilitiesOfDefined") ? "GainsAbilitiesOfDefined" : "GainsAbilitiesOf", params, hostCard, stAb, game)) {
                     for(SpellAbility sa : c.getSpellAbilities()) {
                        if (sa.isActivatedAbility() && stAb.matchesValidParam("GainsValidAbilities", sa)) {
                           SpellAbility newSA = sa.copy(affectedCard, sa.getActivatingPlayer(), false, true);
                           if (params.containsKey("GainsAbilitiesLimitPerTurn")) {
                              newSA.setRestrictions(sa.getRestrictions());
                              newSA.getRestrictions().setLimitToCheck((String)params.get("GainsAbilitiesLimitPerTurn"));
                           }

                           newSA.setOriginalAbility(sa);
                           newSA.setGrantorStatic(stAb);
                           newSA.setIntrinsic(false);
                           addedAbilities.add(newSA);
                        }
                     }
                  }
               }

               if (addReplacements != null) {
                  for(String rep : addReplacements) {
                     addedReplacementEffects.add(affectedCard.getReplacementEffectForStaticAbility(rep, stAb));
                  }
               }

               if (addTriggers != null) {
                  for(String trigger : addTriggers) {
                     addedTrigger.add(affectedCard.getTriggerForStaticAbility(trigger, stAb));
                  }
               }

               if (params.containsKey("GainsTriggerAbsOf")) {
                  for(Card c : cardsGainedFrom("GainsTriggerAbsOf", params, hostCard, stAb, game)) {
                     for(Trigger trig : c.getTriggers()) {
                        Trigger newTrigger = affectedCard.addTriggerForStaticAbility(trig, stAb);
                        if (newTrigger.getKeyword() != null) {
                           newTrigger.removeParam("Secondary");
                        }

                        addedTrigger.add(newTrigger);
                     }
                  }
               }

               if (addStatics != null) {
                  for(String s : addStatics) {
                     if (s.contains("ConvertedManaCost")) {
                        String costcmc = Integer.toString(affectedCard.getCMC());
                        s = TextUtil.fastReplace(s, "ConvertedManaCost", costcmc);
                     }

                     addedStaticAbility.add(affectedCard.getStaticAbilityForStaticAbility(s, stAb));
                  }
               }

               if (!addedAbilities.isEmpty() || !addedTrigger.isEmpty() || addReplacements != null || addStatics != null || removeAbilities != null) {
                  affectedCard.addChangedCardTraits(addedAbilities, addedTrigger, addedReplacementEffects, addedStaticAbility, removeAbilities, se.getTimestamp(), (long)stAb.getId(), false);
               }

               if (cantHaveKeyword != null) {
                  affectedCard.addCantHaveKeyword((Long)se.getTimestamp(), (Iterable)cantHaveKeyword);
               }
            }

            if (addTypes != null && !addTypes.isEmpty() || removeTypes != null && !removeTypes.isEmpty() || addAllCreatureTypes || !remove.isEmpty()) {
               affectedCard.addChangedCardTypes(addTypes != null ? new CardType(addTypes, true) : null, removeTypes != null ? new CardType(removeTypes, true) : null, addAllCreatureTypes, remove, se.getTimestamp(), (long)stAb.getId(), false, stAb.isCharacteristicDefining());
            }

            if (addColors != null) {
               affectedCard.addColor(addColors, !overwriteColors, se.getTimestamp(), stAb);
            }

            if (layer == StaticAbilityLayer.RULES) {
               if (params.containsKey("Goad")) {
                  affectedCard.addGoad(se.getTimestamp(), hostCard.getController());
               }

               if (params.containsKey("CanBlockAny")) {
                  affectedCard.addCanBlockAny(se.getTimestamp());
               }

               if (params.containsKey("CanBlockAmount")) {
                  int v = AbilityUtils.calculateAmount(hostCard, (String)params.get("CanBlockAmount"), stAb, true);
                  affectedCard.addCanBlockAdditional(v, se.getTimestamp());
               }

               if (params.containsKey("LethalDamageByPower")) {
                  affectedCard.addLethalDamageByPower(se.getTimestamp());
               }
            }

            if (controllerMayPlay && (mayPlayLimit == null || stAb.getMayPlayTurn() < mayPlayLimit)) {
               String mayPlayAltCost = mayPlayAltManaCost;
               if (mayPlayAltManaCost != null && mayPlayAltManaCost.contains("ConvertedManaCost")) {
                  String costcmc = Integer.toString(affectedCard.getCMC());
                  mayPlayAltCost = mayPlayAltManaCost.replace("ConvertedManaCost", costcmc);
               }

               Player mayPlayController = params.containsKey("MayPlayPlayer") ? (Player)AbilityUtils.getDefinedPlayers(affectedCard, (String)params.get("MayPlayPlayer"), stAb).get(0) : controller;
               affectedCard.setMayPlay(mayPlayController, mayPlayWithoutManaCost, mayPlayAltCost != null ? new Cost(mayPlayAltCost, false, affectedCard.equals(hostCard)) : null, mayPlayWithFlash, mayPlayGrantZonePermissions, stAb);
               if (mayLookAt != null && mayLookAt.isEmpty()) {
                  mayLookAt.add(mayPlayController);
               }

               if (stAb.hasParam("Affected") && stAb.getParam("Affected").equals("Card.Self") && affectedCard.isInZone(ZoneType.Graveyard)) {
                  for(Player p : game.getPlayers()) {
                     if (p.hasKeyword("Shaman's Trance") && mayPlayController != p) {
                        affectedCard.setMayPlay(p, mayPlayWithoutManaCost, mayPlayAltCost != null ? new Cost(mayPlayAltCost, false) : null, mayPlayWithFlash, mayPlayGrantZonePermissions, stAb);
                     }
                  }
               }
            }

            if (mayLookAt != null && (!affectedCard.getOwner().getTopXCardsFromLibrary(1).contains(affectedCard) || game.getTopLibForPlayer(affectedCard.getOwner()) == null || game.getTopLibForPlayer(affectedCard.getOwner()) == affectedCard)) {
               affectedCard.addMayLookAt(se.getTimestamp(), mayLookAt);
            }
         }

         return affectedCards;
      }
   }

   private static ColorSet getColorsFromParam(StaticAbility stAb, String colors) {
      Card hostCard = stAb.getHostCard();
      ColorSet addColors = null;
      if (colors.equals("ChosenColor")) {
         if (hostCard.hasChosenColor()) {
            addColors = ColorSet.fromNames(hostCard.getChosenColors());
         }
      } else if (colors.equals("All")) {
         addColors = ColorSet.WUBRG;
      } else {
         addColors = ColorSet.fromNames(colors.split(" & "));
      }

      return addColors;
   }

   private static void buildIgnoreEffectAbility(final StaticAbility stAb, String costString, List<Player> players, final CardCollectionView cards) {
      final List<Player> validActivator = new ArrayList(players);

      for(Card c : cards) {
         validActivator.add(c.getController());
      }

      final Card sourceCard = stAb.getHostCard();
      Cost cost = new Cost(costString, true);
      AbilityStatic addIgnore = new AbilityStatic(sourceCard, cost, (TargetRestrictions)null) {
         public void resolve() {
            stAb.addIgnoreEffectPlayers(this.getActivatingPlayer());
            stAb.setIgnoreEffectCards(cards);
         }

         public boolean canPlay() {
            return validActivator.contains(this.getActivatingPlayer()) && sourceCard.isInPlay();
         }
      };
      addIgnore.setIntrinsic(false);
      addIgnore.setApi(ApiType.InternalIgnoreEffect);
      addIgnore.setDescription(String.valueOf(cost) + " Ignore the effect until end of turn.");
      sourceCard.addChangedCardTraits(List.of(addIgnore), (Collection)null, (Collection)null, (Collection)null, (Predicate)null, sourceCard.getLayerTimestamp(), (long)stAb.getId());
      GameCommand removeIgnore = new GameCommand() {
         private static final long serialVersionUID = -5415775215053216360L;

         public void run() {
            stAb.clearIgnoreEffects();
         }
      };
      sourceCard.getGame().getEndOfTurn().addUntil(removeIgnore);
      sourceCard.addLeavesPlayCommand(removeIgnore);
   }

   private static CardCollection cardsGainedFrom(String param, Map<String, String> params, Card hostCard, StaticAbility stAb, Game game) {
      CardCollection cards = new CardCollection();
      if (param.contains("Defined")) {
         cards.addAll(AbilityUtils.getDefinedCards(hostCard, (String)params.get(param), stAb));
      } else {
         String[] valids = ((String)params.get(param)).split(",");
         List<ZoneType> validZones;
         if (params.containsKey("GainsAbilitiesOfZones")) {
            validZones = ZoneType.listValueOf((String)params.get("GainsAbilitiesOfZones"));
         } else {
            validZones = List.of(ZoneType.Battlefield);
         }

         cards.addAll(CardLists.getValidCards(game.getCardsIn((Iterable)validZones), (String[])valids, hostCard.getController(), hostCard, stAb));
      }

      return cards;
   }

   private static List<Player> getAffectedPlayers(StaticAbility stAb) {
      Map<String, String> params = stAb.getMapParams();
      Card hostCard = stAb.getHostCard();
      Player controller = hostCard.getController();
      List<Player> players = new ArrayList();
      if (!params.containsKey("Affected")) {
         return players;
      } else {
         String[] strngs = ((String)params.get("Affected")).split(",");

         for(Player p : controller.getGame().getPlayersInTurnOrder()) {
            if (p.isValid(strngs, controller, hostCard, stAb)) {
               players.add(p);
            }
         }

         players.removeAll(stAb.getIgnoreEffectPlayers());
         return players;
      }
   }

   public static CardCollectionView getAffectedCards(StaticAbility stAb, CardCollectionView preList) {
      Card hostCard = stAb.getHostCard();
      Game game = hostCard.getGame();
      Player controller = hostCard.getController();
      if (stAb.isCharacteristicDefining()) {
         if (stAb.hasParam("ExcludeZone")) {
            for(ZoneType zt : ZoneType.listValueOf(stAb.getParam("ExcludeZone"))) {
               if (hostCard.isInZone(zt)) {
                  return CardCollection.EMPTY;
               }
            }
         }

         return new CardCollection(hostCard);
      } else if (!forge.game.card.TraitEpoch.DISABLED && !stAb.hasParam("AffectedDefined") && stAb.hasParam("Affected") && !(controller.hasKeyword("Shaman's Trance") && stAb.hasParam("MayPlay"))) {
         return novaAffectedCards(stAb, preList, game, hostCard, controller);
      } else {
         CardCollection affectedCards = new CardCollection();
         CardCollection definedCards = null;
         if (stAb.hasParam("AffectedDefined")) {
            definedCards = AbilityUtils.getDefinedCards(hostCard, stAb.getParam("AffectedDefined"), stAb).filter(CardPredicates.phasedIn());
         }

         if (!preList.isEmpty()) {
            if (stAb.hasParam("AffectedDefined")) {
               affectedCards.addAll(preList);
               affectedCards.retainAll(definedCards);
            } else if (stAb.hasParam("AffectedZone")) {
               affectedCards.addAll(CardLists.filter(preList, CardPredicates.inZone((Iterable)ZoneType.listValueOf(stAb.getParam("AffectedZone")))));
            } else {
               affectedCards.addAll(CardLists.filter(preList, CardPredicates.inZone(ZoneType.Battlefield)));
            }
         }

         if (stAb.hasParam("AffectedDefined")) {
            affectedCards.addAll(definedCards);
         } else if (stAb.hasParam("AffectedZone")) {
            affectedCards.addAll(game.getCardsIn((Iterable)ZoneType.listValueOf(stAb.getParam("AffectedZone"))));
         } else {
            affectedCards.addAll(game.getCardsIn(ZoneType.Battlefield));
         }

         if (stAb.hasParam("Affected")) {
            CardCollection affectedCardsOriginal = null;
            if (controller.hasKeyword("Shaman's Trance") && stAb.hasParam("MayPlay")) {
               affectedCardsOriginal = new CardCollection(affectedCards);
            }

            affectedCards = CardLists.getValidCards(affectedCards, (String)stAb.getParam("Affected"), controller, hostCard, stAb);
            if (affectedCardsOriginal != null) {
               String affectedParam = stAb.getParam("Affected");
               affectedParam = affectedParam.replaceAll("[\\.\\+]YouOwn", "");
               affectedParam = affectedParam.replaceAll("[\\.\\+]YouCtrl", "");
               String[] restrictions = affectedParam.split(",");

               for(Card card : affectedCardsOriginal) {
                  if (card.isInZone(ZoneType.Graveyard) && card.getController() != controller && card.isValid(restrictions, controller, hostCard, stAb)) {
                     affectedCards.add(card);
                  }
               }
            }
         }

         affectedCards.removeAll(stAb.getIgnoreEffectCards());
         return affectedCards;
      }
   }

   /**
    * Forge Nova: getAffectedCards without AffectedDefined, with Affected and without the Shaman's Trance case.
    * Forge first collects the candidates into a de-duplicated CardCollection (preList cards in the affected zones,
    * then game.getCardsIn(zones), itself a de-duplicated copy of each player's cards), then keeps those passing
    * Affected in a new CardCollection built card by card from a lazy filter, then removes the ignore-effect cards.
    * The candidates below are the same cards in the same order (first occurrence of each, equality as in
    * CardCollection), tested with the same predicate in the same order, and the result is built by the same add
    * calls; only the two discarded intermediate collections (and their hash sets) are no longer built.
    */
   private static CardCollectionView novaAffectedCards(StaticAbility stAb, CardCollectionView preList, Game game, Card hostCard, Player controller) {
      // Forge Nova: within a layer from COLOR on, a layer-stable restriction has the same answer (NovaLayerMemo)
      final boolean memo = NovaLayerMemo.active() && forge.game.card.NovaLayerStable.isStable(stAb.getParam("Affected"));
      if (memo) {
         Card[] known = NovaLayerMemo.get(stAb, preList);
         if (known != null) {
            CardCollection affectedCards = new CardCollection();
            for(Card c : known) {
               affectedCards.add(c);
            }
            if (forge.game.card.TraitEpoch.VERIFY) {
               CardCollectionView fresh = novaAffectedCardsOriginal(stAb, preList, game, hostCard, controller);
               if (!forge.game.card.TraitEpoch.sameElements(affectedCards, fresh)) {
                  forge.game.card.TraitEpoch.mismatch("layerMemo", stAb, forge.game.card.TraitEpoch.toList(affectedCards), forge.game.card.TraitEpoch.toList(fresh));
               }
            }
            affectedCards.removeAll(stAb.getIgnoreEffectCards());
            return affectedCards;
         }
      }

      NovaCardSeq candidates = new NovaCardSeq();
      boolean hasZone = stAb.hasParam("AffectedZone");
      if (!preList.isEmpty()) {
         Predicate<Card> inZone = hasZone ? CardPredicates.inZone((Iterable)ZoneType.listValueOf(stAb.getParam("AffectedZone"))) : CardPredicates.inZone(ZoneType.Battlefield);
         for(Card c : preList) {
            if (inZone.test(c)) {
               candidates.add(c);
            }
         }
      }

      if (hasZone) {
         // Game.getCardsIn(Iterable<ZoneType>)
         for(ZoneType z : ZoneType.listValueOf(stAb.getParam("AffectedZone"))) {
            if (z == ZoneType.Stack) {
               for(Card c : game.getStackZone().getCards()) {
                  candidates.add(c);
               }
            } else {
               for(Player p : game.getPlayers()) {
                  for(Card c : p.getCardsIn(z)) {
                     candidates.add(c);
                  }
               }
            }
         }
      } else {
         // Game.getCardsIn(ZoneType.Battlefield) = PlayerCollection.getCardsIn
         for(Player p : game.getPlayers()) {
            for(Card c : p.getCardsIn(ZoneType.Battlefield)) {
               candidates.add(c);
            }
         }
      }

      Predicate<Card> valid = CardPredicates.restriction(stAb.getParam("Affected").split(","), controller, hostCard, stAb);
      CardCollection affectedCards = new CardCollection();
      for(int i = 0; i < candidates.size(); ++i) {
         Card c = candidates.get(i);
         if (valid.test(c)) {
            affectedCards.add(c);
         }
      }

      if (forge.game.card.TraitEpoch.VERIFY) {
         CardCollectionView fresh = novaAffectedCardsOriginal(stAb, preList, game, hostCard, controller);
         if (!forge.game.card.TraitEpoch.sameElements(affectedCards, fresh)) {
            forge.game.card.TraitEpoch.mismatch("affectedCards", stAb, forge.game.card.TraitEpoch.toList(affectedCards), forge.game.card.TraitEpoch.toList(fresh));
         }
      }

      if (memo) {
         NovaLayerMemo.put(stAb, preList, affectedCards.toArray(new Card[0]));
      }

      affectedCards.removeAll(stAb.getIgnoreEffectCards());
      return affectedCards;
   }

   /** Forge Nova: the original computation of the same case, for verification (before removeAll). */
   private static CardCollectionView novaAffectedCardsOriginal(StaticAbility stAb, CardCollectionView preList, Game game, Card hostCard, Player controller) {
      CardCollection affectedCards = new CardCollection();
      if (!preList.isEmpty()) {
         if (stAb.hasParam("AffectedZone")) {
            affectedCards.addAll(CardLists.filter(preList, CardPredicates.inZone((Iterable)ZoneType.listValueOf(stAb.getParam("AffectedZone")))));
         } else {
            affectedCards.addAll(CardLists.filter(preList, CardPredicates.inZone(ZoneType.Battlefield)));
         }
      }

      if (stAb.hasParam("AffectedZone")) {
         affectedCards.addAll(game.getCardsIn((Iterable)ZoneType.listValueOf(stAb.getParam("AffectedZone"))));
      } else {
         affectedCards.addAll(game.getCardsIn(ZoneType.Battlefield));
      }

      return CardLists.getValidCards(affectedCards, (String)stAb.getParam("Affected"), controller, hostCard, stAb);
   }

   /** Forge Nova: a list of cards keeping the first of equal cards (Card.equals: same id and class), like CardCollection.add. */
   private static final class NovaCardSeq {
      private Card[] items = new Card[64];
      private int size;
      private int[] slots = new int[128]; // open addressing on card id; stores index+1, 0 = empty
      private int mask = 127;

      int size() {
         return this.size;
      }

      Card get(int i) {
         return this.items[i];
      }

      void add(Card c) {
         if (c == null) {
            return; // CardCollection.add ignores null
         }
         int h = mix(c.getId()) & this.mask;
         while (this.slots[h] != 0) {
            Card e = this.items[this.slots[h] - 1];
            if (e.equals(c)) {
               return;
            }
            h = h + 1 & this.mask;
         }
         if (this.size == this.items.length) {
            this.items = Arrays.copyOf(this.items, this.size * 2);
         }
         this.items[this.size++] = c;
         this.slots[h] = this.size;
         if (this.size * 2 > this.mask) {
            this.rehash();
         }
      }

      private void rehash() {
         this.slots = new int[this.slots.length * 2];
         this.mask = this.slots.length - 1;
         for(int i = 0; i < this.size; ++i) {
            int h = mix(this.items[i].getId()) & this.mask;
            while (this.slots[h] != 0) {
               h = h + 1 & this.mask;
            }
            this.slots[h] = i + 1;
         }
      }

      private static int mix(int x) {
         x *= -1640531535;
         return x ^ x >>> 16;
      }
   }
}
