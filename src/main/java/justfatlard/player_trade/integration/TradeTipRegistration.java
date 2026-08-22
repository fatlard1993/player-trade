package justfatlard.player_trade.integration;

import justfatlard.block_tip.api.BlockTipApi;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.player_trade.trade.TradeManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * How to start a trade, said while you are looking at the person you would trade with.
 *
 * <p>Sneaking and clicking somebody is the mod's only front door and nothing in the game hints at
 * it: a player who has not been told has no reason to try it, and the one place the mod ever
 * explains itself is chat, which only speaks after somebody already knew. A card that is on screen
 * the moment you look at another player is the one place the instruction arrives before the need.
 *
 * <p>Names block-tip types directly, so it must only be loaded behind the isModLoaded guard in the
 * entry point.
 */
public final class TradeTipRegistration {
    private TradeTipRegistration() {}

    /** The gesture, in the fewest words that still say to hold something down and click. */
    private static final String HOW = "Sneak-click to trade";

    /** Worth its line: the click is going to bounce, and it would bounce as a red line in chat. */
    private static final String BUSY = "Already trading";

    public static void register() {
        BlockTipApi.describeEntity(TradeTipRegistration::describe);
    }

    private static String describe(Entity entity, ServerPlayer looking) {
        if (!(entity instanceof ServerPlayer target)) return null;

        if (TradeManager.getInstance().isInTrade(target.getUUID())) return BUSY;

        // The trade is a Pandorical screen at both ends, so a player whose client does not have it
        // cannot be traded with at all. Silence rather than an instruction that ends in an error.
        return PandoricalApi.hasCapability(target, "screens") ? HOW : null;
    }
}
