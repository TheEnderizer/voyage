"""Turn a finished square app-icon PNG into an Android adaptive-icon pair.

The sibling script `gen_launcher_fg.py` strips a known flat navy out of the original sailboat
export so the mark can sit over a swappable background. This one solves the opposite problem:
artwork that is already a *complete* icon — its own tile, its own ground, its own mark — which
should reach the launcher looking exactly like the file, not reinterpreted.

The one thing that cannot be skipped is the scale. An adaptive icon is two 108dp layers and the
launcher only ever shows the middle 72dp of them; hand the whole tile over at full size and a
third of every edge is cropped away, beheading the mark. So the artwork is placed at 72/108 of
the canvas, which is what makes the visible result identical to the source file.

Around it goes a background sampled from the artwork's own edges. At rest it is never seen (the
tile covers the whole visible area); it exists so a launcher that parallaxes or zooms into the
18dp margin finds the icon's own colour there instead of a transparent hole.

Note this deliberately inverts CLAUDE.md's "the foreground must stay transparent outside the mark"
rule. That rule is about the six colourway variants, which share one mark and need the background
layer to supply their colour. Here the ground is part of the authored artwork and there is nothing
to swap underneath.

    python scripts/gen_launcher_fg_from_art.py <art.png> <variant> [hue_shift_deg sat_scale]

Writes mipmap-*/ic_launcher_fg_<variant>.png at all five densities plus a monochrome silhouette,
and prints the background gradient stops for drawable/ic_launcher_background_<variant>.xml.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app", "src", "main", "res")

# 108dp canvas at each density bucket.
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# The launcher shows the middle 72 of the 108dp canvas; the rest is crop and parallax headroom.
VISIBLE_FRACTION = 72.0 / 108.0


def tile_bounds(img):
    """The rounded tile inside the export, ignoring any surround it was mounted on."""
    w, h = img.size
    px = img.convert("RGB").load()
    corner = px[2, 2]

    def differs(p):
        return max(abs(p[i] - corner[i]) for i in range(3)) > 3

    mid_y, mid_x = h // 2, w // 2
    left = next((x for x in range(w) if differs(px[x, mid_y])), 0)
    right = next((x for x in range(w - 1, -1, -1) if differs(px[x, mid_y])), w - 1)
    top = next((y for y in range(h) if differs(px[mid_x, y])), 0)
    bottom = next((y for y in range(h - 1, -1, -1) if differs(px[mid_x, y])), h - 1)
    return (left, top, right + 1, bottom + 1)


def flatten_to_full_bleed(img, radius_frac=0.19, rim_frac=0.04):
    """Push the artwork's ground out to a full square, discarding its tile shape and edge rim.

    A finished icon export carries its own rounded corners and usually a bright border highlight.
    Ship that as-is and the launcher masks a rounded square that already has rounded corners, so
    the icon reads as a small tile floating inside a bigger one with a seam between them. The shape
    has to come from the launcher's mask and nothing else.

    The replacement ground is sampled per row, so it follows the artwork's own vertical gradient
    rather than flattening it to one colour. Rows inside the corner bands reuse the nearest row
    that has full-width ground to sample from — those pixels sit outside every launcher mask
    anyway, so what matters is only that they are the icon's colour and not black.

    Deliberately geometric rather than keyed off the tile edge: on an export whose surround is the
    same colour as its ground, edge detection finds the *mark* instead and floods that across the
    row.
    """
    tile = img.convert("RGB").crop(tile_bounds(img))
    w, h = tile.size
    px = tile.load()
    r = int(min(w, h) * radius_frac)
    # Generous: the trim has to clear both the tile's bright border stroke and any
    # surround the crop could not tell apart from the ground. No mark in these exports
    # comes within 13% of the edge, so there is nothing to lose by taking more.
    rim = max(2, int(min(w, h) * rim_frac))
    gx0, gx1 = int(w * 0.04), int(w * 0.08)

    def ground(y):
        samples = ([px[x, y] for x in range(gx0, gx1)] +
                   [px[x, y] for x in range(w - gx1, w - gx0)])
        samples.sort(key=sum)
        return samples[len(samples) // 2]

    rows = [ground(max(r, min(h - r - 1, y))) for y in range(h)]

    filler = Image.new("RGB", (w, h))
    fp = filler.load()
    for y in range(h):
        c = rows[y]
        for x in range(w):
            fp[x, y] = c

    # Keep only what is inside the tile shrunk by the rim; everything else becomes ground.
    keep = Image.new("L", (w, h), 0)
    ImageDraw.Draw(keep).rounded_rectangle(
        [rim, rim, w - rim - 1, h - rim - 1], radius=r, fill=255)
    keep = keep.filter(ImageFilter.GaussianBlur(rim * 0.35))

    out = filler.copy()
    out.paste(tile, (0, 0), keep)
    return out.convert("RGBA")


def edge_color(img, y_frac):
    """Median of a short run of pixels at both left and right edges of one row.

    Rows are sampled inset from the very edge because these exports carry a faint rim highlight,
    and taken from both sides so a mark that reaches one side cannot drag the sample with it.
    """
    w, h = img.size
    px = img.load()
    y = max(0, min(h - 1, int(h * y_frac)))
    inset = int(w * 0.05)
    run = max(4, int(w * 0.04))
    samples = ([px[x, y][:3] for x in range(inset, inset + run)] +
               [px[x, y][:3] for x in range(w - inset - run, w - inset)])
    samples.sort(key=lambda c: sum(c))
    return samples[len(samples) // 2]


def silhouette(img, size):
    """Themed-icon shape: the artwork's bright mark, keyed off its dark ground by luminance.

    Only alpha survives into a monochrome layer, so this needs the shape and nothing else. A
    luminance cut is crude but these grounds are near-black and every mark on them is bright, which
    is exactly the case it handles well.
    """
    small = img.convert("RGB").resize((size, size), Image.LANCZOS)
    px = small.load()
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    op = out.load()
    for y in range(size):
        for x in range(size):
            r, g, b = px[x, y]
            luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if luma > 95:
                op[x, y] = (0, 0, 0, 255)
    return out


def recolor(img, hue_shift, sat_scale):
    """Rotate the artwork's hue and optionally drain its saturation.

    Applied to the whole tile, ground included, so a warm mark ends up on a warm ground instead of
    floating on the original's blue. Rotation (rather than remapping to a chosen pair of colours)
    is deliberate: it carries the source's own hue *spread* along untouched, so a recolour keeps
    the two-tone fall-off that makes the mark read, and only the key changes.
    """
    if hue_shift == 0 and sat_scale == 1.0:
        return img
    hsv = img.convert("RGB").convert("HSV")
    h, s, v = hsv.split()
    step = int(round(hue_shift / 360.0 * 256)) % 256
    h = h.point(lambda p: (p + step) % 256)
    if sat_scale != 1.0:
        s = s.point(lambda p: max(0, min(255, int(p * sat_scale))))
    return Image.merge("HSV", (h, s, v)).convert("RGB").convert("RGBA")


def main():
    if len(sys.argv) not in (3, 5):
        print(__doc__)
        return 1
    src_path, variant = sys.argv[1], sys.argv[2]
    hue_shift = float(sys.argv[3]) if len(sys.argv) == 5 else 0.0
    sat_scale = float(sys.argv[4]) if len(sys.argv) == 5 else 1.0
    src = flatten_to_full_bleed(Image.open(src_path).convert("RGBA"))
    src = recolor(src, hue_shift, sat_scale)

    for bucket, size in DENSITIES.items():
        art = int(round(size * VISIBLE_FRACTION))
        layer = src.resize((art, art), Image.LANCZOS)
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        off = (size - art) // 2
        canvas.paste(layer, (off, off), layer)
        out_dir = os.path.join(RES, "mipmap-" + bucket)
        canvas.save(os.path.join(out_dir, "ic_launcher_fg_%s.png" % variant))

        mono_art = silhouette(src, art)
        mono = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        mono.paste(mono_art, (off, off), mono_art)
        mono.save(os.path.join(out_dir, "ic_launcher_mono_%s.png" % variant))

    # Sampled well inside the vertical extent: at 6%/94% the "edge" columns are still
    # inside the tile's own rounded corners, which on a black-cornered export reads as
    # pure black rather than as the icon's ground.
    top = edge_color(src, 0.22)
    bottom = edge_color(src, 0.86)
    print("variant    : %s" % variant)
    print("source     : %dx%d -> artwork at %.1f%% of the 108dp canvas"
          % (src.size + (VISIBLE_FRACTION * 100,)))
    print("background : top #FF%02X%02X%02X  bottom #FF%02X%02X%02X" % (top + bottom))
    return 0


if __name__ == "__main__":
    sys.exit(main())
