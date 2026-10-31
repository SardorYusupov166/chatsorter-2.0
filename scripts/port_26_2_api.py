#!/usr/bin/env python3
"""One-time, idempotent post-mapping source migration for Minecraft 26.2.

ChatSorter 2.0 original Yarn -> Mojang mapping migration was performed on
Minecraft 1.21.11 by Fabric Loom. This patch addresses the first set of
Minecraft/Fabric 26.2 API package moves without deleting user features.
"""
from pathlib import Path
import re

ROOT = Path("src/main/java")
modified = []
for path in ROOT.rglob("*.java"):
    before = path.read_text(encoding="utf-8")
    source = before
    source = re.sub(r"\bGuiGraphics\b", "GuiGraphicsExtractor", source)
    source = re.sub(r"\bnet\.minecraft\.client\.GuiMessageTag\b",
                    "net.minecraft.client.multiplayer.chat.GuiMessageTag", source)
    source = re.sub(r"\bnet\.minecraft\.client\.GuiMessage\b",
                    "net.minecraft.client.multiplayer.chat.GuiMessage", source)
    source = re.sub(r"\bWorldRenderEvents\b", "LevelRenderEvents", source)
    source = re.sub(r"\bWorldRenderContext\b", "LevelRenderContext", source)
    source = source.replace("net.fabricmc.fabric.api.client.rendering.v1.world.LevelRenderEvents",
                            "net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents")
    source = source.replace("net.fabricmc.fabric.api.client.rendering.v1.world.LevelRenderContext",
                            "net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext")
    source = re.sub(r"\.gui\.getChat\(\)", ".gui.hud.getChat()", source)
    source = re.sub(r"\b(client|mc|minecraft)\.options\.hideGui\b",
                    lambda m: m[1] + ".gui.hud.isHidden()", source)
    source = re.sub(r"\b(client|mc|minecraft)\.screen\b",
                    lambda m: m[1] + ".gui.screen()", source)
    source = re.sub(r"\b(client|mc|minecraft)\.setScreen\(",
                    lambda m: m[1] + ".gui.setScreen(", source)
    if path.name == "ChatSorter.java":
        source = source.replace(
            "import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;",
            "import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;\n"
            "import net.minecraft.resources.Identifier;",
        )
        source = source.replace(
            "HudRenderCallback.EVENT.register((context, tickCounter) -> {",
            'HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("chatsorter", "unread_dm"), (context, tickCounter) -> {',
        )
        source = source.replace(".getGuiScaledWidth()", ".guiWidth()")
    if source != before:
        path.write_text(source, encoding="utf-8")
        modified.append(str(path))
print("First-pass 26.2 API migration changed", len(modified), "files:")
for path in modified:
    print(" -", path)
