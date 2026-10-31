# ChatSorter 2.0 → Minecraft 26.2 (Fabric) — WIP

**State:** incomplete port. This branch is **not installable**. Do not merge into main until CI build **and in-game tests** pass. Full 2.0 functionality (especially RPG world holograms and independent windows) must be preserved.

## Verified changes

- Original `2.0.0-alpha.20` Minecraft 1.21.11 source migrated from Yarn names to official Mojang mappings through Fabric Loom `migrateMappings` at 1.21.11.
- The initial Mojang-mapped **main Java sources** compiled on 1.21.11. Hologram geometry test sources still had Yarn imports; that phase's full build did not pass.
- Configured `gradle.properties`, `build.gradle`, `fabric.mod.json` for Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.161.0+26.2, Loom 1.18.2, Java 25 and Gradle 9.8.1.
- Added `scripts/port_26_2_api.py`, executed in CI, to update class names and GUI/HUD rendering methods. CI saves changes to the port branch and uploads compilation logs.
- 1.21.11 `main` untouched.

## Must finish before release

1. Port `ChatWorldRenderer` to the Minecraft 26.2 submission-based world renderer. `MultiBufferSource` has been removed; replace it with `LevelRenderEvents.COLLECT_SUBMITS`, `LevelRenderContext.submitNodeCollector()` and proper text/background submissions. Keep anchor/orientation behavior in first- and third-person.
2. Update `ChatHudMixin` targets and callback signatures. Old `ChatComponent.render` and `addMessage(Component, MessageSignature, GuiMessageTag)` descriptors are obsolete; 26.2 uses `extractRenderState` and `addMessage(Component, MessageSignature, GuiMessageSource, GuiMessageTag)`. Verify mixin application when launching.
3. Validate GUI extraction and `ChatPanelManager` independent windows; fix remaining compile errors (check `Actions` logs). Verify texture backgrounds and click/resize/drag.
4. Update `src/hologramTest` Yarn imports to Mojang names and pass `gradle build`.
5. Run in-client testing: connect/disconnect, history and timestamps, multiple independent panels, searches/regex, notifications, copying/selection, RPG world holograms (first/third person), hiding default HUD, persistence and chat performance. Compile-only does not verify these features.
6. Only then upload a validated remapped Fabric .jar artifact for MC 26.2.

## Diagnostics

GitHub Actions: https://github.com/SardorYusupov166/chatsorter-2.0/actions?query=branch%3Aport%2Fmc26.2

Draft pull request: https://github.com/SardorYusupov166/chatsorter-2.0/pull/2

Safe historical snapshot: main branch for Minecraft 1.21.11.

A separate **older 1.4.5** project is configured for 26.2 and has a successful CI run: https://github.com/SardorYusupov166/ChatSorter-26-1-2/actions/runs/39274224609. It is **not** a substitute for 2.0 features.
