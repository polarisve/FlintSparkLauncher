# Replaces the icon of the HMCLauncher.exe stub with fsl.ico, writing a new exe.
# Usage: python -I patch_exe_icon.py <stub.exe> <icon.ico> <output.exe>
import ctypes
import shutil
import struct
import sys
from ctypes import wintypes as W

RT_ICON, RT_GROUP_ICON = 3, 14
GROUP_ID = 107       # group icon id used by the upstream stub
LANG = 0x409         # language of the upstream resources

k = ctypes.WinDLL("kernel32", use_last_error=True)
k.BeginUpdateResourceW.restype = W.HANDLE
k.BeginUpdateResourceW.argtypes = [W.LPCWSTR, W.BOOL]
k.UpdateResourceW.restype = W.BOOL
k.UpdateResourceW.argtypes = [W.HANDLE, ctypes.c_void_p, ctypes.c_void_p, W.WORD, ctypes.c_void_p, W.DWORD]
k.EndUpdateResourceW.restype = W.BOOL
k.EndUpdateResourceW.argtypes = [W.HANDLE, W.BOOL]


def check(ok, what):
    if not ok:
        raise OSError(ctypes.get_last_error(), what)


def main(stub, ico, out):
    data = open(ico, "rb").read()
    _, kind, count = struct.unpack_from("<HHH", data, 0)
    if kind != 1:
        raise ValueError("not an .ico file")

    shutil.copyfile(stub, out)
    h = k.BeginUpdateResourceW(out, False)
    check(h, "BeginUpdateResource")

    group = struct.pack("<HHH", 0, 1, count)
    for i in range(count):
        w, hgt, colors, res, planes, bpp, size, offset = struct.unpack_from("<BBBBHHII", data, 6 + i * 16)
        img = data[offset:offset + size]
        buf = ctypes.create_string_buffer(img, len(img))
        check(k.UpdateResourceW(h, RT_ICON, i + 1, LANG, buf, len(img)), "UpdateResource icon")
        # GRPICONDIRENTRY: same as ICONDIRENTRY but a 2-byte resource id instead of the file offset
        group += struct.pack("<BBBBHHIH", w, hgt, colors, res, planes, bpp, size, i + 1)

    gbuf = ctypes.create_string_buffer(group, len(group))
    check(k.UpdateResourceW(h, RT_GROUP_ICON, GROUP_ID, LANG, gbuf, len(group)), "UpdateResource group")
    check(k.EndUpdateResourceW(h, False), "EndUpdateResource")
    print(f"wrote {out} with {count} icon images")


if __name__ == "__main__":
    main(*sys.argv[1:4])
