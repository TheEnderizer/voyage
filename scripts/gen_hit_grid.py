#!/usr/bin/env python3
"""Generates res/layout/widget_hit_grid.xml: a ROWS x COLS grid of transparent, weighted
`View`s (ids `hit_r_c`) used as RemoteViews click targets for the free-placement widget editor.
See widget/HitGrid.kt for how element rects are mapped onto these cells.

Run from the repo root: `python scripts/gen_hit_grid.py`
"""
import pathlib

ROWS = 16
COLS = 16
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/layout/widget_hit_grid.xml"

CELL = (
    '            <View\n'
    '                android:id="@+id/hit_{r}_{c}"\n'
    '                android:layout_width="0dp"\n'
    '                android:layout_height="match_parent"\n'
    '                android:layout_weight="1"\n'
    '                android:background="@android:color/transparent" />\n'
)

ROW_OPEN = (
    '        <LinearLayout\n'
    '            android:layout_width="match_parent"\n'
    '            android:layout_height="0dp"\n'
    '            android:layout_weight="1"\n'
    '            android:orientation="horizontal">\n'
)
ROW_CLOSE = '        </LinearLayout>\n'

def build():
    lines = []
    lines.append('<?xml version="1.0" encoding="utf-8"?>\n')
    lines.append('<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"\n')
    lines.append('    android:id="@+id/hit_grid"\n')
    lines.append('    android:layout_width="match_parent"\n')
    lines.append('    android:layout_height="match_parent"\n')
    lines.append('    android:orientation="vertical">\n')
    for r in range(ROWS):
        lines.append(ROW_OPEN)
        for c in range(COLS):
            lines.append(CELL.format(r=r, c=c))
        lines.append(ROW_CLOSE)
    lines.append('</LinearLayout>\n')
    return "".join(lines)

if __name__ == "__main__":
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(build(), encoding="utf-8")
    print(f"wrote {OUT} ({ROWS}x{COLS} cells)")
