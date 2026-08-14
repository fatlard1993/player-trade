package justfatlard.player_trade;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentUpdate;
import justfatlard.player_trade.trade.TradeManager;
import justfatlard.player_trade.trade.TradeSession;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class PlayerTrade implements ModInitializer {
    public static final String MOD_ID = "player-trade";
    public static final String SCREEN_TYPE = "player-trade";
    public static final Logger LOGGER = LoggerFactory.getLogger("player-trade");

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("player-trade", path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Player Trade mod");
        this.registerPandoricalHandlers();
        this.registerPlayerInteraction();
        this.registerCommands();
        this.registerDisconnectHandler();
        LOGGER.info("Player Trade mod initialized");
    }

    private void registerPandoricalHandlers() {
        // Accept button toggles acceptance
        PandoricalApi.screens().onAction(SCREEN_TYPE, "accept_btn", (player, data) -> {
            TradeSession session = TradeManager.getInstance().getTradeSession(player.getUUID());
            if (session != null) {
                boolean newState = !session.hasAccepted(player.getUUID());
                session.setAccepted(player.getUUID(), newState);
                syncTradeToPlayers(session, ((net.minecraft.server.level.ServerLevel) player.level()).getServer());
                if (session.bothAccepted()) {
                    TradeManager.getInstance().completeTrade(player.getUUID(), ((net.minecraft.server.level.ServerLevel) player.level()).getServer());
                }
            }
        });

        // Cancel button cancels trade
        PandoricalApi.screens().onAction(SCREEN_TYPE, "cancel_btn", (player, data) -> {
            TradeManager.getInstance().cancelTrade(player.getUUID(), ((net.minecraft.server.level.ServerLevel) player.level()).getServer());
        });

        // Slot changes sync to session
        PandoricalApi.screens().onSlotChange(SCREEN_TYPE, (player, slotIndex, stack) -> {
            TradeSession session = TradeManager.getInstance().getTradeSession(player.getUUID());
            if (session != null) {
                // Only sync "your offer" slots (0-8)
                if (slotIndex < 9) {
                    session.setSlot(player.getUUID(), slotIndex, stack);
                    syncTradeToPlayers(session, ((net.minecraft.server.level.ServerLevel) player.level()).getServer());
                }
            }
        });

        // Container removed: return items
        PandoricalApi.screens().onContainerRemoved(SCREEN_TYPE, (player) -> {
            TradeSession session = TradeManager.getInstance().getTradeSession(player.getUUID());
            if (session != null) {
                session.returnItemsToPlayer(player);
                TradeManager.getInstance().cancelTrade(player.getUUID(), ((net.minecraft.server.level.ServerLevel) player.level()).getServer());
            }
        });

        // Screen close (from ScreenActionC2S "close")
        PandoricalApi.screens().onClose(SCREEN_TYPE, (player) -> {
            TradeManager.getInstance().cancelTrade(player.getUUID(), ((net.minecraft.server.level.ServerLevel) player.level()).getServer());
        });
    }

    /**
     * Build and open the trade screen for a player via Pandorical.
     */
    public static void openTradeScreen(ServerPlayer player, Component otherPlayerName, TradeSession session) {
        if (!PandoricalApi.hasCapability(player, "screens")) {
            player.sendSystemMessage(Component.literal("Trade requires Pandorical mod on client.").withStyle(ChatFormatting.RED));
            return;
        }

        boolean isServerTrade = session.isServerTrade();
        // "Your offer" = slots 0-8, "Their offer" = slots 9-17
        // Read-only: their offer always. Your offer if server trade.
        Set<Integer> readOnly = new java.util.HashSet<>();
        for (int i = 9; i < 18; i++) readOnly.add(i); // their offer always read-only
        if (isServerTrade) {
            for (int i = 0; i < 9; i++) readOnly.add(i); // your offer read-only in server trade
        }

        // Build the container with pre-populated items
        SimpleContainer tradeContainer = new SimpleContainer(18);
        for (int i = 0; i < 9; i++) {
            tradeContainer.setItem(i, session.getSlot(player.getUUID(), i).copy());
            tradeContainer.setItem(i + 9, session.getOtherPlayerOffer(player.getUUID()).get(i).copy());
        }
        // Track this container so syncTradeToPlayer can rewrite the "Their Offer" slots (9-17)
        // live as the counterparty changes their offer.
        session.setDisplayContainer(player.getUUID(), tradeContainer);

        String title = Component.translatable("player-trade.screen.title", otherPlayerName).getString();
        ScreenBuilder builder = new ScreenBuilder(SCREEN_TYPE)
            .size(176, 176)
            .title(title)
            .container(18, true)
            // Background panel
            .panel("bg", 0, 0, 176, 176, Map.of("border", "beveled"))
            // Title bar
            .text("title", 7, 6, Map.of("text", title, "color", "#404040"))
            .button("cancel_btn", 126, 4, 45, 12,
                Map.of("label_key", "player-trade.screen.cancel"))
            // Your offer section
            .text("your_label", 16, 20, Map.of("text", "Your Offer", "color", "#404040"))
            .inventoryGrid("your_grid", 8, 30, 3, 3, 0)
            // Divider
            .sprite("divider", 85, 30, 6, 54, Map.of("color", "#555555"))
            // Their offer section
            .text("their_label", 100, 20, Map.of("text", "Their Offer", "color", "#404040"))
            .inventoryGrid("their_grid", 98, 30, 3, 3, 9)
            // Accept button + indicators
            .button("accept_btn", 68, 68, 40, 20,
                Map.of("label_key", "player-trade.screen.accept"))
            .sprite("your_indicator", 35, 86, 10, 10, Map.of("color", "#AA0000"))
            .sprite("their_indicator", 131, 86, 10, 10, Map.of("color", "#AA0000"))
            .text("your_ind_label", 33, 98, Map.of("text", "You", "color", "#404040"))
            .text("their_ind_label", 127, 98, Map.of("text", "Them", "color", "#404040"))
            // Player inventory
            .inventoryGrid("player_inv", 8, 94, 3, 9, 18)
            // Hotbar
            .inventoryGrid("hotbar", 8, 152, 1, 9, 45);

        PandoricalApi.screens().openContainer(player, builder.build(), tradeContainer, readOnly);
    }

    /**
     * Sync trade state to both players via Pandorical screen updates.
     */
    public static void syncTradeToPlayers(TradeSession session, MinecraftServer server) {
        ServerPlayer player1 = server.getPlayerList().getPlayer(session.getPlayer1Id());
        ServerPlayer player2 = server.getPlayerList().getPlayer(session.getPlayer2Id());
        if (player1 != null) syncTradeToPlayer(session, player1);
        if (player2 != null && !session.isServerTrade()) syncTradeToPlayer(session, player2);
    }

    private static void syncTradeToPlayer(TradeSession session, ServerPlayer player) {
        String screenId = PandoricalApi.getOpenScreenId(player.getUUID());
        if (screenId == null) return;

        boolean youAccepted = session.hasAccepted(player.getUUID());
        boolean theyAccepted = session.hasOtherAccepted(player.getUUID());

        List<ComponentUpdate> updates = List.of(
            new ComponentUpdate("accept_btn", Map.of(
                "label_key", youAccepted ? "player-trade.screen.accepted" : "player-trade.screen.accept",
                "style", youAccepted ? "accepted" : "default"
            )),
            new ComponentUpdate("your_indicator", Map.of(
                "color", youAccepted ? "#00AA00" : "#AA0000"
            )),
            new ComponentUpdate("their_indicator", Map.of(
                "color", theyAccepted ? "#00AA00" : "#AA0000"
            ))
        );

        PandoricalApi.screens().update(player, screenId, updates);

        // Refresh the counterparty ("Their Offer") display slots (9-17) from the live session
        // offer. The trade screen is backed by a real vanilla container, so writing these
        // server-side slots makes vanilla's per-tick broadcastChanges() push them to the client.
        // These slots stay read-only for the player; only the server mutates them here, so their
        // read-only-ness is preserved.
        Container container = session.getDisplayContainer(player.getUUID());
        if (container != null) {
            List<ItemStack> otherOffer = session.getOtherPlayerOffer(player.getUUID());
            for (int i = 0; i < TradeSession.OFFER_SLOTS; i++) {
                ItemStack stack = (otherOffer != null) ? otherOffer.get(i) : ItemStack.EMPTY;
                container.setItem(TradeSession.OFFER_SLOTS + i, stack.copy());
            }
        }
    }

    private void registerPlayerInteraction() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(entity instanceof ServerPlayer targetPlayer)) return InteractionResult.PASS;
            if (world.isClientSide()) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer clickingPlayer)) return InteractionResult.PASS;
            if (!player.isShiftKeyDown()) return InteractionResult.PASS;
            TradeManager.getInstance().sendTradeRequest(clickingPlayer, targetPlayer);
            return InteractionResult.SUCCESS;
        });
    }

    /**
     * Return escrowed trade items if a participant disconnects mid-trade.
     *
     * Fabric fires ServerPlayConnectionEvents.DISCONNECT from Connection.handleDisconnection()
     * immediately BEFORE ServerGamePacketListenerImpl.onDisconnect() runs (verified against the
     * fabric-networking ConnectionMixin: the injection targets the onDisconnect INVOKE with no
     * shift=AFTER). Since onDisconnect is what triggers PlayerList.remove() -> save(player), the
     * disconnecting ServerPlayer is still fully in-world here and any items returned to its
     * inventory are persisted by the subsequent save. Without this, cancelTrade could only reach
     * online players, so a disconnected party's escrow was destroyed.
     */
    private void registerDisconnectHandler() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) {
                TradeManager.getInstance().handleDisconnect(player, server);
            }
        });
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                Commands.literal("trade")
                    .then(Commands.literal("accept")
                        .then(Commands.argument("player", StringArgumentType.word())
                            .executes(context -> {
                                ServerPlayer acceptor = context.getSource().getPlayerOrException();
                                String senderName = StringArgumentType.getString(context, "player");
                                TradeManager.getInstance().acceptTradeRequest(acceptor, senderName, context.getSource().getServer());
                                return 1;
                            })
                        )
                    )
                    .then(Commands.literal("cancel")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            TradeManager.getInstance().cancelTrade(player.getUUID(), context.getSource().getServer());
                            return 1;
                        })
                    )
            );

            dispatcher.register(
                Commands.literal("server-trade")
                    .requires(source -> Commands.LEVEL_ADMINS.check(source.permissions()))
                    .then(buildServerTradeArgs(registryAccess, 1))
            );
        });
    }

    private com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> buildServerTradeArgs(
            net.minecraft.commands.CommandBuildContext registryAccess, int itemIndex) {
        if (itemIndex == 1) {
            return Commands.argument("player", EntityArgument.player())
                .then(buildItemArg(registryAccess, itemIndex));
        }
        return buildItemArg(registryAccess, itemIndex);
    }

    private com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> buildItemArg(
            net.minecraft.commands.CommandBuildContext registryAccess, int index) {
        var itemArg = Commands.argument("item" + index, ItemArgument.item(registryAccess))
            .executes(ctx -> this.executeServerTrade(ctx, index));

        var countArg = Commands.argument("count" + index, IntegerArgumentType.integer(1, 64))
            .executes(ctx -> this.executeServerTrade(ctx, index));

        if (index < 9) {
            countArg.then(buildItemArg(registryAccess, index + 1));
        }

        itemArg.then(countArg);
        return itemArg;
    }

    private int executeServerTrade(CommandContext<CommandSourceStack> ctx, int itemCount) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
            List<ItemStack> items = new ArrayList<>();

            for (int i = 1; i <= itemCount; i++) {
                ItemStack stack = ItemArgument.getItem(ctx, "item" + i).createItemStack(1);
                int count = 1;
                try {
                    count = IntegerArgumentType.getInteger(ctx, "count" + i);
                } catch (IllegalArgumentException ignored) {
                }
                stack.setCount(count);
                items.add(stack);
            }

            TradeManager.getInstance().sendServerTradeRequest(target, items);
            ctx.getSource().sendSuccess(
                () -> Component.literal("Sent server trade request to ")
                    .append(target.getName())
                    .append(" with " + items.size() + " item(s)")
                    .withStyle(ChatFormatting.GREEN),
                true
            );
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Failed to send server trade: " + e.getMessage()));
            return 0;
        }
    }
}
