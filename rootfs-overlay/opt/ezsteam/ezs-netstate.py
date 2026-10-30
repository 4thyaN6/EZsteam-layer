#!/usr/bin/env python3
# Minimal NetworkManager stand-in on the system D-Bus, for the Steam client's connection header
# and its Settings > Internet page. The client builds both on NetworkManager; without one it shows
# no connection even while signed in and downloading (user report 2026-10-27; another
# Steam-on-Android project describes the same, read for behaviour only). Written from the public
# NetworkManager D-Bus API description (org.freedesktop.NetworkManager*), own code, MIT.
# One wired device that is up, one active connection. The state comes from the app:
# /tmp/ezs-net holds "1 wifi" / "1 ethernet" / "1 cellular" / "0" (written on Android network
# changes); without the file the network counts as up. Checked every 2 s, changes are signalled.
# Needs python3-gi (Gio) and a system bus (dbus-daemon with ezs-system-bus.conf, steam-run.sh).
# Steam 클라이언트의 연결 표시와 설정 > 인터넷 화면을 위한 시스템 D-Bus의 최소 NetworkManager 대역. 클라이언트는
# 둘 다 NetworkManager로 만들며, 없으면 로그인해 다운로드 중이어도 연결 없음으로 보임(사용자 제보 2026-10-27.
# 다른 Steam-on-Android 프로젝트도 같은 내용을 적음, 동작만 참고). 공개 NetworkManager D-Bus API 설명
# (org.freedesktop.NetworkManager*)으로 직접 작성, MIT.
# 켜진 유선 장치 하나, 활성 연결 하나. 상태는 앱이 줌: /tmp/ezs-net에 "1 wifi" / "1 ethernet" / "1 cellular" /
# "0"(안드로이드 네트워크가 바뀔 때 씀). 파일이 없으면 연결됨으로 봄. 2초마다 확인하고 바뀌면 신호를 보냄.
# python3-gi(Gio)와 시스템 버스(ezs-system-bus.conf로 띄운 dbus-daemon, steam-run.sh)가 필요함
import os
import sys

import gi

gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib  # noqa: E402

STATE_FILE = "/tmp/ezs-net"
NM = "/org/freedesktop/NetworkManager"
DEV = NM + "/Devices/1"
AC = NM + "/ActiveConnection/1"
IP4 = NM + "/IP4Config/1"
SET = NM + "/Settings"
CON = NM + "/Settings/1"
UUID = "6e8d3f1a-2b7c-4d5e-9f10-0e2500000001"

XML = """
<node>
 <interface name="org.freedesktop.NetworkManager">
  <method name="GetDevices"><arg type="ao" direction="out"/></method>
  <method name="GetAllDevices"><arg type="ao" direction="out"/></method>
  <method name="GetPermissions"><arg type="a{ss}" direction="out"/></method>
  <method name="CheckConnectivity"><arg type="u" direction="out"/></method>
  <method name="state"><arg type="u" direction="out"/></method>
  <signal name="StateChanged"><arg type="u"/></signal>
  <property name="Devices" type="ao" access="read"/>
  <property name="AllDevices" type="ao" access="read"/>
  <property name="ActiveConnections" type="ao" access="read"/>
  <property name="PrimaryConnection" type="o" access="read"/>
  <property name="PrimaryConnectionType" type="s" access="read"/>
  <property name="State" type="u" access="read"/>
  <property name="Connectivity" type="u" access="read"/>
  <property name="ConnectivityCheckAvailable" type="b" access="read"/>
  <property name="ConnectivityCheckEnabled" type="b" access="read"/>
  <property name="NetworkingEnabled" type="b" access="read"/>
  <property name="WirelessEnabled" type="b" access="read"/>
  <property name="WirelessHardwareEnabled" type="b" access="read"/>
  <property name="WwanEnabled" type="b" access="read"/>
  <property name="Metered" type="u" access="read"/>
  <property name="Startup" type="b" access="read"/>
  <property name="Version" type="s" access="read"/>
  <property name="Capabilities" type="au" access="read"/>
 </interface>
 <interface name="org.freedesktop.NetworkManager.Device">
  <property name="Interface" type="s" access="read"/>
  <property name="IpInterface" type="s" access="read"/>
  <property name="DeviceType" type="u" access="read"/>
  <property name="State" type="u" access="read"/>
  <property name="ActiveConnection" type="o" access="read"/>
  <property name="Ip4Config" type="o" access="read"/>
  <property name="Ip6Config" type="o" access="read"/>
  <property name="Managed" type="b" access="read"/>
  <property name="Real" type="b" access="read"/>
  <property name="Autoconnect" type="b" access="read"/>
  <property name="AvailableConnections" type="ao" access="read"/>
  <property name="Driver" type="s" access="read"/>
  <signal name="StateChanged"><arg type="u"/><arg type="u"/><arg type="u"/></signal>
 </interface>
 <interface name="org.freedesktop.NetworkManager.Device.Wired">
  <property name="Carrier" type="b" access="read"/>
  <property name="Speed" type="u" access="read"/>
  <property name="HwAddress" type="s" access="read"/>
 </interface>
 <interface name="org.freedesktop.NetworkManager.Connection.Active">
  <property name="Connection" type="o" access="read"/>
  <property name="Id" type="s" access="read"/>
  <property name="Uuid" type="s" access="read"/>
  <property name="Type" type="s" access="read"/>
  <property name="Devices" type="ao" access="read"/>
  <property name="State" type="u" access="read"/>
  <property name="Default" type="b" access="read"/>
  <property name="Default6" type="b" access="read"/>
  <property name="Vpn" type="b" access="read"/>
  <property name="Ip4Config" type="o" access="read"/>
  <property name="Ip6Config" type="o" access="read"/>
 </interface>
 <interface name="org.freedesktop.NetworkManager.IP4Config">
  <property name="AddressData" type="aa{sv}" access="read"/>
  <property name="Gateway" type="s" access="read"/>
  <property name="NameserverData" type="aa{sv}" access="read"/>
 </interface>
 <interface name="org.freedesktop.NetworkManager.Settings">
  <method name="ListConnections"><arg type="ao" direction="out"/></method>
  <property name="Connections" type="ao" access="read"/>
  <property name="CanModify" type="b" access="read"/>
  <property name="Hostname" type="s" access="read"/>
 </interface>
 <interface name="org.freedesktop.NetworkManager.Settings.Connection">
  <method name="GetSettings"><arg type="a{sa{sv}}" direction="out"/></method>
  <property name="Unsaved" type="b" access="read"/>
  <property name="Flags" type="u" access="read"/>
  <property name="Filename" type="s" access="read"/>
 </interface>
 <interface name="org.freedesktop.DBus.ObjectManager">
  <method name="GetManagedObjects"><arg type="a{oa{sa{sv}}}" direction="out"/></method>
 </interface>
</node>
"""

INFO = Gio.DBusNodeInfo.new_for_xml(XML)
IFACE = {i.name: i for i in INFO.interfaces}
# Which interfaces each object carries / 객체마다 가진 인터페이스
OBJECTS = {
    NM: ["org.freedesktop.NetworkManager"],
    DEV: ["org.freedesktop.NetworkManager.Device", "org.freedesktop.NetworkManager.Device.Wired"],
    AC: ["org.freedesktop.NetworkManager.Connection.Active"],
    IP4: ["org.freedesktop.NetworkManager.IP4Config"],
    SET: ["org.freedesktop.NetworkManager.Settings"],
    CON: ["org.freedesktop.NetworkManager.Settings.Connection"],
}

# Current state: up (bool), kind ("wifi", "ethernet", "cellular") / 지금 상태: 연결(bool), 종류
cur = {"up": True, "kind": "ethernet"}


def read_state():
    try:
        with open(STATE_FILE) as f:
            parts = f.read().split()
    except OSError:
        return {"up": True, "kind": "ethernet"}
    up = bool(parts) and parts[0] == "1"
    kind = parts[1] if len(parts) > 1 else "ethernet"
    return {"up": up, "kind": kind}


def local_ipv4():
    """Address the system would use outside (no packet is sent) / 밖으로 나갈 때 쓸 주소(패킷은 보내지 않음)"""
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))
        return s.getsockname()[0]
    except OSError:
        return "10.0.0.2"
    finally:
        s.close()


def props(path, iface):
    """Property values of one object's interface / 한 객체 인터페이스의 속성 값"""
    up = cur["up"]
    if iface == "org.freedesktop.NetworkManager":
        return {
            "Devices": GLib.Variant("ao", [DEV]),
            "AllDevices": GLib.Variant("ao", [DEV]),
            "ActiveConnections": GLib.Variant("ao", [AC] if up else []),
            "PrimaryConnection": GLib.Variant("o", AC if up else "/"),
            "PrimaryConnectionType": GLib.Variant("s", "802-3-ethernet" if up else ""),
            # 70 = CONNECTED_GLOBAL, 20 = DISCONNECTED; connectivity 4 = FULL, 1 = NONE
            "State": GLib.Variant("u", 70 if up else 20),
            "Connectivity": GLib.Variant("u", 4 if up else 1),
            "ConnectivityCheckAvailable": GLib.Variant("b", False),
            "ConnectivityCheckEnabled": GLib.Variant("b", False),
            "NetworkingEnabled": GLib.Variant("b", True),
            "WirelessEnabled": GLib.Variant("b", False),
            "WirelessHardwareEnabled": GLib.Variant("b", False),
            "WwanEnabled": GLib.Variant("b", False),
            # 4 = GUESS_NO (not metered) / 4 = 종량제 아님(추정)
            "Metered": GLib.Variant("u", 4),
            "Startup": GLib.Variant("b", False),
            "Version": GLib.Variant("s", "1.46.0"),
            "Capabilities": GLib.Variant("au", []),
        }
    if iface == "org.freedesktop.NetworkManager.Device":
        return {
            "Interface": GLib.Variant("s", "eth0"),
            "IpInterface": GLib.Variant("s", "eth0"),
            "DeviceType": GLib.Variant("u", 1),
            # 100 = ACTIVATED, 30 = DISCONNECTED
            "State": GLib.Variant("u", 100 if up else 30),
            "ActiveConnection": GLib.Variant("o", AC if up else "/"),
            "Ip4Config": GLib.Variant("o", IP4 if up else "/"),
            "Ip6Config": GLib.Variant("o", "/"),
            "Managed": GLib.Variant("b", True),
            "Real": GLib.Variant("b", True),
            "Autoconnect": GLib.Variant("b", True),
            "AvailableConnections": GLib.Variant("ao", [CON]),
            "Driver": GLib.Variant("s", "ezs"),
        }
    if iface == "org.freedesktop.NetworkManager.Device.Wired":
        return {
            "Carrier": GLib.Variant("b", up),
            "Speed": GLib.Variant("u", 1000),
            "HwAddress": GLib.Variant("s", "02:00:00:00:00:01"),
        }
    if iface == "org.freedesktop.NetworkManager.Connection.Active":
        return {
            "Connection": GLib.Variant("o", CON),
            "Id": GLib.Variant("s", "Android"),
            "Uuid": GLib.Variant("s", UUID),
            "Type": GLib.Variant("s", "802-3-ethernet"),
            "Devices": GLib.Variant("ao", [DEV]),
            # 2 = ACTIVATED / 2 = 활성
            "State": GLib.Variant("u", 2 if up else 4),
            "Default": GLib.Variant("b", up),
            "Default6": GLib.Variant("b", False),
            "Vpn": GLib.Variant("b", False),
            "Ip4Config": GLib.Variant("o", IP4),
            "Ip6Config": GLib.Variant("o", "/"),
        }
    if iface == "org.freedesktop.NetworkManager.IP4Config":
        return {
            "AddressData": GLib.Variant("aa{sv}", [{"address": GLib.Variant("s", local_ipv4()),
                                                     "prefix": GLib.Variant("u", 24)}]),
            "Gateway": GLib.Variant("s", ""),
            "NameserverData": GLib.Variant("aa{sv}", []),
        }
    if iface == "org.freedesktop.NetworkManager.Settings":
        return {
            "Connections": GLib.Variant("ao", [CON]),
            "CanModify": GLib.Variant("b", False),
            "Hostname": GLib.Variant("s", "steamdeck"),
        }
    if iface == "org.freedesktop.NetworkManager.Settings.Connection":
        return {
            "Unsaved": GLib.Variant("b", False),
            "Flags": GLib.Variant("u", 0),
            "Filename": GLib.Variant("s", ""),
        }
    return {}


def settings():
    return GLib.Variant("(a{sa{sv}})", ({
        "connection": {"id": GLib.Variant("s", "Android"), "uuid": GLib.Variant("s", UUID),
                       "type": GLib.Variant("s", "802-3-ethernet"), "autoconnect": GLib.Variant("b", True)},
        "802-3-ethernet": {},
        "ipv4": {"method": GLib.Variant("s", "auto")},
        "ipv6": {"method": GLib.Variant("s", "ignore")},
    },))


def managed():
    out = {}
    for path, ifaces in OBJECTS.items():
        if path == NM:
            continue
        out[path] = {i: props(path, i) for i in ifaces}
    return GLib.Variant("(a{oa{sa{sv}}})", (out,))


# What the client asks, each distinct request once (path, interface, method or property). Why: Big
# Picture kept its "no network" icon with the stand-in running (2026-10-30) and nothing showed
# whether the client asks it anything at all
# 클라이언트가 묻는 것, 서로 다른 요청마다 한 번(경로, 인터페이스, 메서드 또는 속성). 이유: 대역이 떠 있어도
# 빅픽처가 "네트워크 없음" 아이콘을 유지했고(2026-10-30), 클라이언트가 무엇이든 묻기는 하는지 보이지 않았음
seen = set()
asked = {"n": 0}


def note(kind, path, iface, name, answered):
    asked["n"] += 1
    key = (kind, path, iface, name)
    if key in seen or len(seen) >= 80:
        return
    seen.add(key)
    print("ezs-netstate: 요청 %s %s %s.%s%s" % (kind, path, iface.rsplit(".", 1)[-1], name,
                                              "" if answered else " (답 없음)"), flush=True)


def on_call(conn, sender, path, iface, method, params, inv):
    note("호출", path, iface, method, method in (
        "GetDevices", "GetAllDevices", "GetPermissions", "CheckConnectivity", "state", "ListConnections",
        "GetSettings", "GetManagedObjects"))
    if method in ("GetDevices", "GetAllDevices"):
        inv.return_value(GLib.Variant("(ao)", ([DEV],)))
    elif method == "GetPermissions":
        inv.return_value(GLib.Variant("(a{ss})", ({},)))
    elif method in ("CheckConnectivity",):
        inv.return_value(GLib.Variant("(u)", (4 if cur["up"] else 1,)))
    elif method == "state":
        inv.return_value(GLib.Variant("(u)", (70 if cur["up"] else 20,)))
    elif method == "ListConnections":
        inv.return_value(GLib.Variant("(ao)", ([CON],)))
    elif method == "GetSettings":
        inv.return_value(settings())
    elif method == "GetManagedObjects":
        inv.return_value(managed())
    else:
        inv.return_dbus_error("org.freedesktop.NetworkManager.NotSupported", method + " not supported")


def on_get(conn, sender, path, iface, name):
    v = props(path, iface).get(name)
    note("속성", path, iface, name, v is not None)
    return v


def changed(conn):
    """Tell watchers that the state changed / 상태가 바뀌었다고 알림"""
    for path, ifaces in OBJECTS.items():
        for i in ifaces:
            if i in ("org.freedesktop.NetworkManager.Settings", "org.freedesktop.NetworkManager.Settings.Connection"):
                continue
            conn.emit_signal(None, path, "org.freedesktop.DBus.Properties", "PropertiesChanged",
                             GLib.Variant("(sa{sv}as)", (i, props(path, i), [])))
    st = 70 if cur["up"] else 20
    conn.emit_signal(None, NM, "org.freedesktop.NetworkManager", "StateChanged", GLib.Variant("(u)", (st,)))
    conn.emit_signal(None, DEV, "org.freedesktop.NetworkManager.Device", "StateChanged",
                     GLib.Variant("(uuu)", (100 if cur["up"] else 30, 30 if cur["up"] else 100, 0)))


def main():
    addr = os.environ.get("DBUS_SYSTEM_BUS_ADDRESS", "unix:path=/run/dbus/system_bus_socket")
    conn = Gio.DBusConnection.new_for_address_sync(
        addr, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION,
        None, None)
    for path, ifaces in OBJECTS.items():
        for i in ifaces:
            conn.register_object(path, IFACE[i], on_call, on_get, None)
    for path in ("/org/freedesktop", NM):
        conn.register_object(path, IFACE["org.freedesktop.DBus.ObjectManager"], on_call, None, None)
    # RequestName(name, flags 4 = DO_NOT_QUEUE) / 이름 요청
    r = conn.call_sync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName",
                       GLib.Variant("(su)", ("org.freedesktop.NetworkManager", 4)), GLib.VariantType("(u)"),
                       Gio.DBusCallFlags.NONE, -1, None)
    cur.update(read_state())
    print("ezs-netstate: NetworkManager 대역 시작 (이름 요청 결과 %d, 연결 %s %s)"
          % (r.unpack()[0], "있음" if cur["up"] else "없음", cur["kind"]), flush=True)

    # Requests per minute, only when there were any / 1분 요청 수, 있었을 때만
    def minute():
        if asked["n"]:
            print("ezs-netstate: 1분 요청 %d건" % asked["n"], flush=True)
            asked["n"] = 0
        return True

    GLib.timeout_add_seconds(60, minute)

    def poll():
        s = read_state()
        if s != cur:
            cur.update(s)
            print("ezs-netstate: 연결 %s %s" % ("있음" if cur["up"] else "없음", cur["kind"]), flush=True)
            changed(conn)
        return True

    GLib.timeout_add_seconds(2, poll)
    GLib.MainLoop().run()


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001 - one line in the log, Steam goes on without it
        print("ezs-netstate: 시작 실패: %s" % e, file=sys.stderr, flush=True)
        sys.exit(1)
