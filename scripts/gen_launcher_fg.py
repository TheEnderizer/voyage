"""Strip the baked-in navy background out of the adaptive-icon foreground rasters.

The original `ic_launcher_fg.png` set was exported as the *complete* icon: a navy
gradient square with the cream sailboat and blue wave drawn on top, fully opaque
at every pixel. An adaptive icon draws its foreground layer over its background
layer at the same size, so an opaque foreground hides the background entirely —
which meant `@drawable/ic_launcher_background*` never showed anywhere, and the
per-variant icon colours (navy/midnight/ocean/ember/plum/minimal) all rendered
identically navy in the launcher AND in the in-app picker.

This script rewrites each density's foreground to hold only the sailboat + wave
on transparent pixels, so the background layer is what supplies the colour.

Method: the art separates cleanly by red channel — the navy background sits at
R<45, the cream (253,243,229) and wave blue (108,139,194) content at R>=100, and
only ~0.25% of pixels are antialiased blends in between. Background and content
colours for those blend pixels are estimated from their immediate neighbours,
then alpha is solved from P = a*F + (1-a)*B on the channel with the most
separation. Run against the original opaque exports; it is not idempotent.
"""

import sys
from pathlib import Path

import numpy as np
from PIL import Image

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
DENSITIES = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]

NAVY_MAX_R = 45      # every background pixel measured below this
CONTENT_MIN_R = 100  # every sailboat/wave pixel measured at or above this


def neighbour_mean(rgb, mask, radius):
    """Mean of `rgb` over `mask` pixels within a square radius, per pixel."""
    h, w, _ = rgb.shape
    acc = np.zeros((h, w, 3), np.float64)
    cnt = np.zeros((h, w), np.float64)
    src = rgb * mask[..., None]
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            acc += np.roll(np.roll(src, dy, 0), dx, 1)
            cnt += np.roll(np.roll(mask, dy, 0), dx, 1)
    cnt = np.maximum(cnt, 1e-6)
    return acc / cnt[..., None]


def strip(path: Path) -> None:
    rgb = np.asarray(Image.open(path).convert("RGB"), np.float64)
    red = rgb[..., 0]

    background = red < NAVY_MAX_R
    content = red >= CONTENT_MIN_R
    blend = ~background & ~content

    out = np.zeros(rgb.shape[:2] + (4,), np.float64)
    out[..., :3] = rgb
    out[content, 3] = 255.0

    if blend.any():
        # Estimate what each blend pixel sits between, from nearby pure pixels.
        bg = neighbour_mean(rgb, background.astype(np.float64), 3)
        fg = neighbour_mean(rgb, content.astype(np.float64), 3)
        delta = fg - bg
        # Solve per channel, then trust the channel with the widest separation.
        with np.errstate(divide="ignore", invalid="ignore"):
            alpha = np.where(np.abs(delta) > 1e-6, (rgb - bg) / delta, 0.0)
        pick = np.argmax(np.abs(delta), axis=2)
        alpha = np.take_along_axis(alpha, pick[..., None], axis=2)[..., 0]
        out[blend, 3] = np.clip(alpha[blend], 0.0, 1.0) * 255.0
        out[blend, :3] = fg[blend]  # unmixed colour, not the blended pixel

    Image.fromarray(out.round().astype(np.uint8), "RGBA").save(path)
    kept = float((out[..., 3] > 0).mean()) * 100
    print(f"{path.parent.name:>8}: {kept:5.2f}% of pixels kept")


def main() -> int:
    for density in DENSITIES:
        target = RES / f"mipmap-{density}" / "ic_launcher_fg.png"
        if not target.exists():
            print(f"missing: {target}", file=sys.stderr)
            return 1
        strip(target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
