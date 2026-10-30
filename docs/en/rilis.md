# Releasing

*English version of [docs/rilis.md](../rilis.md).*

## Building the release files

```bash
./gradlew build mrpack
```

- `build/libs/mine2metal-<version>.jar`: the mod, with `libmine2metal.dylib` (arm64, macOS 14+) and the MIT licenses of mine2metal and AMD in `META-INF/licenses`.
- `build/distributions/mine2metal-<version>.mrpack`: the modpack for the Modrinth App and Prism Launcher.
  - Minecraft, Fabric Loader and Sodium are downloaded by the launcher from their official sources. Sodium comes from Modrinth's CDN (`sodium_mrpack_url` in `gradle.properties`); its hash and size are computed from the Sodium artifact the build uses.
  - mine2metal rides along in `overrides/mods` until the mod is on Modrinth. After that its jar can be referenced through the CDN like Sodium.
- The version is `version` in `gradle.properties`. When Sodium changes, `sodium_version` and `sodium_mrpack_url` must point at the same file.

Tests before uploading:
- `./gradlew runProductionClient` runs the release jar and Sodium in a production client (no dev classpath), under `build/production-run`.
- `JAVA_TOOL_OPTIONS="-Dos.version=13.6 -Dmine2metal.test.fallbackOnly=true" ./gradlew runClientGameTest` checks the unsupported-Mac path: the game uses OpenGL and the title screen explains why.

## Modrinth / CurseForge page

- **Loader:** Fabric. **Game version:** 26.3. **Environment:** client only.
- **Dependencies:** Sodium (optional/recommended). Fabric API is not needed.
- **License:** MIT. **Icon:** `src/main/resources/assets/mine2metal/icon.png`.
- **Categories:** Optimization, Utility.

Description (for the project page):

> **mine2metal** renders Minecraft with Apple's Metal on Macs with Apple silicon instead of OpenGL, which macOS has long stopped improving. It plugs into Minecraft's own graphics layer and works alongside Sodium.
>
> - Higher frame rates than OpenGL, most of all in busy scenes and at long render distances (Metal 4 on macOS 26 or later, Metal 3 otherwise).
> - Upscaling: MetalFX Spatial and Temporal, and AMD FSR 1 and FSR 3.1. Your hand and the HUD stay at full resolution.
> - Frame generation: MetalFX frame interpolation (macOS 26 or later) or AMD FSR 3.1, paced to your display. It pauses itself if it costs more than it gains.
> - HDR (EDR) on displays that support it. The world's highlights go brighter than white while the GUI stays as it is.
> - Settings under Video Settings: in Sodium's sidebar, or a "Metal Settings..." button without Sodium.
>
> **Requirements:** a Mac with Apple silicon (M1 or later), macOS 14 or later, Minecraft 26.3, and Fabric Loader 0.20.1 or later. On any other computer the mod steps aside: the game uses its usual graphics API and the title screen says why.
>
> **Known limits:** mods that call OpenGL directly will not draw. To turn Metal off, set Graphics API to OpenGL in Video Settings.
>
> AMD FidelityFX SDK code is included under its MIT license.

## Release state (2026-10-31)

- Public repository: https://github.com/HoshizoraAkira/Mine2Metal. The homepage, source and issue links in `fabric.mod.json` point there.
- GitHub Release `v0.0.1` holds `mine2metal-0.0.1.jar` and `mine2metal-0.0.1.mrpack` with SHA-256 checksums.
- The Modrinth page is being created by the owner with the text above; CurseForge not yet. Both need the owner's accounts.
