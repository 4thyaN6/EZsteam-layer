#!/usr/bin/env python3
# Key input check window for the live display test: an X window that takes the keyboard focus and
# shows the last key events it received (X keycode, key name, character). Uses libX11 through
# ctypes only (no extra packages).
#   keyview.py <x> <y>
# 실시간 화면 시험용 키 입력 확인 창: 키보드 포커스를 가져가고 받은 키 이벤트(X 키코드, 키 이름,
# 글자)를 최근 순으로 보여 주는 X 창. libX11을 ctypes로만 씀(추가 패키지 없음)
import ctypes
import ctypes.util
import sys

X = ctypes.CDLL(ctypes.util.find_library("X11") or "libX11.so.6")
c_ulong, c_int, c_uint, c_void_p = ctypes.c_ulong, ctypes.c_int, ctypes.c_uint, ctypes.c_void_p

# Function types (Display* and Window must keep 64-bit width) / 함수 형식(Display*와 Window는 64비트 유지)
X.XOpenDisplay.restype = c_void_p
X.XOpenDisplay.argtypes = [ctypes.c_char_p]
X.XDefaultScreen.argtypes = [c_void_p]
X.XRootWindow.restype = c_ulong
X.XRootWindow.argtypes = [c_void_p, c_int]
X.XBlackPixel.restype = c_ulong
X.XBlackPixel.argtypes = [c_void_p, c_int]
X.XWhitePixel.restype = c_ulong
X.XWhitePixel.argtypes = [c_void_p, c_int]
X.XCreateSimpleWindow.restype = c_ulong
X.XCreateSimpleWindow.argtypes = [c_void_p, c_ulong, c_int, c_int, c_uint, c_uint, c_uint, c_ulong, c_ulong]
X.XStoreName.argtypes = [c_void_p, c_ulong, ctypes.c_char_p]
X.XSelectInput.argtypes = [c_void_p, c_ulong, ctypes.c_long]
X.XMapWindow.argtypes = [c_void_p, c_ulong]
X.XDefaultGC.restype = c_void_p
X.XDefaultGC.argtypes = [c_void_p, c_int]
X.XNextEvent.argtypes = [c_void_p, c_void_p]
X.XSetInputFocus.argtypes = [c_void_p, c_ulong, c_int, c_ulong]
X.XClearWindow.argtypes = [c_void_p, c_ulong]
X.XDrawString.argtypes = [c_void_p, c_ulong, c_void_p, c_int, c_int, ctypes.c_char_p, c_int]
X.XFlush.argtypes = [c_void_p]
X.XLookupString.argtypes = [c_void_p, ctypes.c_char_p, c_int, ctypes.POINTER(c_ulong), c_void_p]
X.XKeysymToString.restype = ctypes.c_char_p
X.XKeysymToString.argtypes = [c_ulong]


class XKeyEvent(ctypes.Structure):
    """XKeyEvent layout (Xlib.h) / XKeyEvent 구조(Xlib.h)"""
    _fields_ = [("type", c_int), ("serial", c_ulong), ("send_event", c_int), ("display", c_void_p),
                ("window", c_ulong), ("root", c_ulong), ("subwindow", c_ulong), ("time", c_ulong),
                ("x", c_int), ("y", c_int), ("x_root", c_int), ("y_root", c_int),
                ("state", c_uint), ("keycode", c_uint), ("same_screen", c_int)]


class XEvent(ctypes.Union):
    """XEvent is a 24-long union; only the type and key fields are read / XEvent는 long 24개 크기, 종류와 키 항목만 읽음"""
    _fields_ = [("type", c_int), ("xkey", XKeyEvent), ("pad", ctypes.c_long * 24)]


KEY_PRESS, KEY_RELEASE, EXPOSE = 2, 3, 12
MASK = (1 << 0) | (1 << 1) | (1 << 15)  # KeyPress | KeyRelease | Exposure

x = int(sys.argv[1]) if len(sys.argv) > 1 else 20
y = int(sys.argv[2]) if len(sys.argv) > 2 else 520
d = X.XOpenDisplay(None)
if not d:
    raise SystemExit("keyview: cannot open display")
scr = X.XDefaultScreen(d)
win = X.XCreateSimpleWindow(d, X.XRootWindow(d, scr), x, y, 460, 130, 2, X.XBlackPixel(d, scr), X.XWhitePixel(d, scr))
X.XStoreName(d, win, b"ezs keyview")
X.XSelectInput(d, win, MASK)
X.XMapWindow(d, win)
gc = X.XDefaultGC(d, scr)

lines = ["key test: type on the keyboard", "(keyboard focus is on this window)"]
focused = False


def draw():
    # Redraw the newest lines first / 최신 줄부터 다시 그림
    X.XClearWindow(d, win)
    for i, s in enumerate(lines[-7:]):
        b = s.encode("ascii", "replace")
        X.XDrawString(d, win, gc, 10, 18 + 16 * i, b, len(b))
    X.XFlush(d)


ev = XEvent()
buf = ctypes.create_string_buffer(16)
sym = c_ulong()
while True:
    X.XNextEvent(d, ctypes.byref(ev))
    if ev.type == EXPOSE:
        # Take the keyboard focus once the window is visible (no window manager here)
        # 창이 보이면 키보드 포커스를 가져감(여기에는 창 관리자가 없음)
        if not focused:
            X.XSetInputFocus(d, win, 1, 0)  # RevertToPointerRoot, CurrentTime
            focused = True
        draw()
    elif ev.type in (KEY_PRESS, KEY_RELEASE):
        n = X.XLookupString(ctypes.byref(ev.xkey), buf, 15, ctypes.byref(sym), None)
        name = X.XKeysymToString(sym.value) or b"?"
        ch = buf.raw[:n].decode("latin-1") if n > 0 and buf.raw[0] >= 32 else ""
        kind = "press  " if ev.type == KEY_PRESS else "release"
        lines.append(f"{kind} keycode={ev.xkey.keycode:3d} {name.decode()} {ch!r}")
        print(lines[-1], flush=True)
        draw()
