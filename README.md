# Nebula (Fabric, Minecraft 1.21.11, client-side)

Press **\\** (backslash) in a world to open the GUI. Press it again to close.
Rebind it in *Options > Controls > Key Binds > Nebula*.

## GUI
- **Left-click** a module = turn it on/off
- **Right-click** a module = open its dropdown (everything below slides down)

## Categories (left side of the GUI)
All / Client (HUD) / Render (Block ESP, Swing Speed, Sus Chunk Finder, Live Player Detector) / Misc (Freecam, Skin Protect)

Modules are shown two per row in a scrollable box (mouse wheel). A module's settings open full width under its row.

## Sus Chunk Finder (Render)
Marks chunks that look like players have been there with a purple layer at Y 45, through walls. It looks for growth and
placement signs (each can be toggled): fully grown **amethyst** clusters, fully grown **kelp** (age 25), fully grown
**cave vines**, very long **vines**, fully grown **bamboo**, and **rotated deepslate** (worldgen only places it upright).
The **Sensitivity** slider (1-10) decides how much evidence a chunk needs: higher = more chunks marked but more false
alarms. Flagged chunks stay marked for the session. Bamboo, vines and amethyst can also occur naturally, so they are weighted low.

## Live Player Detector (Render)
Marks the chunk of every other player your game can currently see (same chunk or nearby) with a color-cycling rainbow
layer at Y 45. No settings.

## HUD (Client)
Shows a list of every enabled module on screen. Right-click it to choose **Left** or **Right**.

## Swing Speed (Render)
Changes how fast your swing animation plays (punching, mining, placing, using items; first and third person).
**15 = normal speed**, lower = slower (down to 1), higher = faster (up to 30). Visual only: attack timing and
cooldowns are unchanged. Eating/drinking animations aren't affected (they don't use the swing).

## Skin Protect (Misc)
Draws your own character with another player's skin. Right-click the module, type a username (default **Nebula**);
it applies a moment after you stop typing. Only you see the change (handy for streaming/recording); other players
still see your real skin, and your name stays the same.

## Freecam (Misc)
Your camera flies around (mouse = look, WASD = move, Space = up, Shift = down) while your character stays exactly
where it is and keeps facing the same way. Your hands still show, and attacking, eating and using items work as normal
from your real character's position.
- **Speed** slider: 1.0x (normal walking speed) to 10x
- **Keybind**: click the button, then press a key **or middle / side mouse button** to bind it. Press **Esc** to clear it
  (shows NONE); left / right click just cancels. It is the same binding as *Options > Controls > Key Binds > Toggle Freecam*.
- Your character's body is visible while in freecam.
- The camera stays inside the chunks that are already loaded/rendered around you, so no new chunks are shown.
- 1.0x is 15 blocks/second (`freecamBlocksPerSecond` in `nebula.json`).
- Freecam turns itself off if you die or change worlds.

## Block ESP
- **Blocks Selected: N** opens the block picker (every block in the game, with search).
  *Inactive* = not selected, *Active* = drawn by the ESP. Click a block to move it between tabs.
- **Opacity** slider = fill + outline brightness
- **Tracers** toggle = a line from your crosshair to every highlighted block
- Boxes/lines are drawn through walls. Each block type gets its own colour (shown as a dot on the Active tab).

Settings are saved to `.minecraft/config/nebula.json`.

The ESP scans your whole render distance (set `blockEspRadiusOverride` to force a chunk radius) and highlights every selected block it finds.
Safety limits: up to 20,000 blocks are drawn per frame (nearest first), only the nearest 4,000 get outlines, and each chunk reports at most 2,048 matches, so selecting something as common as stone won't freeze the game.

## Build
JDK 21, then `gradle build`. The jar is in `build/libs/nebula-1.0.0.jar` (not the `-sources` one).
A GitHub Actions workflow is included (`.github/workflows/build.yml`).
