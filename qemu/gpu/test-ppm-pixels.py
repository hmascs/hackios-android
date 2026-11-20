#!/usr/bin/env python3
import io
from pathlib import Path
import tempfile
import unittest
from PIL import Image
from ppm_pixels import decode_p6,load_rgb


def raster(width,height):
    rgb=bytearray((i*37+11)%256 for i in range(width*height*3))
    rgb[:3]=b'\n #'
    return bytes(rgb)


def ppm(width,height,rgb,padded=False,crlf=False):
    eol=b'\r\n' if crlf else b'\n'
    header=b'P6'+eol+b'# header comment'+eol+str(width).encode()+b'\t'+str(height).encode()+eol+b'# maxval follows'+eol+b'255'+eol
    row=width*3;pitch=(row+3)//4*4 if padded else row
    body=b''.join(rgb[y*row:(y+1)*row]+bytes([0xa5])*(pitch-row) for y in range(height))
    return header+body


class PpmTests(unittest.TestCase):
    def test_width1125_packed3375_and_padded3376_are_identical(self):
        rgb=raster(1125,3)
        for padded,pitch in ((False,3375),(True,3376)):
            with self.subTest(pitch=pitch):
                w,h,actual,meta=decode_p6(ppm(1125,3,rgb,padded))
                self.assertEqual((w,h),(1125,3));self.assertEqual(actual,rgb)
                self.assertEqual(meta['source_pitch'],pitch)
                self.assertEqual(actual[:3],b'\n #')

    def test_odd_widths_crlf_comments_and_first_pixel_whitespace(self):
        for width in (1,3,5,7):
            for padded in (False,True):
                data=raster(width,5)
                self.assertEqual(decode_p6(ppm(width,5,data,padded,True))[2],data)

    def test_ring_bgra_png_and_padded_ppm_red_blue_identity(self):
        width,height=1125,3
        rgb=(bytes([255,0,0,0,0,255,32,10,35])*(width*height//3+1))[:width*height*3]
        bgra=bytearray()
        for i in range(0,len(rgb),3):
            r,g,b=rgb[i:i+3];bgra.extend((b,g,r,255))
        ring=Image.frombytes('RGBA',(width,height),bytes(bgra),'raw','BGRA').convert('RGB')
        self.assertEqual(ring.tobytes(),rgb)
        with tempfile.TemporaryDirectory() as directory:
            a,b=Path(directory)/'reference.png',Path(directory)/'screendump.ppm'
            ring.save(a);b.write_bytes(ppm(width,height,rgb,True))
            decoded_png,meta_png=load_rgb(a);decoded_ppm,meta_ppm=load_rgb(b)
            self.assertEqual(decoded_png.tobytes(),decoded_ppm.tobytes())
            self.assertEqual(meta_png['packed_rgb_sha256'],meta_ppm['packed_rgb_sha256'])

    def test_truncated_extra_bad_depth_and_ambiguous_delimiters_fail_closed(self):
        data=ppm(1125,3,raster(1125,3),True)
        cases=[data[:-1],data+b'\0',data.replace(b'255\n',b'65535\n',1),
               b'P6\n1 1\n255\r\n'+b'abc',b'P6\n# unterminated']
        for blob in cases:
            with self.subTest(size=len(blob)),self.assertRaises(ValueError):decode_p6(blob)

    def test_legacy_pillow_padded_reader_negative_control_is_retained(self):
        rgb=raster(1125,3);data=ppm(1125,3,rgb,True)
        with Image.open(io.BytesIO(data)) as legacy:
            self.assertNotEqual(legacy.convert('RGB').tobytes(),rgb)
        self.assertEqual(decode_p6(data)[2],rgb)

if __name__=='__main__':unittest.main()
