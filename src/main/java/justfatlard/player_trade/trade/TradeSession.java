package justfatlard.player_trade.trade;

import net.minecraft.util.Prediction;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

public class TradeSession {
    private final UUID sessionId = UUID.randomUUID();
    private final UUID player1Id;
    private final UUID player2Id;
    private final NonNullList<ItemStack> player1Offer;
    private final NonNullList<ItemStack> player2Offer;
    private boolean player1Accepted;
    private boolean player2Accepted;
    private final long createdAt;
    private final boolean isServerTrade;
    /**
     * The live trade screen container backing each participant's open menu, keyed by player UUID.
     * Each container's slots 0-8 hold that player's own offer and slots 9-17 the counterparty's
     * offer ("Their Offer"). Kept here so the counterparty display slots can be rewritten live
     * when either offer changes (see PlayerTrade.syncTradeToPlayer).
     */
    private final Map<UUID, Container> displayContainers = new ConcurrentHashMap<>();
    /** Counterparty display name per participant, for the screen's status line. */
    private final Map<UUID, String> counterpartyNames = new ConcurrentHashMap<>();
    public static final int OFFER_SLOTS = 9;
    public static final UUID SERVER_UUID = new UUID(0L, 0L);

    public TradeSession(UUID player1Id, UUID player2Id) {
        this(player1Id, player2Id, false);
    }

    public TradeSession(UUID player1Id, UUID player2Id, boolean isServerTrade) {
        this.player1Id = player1Id;
        this.player2Id = player2Id;
        this.player1Offer = NonNullList.withSize(9, ItemStack.EMPTY);
        this.player2Offer = NonNullList.withSize(9, ItemStack.EMPTY);
        this.player1Accepted = false;
        this.player2Accepted = isServerTrade;
        this.createdAt = System.currentTimeMillis();
        this.isServerTrade = isServerTrade;
    }

    public UUID getSessionId() {
        return this.sessionId;
    }

    public UUID getPlayer1Id() {
        return this.player1Id;
    }

    public UUID getPlayer2Id() {
        return this.player2Id;
    }

    public UUID getOtherPlayerId(UUID playerId) {
        if (playerId.equals(this.player1Id)) {
            return this.player2Id;
        } else {
            return playerId.equals(this.player2Id) ? this.player1Id : null;
        }
    }

    public boolean isParticipant(UUID playerId) {
        return playerId.equals(this.player1Id) || playerId.equals(this.player2Id);
    }

    public boolean isPlayer1(UUID playerId) {
        return playerId.equals(this.player1Id);
    }

    public NonNullList<ItemStack> getPlayerOffer(UUID playerId) {
        if (playerId.equals(this.player1Id)) {
            return this.player1Offer;
        } else {
            return playerId.equals(this.player2Id) ? this.player2Offer : null;
        }
    }

    public NonNullList<ItemStack> getOtherPlayerOffer(UUID playerId) {
        if (playerId.equals(this.player1Id)) {
            return this.player2Offer;
        } else {
            return playerId.equals(this.player2Id) ? this.player1Offer : null;
        }
    }

    public void setSlot(UUID playerId, int slot, ItemStack stack) {
        if (slot >= 0 && slot < 9) {
            NonNullList<ItemStack> offer = this.getPlayerOffer(playerId);
            if (offer != null) {
                offer.set(slot, stack.copy());
                this.invalidateAcceptance();
            }
        }
    }

    public ItemStack getSlot(UUID playerId, int slot) {
        if (slot >= 0 && slot < 9) {
            NonNullList<ItemStack> offer = this.getPlayerOffer(playerId);
            return offer != null ? offer.get(slot) : ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }
    }

    public void invalidateAcceptance() {
        this.player1Accepted = false;
        if (!this.isServerTrade) {
            this.player2Accepted = false;
        }
    }

    public void setAccepted(UUID playerId, boolean accepted) {
        if (playerId.equals(this.player1Id)) {
            this.player1Accepted = accepted;
        } else if (playerId.equals(this.player2Id)) {
            this.player2Accepted = accepted;
        }
    }

    public boolean hasAccepted(UUID playerId) {
        if (playerId.equals(this.player1Id)) {
            return this.player1Accepted;
        } else {
            return playerId.equals(this.player2Id) ? this.player2Accepted : false;
        }
    }

    public boolean hasOtherAccepted(UUID playerId) {
        if (playerId.equals(this.player1Id)) {
            return this.player2Accepted;
        } else {
            return playerId.equals(this.player2Id) ? this.player1Accepted : false;
        }
    }

    public boolean bothAccepted() {
        return this.player1Accepted && this.player2Accepted;
    }

    public long getCreatedAt() {
        return this.createdAt;
    }

    public void returnItemsToPlayer(ServerPlayer player) {
        NonNullList<ItemStack> offer = this.getPlayerOffer(player.getUUID());
        if (offer != null) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = offer.get(i);
                if (!stack.isEmpty()) {
                    if (!player.getInventory().add(stack.copy())) {
                        player.drop(stack.copy(), false, Prediction.SERVER_ONLY);
                    }
                    offer.set(i, ItemStack.EMPTY);
                }
            }
        }
    }

    public boolean isServerTrade() {
        return this.isServerTrade;
    }

    /** Record the trade screen container opened for a participant, so its display slots can be updated later. */
    public void setDisplayContainer(UUID playerId, Container container) {
        this.displayContainers.put(playerId, container);
    }

    /** The trade screen container currently open for a participant, or null if none is tracked. */
    public Container getDisplayContainer(UUID playerId) {
        return this.displayContainers.get(playerId);
    }

    /**
     * Record who the screen says a participant is trading with, so later screen updates can name
     * them. Captured when the screen opens rather than looked up per update: the counterparty may
     * already be gone from the player list by the time the last update goes out.
     */
    public void setCounterpartyName(UUID playerId, String name) {
        this.counterpartyNames.put(playerId, name);
    }

    /** The counterparty's display name for a participant's screen. */
    public String getCounterpartyName(UUID playerId) {
        return this.counterpartyNames.getOrDefault(playerId, "");
    }

    public void setServerOffer(List<ItemStack> items) {
        if (this.isServerTrade) {
            for (int i = 0; i < Math.min(items.size(), 9); i++) {
                this.player2Offer.set(i, items.get(i).copy());
            }
        }
    }
}
