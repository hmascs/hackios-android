#!/usr/bin/env python3
"""A bounded data-only snapshot of the small Redwood root build tree."""
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import sys
import tarfile

MAX_ARCHIVE = 48 << 20
MAX_PAYLOAD = 32 << 20
MAX_ENTRIES = 512
MAX_DEPTH = 16


def safe_parts(name):
    if (not name or name.startswith('/') or '\\' in name or '\0' in name
            or len(os.fsencode(name)) > 255):
        raise ValueError('invalid stage path')
    parts = PurePosixPath(name).parts
    if not parts or len(parts) > MAX_DEPTH or any(p in ('..', '.') for p in parts):
        raise ValueError('invalid stage path')
    if name != '/'.join(parts):
        raise ValueError('noncanonical stage path')
    return parts


def archive(source, output):
    total = count = 0
    source = Path(source)
    with tarfile.open(output, 'x', format=tarfile.USTAR_FORMAT) as tar:
        for path in sorted(source.rglob('*')):
            name = path.relative_to(source).as_posix()
            safe_parts(name)
            # Save independent file bodies; the source build may use host hard links.
            tar.inodes.clear()
            info = tar.gettarinfo(str(path), name)
            info.mode = stat.S_IMODE(info.mode)
            count += 1
            total += info.size
            check(info, count, total)
            info.uid = info.gid = info.mtime = 0
            info.uname = info.gname = ''
            if info.isfile():
                with path.open('rb') as body:
                    tar.addfile(info, body)
            else:
                tar.addfile(info)
    if os.stat(output).st_size > MAX_ARCHIVE:
        raise ValueError('stage archive exceeds limit')


def check(info, count, total):
    if count > MAX_ENTRIES or not 0 <= total <= MAX_PAYLOAD:
        raise ValueError('stage limits exceeded')
    if info.type not in (tarfile.REGTYPE, tarfile.DIRTYPE, tarfile.SYMTYPE):
        raise ValueError('only ordinary files, directories and guest symlinks allowed')
    if info.mode & ~0o777 or info.size < 0 or (not info.isfile() and info.size):
        raise ValueError('invalid stage mode or size')
    if (info.isdir() and info.mode & 0o700 != 0o700) or (info.isfile() and not info.mode & 0o400):
        raise ValueError('stage entry must remain accessible to the private build and cleanup')
    if info.issym() and (not info.linkname or '\0' in info.linkname
                         or len(os.fsencode(info.linkname)) > 100):
        raise ValueError('invalid guest symlink target')


def restore(snapshot, output):
    root = Path(output)
    root.mkdir(mode=0o700)
    if not 0 < os.stat(snapshot).st_size <= MAX_ARCHIVE:
        raise ValueError('invalid stage archive size')
    dirs = {(): root}
    seen = set()
    links = []
    modes = []
    total = 0
    # Inspect the fixed headers before TarFile can consume extensions (including PAX and GNU longnames).
    with open(snapshot, 'rb') as src:
        while True:
            header = src.read(tarfile.BLOCKSIZE)
            if header == bytes(tarfile.BLOCKSIZE):
                if src.read(tarfile.BLOCKSIZE) != header:
                    raise ValueError('missing stage end block')
                if any(src.read()):
                    raise ValueError('extraneous stage data')
                break
            if len(header) != tarfile.BLOCKSIZE or header[156:157] not in (b'0', b'5', b'2'):
                raise ValueError('unsupported stage header')
            info = tarfile.TarInfo.frombuf(header, 'utf-8', 'strict')
            parts = safe_parts(info.name)
            total += info.size
            check(info, len(seen) + 1, total)
            if parts in seen or parts[:-1] not in dirs:
                raise ValueError('duplicate stage path or unsafe parent')
            seen.add(parts)
            path = dirs[parts[:-1]] / parts[-1]
            if info.isdir():
                path.mkdir(mode=0o700)
                dirs[parts] = path
                modes.append((path, info.mode))
            elif info.isfile():
                with path.open('xb') as out:
                    for offset in range(0, info.size, 65536):
                        length = min(65536, info.size - offset)
                        data = src.read(length)
                        if len(data) != length:
                            raise ValueError('truncated stage data')
                        out.write(data)
                path.chmod(info.mode)
                padding = (-info.size) % tarfile.BLOCKSIZE
                if len(src.read(padding)) != padding:
                    raise ValueError('truncated stage padding')
            else:
                # Created last and only ever inspected by lstat/readlink in mkapfs.
                links.append((path, info.linkname))
    for path, target in links:
        path.symlink_to(target)
    for path, mode in reversed(modes):
        path.chmod(mode)


if __name__ == '__main__':
    if len(sys.argv) != 4 or sys.argv[1] not in ('archive', 'restore'):
        raise SystemExit('usage: gpu-stage-archive.py archive|restore SOURCE OUTPUT')
    (archive if sys.argv[1] == 'archive' else restore)(sys.argv[2], sys.argv[3])
