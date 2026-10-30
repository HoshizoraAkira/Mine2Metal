# Manual testing

*English version of [docs/uji-manual.md](../uji-manual.md).*

The automated game tests (`./gradlew runClientGameTest`) already check a number of things: the title screen, the world (spawn, from above, at night, render distance 16), improved transparency, fullscreen, resizing, backend selection, and Metal API/shader validation. Their results are compared pixel by pixel with OpenGL. The list below holds what can only be judged by playing.

## How to run

Dev environment (Sodium is loaded too):

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk.jdk/Contents/Home ./gradlew runClient
```

Add `JAVA_TOOL_OPTIONS="-Dmine2metal.stats=true"` to log FPS and GPU time every 5 seconds.

Official launcher:

1. Install Fabric Loader ≥ 0.20.1 for Minecraft 26.3 with the [Fabric installer](https://fabricmc.net/use/installer/) and make a separate profile.
2. Copy `build/libs/mine2metal-0.0.1.jar` into that profile's `mods` folder (default: `~/Library/Application Support/minecraft/mods`). Sodium may come along; Fabric API is not required.
3. Run the profile. Press F3: the right column must show `Metal 3 (mine2metal)`.

## Checklist

| # | What to check | Expected |
|---|---|---|
| 1 | F3 | The backend reads `Metal 3 (mine2metal)` |
| 2 | Moving, jumping, flying, turning fast | No flicker, tearing or missing objects |
| 3 | Breaking and placing blocks | Chunks update at once, block cracks are visible |
| 4 | Water, stained glass, ice (up close and from under water) | Transparency and water fog are right |
| 5 | Rain, thunderstorm, snow | Weather particles and lightning show |
| 6 | The Nether and the End | Sky, fog and portals are right |
| 7 | Inventory, chat, pause menu, Options | GUI, text and 3D items in slots are right |
| 8 | Video Settings: change render distance, GUI scale, graphics preset, Improved Transparency | Switches without crashes or broken images |
| 9 | Vsync on/off, Max Framerate; with the Metal Performance HUD on | With vsync motion is smooth and FPS sticks to the refresh rate (60); without vsync FPS rises and there is no periodic stutter. Input with vsync on does not feel later than before |
| 10 | F11 (fullscreen), leaving fullscreen, resizing the window with the mouse | The image follows at once, neither squashed nor blurred |
| 11 | Moving the window to another monitor (if any), minimise and restore, Cmd+Tab | Still normal after coming back |
| 12 | F2 (screenshot) | The file in `screenshots/` is not flipped and its colors are right |
| 13 | Play for 15–30 minutes | No steady FPS decline or ever-growing memory |
| 14 | Video Settings → Graphics API → OpenGL, then restart | F3 shows OpenGL (the Metal backend is off); set it back to Default for Metal again |
| 15 | Video Settings → Metal (Sodium) or Metal Settings... (vanilla): Upscaling Spatial, drag Render Scale 50 % ↔ 100 % | The world gets softer at low scales but keeps sharp edges; menus, HUD and text stay sharp; no flicker while dragging |
| 16 | Upscaling Spatial, then resize the window and F11 | The image follows at once, not squashed |
| 17 | Metal menu → Frame Interpolation: on, then off | On macOS 26+ it can be chosen and applies at once; F3 shows a render FPS around half the display's refresh (≈30 at 60 Hz), and right below it the line `… fps on screen, MetalFX/FSR 3 frame generation` with ≈60 frames shown (same as the Metal Performance HUD). Off again, FPS returns to what it was |
| 24 | Frame Interpolation on: turn the camera slowly and fast, fly forward, open the inventory and chat, change the GUI scale | Motion smoother than 30 FPS without interpolation; hotbar, crosshair, text and hand stay sharp without double images; blocks do not flicker while the camera turns. Moving mobs and water may wobble a little |
| 26 | Metal menu → HDR (EDR): turn on with the macOS brightness slider below maximum, daytime outdoors, then approach lava or glowstone; drag HDR Brightness 125 % ↔ 400 % | Sky, sun and lava look brighter than ordinary white without broken colors; hotbar, text, chat and hand stay as bright as usual; dragging the slider changes the brightness at once. The HDR tooltip names how many times white the display can show |
| 27 | HDR on, then Frame Interpolation and Temporal Upscaling; open the pause menu and inventory; F2 | No bright/dark flicker between frames; with a menu open the world behind it does not glare; F2 screenshots stay plain SDR |
| 28 | Metal menu → Frame Generation: Off, MetalFX, FSR 3.1 in turn at the same spot (walking towards the forest), with the Metal Performance HUD on | Both engines can be chosen without a crash; the first pick of FSR 3.1 may hitch ~3 seconds (shader compile). Motion is equally smooth; FSR 3.1 gives a higher render FPS than MetalFX at large resolutions. Hotbar, text and hand stay sharp with both |
| 29 | Frame Generation on (MetalFX or FSR 3.1) with the Metal Performance HUD; then cover the game window completely with another window for a few seconds, and uncover it | The HUD shows ~60 frames shown with a flat frame-time graph; motion does not alternate smooth/choppy. After uncovering, the game continues at once without a long hitch |
| 30 | Frame Generation MetalFX in fullscreen Retina without upscaling (heavy), then FSR 3.1 + Temporal Upscaling 50 % | With heavy MetalFX: within seconds a toast "Frame generation paused" with the cost appears, the game runs smoothly again, then frame gen is retried every 30 seconds (no further toast). With FSR + Temporal: not paused if the game stays at ~30 render FPS. Changing the choice in the menu cancels the pause at once |
| 31 | Temporal Upscaling 50 %, standing still near a torch, campfire or portal, then watching mobs walk and arrows fly | Particles (smoke, fire, portal purple) stay bright and flicker as without upscaling, neither faded nor smeared; mobs and arrows leave no ghost. Compare with `-Dmine2metal.temporal.reactiveMask=false` |
| 32 | Upscaling FSR 1 then FSR 3.1 at 50 % (and FSR 3.1 at 100 %), walking and turning in a forest; then FSR 3.1 + Frame Generation FSR 3.1 | Both choices appear in the Sodium menu and the Metal Settings screen; switching does not crash. FSR 1 as sharp as or sharper than MetalFX Spatial without excessive halos; FSR 3.1 stable when still, no ghosting or flicker when turning. FPS slightly below MetalFX |
| 25 | Frame Interpolation + Temporal Upscaling 50 %, VSync on then off; F11 and window resizing | Still smooth without tearing; no stale image after resizing; input feels slightly delayed (about one render frame) |
| 20 | Temporal Upscaling, Render Scale 50 % then 75 % and 100 %; still, then turn the camera and fly forward | When still, the world is nearly as sharp as native (sharper than Spatial 50 %) and block edges are smooth without jaggies; when moving, no double images on blocks. Fast mobs may leave a faint trail. The hand is always sharp |
| 21 | Temporal Upscaling, then /tp far away, change dimension, F11, resize the window | No stale image mixed in after the move |
| 22 | Metal menu → Graphics API → Metal version: Auto, Metal 4, Metal 3 (restart after each) | F3 shows the driver as "Metal 4 (mine2metal)" or "Metal 3 (mine2metal)" as chosen (Auto = Metal 4 on macOS 26+); the tooltip names the version in use; the world looks the same on both |
| 23 | Metal 4: play 10 minutes with VSync on then off, upscaling Spatial and Temporal | No crash, black screen or flicker; FPS in busy scenes equal to or higher than Metal 3 |
| 18 | Metal menu → Diagnostics → Metal Performance HUD: on, then off | Apple's overlay (FPS, frame-time graph, GPU time, memory) appears in the window corner and goes away again; it stays after F11 or a restart as long as the option is on |
| 19 | Open the inventory with a compass and a clock in the hotbar, wait a few seconds | The compass and clock icons change cleanly, without stale images in the slot |

If something is wrong: note the row number, take a screenshot (F2) and keep `logs/latest.log`. To find out whether the problem comes from Metal, run again with `-Dmine2metal.enabled=false` and compare.
