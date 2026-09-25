#!/usr/bin/env python3
"""Render exported production components with the shared Minecraft chat renderer."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import zipfile

import yaml


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ops", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--client-jar", type=Path, required=True)
    parser.add_argument("--resource-pack", type=Path, required=True)
    parser.add_argument("--faithful32", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    module_path = args.ops / ".agents/skills/draw-pixel-art-icons/scripts/render_chat_preview.py"
    spec = importlib.util.spec_from_file_location("crate_chat_preview", module_path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    core = module.CORE
    content = args.ops / "classic/plugins/ItemsAdder/contents/arc"
    definition = yaml.safe_load((content / "configs/crate_messages.yml").read_text())["font_images"]["crate_key_large"]
    symbol = definition["symbol"]
    bitmap = content / "resourcepack/arc/textures" / Path(definition["path"]).with_suffix(".png")
    provider = {"type": "bitmap", "file": "arc:glyphs/crate_key_large.png", "ascent": definition["y_position"], "height": definition["scale_ratio"], "chars": [symbol]}
    args.output.mkdir(parents=True, exist_ok=True)
    preview_pack = args.output / "preview-pack.zip"
    font_path = "assets/minecraft/font/default.json"
    with zipfile.ZipFile(args.resource_pack) as source, zipfile.ZipFile(preview_pack, "w") as target:
        font = json.loads(source.read(font_path))
        providers = font["providers"]
        matching = [p for p in providers if symbol in "".join(p.get("chars", []))]
        if matching and matching != [provider]:
            raise ValueError("U+E531 provider differs from source registration")
        if not matching:
            providers.insert(0, provider)
        for item in source.infolist():
            if item.filename not in {font_path, "assets/arc/textures/glyphs/crate_key_large.png"}:
                target.writestr(item, source.read(item.filename))
        target.writestr(font_path, json.dumps(font, ensure_ascii=False))
        target.writestr("assets/arc/textures/glyphs/crate_key_large.png", bitmap.read_bytes())
    fixtures = json.loads(args.fixtures.read_text())
    if isinstance(fixtures, dict):
        fixtures = fixtures["notices"]
    records = []
    for name, faithful in (("vanilla", None), ("faithful32", args.faithful32)):
        stack = core.load_candidate_stack(args.client_jar, preview_pack, core.load_icon(bitmap), ord(symbol), faithful32=faithful, raster_scale=2)
        for p in providers:
            if p.get("type") in {"space", "minecraft:space"}:
                stack.advances.update({ord(k): float(v) for k, v in p["advances"].items()})
        if stack.candidate_advance != 23:
            raise ValueError(f"Expected 23px key advance, got {stack.candidate_advance}")
        for fixture in fixtures:
            lines = tuple(fixture.get("legacyAmpersand", fixture["legacy"]).split("\n"))
            widths = [core.measured_width(line, stack.advances) for line in lines]
            if max(widths) > 320:
                raise ValueError(f"{fixture['id']} exceeds Minecraft chat width: {max(widths)}")
            panel = module.render_clean_panel(lines, stack)
            destination = args.output / f"{fixture['id']}-{name}.png"
            panel.save(destination)
            records.append({"id": fixture["id"], "font": name, "widths": widths, "file": str(destination)})
    def checksum(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    (args.output / "metrics.json").write_text(json.dumps({
        "kind": "offline preview of exported production components; not a client screenshot",
        "fixtures_sha256": checksum(args.fixtures), "bitmap_sha256": checksum(bitmap),
        "pack_sha256": checksum(args.resource_pack), "client_sha256": checksum(args.client_jar),
        "provider": provider, "gui_scale": 2, "chat_line_step": 9, "notices": records,
    }, ensure_ascii=False, indent=2) + "\n")
    print(f"Rendered {len(records)} exact-component previews to {args.output}")


if __name__ == "__main__":
    main()
