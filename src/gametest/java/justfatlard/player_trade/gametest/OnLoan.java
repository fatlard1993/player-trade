package justfatlard.player_trade.gametest;

import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.player_trade.trade.TradeManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nobody trades while what they carry is on loan (a builder at a Miniature Bench, say): an offer
 * cannot be taken up, it still stands once the loan is over, and a trade already running when a
 * loan begins is called off with nothing handed over.
 */
public final class OnLoan implements FabricClientGameTest {
	private static final AtomicBoolean LENT = new AtomicBoolean();

	static {
		PandoricalApi.loans().register(player -> LENT.get() ? Component.literal("building somewhere") : null);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			TradeManager trades = TradeManager.getInstance();

			LENT.set(true);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				player.getInventory().clearContent();
				trades.sendServerTradeRequest(player, List.of(new ItemStack(Items.DIAMOND, 2)));
				trades.acceptServerTradeRequest(player, s);
			});
			check(!server.computeOnServer(s -> trades.isInTrade(connection.getServerPlayer().getUUID())), "a trade began with the player's pack on loan");
			server.runOnServer(s -> trades.startServerTrade(connection.getServerPlayer(), List.of(new ItemStack(Items.DIAMOND, 2))));
			check(!server.computeOnServer(s -> trades.isInTrade(connection.getServerPlayer().getUUID())), "a server trade was opened on a pack on loan");

			LENT.set(false);
			server.runOnServer(s -> trades.acceptServerTradeRequest(connection.getServerPlayer(), s));
			check(server.computeOnServer(s -> trades.isInTrade(connection.getServerPlayer().getUUID())), "the offer did not still stand once the loan was over");
			context.waitTicks(5);

			LENT.set(true);
			context.waitTicks(30);
			check(!server.computeOnServer(s -> trades.isInTrade(connection.getServerPlayer().getUUID())), "a running trade was not called off when the loan began");
			check(!server.computeOnServer(s -> connection.getServerPlayer().getInventory().hasAnyMatching(stack -> stack.is(Items.DIAMOND))), "the called-off trade handed its diamonds over");
			LENT.set(false);
			System.out.println("[player-trade-test] on loan passed");
		}
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
