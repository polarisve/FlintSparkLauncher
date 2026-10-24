# Rewrites the version resource (the "Details" tab in file properties) of the launcher stub.
# Usage: python -I patch_exe_version.py <exe> <version, e.g. 1.0>
# Run it again whenever the launcher version changes; the stub is checked in already patched.
import ctypes
import struct
import sys
from ctypes import wintypes as W

RT_VERSION = 16
LANG = 0x409          # language of the upstream resource
STRING_TABLE = "080404b0"  # Simplified Chinese, Unicode

AUTHOR = "polarisve"
PRODUCT = "Flint Spark Launcher"
COPYRIGHT = "Copyright (C) 2026 polarisve. Based on HMCL, Copyright (C) huangyuhui and contributors, GPL v3"

k = ctypes.WinDLL("kernel32", use_last_error=True)
k.BeginUpdateResourceW.restype = W.HANDLE
k.BeginUpdateResourceW.argtypes = [W.LPCWSTR, W.BOOL]
k.UpdateResourceW.restype = W.BOOL
k.UpdateResourceW.argtypes = [W.HANDLE, ctypes.c_void_p, ctypes.c_void_p, W.WORD, ctypes.c_void_p, W.DWORD]
k.EndUpdateResourceW.restype = W.BOOL
k.EndUpdateResourceW.argtypes = [W.HANDLE, W.BOOL]


def pad(b):
    return b + b"\0" * (-len(b) % 4)


def block(key, value=b"", children=b"", text=True, value_len=None):
    """One VS_VERSIONINFO node: wLength, wValueLength, wType, szKey, padding, value, padding, children."""
    head_key = key.encode("utf-16-le") + b"\0\0"
    if value_len is None:
        value_len = len(value) // 2 if text else len(value)
    body = pad(struct.pack("<HHH", 0, value_len, 1 if text else 0) + head_key) + pad(value) + children
    return struct.pack("<H", len(body)) + body[2:]


def string(key, text):
    return pad(block(key, text.encode("utf-16-le") + b"\0\0"))


def main(exe, version):
    parts = [int(p) for p in version.split(".")] + [0, 0, 0, 0]
    a, b, c, d = parts[:4]
    full = f"{a}.{b}.{c}.{d}"
    fixed = struct.pack("<13I", 0xFEEF04BD, 0x00010000,
                        (a << 16) | b, (c << 16) | d, (a << 16) | b, (c << 16) | d,
                        0x3F, 0, 0x40004, 1, 0, 0, 0)
    strings = b"".join(string(key, text) for key, text in [
        ("CompanyName", AUTHOR),
        ("FileDescription", PRODUCT),
        ("FileVersion", full),
        ("InternalName", "FSL.exe"),
        ("LegalCopyright", COPYRIGHT),
        ("OriginalFilename", "FSL.exe"),
        ("ProductName", PRODUCT),
        ("ProductVersion", version),
    ])
    string_info = pad(block("StringFileInfo", children=pad(block(STRING_TABLE, children=strings))))
    var_info = pad(block("VarFileInfo", children=pad(block("Translation", struct.pack("<HH", 0x0804, 0x04B0), text=False))))
    root = block("VS_VERSION_INFO", fixed, string_info + var_info, text=False)

    h = k.BeginUpdateResourceW(exe, False)
    if not h:
        raise OSError(ctypes.get_last_error(), "BeginUpdateResource")
    buf = ctypes.create_string_buffer(root, len(root))
    if not k.UpdateResourceW(h, RT_VERSION, 1, LANG, buf, len(root)):
        raise OSError(ctypes.get_last_error(), "UpdateResource")
    if not k.EndUpdateResourceW(h, False):
        raise OSError(ctypes.get_last_error(), "EndUpdateResource")
    print(f"wrote version {full} to {exe}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
