"""Turn one of the authored icon exports into a FLAT adaptive icon.

`gen_launcher_fg_from_art.py` ships the artwork as authored -- its own ground, its own gradient,
its own two-tone fall-off -- and therefore has to put the whole tile in the foreground layer. This
script does the opposite, and goes back to the conventional adaptive-icon split that CLAUDE.md
describes: **the background layer is one flat colour, and the foreground is the mark alone with
transparency everywhere else.**

Getting the mark out is the whole problem, and the obvious method does not work. These are soft
3D-ish renders: the V's front page ramps from a bright head to a shaded foot that is *darker than
the lit head of the page behind it*, so the two pages' brightness ranges overlap and NO threshold
separates them. Thresholding high enough to exclude the back pages welds the layers into one blank
wedge; thresholding low enough to keep them fuses everything into one blob; posterising into tone
bands just draws contour lines through the middle of a page, because that is where the iso-
luminance curves actually run. Component labelling does not rescue it either -- measured, the front
and middle pages come out as one connected region.

What IS reliable is that the artwork draws a dark trough between every pair of pages. A black-hat
(closing minus original) responds to a thin dark line at whatever brightness it happens to sit on,
so the seams can be found even though the regions they separate cannot be told apart. Hence the
shape this script builds:

    silhouette (everything above the ground) MINUS seams (the artwork's own dividing troughs)

filled with one flat ink. Which is how a flat icon draws a stack of pages anyway -- a solid shape
with the divisions cut back out of it as negative space.

    python scripts/gen_launcher_flat.py <art.png> <variant> <#ground> <#ink> [ground_cut]

Writes mipmap-*/ic_launcher_fg_<variant>.png at all five densities, a monochrome mask alongside,
drawable/ic_launcher_background_<variant>.xml as a solid fill, and both adaptive-icon XMLs.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app", "src", "main", "res")

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# The launcher shows the middle 72 of the 108dp canvas, and this is the mark's long side inside it.
#
# Two constraints pin it, and they agree. The artwork draws its mark at about 0.74 (Vee) and 0.82
# (Voyager) of its own tile, so reproducing the authored proportion puts the long side near 53-59
# on this canvas -- anything larger is not "bigger", it is a crop of a different composition. And
# Android's mask can be a circle, which cuts the bounding box's CORNERS: with these marks' aspect
# ratios the diagonal is ~1.27x the long side, so a long side over ~56 loses the V's outer page
# strokes to a circular launcher. 54 sits under both.
MARK_FRACTION = 54.0 / 108.0

# Where the tile's ground ends and the mark begins. Per-icon, because it is not really a property
# of the algorithm: Vee sits on a near-black ground and its darkest page is still well clear of it
# (30 is plenty), while Voyager's sail rises out of a broad soft glow that has no flat equivalent
# and has to be cut away instead of solidified (60 keeps the trail a tapered stroke rather than the
# amorphous blob a low cut turns it into).
DEFAULT_GROUND_CUT = 30

# Black-hat kernel and response threshold for the seams. 15px at these sources spans a page divide
# comfortably; 34 is high enough to ignore the gentle shading inside a page and low enough to catch
# every real trough.
SEAM_KERNEL = 15
SEAM_THRESHOLD = 34

# Cleans up the anti-aliasing crumbs a low cut picks up along the ground's gradient. 5 is safe here
# because the silhouette is a solid shape -- it was the earlier thin-sliver approach that a 5px
# opening severed.
OPEN_KERNEL = 5

# The exports carry a rim highlight and a corner glow just inside their own edge. Cropping past it
# is cheaper and more predictable than keying around it, and neither mark comes near its tile edge.
INSET_FRACTION = 0.07


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


def clear_frame_connected(mask):
    """Erase every white region that touches the frame.

    With the cut low enough to keep the mark's darkest pages, it is also low enough to catch the
    ground's corner glow and vignette. Those always reach the edge of the crop and the mark never
    does, so reachability from the border separates them where a brightness rule cannot -- the glow
    and the back page occupy the same luminances.
    """
    w, h = mask.size
    px = mask.load()
    for x in range(w):
        for y in (0, h - 1):
            if px[x, y] == 255:
                ImageDraw.floodfill(mask, (x, y), 0)
    for y in range(h):
        for x in (0, w - 1):
            if px[x, y] == 255:
                ImageDraw.floodfill(mask, (x, y), 0)
    return mask


def mark_mask(img, ground_cut):
    """Full-resolution alpha mask of the mark, cropped to its own bounding box.

    Cropping to the mark rather than to the tile is what lets both flat variants be laid out to the
    same optical size: the two authored tiles frame their marks differently, and centring the tiles
    would leave one mark visibly smaller than the other in the launcher.
    """
    w, h = img.size
    inset = int(min(w, h) * INSET_FRACTION)
    art = img.crop((inset, inset, w - inset, h - inset))
    grey = art.convert("L")

    solid = grey.point(lambda p: 255 if p > ground_cut else 0, mode="L")
    clear_frame_connected(solid)
    solid = solid.filter(ImageFilter.MinFilter(OPEN_KERNEL)).filter(ImageFilter.MaxFilter(OPEN_KERNEL))

    closed = grey.filter(ImageFilter.MaxFilter(SEAM_KERNEL)).filter(ImageFilter.MinFilter(SEAM_KERNEL))
    blackhat = np.asarray(closed, dtype=np.int16) - np.asarray(grey, dtype=np.int16)

    keep = (np.asarray(solid) > 127) & (blackhat <= SEAM_THRESHOLD)
    mask = Image.fromarray((keep * 255).astype(np.uint8), "L")

    box = mask.getbbox()
    if box is None:
        raise SystemExit("no mark found - is the ground really darker than the mark?")
    return mask.crop(box)


def parse_hex(value):
    v = value.lstrip("#")
    if len(v) != 6:
        raise SystemExit("colours must be #RRGGBB, got %r" % value)
    return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))


def foreground(mask, ink, size):
    """A `size` canvas with the mark, in `ink`, centred at MARK_FRACTION and nothing else."""
    target = int(round(size * MARK_FRACTION))
    mw, mh = mask.size
    scale = target / float(max(mw, mh))
    dst = (max(1, int(round(mw * scale))), max(1, int(round(mh * scale))))
    # Resize the MASK, not a filled image: filling first and then resizing blends the ink with the
    # transparent surround and leaves a dark halo on every edge.
    small = mask.resize(dst, Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer = Image.new("RGBA", dst, ink + (255,))
    out.paste(layer, ((size - dst[0]) // 2, (size - dst[1]) // 2), small)
    return out


BACKGROUND = '''<?xml version="1.0" encoding="utf-8"?>
<!-- "%(variant)s" flat variant background: one colour, no gradient. Unlike the authored-art
     variants this layer is genuinely visible - the foreground here is the mark alone, so this IS
     the icon's ground. Regenerate with scripts/gen_launcher_flat.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="M0,0h108v108H0z"
        android:fillColor="%(ground)s" />
</vector>
'''

ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background_%(id)s" />
    <foreground android:drawable="@mipmap/ic_launcher_fg_%(id)s" />
    <monochrome android:drawable="@mipmap/ic_launcher_mono_%(id)s" />
</adaptive-icon>
'''


def main():
    if len(sys.argv) < 5:
        raise SystemExit(__doc__)
    art_path, variant, ground_hex, ink_hex = sys.argv[1:5]
    ground_cut = int(sys.argv[5]) if len(sys.argv) > 5 else DEFAULT_GROUND_CUT
    ink = parse_hex(ink_hex)

    art = Image.open(art_path).convert("RGB")
    art = art.crop(tile_bounds(art))
    mask = mark_mask(art, ground_cut)

    for bucket, size in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + bucket)
        os.makedirs(folder, exist_ok=True)
        foreground(mask, ink, size).save(
            os.path.join(folder, "ic_launcher_fg_%s.png" % variant))
        # The themed-icon layer keeps only alpha, so the ink colour there is irrelevant.
        foreground(mask, (0, 0, 0), size).save(
            os.path.join(folder, "ic_launcher_mono_%s.png" % variant))

    with open(os.path.join(RES, "drawable", "ic_launcher_background_%s.xml" % variant),
              "w", encoding="utf-8") as fh:
        fh.write(BACKGROUND % {"variant": variant,
                               "ground": "#FF%s" % ground_hex.lstrip("#").upper()})

    for suffix in ("", "_round"):
        with open(os.path.join(RES, "mipmap-anydpi-v26",
                               "ic_launcher_%s%s.xml" % (variant, suffix)),
                  "w", encoding="utf-8") as fh:
            fh.write(ADAPTIVE % {"id": variant})

    print("%-22s ground %s  ink %s  cut %d  mark %dx%d"
          % (variant, ground_hex.upper(), ink_hex.upper(), ground_cut, mask.size[0], mask.size[1]))


if __name__ == "__main__":
    main()
