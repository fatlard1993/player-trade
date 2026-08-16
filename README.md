# Player Trade

A Fabric mod that lets players trade items with each other safely, without the risk of a scam drop-and-grab. Shift-right-click another player to send a trade request; once accepted, both players get a synced trade screen where they can offer items, see the other side's offer update live, and both must accept before anything actually changes hands. Server operators can also push a one-sided "server trade" offer at a player, letting them accept or decline a bundle of items via the same screen.

## Features

- **Player-to-player trading**: shift-right-click another player to send a trade request; a clickable chat prompt lets them accept
- **Synced trade screen**: both players see each other's offered items update live as slots change
- **Mutual acceptance required**: items only change hands once both sides accept; accepting again un-accepts if the offer changes
- **Safe cancellation**: closing the screen, disconnecting, or explicitly cancelling returns all offered items to their owner
- **Inventory-full protection**: a trade won't complete if either player lacks the space to receive their side of the deal
- **`/trade accept <player>`** and **`/trade cancel`** commands for responding to and leaving trades
- **`/server-trade <player> <item> <count> ...`** (operator-only): send a player a preset one-sided offer of up to 9 items, which they can accept or decline through the normal trade screen

## Pandorical

Player Trade uses Pandorical's `screens()` API to build and drive the trade UI (the item grids, accept/cancel buttons, and live "who's accepted" indicators) entirely server-side. There's no bundled client mod or resource pack. Pandorical must be installed client-side for a player to see or use the trade screen. If a player without Pandorical is sent a trade request and it opens for them, they instead get a chat message telling them Pandorical is required, rather than a broken or missing screen.

## Installation

Install server-side alongside its declared dependencies (see `fabric.mod.json`); connecting clients need only Pandorical. Version targets live in `gradle.properties` (Minecraft, loader, Fabric API) and `fabric.mod.json` (Java).

## License

MIT, see [LICENSE](LICENSE).
