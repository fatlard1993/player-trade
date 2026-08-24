package justfatlard.player_trade.screen;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentUpdate;
import justfatlard.player_trade.trade.TradeSession;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * Layout and live state of the two-sided trade screen.
 *
 * <p>The screen is read by children, so every piece of state is said three ways at once: a colour,
 * a shape, and a sentence. Colour alone cannot carry it - a player who cannot tell the green from
 * the grey still has the words.
 */
public final class TradeScreen {
    private TradeScreen() {}

    public static final String SCREEN_TYPE = "player-trade";

    // --- Geometry -------------------------------------------------------------------------
    // Wider than a vanilla screen, no taller. Height is the scarce dimension: a screen taller
    // than vanilla's 166 starts clipping at large GUI scales, while width has room to spare.

    private static final int WIDTH = 240;
    private static final int HEIGHT = 188;

    private static final int MARGIN = 10;
    private static final int CENTER_X = WIDTH / 2;

    private static final int TITLE_X = 12;
    private static final int TITLE_Y = 7;
    private static final int CANCEL_W = 50;
    private static final int CANCEL_H = 14;
    private static final int CANCEL_X = WIDTH - MARGIN - CANCEL_W;
    private static final int CANCEL_Y = 4;
    /** Everything left of the cancel button, less a gap, so a long name is ellipsised not overrun. */
    private static final int TITLE_WRAP = CANCEL_X - TITLE_X - 6;

    /** One offer box: a header row, a 3x3 grid, and a border that carries the ready state. */
    private static final int GRID_SIDE = 3;
    private static final int CELL = 18;
    private static final int BOX_INSET = 4;
    private static final int BOX_W = GRID_SIDE * CELL + BOX_INSET * 2;
    private static final int BOX_H = 76;
    private static final int BOX_Y = 20;
    private static final int GIVE_BOX_X = MARGIN;
    private static final int GET_BOX_X = WIDTH - MARGIN - BOX_W;
    private static final int LABEL_Y = BOX_Y + 4;
    private static final int GRID_Y = BOX_Y + 16;
    private static final int PIP = 7;

    private static final int ACCEPT_W = 84;
    private static final int ACCEPT_H = 20;
    private static final int ACCEPT_X = CENTER_X - ACCEPT_W / 2;
    private static final int ACCEPT_Y = 41;

    private static final int HINT_X = ACCEPT_X;
    private static final int HINT_W = ACCEPT_W;
    private static final int HINT_Y = 64;
    private static final int HINT_MAX_LINES = 3;
    /** Matches the client's text line height, so the bounds hold every line the hint can wrap to. */
    private static final int LINE_HEIGHT = 11;

    /**
     * Separates the deal from the player's own pockets. Without it the trade boxes and the
     * inventory read as one field of slots, and the first thing a new player does is drop an item
     * into the wrong half.
     */
    private static final int DIVIDER_Y = 100;

    private static final int PLAYER_INV_COLS = 9;
    private static final int PLAYER_INV_X = (WIDTH - PLAYER_INV_COLS * CELL) / 2;
    private static final int PLAYER_INV_Y = 108;
    private static final int HOTBAR_Y = 166;

    // Arrow geometry. Drawn from rectangles rather than a glyph: an arrow made of fills renders
    // identically in every language and font, where a character depends on a fallback font being
    // present. Head rows taper by two so the point lands on the shaft's centre line.
    private static final int ARROW_SHAFT = 12;
    private static final int ARROW_HEAD = 4;
    private static final int ARROW_H = 7;
    private static final int ARROW_W = ARROW_SHAFT + ARROW_HEAD;
    private static final int ARROW_X = CENTER_X - ARROW_W / 2;
    private static final int ARROW_OUT_Y = 20;
    private static final int ARROW_IN_Y = 30;

    // --- Palette --------------------------------------------------------------------------
    // Two side colours that stay put: whatever is blue is yours to give, whatever is gold is
    // yours to get. Green means only one thing on this screen, and that is "ready".

    private static final String GIVE = "#FF6FB3FF";
    private static final String GET = "#FFFFC14D";
    private static final String READY = "#FF57DD5B";
    private static final String PENDING = "#FF9A9A9A";
    private static final String BOX_BG = "#FF2E2E2E";
    private static final String BODY_TEXT = "#FF3F3F3F";
    private static final String ARROW_COLOR = "#FF8A8A8A";
    private static final String MUTED = "#FF808080";
    private static final String ACCENT_GO = "#FF57DD5B";
    private static final String ACCENT_STOP = "#FFD35450";
    private static final String DIVIDER_DARK = "#FF8A8A8A";
    private static final String DIVIDER_LIGHT = "#FFDDDDDD";

    // --- Component ids --------------------------------------------------------------------

    public static final String ACCEPT_BTN = "accept_btn";
    public static final String CANCEL_BTN = "cancel_btn";
    private static final String GIVE_BOX = "give_box";
    private static final String GET_BOX = "get_box";
    private static final String GIVE_PIP = "give_pip";
    private static final String GET_PIP = "get_pip";
    private static final String HINT = "hint";

    /**
     * Build the trade screen and hand the player their menu.
     *
     * <p>The container's slots 0-8 are this player's own offer and 9-17 the counterparty's; the
     * caller keeps the container so the counterparty half can be rewritten as their offer changes.
     */
    public static void open(ServerPlayer player, Component otherName, TradeSession session) {
        boolean serverTrade = session.isServerTrade();

        SimpleContainer container = new SimpleContainer(TradeSession.OFFER_SLOTS * 2);
        List<ItemStack> otherOffer = session.getOtherPlayerOffer(player.getUUID());
        for (int i = 0; i < TradeSession.OFFER_SLOTS; i++) {
            container.setItem(i, session.getSlot(player.getUUID(), i).copy());
            container.setItem(TradeSession.OFFER_SLOTS + i, otherOffer.get(i).copy());
        }
        session.setDisplayContainer(player.getUUID(), container);
        session.setCounterpartyName(player.getUUID(), otherName.getString());

        // Their half is never yours to touch, and in a server trade neither is your own.
        Set<Integer> readOnly = new HashSet<>();
        for (int i = TradeSession.OFFER_SLOTS; i < TradeSession.OFFER_SLOTS * 2; i++) readOnly.add(i);
        if (serverTrade) {
            for (int i = 0; i < TradeSession.OFFER_SLOTS; i++) readOnly.add(i);
        }

        String title = Component.translatable("player-trade.screen.title", otherName).getString();
        String giveColor = serverTrade ? MUTED : GIVE;

        ScreenBuilder builder = new ScreenBuilder(SCREEN_TYPE)
            .size(WIDTH, HEIGHT)
            .title(title)
            .container(TradeSession.OFFER_SLOTS * 2, true)
            .panel("bg", 0, 0, WIDTH, HEIGHT, Map.of(ComponentType.PROP_BORDER, "beveled"))
            .component(new ComponentBuilder("title", ComponentType.TEXT)
                .bounds(TITLE_X, TITLE_Y, TITLE_WRAP, LINE_HEIGHT)
                .prop(ComponentType.PROP_TEXT, title)
                .prop(ComponentType.PROP_COLOR, BODY_TEXT)
                .prop(ComponentType.PROP_WRAP_WIDTH, String.valueOf(TITLE_WRAP))
                .prop(ComponentType.PROP_MAX_LINES, "1"))
            .button(CANCEL_BTN, CANCEL_X, CANCEL_Y, CANCEL_W, CANCEL_H, Map.of(
                ComponentType.PROP_LABEL_KEY, "player-trade.screen.cancel",
                ComponentType.PROP_ACCENT, ACCENT_STOP));

        offerBox(builder, GIVE_BOX, GIVE_PIP, "give_label", GIVE_BOX_X,
            "player-trade.screen.you_give", giveColor, 0);
        offerBox(builder, GET_BOX, GET_PIP, "get_label", GET_BOX_X,
            "player-trade.screen.you_get", GET, TradeSession.OFFER_SLOTS);

        // The pair of arrows says "these two swap" without naming a direction the boxes do not
        // actually have. A server trade only ever moves one way, so it only gets the one arrow.
        if (!serverTrade) {
            arrow(builder, "arrow_out", ARROW_X, ARROW_OUT_Y, true);
        }
        arrow(builder, "arrow_in", ARROW_X, ARROW_IN_Y, false);

        builder
            .button(ACCEPT_BTN, ACCEPT_X, ACCEPT_Y, ACCEPT_W, ACCEPT_H, Map.of(
                ComponentType.PROP_LABEL_KEY, "player-trade.screen.accept",
                ComponentType.PROP_ACCENT, ACCENT_GO))
            .component(new ComponentBuilder(HINT, ComponentType.TEXT)
                .bounds(HINT_X, HINT_Y, HINT_W, HINT_MAX_LINES * LINE_HEIGHT)
                .prop(ComponentType.PROP_TEXT, hint(session, player))
                .prop(ComponentType.PROP_COLOR, BODY_TEXT)
                .prop(ComponentType.PROP_ALIGN, "center")
                .prop(ComponentType.PROP_WRAP_WIDTH, String.valueOf(HINT_W))
                .prop(ComponentType.PROP_MAX_LINES, String.valueOf(HINT_MAX_LINES)))
            .sprite("divider_dark", MARGIN, DIVIDER_Y, WIDTH - MARGIN * 2, 1,
                Map.of(ComponentType.PROP_COLOR, DIVIDER_DARK))
            .sprite("divider_light", MARGIN, DIVIDER_Y + 1, WIDTH - MARGIN * 2, 1,
                Map.of(ComponentType.PROP_COLOR, DIVIDER_LIGHT))
            .inventoryGrid("player_inv", PLAYER_INV_X, PLAYER_INV_Y, 3, PLAYER_INV_COLS,
                TradeSession.OFFER_SLOTS * 2)
            .inventoryGrid("hotbar", PLAYER_INV_X, HOTBAR_Y, 1, PLAYER_INV_COLS,
                TradeSession.OFFER_SLOTS * 2 + 27);

        PandoricalApi.screens().openContainer(player, builder.build(), container, readOnly);
    }

    /** One side of the trade: a dark box, a coloured header with a ready pip, and the grid. */
    private static void offerBox(ScreenBuilder builder, String boxId, String pipId, String labelId,
            int x, String labelKey, String color, int startSlot) {
        builder
            .panel(boxId, x, BOX_Y, BOX_W, BOX_H, Map.of(
                ComponentType.PROP_BACKGROUND, BOX_BG,
                ComponentType.PROP_BORDER, "flat",
                ComponentType.PROP_BORDER_COLOR, color))
            .text(labelId, x + BOX_INSET, LABEL_Y, Map.of(
                ComponentType.PROP_TEXT_KEY, labelKey,
                ComponentType.PROP_COLOR, color,
                ComponentType.PROP_SHADOW, "true"))
            .sprite(pipId, x + BOX_W - BOX_INSET - PIP, LABEL_Y, PIP, PIP,
                Map.of(ComponentType.PROP_COLOR, PENDING))
            .inventoryGrid(boxId + "_grid", x + BOX_INSET, GRID_Y, GRID_SIDE, GRID_SIDE, startSlot);
    }

    /** A filled arrow, shaft then tapering head, pointing right or left from {@code x}. */
    private static void arrow(ScreenBuilder builder, String id, int x, int y, boolean pointsRight) {
        Map<String, String> color = Map.of(ComponentType.PROP_COLOR, ARROW_COLOR);
        int shaftX = pointsRight ? x : x + ARROW_HEAD;
        builder.sprite(id + "_shaft", shaftX, y + (ARROW_H - 3) / 2, ARROW_SHAFT, 3, color);
        for (int i = 0; i < ARROW_HEAD; i++) {
            int headX = pointsRight ? x + ARROW_SHAFT + i : x + ARROW_HEAD - 1 - i;
            builder.sprite(id + "_head" + i, headX, y + i, 1, ARROW_H - i * 2, color);
        }
    }

    /**
     * Everything on the screen that can change while it is open.
     *
     * <p>Both sides' acceptance is dropped whenever either offer changes, so the hint has to be
     * resent on every sync: a player who pressed Accept and then added an item is no longer
     * accepted, and nothing else on the screen would tell them why.
     */
    public static List<ComponentUpdate> stateUpdates(TradeSession session, ServerPlayer player) {
        boolean youReady = session.hasAccepted(player.getUUID());
        boolean theyReady = session.hasOtherAccepted(player.getUUID());
        String giveIdle = session.isServerTrade() ? MUTED : GIVE;

        return List.of(
            new ComponentUpdate(ACCEPT_BTN, Map.of(
                ComponentType.PROP_LABEL_KEY,
                    youReady ? "player-trade.screen.accepted" : "player-trade.screen.accept",
                ComponentType.PROP_STYLE, youReady ? "accepted" : "default")),
            new ComponentUpdate(GIVE_BOX, Map.of(
                ComponentType.PROP_BORDER_COLOR, youReady ? READY : giveIdle)),
            new ComponentUpdate(GET_BOX, Map.of(
                ComponentType.PROP_BORDER_COLOR, theyReady ? READY : GET)),
            new ComponentUpdate(GIVE_PIP, Map.of(
                ComponentType.PROP_COLOR, youReady ? READY : PENDING)),
            new ComponentUpdate(GET_PIP, Map.of(
                ComponentType.PROP_COLOR, theyReady ? READY : PENDING)),
            new ComponentUpdate(HINT, Map.of(
                ComponentType.PROP_TEXT, hint(session, player)))
        );
    }

    /**
     * The sentence under the button: what this player should do next, in words.
     *
     * <p>Resolved server-side because the counterparty's name is an argument, which the client's
     * key-only text component cannot fill in - the same trade the title already makes.
     */
    private static String hint(TradeSession session, ServerPlayer player) {
        boolean youReady = session.hasAccepted(player.getUUID());
        boolean theyReady = session.hasOtherAccepted(player.getUUID());
        String other = session.getCounterpartyName(player.getUUID());

        if (session.isServerTrade()) {
            return Component.translatable("player-trade.screen.hint_gift").getString();
        }
        if (youReady && !theyReady) {
            return Component.translatable("player-trade.screen.hint_waiting", other).getString();
        }
        if (!youReady && theyReady) {
            return Component.translatable("player-trade.screen.hint_they_ready", other).getString();
        }
        return Component.translatable("player-trade.screen.hint_start").getString();
    }
}
