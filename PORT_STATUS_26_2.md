# ChatSorter 2.0 Alpha.20 — Minecraft 26.2 (Fabric)

**Status: testable port, not a stable release.** The complete Gradle build and a headless graphical Minecraft 26.2 client startup smoke test both **passed** on 31 October 2026. Actual chat interaction, connections to servers, RPG holograms and multi-window interaction still need manual in-game verification before merging this branch into main.

## Build and download (current)
- CI build: https://github.com/SardorYusupov166/chatsorter-2.0/actions/runs/41189733310
- Download Minecraft 26.2 JAR artifact: https://github.com/SardorYusupov166/chatsorter-2.0/actions/runs/41189733310/artifacts/13038611110
- Client smoke test: https://github.com/SardorYusupov166/chatsorter-2.0/actions/runs/41189733311

## Completed
- Ported original 2.0 Alpha.20 sources from Yarn to Mojang mappings through Loom migration at Minecraft 1.21.11.
- Updated Minecraft 26.2, Java 25, Fabric Loader 0.19.3, Fabric API 0.161.0+26.2, Loom 1.18.2 and Gradle 9.8.1.
- Migrated old GUI/HUD class names and screen extraction APIs.
- Migrated RPG world renderer from obsolete `MultiBufferSource` and `END_MAIN` to `LevelRenderEvents.COLLECT_SUBMITS`, `SubmitNodeCollector`, `submitText` and custom background geometry.
- Migrated chat interception code and Java25 Mixin configuration. Updated hologram test mocks to Mojang VertexConsumer API.
- `gradle build` and hologram layout/quad tests passed in GitHub Actions; binary artifact created.
- Startup smoke test ran `gradle runClient` with Mesa software GL and Xvfb. Minecraft 26.2 initialized the mod and its rendering callback and remained alive through the test window. An earlier failure caused by the removed `DrawnTextConsumer$ClickHandler` constructor was resolved by making that obsolete injection optional. **Click-target displacement still needs an explicit functional test.**
- The original Minecraft 1.21.11 `main` branch remains unchanged.

## Open testing / potential regressions
1. Connect to MigosMC. Verify capture of all chat formats (global/local, DM, Spy, Console) and that messages retain proper colors and player heads.
2. Open chat; test movable/resizable **independent windows**, drag/drop, scrolling, click/hover, message hyperlinks/copy/selection, notifications, regex filters and per-server chat history.
3. Enable RPG world holograms; verify they appear, follow the player, respect first/third person view and distance/occlusion, and don't trigger unexpected black rectangles or duplicate fallback vanilla chat.
4. Verify transitions to menus, disconnect/reconnect and hotkey setup.
5. Inspect Minecraft latest.log for warnings or Mixin errors when actually opening the chat or joining world. Startup-only smoke does not exercise these features.

**Do not merge or publish as stable until manual tests pass.**

## References
- Draft PR: https://github.com/SardorYusupov166/chatsorter-2.0/pull/2
- CI: https://github.com/SardorYusupov166/chatsorter-2.0/actions?query=branch%3Aport%2Fmc26.2
- Older 1.4.5 version also targeted 26.2: https://github.com/SardorYusupov166/ChatSorter-26-1-2/actions/runs/39274224609
