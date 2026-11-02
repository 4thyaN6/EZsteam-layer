#!/usr/bin/env python3
# What libnm makes of the NetworkManager stand-in, read with the same libnm calls the Steam client
# uses (its steamclient.so names nm_client_get_devices, nm_device_get_state, nm_device_get_active_
# connection, nm_active_connection_get_state, nm_device_wifi_get_active_access_point,
# nm_ip_config_get_gateway and others, scan 2026-11-02). Why: the stand-in answers "connected" in
# every value it gives, yet Big Picture's Internet page shows no active network; this shows which
# value libnm itself does not get. One short run started once by ezs-agent, output lines
# "ezs-nmcheck: ...". Standard library (ctypes) and the rootfs' libnm only; nothing is changed.
# libnm이 NetworkManager 대역을 어떻게 보는지, Steam 클라이언트가 쓰는 것과 같은 libnm 호출로 읽음(steamclient.so에
# nm_client_get_devices, nm_device_get_state, nm_device_get_active_connection, nm_active_connection_get_state,
# nm_device_wifi_get_active_access_point, nm_ip_config_get_gateway 등이 있음, 스캔 2026-11-02). 이유: 대역은 주는
# 값마다 "연결됨"인데 빅픽처 인터넷 화면에 활성 네트워크가 없음. libnm 자신이 어떤 값을 못 받는지 보여 줌. ezs-agent가
# 한 번 띄우는 짧은 실행, 출력 줄은 "ezs-nmcheck: ...". 표준 라이브러리(ctypes)와 rootfs의 libnm만 씀. 바꾸는 것 없음
import ctypes as C
import sys


def say(msg):
    print("ezs-nmcheck: " + msg, flush=True)


try:
    nm = C.CDLL("libnm.so.0")
    glib = C.CDLL("libglib-2.0.so.0")
    gobj = C.CDLL("libgobject-2.0.so.0")
except OSError as e:
    say("libnm 불러오기 실패: %s" % e)
    sys.exit(0)


class GPtrArray(C.Structure):
    _fields_ = [("pdata", C.POINTER(C.c_void_p)), ("len", C.c_uint)]


class GError(C.Structure):
    _fields_ = [("domain", C.c_uint32), ("code", C.c_int), ("message", C.c_char_p)]


def fn(name, res, *args):
    f = getattr(nm, name)
    f.restype = res
    f.argtypes = list(args)
    return f


P = C.c_void_p
client_new = fn("nm_client_new", P, P, C.POINTER(C.POINTER(GError)))
get_devices = fn("nm_client_get_devices", C.POINTER(GPtrArray), P)
get_state = fn("nm_client_get_state", C.c_int, P)
get_conn = fn("nm_client_get_connectivity", C.c_int, P)
get_running = fn("nm_client_get_nm_running", C.c_int, P)
get_version = fn("nm_client_get_version", C.c_char_p, P)
get_primary = fn("nm_client_get_primary_connection", P, P)
get_actives = fn("nm_client_get_active_connections", C.POINTER(GPtrArray), P)
wifi_enabled = fn("nm_client_wireless_get_enabled", C.c_int, P)
dev_iface = fn("nm_device_get_iface", C.c_char_p, P)
dev_type = fn("nm_device_get_device_type", C.c_int, P)
dev_state = fn("nm_device_get_state", C.c_int, P)
dev_reason = fn("nm_device_get_state_reason", C.c_int, P)
dev_udi = fn("nm_device_get_udi", C.c_char_p, P)
dev_ac = fn("nm_device_get_active_connection", P, P)
dev_ip4 = fn("nm_device_get_ip4_config", P, P)
dev_avail = fn("nm_device_get_available_connections", C.POINTER(GPtrArray), P)
dev_product = fn("nm_device_get_product", C.c_char_p, P)
ac_state = fn("nm_active_connection_get_state", C.c_int, P)
ac_id = fn("nm_active_connection_get_id", C.c_char_p, P)
ac_default = fn("nm_active_connection_get_default", C.c_int, P)
ac_conn = fn("nm_active_connection_get_connection", P, P)
conn_id = fn("nm_connection_get_id", C.c_char_p, P)
conn_type = fn("nm_connection_get_connection_type", C.c_char_p, P)
ip_gw = fn("nm_ip_config_get_gateway", C.c_char_p, P)
ip_addrs = fn("nm_ip_config_get_addresses", C.POINTER(GPtrArray), P)
ip_addr = fn("nm_ip_address_get_address", C.c_char_p, P)
wifi_ap = fn("nm_device_wifi_get_active_access_point", P, P)
wifi_aps = fn("nm_device_wifi_get_access_points", C.POINTER(GPtrArray), P)
ap_ssid = fn("nm_access_point_get_ssid", P, P)
ap_strength = fn("nm_access_point_get_strength", C.c_uint8, P)
glib.g_bytes_get_data.restype = P
glib.g_bytes_get_data.argtypes = [P, C.POINTER(C.c_size_t)]
gobj.g_type_name_from_instance.restype = C.c_char_p
gobj.g_type_name_from_instance.argtypes = [P]


def items(arr):
    if not arr:
        return []
    a = arr.contents
    return [a.pdata[i] for i in range(a.len)]


def s(b):
    return b.decode(errors="replace") if b else "(없음)"


err = C.POINTER(GError)()
client = client_new(None, C.byref(err))
if not client:
    say("nm_client_new 실패: %s" % (s(err.contents.message) if err else "?"))
    sys.exit(0)
say("NM 실행 %d, 버전 %s, 상태 %d, 연결성 %d, Wi-Fi 켬 %d" % (get_running(client), s(get_version(client)),
                                                       get_state(client), get_conn(client), wifi_enabled(client)))
prim = get_primary(client)
say("주 연결 %s, 활성 연결 %d개" % (s(ac_id(prim)) if prim else "(없음)", len(items(get_actives(client)))))
devs = items(get_devices(client))
say("장치 %d개" % len(devs))
for d in devs:
    say("장치 %s: 종류 %d (%s), 상태 %d, 이유 %d, udi %s, 제품 %s, 사용 가능 연결 %d개"
        % (s(dev_iface(d)), dev_type(d), s(gobj.g_type_name_from_instance(d)), dev_state(d), dev_reason(d),
           s(dev_udi(d)), s(dev_product(d)), len(items(dev_avail(d)))))
    ac = dev_ac(d)
    if ac:
        c = ac_conn(ac)
        say("  활성 연결 %s: 상태 %d, 기본 %d, 연결 객체 %s (%s, %s)"
            % (s(ac_id(ac)), ac_state(ac), ac_default(ac), "있음" if c else "없음",
               s(conn_id(c)) if c else "-", s(conn_type(c)) if c else "-"))
    else:
        say("  활성 연결 없음")
    ip4 = dev_ip4(d)
    if ip4:
        addrs = [s(ip_addr(a)) for a in items(ip_addrs(ip4))]
        say("  IPv4 주소 %s, 게이트웨이 %s" % (",".join(addrs) or "(없음)", s(ip_gw(ip4))))
    else:
        say("  IPv4 설정 없음")
    if dev_type(d) == 2:
        ap = wifi_ap(d)
        if ap:
            n = C.c_size_t()
            b = ap_ssid(ap)
            data = C.string_at(glib.g_bytes_get_data(b, C.byref(n)), n.value) if b else b""
            say("  접속점 %s, 세기 %d, 접속점 목록 %d개" % (data.decode(errors="replace"), ap_strength(ap),
                                                    len(items(wifi_aps(d)))))
        else:
            say("  활성 접속점 없음 (목록 %d개)" % len(items(wifi_aps(d))))
