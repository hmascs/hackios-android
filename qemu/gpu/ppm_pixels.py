#!/usr/bin/env python3
"""Strict P6 RGB8 decoder with explicitly proven QEMU pixman row padding.

Never greedily trim raster whitespace: a pixel is allowed to start with space,
newline or '#'. Consume only the required maxval delimiter (or a size-proven
CRLF pair). Header comments are accepted before tokens. Ambiguous sizes fail.
"""
import hashlib
from pathlib import Path

WS=b' \t\r\n\v\f'


def _skip(data,index,limit):
    while index<limit:
        if data[index] in WS:
            index+=1
        elif data[index]==35:
            end=index+1
            while end<limit and data[end] not in b'\r\n':end+=1
            if end==limit:raise ValueError('unterminated PPM header comment')
            index=end
        else:break
    return index


def decode_p6(data):
    if not isinstance(data,bytes) or not data.startswith(b'P6'):
        raise ValueError('not a P6 byte stream')
    index=0;tokens=[]
    for _ in range(4):
        index=_skip(data,index,min(len(data),65536))
        start=index
        while index<len(data) and data[index] not in WS and data[index]!=35:
            index+=1
            if index>65536:raise ValueError('PPM header too large')
        if index==start:raise ValueError('incomplete PPM header')
        tokens.append(data[start:index])
    if tokens[0]!=b'P6' or any(not token.isdigit() for token in tokens[1:]):
        raise ValueError('invalid PPM header tokens')
    width,height,maximum=map(int,tokens[1:])
    if not 1<=width<=32768 or not 1<=height<=32768 or width*height>100_000_000:
        raise ValueError('PPM dimensions outside diagnostic budget')
    if maximum!=255:
        raise ValueError('diagnostic P6 decoder requires RGB8 maxval=255')
    row=width*3;aligned=(row+3)//4*4
    if index>=len(data) or data[index] not in WS:
        raise ValueError('PPM maxval requires a whitespace delimiter')
    offsets=[index+1]
    if data[index:index+2]==b'\r\n':
        offsets.append(index+2)
    candidates=[]
    for offset in offsets:
        for pitch in sorted(set((row,aligned))):
            if len(data)-offset==pitch*height:
                candidates.append((pitch,offset))
    if len(candidates)!=1:
        raise ValueError('PPM raster size/header tail is short, extra, unsupported or ambiguous')
    pitch,offset=candidates[0]
    if pitch==row:
        rgb=data[offset:]
    else:
        rgb=b''.join(data[offset+y*pitch:offset+y*pitch+row] for y in range(height))
    assert len(rgb)==row*height
    return width,height,rgb,dict(format='P6_RGB8',source_pitch=pitch,packed_pitch=row,
        header_bytes=offset,padded=pitch!=row,packed_rgb_sha256=hashlib.sha256(rgb).hexdigest())


def load_rgb(path):
    from PIL import Image
    data=Path(path).read_bytes()
    if data.startswith(b'P6'):
        width,height,rgb,metadata=decode_p6(data)
        return Image.frombytes('RGB',(width,height),rgb),metadata
    with Image.open(path) as source:
        image=source.convert('RGB').copy()
    return image,dict(format='Pillow_RGB',source_format=Path(path).suffix,
                      packed_rgb_sha256=hashlib.sha256(image.tobytes()).hexdigest())
