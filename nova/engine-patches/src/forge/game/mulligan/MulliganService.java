package forge.game.mulligan;

import com.google.common.collect.Lists;
import forge.MulliganDefs;
import forge.StaticData;
import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.event.GameEventAddLog;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MulliganService {
   Player firstPlayer;
   Game game;
   List<AbstractMulligan> mulligans = Lists.newArrayList();
   /** Forge Nova: the players who took the house rule's free mulligan this game */
   private final Set<Player> novaFreeMulliganUsed = new HashSet<>();

   public MulliganService(Player player) {
      this.firstPlayer = player;
      this.game = this.firstPlayer.getGame();
   }

   public void perform() {
      this.initializeMulligans();
      this.runPlayerMulligans();
      this.runPostMulligans();
   }

   private void initializeMulligans() {
      List<Player> whoCanMulligan = Lists.newArrayList(this.game.getPlayers());
      int offset = whoCanMulligan.indexOf(this.firstPlayer);

      for(int i = 0; i < offset; ++i) {
         whoCanMulligan.add((Player)whoCanMulligan.remove(0));
      }

      boolean firstMullFree = this.game.getPlayers().size() > 2 || this.game.getRules().hasAppliedVariant(GameType.Brawl);

      for(Player player : whoCanMulligan) {
         MulliganDefs.MulliganRule rule = StaticData.instance().getMulliganRule();
         AbstractMulligan mulligan;
         switch (rule) {
            case Original -> mulligan = new OriginalMulligan(player, firstMullFree);
            case Paris -> mulligan = new ParisMulligan(player, firstMullFree);
            case Vancouver -> mulligan = new VancouverMulligan(player, firstMullFree);
            case London -> mulligan = new LondonMulligan(player, firstMullFree);
            case Houston -> mulligan = new HoustonMulligan(player, firstMullFree);
            default -> mulligan = new VancouverMulligan(player, firstMullFree);
         }

         this.mulligans.add(mulligan);
         mulligan.beforeFirstMulligan();
      }

   }

   private void runPlayerMulligans() {
      boolean allKept;
      do {
         allKept = true;

         for(AbstractMulligan mulligan : this.mulligans) {
            if (!mulligan.hasKept()) {
               Player p = mulligan.getPlayer();
               boolean keep = !mulligan.canMulligan() || p.getController().mulliganKeepHand(this.firstPlayer, mulligan.tuckCardsDuringMulligan());
               if (this.game.isGameOver()) {
                  return;
               }

               if (keep) {
                  mulligan.keep();
               } else {
                  allKept = false;
                  if (this.novaFreeMulligan(p)) {
                     // mulligan() counts this one again: the count, and so the cards to put back, stays the same
                     --mulligan.timesMulliganed;
                  }

                  mulligan.mulligan();
               }
            }
         }
      } while(!allKept);

   }

   /**
    * Forge Nova house rule (on while the system property nova.houseRule.freeMulligan is "true"): the first time a player
    * sends back a hand with no lands or with seven lands, that mulligan is free. Once per player per game.
    */
   private boolean novaFreeMulligan(Player p) {
      if (!Boolean.getBoolean("nova.houseRule.freeMulligan") || this.novaFreeMulliganUsed.contains(p)) {
         return false;
      } else {
         int cards = 0;
         int lands = 0;

         for(Card c : p.getCardsIn(ZoneType.Hand)) {
            ++cards;
            if (c.isLand()) {
               ++lands;
            }
         }

         if (cards != 0 && (lands == 0 || lands >= 7)) {
            this.novaFreeMulliganUsed.add(p);
            this.game.fireEvent(new GameEventAddLog(GameLogEntryType.MULLIGAN, p.getName() + " takes a free mulligan (house rule: a hand with " + (lands == 0 ? "no lands" : "seven lands") + ")."));
            return true;
         } else {
            return false;
         }
      }
   }

   private void runPostMulligans() {
      for(AbstractMulligan mulligan : this.mulligans) {
         mulligan.afterMulligan();
      }

   }
}
