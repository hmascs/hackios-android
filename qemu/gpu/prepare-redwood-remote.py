#!/usr/bin/env python3
"""Generate an opt-in Redwood EAGL source with remote IOSurface readback."""
import argparse
from pathlib import Path

ANCHOR = '''		base = IOSurfaceGetBaseAddress(surface);
		glPixelStorei(GL_PACK_ROW_LENGTH, (GLint)(row / 4));
		glPixelStorei(GL_PACK_ALIGNMENT, 4);
		if (!image->invert) {
			glReadPixels(0, 0, image->width, image->height, GL_BGRA, GL_UNSIGNED_BYTE, base);
		} else {
			for (GLsizei y = 0; y < image->height; y++) {
				glReadPixels(0, y, image->width, 1, GL_BGRA, GL_UNSIGNED_BYTE, base + row * (size_t)(image->height - 1 - y));
			}
		}
		glPixelStorei(GL_PACK_ROW_LENGTH, 0);'''

REMOTE = '''		base = IOSurfaceGetBaseAddress(surface);
		if (!hios_eagl_read_surface(base, IOSurfaceGetAllocSize(surface), row,
		    image->width, image->height, !!image->invert)) {
			IOSurfaceUnlock(surface, 0, NULL);
			say("hackios-eagl: remote IOSurface readback failed; refusing to publish a stale frame\\n");
			abort();
		}'''


def generate(source):
    include = '#include <IOSurface/IOSurfaceRef.h>'
    if 'HIOS_REMOTE_GLES' in source or source.count(ANCHOR) != 1 or source.count(include) != 1:
        raise ValueError('unsupported Redwood EAGL source: expected exact IOSurface readback block')
    return source.replace(include, include + '\n#ifdef HIOS_REMOTE_GLES\n#include "eagl-pixel-transfer.h"\n#endif', 1).replace(
        ANCHOR, '#ifdef HIOS_REMOTE_GLES\n' + REMOTE + '\n#else\n' + ANCHOR + '\n#endif', 1)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('source', type=Path)
    p.add_argument('output', type=Path)
    args = p.parse_args()
    result = generate(args.source.read_text())
    with args.output.open('x') as out:
        out.write(result)


if __name__ == '__main__':
    main()
