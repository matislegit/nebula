# Nebula (Fabric, Minecraft 1.21.11, client-side)

Press **\\** (backslash) in a world to open the GUI. Press it again to close.
Rebind it in *Options > Controls > Key Binds > Nebula*.

## GUI
- **Left-click** a module = turn it on/off
- **Right-click** a module = open its dropdown (everything below slides down)

## Categories (left side of the GUI)
All / Client (empty for now) / Render (Block ESP) / Misc (Freecam)

## Freecam (Misc)
Your camera flies around (mouse = look, WASD = move, Space = up, Shift = down) while your character stays exactly
where it is and keeps facing the same way. Your hands still show, and attacking, eating and using items work as normal
from your real character's position.
- **Speed** slider: 1.0x (normal walking speed) to 10x
- **Keybind**: click the button, press a key to bind it, press **Esc** to clear it (shows NONE). It is the same
  binding as *Options > Controls > Key Binds > Toggle Freecam*.
- Freecam turns itself off if you die or change worlds.

## Block ESP
- **Blocks Selected: N** opens the block picker (every block in the game, with search).
  *Inactive* = not selected, *Active* = drawn by the ESP. Click a block to move it between tabs.
- **Opacity** slider = fill + outline brightness
- **Tracers** toggle = a line from your crosshair to every highlighted block
- Boxes/lines are drawn through walls. Each block type gets its own colour (shown as a dot on the Active tab).

Settings are saved to `.minecraft/config/nebula.json` (you can also raise `blockEspRadiusChunks`, default 6, max 16).

Limits: the 2000 nearest matching blocks are drawn per frame (selecting something like stone would otherwise freeze the game).

## Build
JDK 21, then `gradle build`. The jar is in `build/libs/nebula-1.0.0.jar` (not the `-sources` one).
A GitHub Actions workflow is included (`.github/workflows/build.yml`).
