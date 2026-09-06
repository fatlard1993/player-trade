package justfatlard.player_trade.trade;

import net.minecraft.util.Prediction;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.player_trade.PlayerTrade;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public class TradeManager {
    private static TradeManager instance;
    private final Map<UUID, TradeSession> activeTrades = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToSession = new ConcurrentHashMap<>();
    private final Map<UUID, TradeRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Map<UUID, List<ItemStack>> pendingServerItems = new ConcurrentHashMap<>();
    /** When each player last had a request actually go out; see {@link #tooSoon}. */
    private final Map<UUID, Long> lastRequestSent = new ConcurrentHashMap<>();

    /** How long after sending one request a player must wait before sending another to anybody. */
    private static final long REQUEST_COOLDOWN_MS = 3000L;

    public static TradeManager getInstance() {
        if (instance == null) {
            instance = new TradeManager();
        }
        return instance;
    }

    public boolean isInTrade(UUID playerId) {
        return this.playerToSession.containsKey(playerId);
    }

    public TradeSession getTradeSession(UUID playerId) {
        UUID sessionId = this.playerToSession.get(playerId);
        return sessionId == null ? null : this.activeTrades.get(sessionId);
    }

    public void sendTradeRequest(ServerPlayer sender, ServerPlayer target) {
        UUID senderId = sender.getUUID();
        UUID targetId = target.getUUID();
        if (this.isInTrade(senderId)) {
            sender.sendSystemMessage(Component.translatable("player-trade.chat.already_trading").withStyle(ChatFormatting.RED));
        } else if (this.isInTrade(targetId)) {
            sender.sendSystemMessage(
                Component.translatable("player-trade.chat.player_busy", target.getName()).withStyle(ChatFormatting.RED)
            );
        } else if (this.offeredUs(senderId, targetId)) {
            // They asked first and we asked back: that is agreement, not a second offer. Two
            // people reaching for the same thing at once is the ordinary way this happens - the
            // request has a thirty second life and a chat button that is easy to miss - and the
            // old behaviour left each of them holding an unanswered offer from the other,
            // waiting on somebody who was already waiting on them.
            this.pendingRequests.remove(senderId);
            sender.sendSystemMessage(
                Component.translatable("player-trade.chat.mutual_accept", target.getName())
                    .withStyle(ChatFormatting.GREEN)
            );
            target.sendSystemMessage(
                Component.translatable("player-trade.chat.mutual_accept", sender.getName())
                    .withStyle(ChatFormatting.GREEN)
            );
            // Target first: they are the one whose request is being answered, which is the order
            // accepting from chat would have produced
            this.startTrade(target, sender);
        } else if (this.alreadyAsked(senderId, targetId)) {
            // The request is already standing. Shift-click is a gesture people repeat without
            // meaning to - it is half the controls in the game - and every repeat used to put
            // another line in the target's chat. Said on the action bar rather than in chat, so
            // a fistful of clicks does not fill the clicker's own log either.
            sender.sendSystemMessage(
                Component.translatable("player-trade.chat.request_pending",
                    target.getName(), this.secondsLeft(targetId)).withStyle(ChatFormatting.GRAY),
                true
            );
        } else if (this.tooSoon(senderId)) {
            // And the same click aimed around a room: the rule above allows one request per
            // target at a time, this is the one that stops a crowd being worked through at
            // click speed. Never reached by the accept branch above - answering somebody who
            // asked you is not spam, whatever you were clicking a moment ago.
            sender.sendSystemMessage(
                Component.translatable("player-trade.chat.request_too_fast")
                    .withStyle(ChatFormatting.GRAY),
                true
            );
        } else {
            TradeRequest request = new TradeRequest(senderId, targetId, System.currentTimeMillis());
            this.pendingRequests.put(targetId, request);
            this.lastRequestSent.put(senderId, request.timestamp());
            sender.sendSystemMessage(
                Component.translatable("player-trade.chat.request_sent", target.getName()).withStyle(ChatFormatting.GREEN)
            );
            Component acceptButton = Component.translatable("player-trade.chat.accept_button")
                .setStyle(
                    Style.EMPTY
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/trade accept " + sender.getName().getString()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to accept trade")))
                );
            Component message = Component.translatable("player-trade.chat.trade_request", sender.getName())
                .withStyle(ChatFormatting.YELLOW)
                .append(acceptButton);
            target.sendSystemMessage(message);
        }
    }

    /**
     * Whether {@code target} has a live request in to {@code sender} right now.
     *
     * <p>Requests are keyed by who they are addressed to, so ours is the one filed under our own
     * id. An expired one does not count and is left where it is for the sweeper; the server's own
     * offers never match, since {@link TradeSession#SERVER_UUID} is nobody's player id, so
     * offering a trade to someone cannot swallow a gift the server is holding out to you.
     */
    private boolean offeredUs(UUID senderId, UUID targetId) {
        TradeRequest incoming = this.pendingRequests.get(senderId);
        return incoming != null && !incoming.isExpired() && incoming.senderId().equals(targetId);
    }

    /** Whether the request we are about to send is one we already have standing to this player. */
    private boolean alreadyAsked(UUID senderId, UUID targetId) {
        TradeRequest standing = this.pendingRequests.get(targetId);
        return standing != null && !standing.isExpired() && standing.senderId().equals(senderId);
    }

    /** Seconds left on the request standing against this target, for telling the sender to wait. */
    private long secondsLeft(UUID targetId) {
        TradeRequest standing = this.pendingRequests.get(targetId);
        if (standing == null) return 0;
        long remaining = standing.timestamp() + TradeRequest.EXPIRATION_MS - System.currentTimeMillis();
        return Math.max(1, (remaining + 999) / 1000);
    }

    /**
     * Whether this player has sent a request too recently to send another.
     *
     * <p>Only bites across different targets, because a repeat at the same one is already stopped
     * by {@link #alreadyAsked}. Short on purpose: it is here to stop a room being worked through
     * at the speed a mouse can be clicked, not to make asking twice a punishment.
     */
    private boolean tooSoon(UUID senderId) {
        Long last = this.lastRequestSent.get(senderId);
        return last != null && System.currentTimeMillis() - last < REQUEST_COOLDOWN_MS;
    }

    public void acceptTradeRequest(ServerPlayer acceptor, String senderName, MinecraftServer server) {
        UUID acceptorId = acceptor.getUUID();
        TradeRequest request = this.pendingRequests.get(acceptorId);
        if (request != null && !request.isExpired()) {
            if (request.senderId().equals(TradeSession.SERVER_UUID) && senderName.equalsIgnoreCase("Server")) {
                this.acceptServerTradeRequest(acceptor, server);
            } else {
                ServerPlayer sender = server.getPlayerList().getPlayer(request.senderId());
                if (sender == null) {
                    acceptor.sendSystemMessage(Component.translatable("player-trade.chat.request_expired").withStyle(ChatFormatting.RED));
                    this.pendingRequests.remove(acceptorId);
                } else if (sender.getName().getString().equalsIgnoreCase(senderName)) {
                    if (!this.isInTrade(acceptorId) && !this.isInTrade(request.senderId())) {
                        this.pendingRequests.remove(acceptorId);
                        this.startTrade(sender, acceptor);
                    } else {
                        acceptor.sendSystemMessage(
                            Component.translatable("player-trade.chat.player_busy", sender.getName()).withStyle(ChatFormatting.RED)
                        );
                        this.pendingRequests.remove(acceptorId);
                    }
                }
            }
        } else {
            acceptor.sendSystemMessage(Component.translatable("player-trade.chat.request_expired").withStyle(ChatFormatting.RED));
            this.pendingRequests.remove(acceptorId);
        }
    }

    public void startTrade(ServerPlayer player1, ServerPlayer player2) {
        TradeSession session = new TradeSession(player1.getUUID(), player2.getUUID());
        this.activeTrades.put(session.getSessionId(), session);
        this.playerToSession.put(player1.getUUID(), session.getSessionId());
        this.playerToSession.put(player2.getUUID(), session.getSessionId());
        PlayerTrade.openTradeScreen(player1, player2.getName(), session);
        PlayerTrade.openTradeScreen(player2, player1.getName(), session);
    }

    /**
     * Drop a session from the active maps.
     *
     * <p>Must run before any closeContainer() on a participant. Closing the menu fires
     * Pandorical's container-removed and close callbacks, and those look the player's session up
     * and cancel whatever they find - so a session still registered at close time gets cancelled
     * by the very act of finishing it, and the player is told the trade completed and then that it
     * was cancelled.
     */
    private void endSession(TradeSession session) {
        this.activeTrades.remove(session.getSessionId());
        this.playerToSession.remove(session.getPlayer1Id());
        this.playerToSession.remove(session.getPlayer2Id());
    }

    public void cancelTrade(UUID playerId, MinecraftServer server) {
        TradeSession session = this.getTradeSession(playerId);
        if (session != null) {
            if (session.isServerTrade()) {
                this.cancelServerTrade(playerId, server);
            } else {
                this.endSession(session);
                ServerPlayer player1 = server.getPlayerList().getPlayer(session.getPlayer1Id());
                ServerPlayer player2 = server.getPlayerList().getPlayer(session.getPlayer2Id());
                if (player1 != null) {
                    session.returnItemsToPlayer(player1);
                    player1.closeContainer();
                    player1.sendSystemMessage(Component.translatable("player-trade.chat.trade_cancelled").withStyle(ChatFormatting.RED));
                }
                if (player2 != null) {
                    session.returnItemsToPlayer(player2);
                    player2.closeContainer();
                    player2.sendSystemMessage(Component.translatable("player-trade.chat.trade_cancelled").withStyle(ChatFormatting.RED));
                }
            }
        }
    }

    /**
     * Handle a participant disconnecting mid-trade. Called from
     * ServerPlayConnectionEvents.DISCONNECT, which fires while the disconnecting ServerPlayer is
     * still fully in-world and before its data is saved (see PlayerTrade.registerDisconnectHandler).
     *
     * Cancels the disconnecting player's active trade and returns ALL escrowed items to BOTH
     * parties so nothing is destroyed: the leaving player's items go back to its own (still-valid,
     * about-to-be-saved) inventory via the direct player reference, and the counterparty (still
     * online) gets its items back too. returnItemsToPlayer falls back to dropping at the player's
     * position if the inventory is full, so no path can lose items.
     */
    /**
     * Hand a dying player their escrow back before the world takes their pockets.
     *
     * <p>Dying mid-trade destroyed the escrow outright. The screen closing on death ran the same
     * teardown a cancel does, which puts the offered items into the player's inventory - and that
     * inventory was on its way to being emptied onto the ground and replaced. The items went into
     * a corpse and never came out.
     *
     * <p>Called before the death is processed, so the escrow is in the player's own inventory by
     * the time the game decides what happens to it: it drops with everything else they were
     * carrying, or survives with them under keepInventory. Either way it obeys the same rule as the
     * rest of their things, which is the only answer that will not surprise anybody.
     *
     * <p>The counterparty, who did not die, simply gets theirs back as with any other cancel.
     */
    public void handleDeath(ServerPlayer player, MinecraftServer server) {
        // Same shape as a disconnect: tear the session down before returning anything, so nothing
        // can re-enter through the screen closing a moment later and hand the escrow out twice.
        this.handleDisconnect(player, server);
    }

    public void handleDisconnect(ServerPlayer player, MinecraftServer server) {
        UUID playerId = player.getUUID();
        // Above the session check, which returns early for the great majority of disconnects:
        // nothing else prunes this map, and a leaving player is owed no cooldown anyway
        this.lastRequestSent.remove(playerId);
        TradeSession session = this.getTradeSession(playerId);
        if (session == null) {
            return;
        }
        if (session.isServerTrade()) {
            // Server trades: only player1 is a real player. Tear down and return its escrow
            // directly (normally empty, since the player's own slots are read-only in a gift trade).
            this.endSession(session);
            session.returnItemsToPlayer(player);
            return;
        }
        // Player-to-player trade: tear down mappings first so no further action re-enters.
        this.endSession(session);

        // Return the disconnecting player's escrow using its still-valid direct reference,
        // rather than a player-list lookup (which is being torn down as part of this disconnect).
        session.returnItemsToPlayer(player);

        // Return the counterparty's escrow; they are still online.
        UUID otherId = session.getOtherPlayerId(playerId);
        ServerPlayer other = (otherId == null) ? null : server.getPlayerList().getPlayer(otherId);
        if (other != null) {
            session.returnItemsToPlayer(other);
            other.closeContainer();
            other.sendSystemMessage(Component.translatable("player-trade.chat.trade_cancelled").withStyle(ChatFormatting.RED));
        }
    }

    public boolean completeTrade(UUID playerId, MinecraftServer server) {
        TradeSession session = this.getTradeSession(playerId);
        if (session == null || !session.bothAccepted()) {
            return false;
        } else if (session.isServerTrade()) {
            return this.completeServerTrade(playerId, server);
        } else {
            ServerPlayer player1 = server.getPlayerList().getPlayer(session.getPlayer1Id());
            ServerPlayer player2 = server.getPlayerList().getPlayer(session.getPlayer2Id());
            if (player1 == null || player2 == null) {
                this.cancelTrade(playerId, server);
                return false;
            } else if (this.canReceiveItems(player1, session.getOtherPlayerOffer(player1.getUUID()))
                && this.canReceiveItems(player2, session.getOtherPlayerOffer(player2.getUUID()))) {
                this.transferItems(player1, session.getOtherPlayerOffer(player1.getUUID()));
                this.transferItems(player2, session.getOtherPlayerOffer(player2.getUUID()));

                for (int i = 0; i < 9; i++) {
                    session.getPlayerOffer(player1.getUUID()).set(i, ItemStack.EMPTY);
                    session.getPlayerOffer(player2.getUUID()).set(i, ItemStack.EMPTY);
                }

                this.endSession(session);
                player1.sendSystemMessage(Component.translatable("player-trade.chat.trade_complete").withStyle(ChatFormatting.GREEN));
                player2.sendSystemMessage(Component.translatable("player-trade.chat.trade_complete").withStyle(ChatFormatting.GREEN));
                player1.closeContainer();
                player2.closeContainer();
                return true;
            } else {
                player1.sendSystemMessage(Component.translatable("player-trade.chat.inventory_full").withStyle(ChatFormatting.RED));
                player2.sendSystemMessage(Component.translatable("player-trade.chat.inventory_full").withStyle(ChatFormatting.RED));
                return false;
            }
        }
    }

    private boolean canReceiveItems(ServerPlayer player, List<ItemStack> items) {
        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i).isEmpty()) {
                emptySlots++;
            }
        }

        int nonEmptyItems = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                nonEmptyItems++;
            }
        }
        return emptySlots >= nonEmptyItems;
    }

    private void transferItems(ServerPlayer player, List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && !player.getInventory().add(stack.copy())) {
                player.drop(stack.copy(), false, Prediction.SERVER_ONLY);
            }
        }
    }

    public void cleanupExpiredRequests() {
        this.pendingRequests.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    public void sendServerTradeRequest(ServerPlayer target, List<ItemStack> items) {
        UUID targetId = target.getUUID();
        if (!this.isInTrade(targetId)) {
            TradeRequest request = new TradeRequest(TradeSession.SERVER_UUID, targetId, System.currentTimeMillis());
            this.pendingRequests.put(targetId, request);
            this.pendingServerItems.put(targetId, items);
            Component acceptButton = Component.translatable("player-trade.chat.accept_button")
                .setStyle(
                    Style.EMPTY
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/trade accept Server"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to accept trade")))
                );
            Component message = Component.translatable("player-trade.chat.server_trade_request")
                .withStyle(ChatFormatting.GOLD)
                .append(acceptButton);
            target.sendSystemMessage(message);
        }
    }

    public void acceptServerTradeRequest(ServerPlayer acceptor, MinecraftServer server) {
        UUID acceptorId = acceptor.getUUID();
        TradeRequest request = this.pendingRequests.get(acceptorId);
        if (request == null || request.isExpired() || !request.senderId().equals(TradeSession.SERVER_UUID)) {
            acceptor.sendSystemMessage(Component.translatable("player-trade.chat.request_expired").withStyle(ChatFormatting.RED));
            this.pendingRequests.remove(acceptorId);
            this.pendingServerItems.remove(acceptorId);
        } else if (this.isInTrade(acceptorId)) {
            acceptor.sendSystemMessage(Component.translatable("player-trade.chat.already_trading").withStyle(ChatFormatting.RED));
            this.pendingRequests.remove(acceptorId);
            this.pendingServerItems.remove(acceptorId);
        } else {
            List<ItemStack> items = this.pendingServerItems.remove(acceptorId);
            if (items != null && !items.isEmpty()) {
                this.pendingRequests.remove(acceptorId);
                this.startServerTrade(acceptor, items);
            } else {
                acceptor.sendSystemMessage(Component.translatable("player-trade.chat.request_expired").withStyle(ChatFormatting.RED));
                this.pendingRequests.remove(acceptorId);
            }
        }
    }

    public void startServerTrade(ServerPlayer player, List<ItemStack> items) {
        TradeSession session = new TradeSession(player.getUUID(), TradeSession.SERVER_UUID, true);
        session.setServerOffer(items);
        this.activeTrades.put(session.getSessionId(), session);
        this.playerToSession.put(player.getUUID(), session.getSessionId());
        PlayerTrade.openTradeScreen(player, Component.literal("Server"), session);
    }

    public boolean completeServerTrade(UUID playerId, MinecraftServer server) {
        TradeSession session = this.getTradeSession(playerId);
        if (session != null && session.isServerTrade() && session.bothAccepted()) {
            ServerPlayer player = server.getPlayerList().getPlayer(session.getPlayer1Id());
            if (player == null) {
                this.cancelServerTrade(playerId, server);
                return false;
            } else if (!this.canReceiveItems(player, session.getOtherPlayerOffer(player.getUUID()))) {
                player.sendSystemMessage(Component.translatable("player-trade.chat.inventory_full").withStyle(ChatFormatting.RED));
                return false;
            } else {
                this.transferItems(player, session.getOtherPlayerOffer(player.getUUID()));

                for (int i = 0; i < 9; i++) {
                    session.getPlayerOffer(TradeSession.SERVER_UUID).set(i, ItemStack.EMPTY);
                }

                this.endSession(session);
                player.sendSystemMessage(Component.translatable("player-trade.chat.trade_complete").withStyle(ChatFormatting.GREEN));
                player.closeContainer();
                return true;
            }
        } else {
            return false;
        }
    }

    public void cancelServerTrade(UUID playerId, MinecraftServer server) {
        TradeSession session = this.getTradeSession(playerId);
        if (session != null && session.isServerTrade()) {
            this.endSession(session);
            ServerPlayer player = server.getPlayerList().getPlayer(session.getPlayer1Id());
            if (player != null) {
                session.returnItemsToPlayer(player);
                player.closeContainer();
                player.sendSystemMessage(Component.translatable("player-trade.chat.trade_cancelled").withStyle(ChatFormatting.RED));
            }
        }
    }

    public record TradeRequest(UUID senderId, UUID targetId, long timestamp) {
        public static final long EXPIRATION_MS = 30000L;

        public boolean isExpired() {
            return System.currentTimeMillis() - this.timestamp > 30000L;
        }
    }
}
