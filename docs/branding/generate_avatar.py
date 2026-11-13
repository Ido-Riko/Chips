"""Draw Chips' geometric eye from vector shapes. Requires Python pycairo."""

import argparse
import io
import math
from pathlib import Path

import cairo


OUTPUT = Path(__file__).resolve().parent
SIZE = 1024
BACKGROUND = "#191932"
BLUE = "#64B4FF"
WHITE = "#FFFFFF"
# Measurements use the downloaded avatar's 512-unit coordinate space.
CIRCLES = ((160, BLUE), (80, WHITE), (48, BACKGROUND), (32, BLUE))


def rgb(color):
    return tuple(int(color[i:i + 2], 16) / 255 for i in (1, 3, 5))


def render(size, round_icon=False):
    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    ctx = cairo.Context(surface)
    ctx.scale(size / 512, size / 512)
    if round_icon:
        ctx.arc(256, 256, 256, 0, 2 * math.pi)
        ctx.clip()
    ctx.set_source_rgb(*rgb(BACKGROUND))
    ctx.paint()
    for radius, color in CIRCLES:
        ctx.set_source_rgb(*rgb(color))
        ctx.arc(256, 256, radius, 0, 2 * math.pi)
        ctx.fill()
    return surface


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android', action='store_true', help='Also render Android legacy launcher WebPs (requires Pillow).')
    args = parser.parse_args()
    svg = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 512 512">',
        '  <title>Chips blue eye avatar</title>',
        f'  <rect width="512" height="512" fill="{BACKGROUND}"/>',
    ]
    for radius, color in CIRCLES:
        svg.append(f'  <circle cx="256" cy="256" r="{radius}" fill="{color}"/>')
    svg.append('</svg>')
    (OUTPUT / 'chips-avatar.svg').write_text('\n'.join(svg) + '\n')

    render(SIZE).write_to_png(str(OUTPUT / 'chips-avatar-1024.png'))
    if args.android:
        from PIL import Image

        resources = OUTPUT.parents[1] / 'chips-mobile/app/src/main/res'
        for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
            for name, rounded in [('ic_launcher', False), ('ic_launcher_round', True)]:
                buffer = io.BytesIO()
                render(size, rounded).write_to_png(buffer)
                buffer.seek(0)
                with Image.open(buffer) as image:
                    image.save(resources / f'mipmap-{density}/{name}.webp', lossless=True)


if __name__ == '__main__':
    main()
