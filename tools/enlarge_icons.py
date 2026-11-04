#!/usr/bin/env python3
"""Render high-resolution achievement icons from the original texture atlases."""

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
UI_DIR = ROOT / "img" / "beta-ui"
TARGETS = (
    ("icon_32x", 32),
    ("icon_256x", 256),
)

ITEM_ICON_INDICES = {
    "openInventory": 59,
    "buildPickaxe": 96,
    "acquireIron": 23,
    "buildHoe": 128,
    "makeBread": 41,
    "bakeCake": 29,
    "buildBetterPickaxe": 97,
    "cookFish": 90,
    "buildSword": 64,
    "killEnemy": 28,
    "killCow": 103,
    "flyPig": 104,
}

# Top, left and right texture indices from the Beta 1.7.3 block renderer.
ISOMETRIC_ICONS = {
    "mineWood": (21, 20, 20),
    "buildWorkBench": (43, 59, 60),
    "buildFurnace": (62, 45, 61),
}

SPECIAL_ACHIEVEMENTS = {"onARail", "flyPig"}


def tile(atlas: Image.Image, texture_index: int) -> Image.Image:
    column = texture_index % 16
    row = texture_index // 16
    return atlas.crop((column * 16, row * 16, column * 16 + 16, row * 16 + 16))


def draw_face(
    destination: Image.Image,
    texture: Image.Image,
    origin_grid: tuple[float, float],
    du_grid: tuple[float, float],
    dv_grid: tuple[float, float],
    brightness: float,
) -> None:
    """Project a 16x16 texture onto a parallelogram at target resolution."""
    size = destination.width
    scale = size / 16
    ox, oy = origin_grid[0] * scale, origin_grid[1] * scale
    ax, ay = du_grid[0] * scale, du_grid[1] * scale
    bx, by = dv_grid[0] * scale, dv_grid[1] * scale
    determinant = ax * by - ay * bx
    if determinant == 0:
        raise ValueError("Face vectors must not be parallel")

    pixels = destination.load()
    source = texture.load()
    for y in range(size):
        for x in range(size):
            px = x + 0.5 - ox
            py = y + 0.5 - oy
            u = (px * by - py * bx) / determinant
            v = (-px * ay + py * ax) / determinant
            if not (0 <= u < 1 and 0 <= v < 1):
                continue
            sx = min(15, int(u * 16))
            sy = min(15, int(v * 16))
            red, green, blue, alpha = source[sx, sy]
            if alpha:
                pixels[x, y] = (
                    round(red * brightness),
                    round(green * brightness),
                    round(blue * brightness),
                    alpha,
                )


def isometric_block(
    terrain: Image.Image,
    texture_indices: tuple[int, int, int],
    size: int,
) -> Image.Image:
    top, left, right = texture_indices
    output = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw_face(output, tile(terrain, top), (1, 4), (7, -3), (7, 3), 1.0)
    draw_face(output, tile(terrain, left), (1, 4), (7, 3), (0, 8), 0.8)
    draw_face(output, tile(terrain, right), (8, 7), (7, -3), (0, 8), 0.6)
    return output


def foreground_for(
    name: str,
    size: int,
    items: Image.Image,
    terrain: Image.Image,
) -> Image.Image:
    if name in ITEM_ICON_INDICES:
        return tile(items, ITEM_ICON_INDICES[name]).resize(
            (size, size),
            Image.Resampling.NEAREST,
        )
    if name in ISOMETRIC_ICONS:
        return isometric_block(terrain, ISOMETRIC_ICONS[name], size)
    if name == "onARail":
        return tile(terrain, 128).resize((size, size), Image.Resampling.NEAREST)
    raise KeyError(f"No icon source mapping for {name}")


def main() -> None:
    source_names = {path.stem for path in (ROOT / "img" / "icon").glob("*.png")}
    if len(source_names) != 16:
        raise SystemExit(f"Expected 16 original icons, found {len(source_names)}")

    items = Image.open(UI_DIR / "items.png").convert("RGBA")
    terrain = Image.open(UI_DIR / "terrain.png").convert("RGBA")
    frames = Image.open(UI_DIR / "achievement-bg.png").convert("RGBA")
    normal_frame = frames.crop((0, 202, 26, 228))
    special_frame = frames.crop((26, 202, 52, 228))

    for directory_name, size in TARGETS:
        output_dir = ROOT / "img" / directory_name
        output_dir.mkdir(parents=True, exist_ok=True)
        foreground_size = round(16 * size / 26)
        foreground_offset = round(5 * size / 26)

        for name in sorted(source_names):
            selected_frame = special_frame if name in SPECIAL_ACHIEVEMENTS else normal_frame
            icon = selected_frame.resize((size, size), Image.Resampling.NEAREST)
            foreground = foreground_for(name, foreground_size, items, terrain)
            icon.alpha_composite(foreground, (foreground_offset, foreground_offset))
            icon.save(output_dir / f"{name}.png", optimize=True)

        print(f"{directory_name}: {len(source_names)} icons rendered at {size}x{size}")


if __name__ == "__main__":
    main()
