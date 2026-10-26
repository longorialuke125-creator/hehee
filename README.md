# BetLedger (Fabric, Minecraft 26.1.x)

Client-side /pay gambling tracker. Original code, inspired by Gambler Plus.

## Build
Needs JDK 25 and Gradle 9+ (or run `gradle wrapper` once, then use `./gradlew`):

    gradle build

Jar: `build/libs/betledger-1.0.0.jar` -> drop in `mods/` with Fabric API.

## Use
- **Right Shift** (or `/betledger gui`) opens the dashboard window. Rebind it in Controls: "Open BetLedger window".
  Pages: Sessions, Players (per-player rakeback), Tools (timer, hud layout, top gamblers, hud text), Look (accent colour, opacity), Settings.
- Put your name and Discord in the footer: `/betledger set creator <name>` and `/betledger set discordUrl <invite link>`.
- Auction (Tools > Auction): start a round; payments you receive while it is live count as bids, highest wins. Rebind the menu key in Settings.
- `]` toggles the HUD (rebind in Controls: "Toggle BetLedger HUD")
- `/betledger` stats, `start [name]`, `stop`, `reset`, `players`, `history`, `export` (CSV)
- `/betledger rake <player> [percent]` show owed rakeback / set a per-player %
- `/betledger get` lists every setting; `/betledger set <field> <value>` changes one
  (hudX, hudY, hudScale, hudColor, hudBackground, hudTopPlayers, compactNumbers,
  confirmLargePay, confirmThreshold, rakebackPercent, rakebackBase, receivedRegex, sentRegex)
- Pays >= confirmThreshold are held until `/betledger yes` or `/betledger no`.

Files: `config/betledger.json`, `config/betledger-sessions.json`.

## Important
The default chat regexes are guesses. If nothing is tracked, copy a real "paid you" and
"you paid" line from chat and adjust `receivedRegex` / `sentRegex` (group 1 = player, group 2 = amount).
Rakeback assumes you are the house: owed = % of each player's net loss (or volume).
