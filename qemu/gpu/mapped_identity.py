#!/usr/bin/env python3
"""Resolve executable mappings by device+inode, never by a library basename."""
import hashlib
import os
from pathlib import Path
import stat
import sys


def file_key(status):
    return (os.major(status.st_dev), os.minor(status.st_dev), status.st_ino)


def verified_proot_prefix():
    try:
        virtual = str(Path(sys.executable).resolve())
        wanted = file_key(os.stat(virtual))
        if wanted != file_key(os.stat('/proc/self/exe')):
            return None
        paths = [os.readlink('/proc/self/exe')]
        for row in executable_mappings(Path('/proc/self/maps').read_text()):
            if tuple(row['device']) + (row['inode'],) == wanted:
                paths.append(row['kernel_path'])
        for kernel in paths:
            if kernel.endswith(virtual) and kernel[:-len(virtual)]:
                return kernel[:-len(virtual)]
        return '' if virtual in paths else None
    except OSError:
        pass
    return None


def mapping_identity(record, pid, root_prefix=None):
    filename = record['kernel_path']
    wanted = tuple(record['device']) + (record['inode'],)
    candidates = [('kernel_path', Path(filename))]
    if root_prefix and filename.startswith(root_prefix + '/'):
        candidates.append(('verified_proot_prefix', Path(filename[len(root_prefix):])))
    candidates += [('proc_root', Path(f'/proc/{pid}/root') / filename.lstrip('/')),
                   ('proc_map_file', Path(f'/proc/{pid}/map_files') / record['range'])]
    failures = []
    for method,path in candidates:
        try:
            with path.open('rb') as stream:
                before = os.fstat(stream.fileno())
                if not stat.S_ISREG(before.st_mode) or file_key(before) != wanted:
                    failures.append(dict(method=method,error='device/inode mismatch or non-regular'))
                    continue
                digest = hashlib.sha256()
                for block in iter(lambda: stream.read(1024*1024),b''):
                    digest.update(block)
                after = os.fstat(stream.fileno())
                if file_key(after) != wanted or (before.st_size,before.st_mtime_ns) != (after.st_size,after.st_mtime_ns):
                    failures.append(dict(method=method,error='mapped file changed while hashing'))
                    continue
                return dict(path=str(path),bytes=before.st_size,sha256=digest.hexdigest(),
                            matched_by=method,device=list(wanted[:2]),inode=wanted[2]), failures
        except OSError as exc:
            failures.append(dict(method=method,error=f'{type(exc).__name__}: {exc.errno}'))
    return None, failures


def executable_mappings(text):
    records = []
    for line in text.splitlines():
        fields = line.split(None,5)
        if len(fields)!=6 or 'x' not in fields[1] or not fields[5].startswith('/'):
            continue
        major,minor = (int(value,16) for value in fields[3].split(':'))
        records.append(dict(range=fields[0],permissions=fields[1],offset=fields[2],
                            device=[major,minor],inode=int(fields[4]),kernel_path=fields[5]))
    return records


def runtime_identity(pid):
    result = dict(pid=pid,mapped_files={},unreadable=[],environment={},mappings=[],
                  scope='terminal executable mappings only; unloaded historical DSOs are not covered')
    try:
        for item in Path(f'/proc/{pid}/environ').read_bytes().split(b'\0'):
            key,separator,value=item.partition(b'=')
            name=key.decode(errors='replace')
            if separator and name.startswith(('HIOS_','HACKIOS_','MESA_','GALLIUM_','VK_','EGL_','LIBGL_','__EGL_','LD_LIBRARY_PATH')):
                result['environment'][name]=value.decode(errors='replace')
        maps=Path(f'/proc/{pid}/maps').read_text()
        result['maps_sha256']=hashlib.sha256(maps.encode()).hexdigest()
        root_prefix=verified_proot_prefix()
        result['verified_proot_prefix']=root_prefix
        for record in executable_mappings(maps):
            identity,failures=mapping_identity(record,pid,root_prefix)
            record.update(identity=identity,resolution_attempts=failures)
            result['mappings'].append(record)
            if identity:
                result['mapped_files'][record['kernel_path']]=identity
            else:
                result['unreadable'].append(record['kernel_path'])
    except OSError as exc:
        result['error']=str(exc)
    return result
