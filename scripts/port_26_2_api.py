#!/usr/bin/env python3
"""One-time, idempotent post-mapping source migration for Minecraft 26.2.

ChatSorter 2.0 original Yarn -> Mojang mapping migration was performed on
Minecraft 1.21.11 by Fabric Loom. This patch addresses the first set of
Minecraft/Fabric 26.2 API package moves without deleting user features.
"""
from pathlib import Path
import re

ROOT = Path("src/main/java")

def split_args(text):
    """Split Java method arguments without splitting nested calls or strings."""
    result, last, depth, quote, escape = [], 0, 0, "", False
    for pos, char in enumerate(text):
        if quote:
            if escape:
                escape = False
            elif char == "\\\\":
                escape = True
            elif char == quote:
                quote = ""
        elif char in ("'", '"'):
            quote = char
        elif char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            result.append(text[last:pos].strip())
            last = pos + 1
    result.append(text[last:].strip())
    return result


def rewrite_draw_calls(text):
    pattern = re.compile(r"\\b([A-Za-z_][\\w]*)\\.(drawCenteredString|drawString)\\(")
    result, pos = [], 0
    for match in pattern.finditer(text):
        if match.start() < pos:
            continue
        index = match.end()
        depth, quote, escape = 1, "", False
        while index < len(text) and depth:
            ch = text[index]
            if quote:
                if escape:
                    escape = False
                elif ch == "\\\\":
                    escape = True
                elif ch == quote:
                    quote = ""
            elif ch in ("'", '"'):
                quote = ch
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            index += 1
        if depth or index >= len(text) + 1:
            continue
        args = split_args(text[match.end(): index - 1])
        if len(args) != 5:
            continue
        result.append(text[pos:match.start()])
        var, method = match.group(1, 2)
        if method == "drawString":
            expr = f"{var}.text({', '.join(args)}, true)"
        else:
            font, string, center_x, y, color = args
            expr = f"{var}.text({font}, {string}, ({center_x}) - {font}.width({string}) / 2, {y}, {color}, true)"
        result.append(expr)
        pos = index
    result.append(text[pos:])
    return "".join(result)


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
    source = rewrite_draw_calls(source)
    source = source.replace(".inGameHud.getChat()", ".gui.hud.getChat()")
    source = source.replace("Minecraft.getInstance().screen", "Minecraft.getInstance().gui.screen()")
    source = source.replace("Minecraft.getInstance().setScreen(", "Minecraft.getInstance().gui.setScreen(")
    source = re.sub(r"\\b(super)\\.render\\(context,", "super.extractRenderState(context,", source)
    source = re.sub(r"\\bvoid render\\(GuiGraphicsExtractor", "void extractRenderState(GuiGraphicsExtractor", source)
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
