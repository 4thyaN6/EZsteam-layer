#!/usr/bin/env python3
# ezs-agent: one resident process for the Steam session in place of three. It does
#   1. the system D-Bus "bus" itself (socket /run/dbus/system_bus_socket), only as much as the
#      Steam client and the web helper use: Hello, name queries, match rules, error answers for
#      services that do not exist. Was: dbus-daemon with ezs-system-bus.conf
#   2. the NetworkManager stand-in on that bus, now with a Wi-Fi device and access point when the
#      phone is on Wi-Fi (the connection icon stayed "none" with a wired device only, 2026-10-30).
#      Was: ezs-netstate.py (a second process)
#   3. the Steam start loop: steam-run.sh still does every check before each start ("prepare"
#      step, then it ends), this process starts the client, waits, and starts it again after a
#      self update (rc 42) or a Big Picture restart request. Was: the steam-run.sh shell staying
#      alive for the whole session
# Why: the app must stay under Android's limit of 32 child processes for all apps together, with
# a target of 25 for this layer (user decisions 2026-10-30/11-01); these three were small but
# resident. If python3-gi (Gio) cannot be loaded the old path runs unchanged (steam-run.sh alone).
# The bus is a peer-to-peer GDBus server per client: every client talks to this process only, so
# the "bus" answers what a message bus would and the NetworkManager objects live on each
# connection. Written from the public D-Bus specification and NetworkManager D-Bus API
# description, own code, MIT. Started by display.sh (exec), off with the switch file
# ~/.local/share/ezsteam/no-agent.
#
# ezs-agent: Steam 세션의 상주 프로세스 셋을 하나로 함. 하는 일
#   1. 시스템 D-Bus "버스" 자체(소켓 /run/dbus/system_bus_socket). Steam 클라이언트와 웹 도우미가 쓰는 만큼만:
#      Hello, 이름 조회, 매치 규칙, 없는 서비스에 대한 오류 답. 전에는 ezs-system-bus.conf로 띄운 dbus-daemon
#   2. 그 버스 위의 NetworkManager 대역. 휴대폰이 Wi-Fi일 때는 Wi-Fi 장치와 접속점까지 보임(유선 장치만으로는 연결
#      아이콘이 "없음"으로 남았음, 2026-10-30). 전에는 ezs-netstate.py(프로세스 하나 더)
#   3. Steam 시작 반복: 시작 전 검사는 여전히 steam-run.sh가 모두 함("prepare" 단계, 그다음 끝남). 이 프로세스가
#      클라이언트를 띄우고 기다리고, 자체 갱신(rc 42)이나 빅픽처 다시 시작 요청 뒤 다시 띄움. 전에는 steam-run.sh 셸이
#      세션 내내 떠 있었음
# 이유: 앱은 모든 앱 합계 자식 프로세스 32개 한도 안에 있어야 하고, 이 레이어 목표는 25개임(사용자 결정 2026-10-30,
# 11-01). 이 셋은 작지만 상주했음. python3-gi(Gio)를 불러올 수 없으면 예전 경로(steam-run.sh 단독)가 그대로 돎.
# 버스는 클라이언트마다 1:1 GDBus 서버 연결임: 모든 클라이언트가 이 프로세스하고만 대화하므로, "버스"는 메시지 버스가
# 할 답을 하고 NetworkManager 객체는 연결마다 등록됨. 공개 D-Bus 명세와 NetworkManager D-Bus API 설명으로 직접
# 작성, MIT. display.sh가 exec로 띄움. 스위치 파일 ~/.local/share/ezsteam/no-agent로 끔.
import os
import subprocess
import sys
import threading
import time

RUN = "/opt/ezsteam/steam-run.sh"
BUS_DIR = "/run/dbus"
BUS_SOCK = BUS_DIR + "/system_bus_socket"
CMD_FILE = "/tmp/ezs-steam.cmd"
ENV_FILE = "/tmp/ezs-steam.env"
CWD_FILE = "/tmp/ezs-steam.cwd"
BPMARK = "/tmp/ezs-bigpicture-next"
STATE_FILE = "/tmp/ezs-net"


_out = threading.Lock()


def say(line):
    """One whole line per write, so lines from the bus and the start loop never mix
    한 번 쓸 때 한 줄 전체: 버스와 시작 반복의 줄이 섞이지 않게 함"""
    with _out:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def log(msg):
    say("ezs-agent: " + msg)


def fallback(why):
    """Old path: the shell script alone (it starts dbus-daemon and ezs-netstate.py itself)
    예전 경로: 셸 스크립트 단독(dbus-daemon과 ezs-netstate.py를 스스로 띄움)"""
    log("예전 경로로 시작 (%s)" % why)
    os.environ.pop("EZS_AGENT_STEP", None)
    os.execvp("sh", ["sh", RUN])


try:
    import gi

    gi.require_version("Gio", "2.0")
    from gi.repository import Gio, GLib  # noqa: E402
except Exception as e:  # noqa: BLE001 - any import problem means the old path
    fallback("python3-gi 없음: %s" % e)

# ---------------------------------------------------------------- NetworkManager stand-in
# NetworkManager 대역

NM = "/org/freedesktop/NetworkManager"
DEV = NM + "/Devices/1"
AP = NM + "/AccessPoint/1"
AC = NM + "/ActiveConnection/1"
IP4 = NM + "/IP4Config/1"
SET = NM + "/Settings"
CON = NM + "/Settings/1"
UUID = "6e8d3f1a-2b7c-4d5e-9f10-0e2500000001"
# Shown name of the network: the phone's real Wi-Fi name needs a location permission the app does
# not ask for, so a fixed name / 보이는 네트워크 이름: 휴대폰의 실제 Wi-Fi 이름은 앱이 묻지 않는 위치 권한이 필요해서 고정값
SSID = "Android"

NM_XML = """
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
 <interface name="org.freedesktop.NetworkManager.Device.Wireless">
  <method name="GetAccessPoints"><arg type="ao" direction="out"/></method>
  <method name="GetAllAccessPoints"><arg type="ao" direction="out"/></method>
  <method name="RequestScan"><arg type="a{sv}" direction="in"/></method>
  <property name="HwAddress" type="s" access="read"/>
  <property name="PermHwAddress" type="s" access="read"/>
  <property name="Mode" type="u" access="read"/>
  <property name="Bitrate" type="u" access="read"/>
  <property name="AccessPoints" type="ao" access="read"/>
  <property name="ActiveAccessPoint" type="o" access="read"/>
  <property name="WirelessCapabilities" type="u" access="read"/>
  <property name="LastScan" type="x" access="read"/>
 </interface>
 <interface name="org.freedesktop.NetworkManager.AccessPoint">
  <property name="Flags" type="u" access="read"/>
  <property name="WpaFlags" type="u" access="read"/>
  <property name="RsnFlags" type="u" access="read"/>
  <property name="Ssid" type="ay" access="read"/>
  <property name="Frequency" type="u" access="read"/>
  <property name="HwAddress" type="s" access="read"/>
  <property name="Mode" type="u" access="read"/>
  <property name="MaxBitrate" type="u" access="read"/>
  <property name="Strength" type="y" access="read"/>
  <property name="LastSeen" type="i" access="read"/>
 </interface>
 <interface name="org.freedesktop.NetworkManager.Connection.Active">
  <property name="Connection" type="o" access="read"/>
  <property name="SpecificObject" type="o" access="read"/>
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

# The bus's own interface, as much as clients use / 버스 자체 인터페이스, 클라이언트가 쓰는 만큼
BUS_XML = """
<node>
 <interface name="org.freedesktop.DBus">
  <method name="Hello"><arg type="s" direction="out"/></method>
  <method name="RequestName"><arg type="s" direction="in"/><arg type="u" direction="in"/><arg type="u" direction="out"/></method>
  <method name="ReleaseName"><arg type="s" direction="in"/><arg type="u" direction="out"/></method>
  <method name="GetNameOwner"><arg type="s" direction="in"/><arg type="s" direction="out"/></method>
  <method name="NameHasOwner"><arg type="s" direction="in"/><arg type="b" direction="out"/></method>
  <method name="ListNames"><arg type="as" direction="out"/></method>
  <method name="ListActivatableNames"><arg type="as" direction="out"/></method>
  <method name="StartServiceByName"><arg type="s" direction="in"/><arg type="u" direction="in"/><arg type="u" direction="out"/></method>
  <method name="AddMatch"><arg type="s" direction="in"/></method>
  <method name="RemoveMatch"><arg type="s" direction="in"/></method>
  <method name="GetConnectionUnixUser"><arg type="s" direction="in"/><arg type="u" direction="out"/></method>
  <method name="GetConnectionUnixProcessID"><arg type="s" direction="in"/><arg type="u" direction="out"/></method>
  <method name="GetId"><arg type="s" direction="out"/></method>
  <signal name="NameOwnerChanged"><arg type="s"/><arg type="s"/><arg type="s"/></signal>
  <signal name="NameAcquired"><arg type="s"/></signal>
 </interface>
</node>
"""

NM_INFO = Gio.DBusNodeInfo.new_for_xml(NM_XML)
NM_IFACE = {i.name: i for i in NM_INFO.interfaces}
BUS_IFACE = Gio.DBusNodeInfo.new_for_xml(BUS_XML).interfaces[0]
NM_NAME = "org.freedesktop.NetworkManager"
NM_OWNER = ":1.0"

# Current state: up (bool), kind ("wifi", "ethernet", "cellular") / 지금 상태: 연결, 종류
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


def wifi():
    return cur["kind"] == "wifi"


def objects():
    """Which interfaces each object carries for the current kind / 지금 종류에서 객체마다 가진 인터페이스"""
    o = {
        NM: ["org.freedesktop.NetworkManager"],
        DEV: ["org.freedesktop.NetworkManager.Device",
              "org.freedesktop.NetworkManager.Device.Wireless" if wifi() else "org.freedesktop.NetworkManager.Device.Wired"],
        AC: ["org.freedesktop.NetworkManager.Connection.Active"],
        IP4: ["org.freedesktop.NetworkManager.IP4Config"],
        SET: ["org.freedesktop.NetworkManager.Settings"],
        CON: ["org.freedesktop.NetworkManager.Settings.Connection"],
    }
    if wifi():
        o[AP] = ["org.freedesktop.NetworkManager.AccessPoint"]
    return o


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


def con_type():
    return "802-11-wireless" if wifi() else "802-3-ethernet"


def props(path, iface):
    """Property values of one object's interface / 한 객체 인터페이스의 속성 값"""
    up = cur["up"]
    V = GLib.Variant
    if iface == "org.freedesktop.NetworkManager":
        return {
            "Devices": V("ao", [DEV]),
            "AllDevices": V("ao", [DEV]),
            "ActiveConnections": V("ao", [AC] if up else []),
            "PrimaryConnection": V("o", AC if up else "/"),
            "PrimaryConnectionType": V("s", con_type() if up else ""),
            # 70 = CONNECTED_GLOBAL, 20 = DISCONNECTED; connectivity 4 = FULL, 1 = NONE
            "State": V("u", 70 if up else 20),
            "Connectivity": V("u", 4 if up else 1),
            "ConnectivityCheckAvailable": V("b", False),
            "ConnectivityCheckEnabled": V("b", False),
            "NetworkingEnabled": V("b", True),
            "WirelessEnabled": V("b", wifi()),
            "WirelessHardwareEnabled": V("b", wifi()),
            "WwanEnabled": V("b", False),
            # 4 = GUESS_NO (not metered) / 4 = 종량제 아님(추정)
            "Metered": V("u", 4),
            "Startup": V("b", False),
            "Version": V("s", "1.46.0"),
            "Capabilities": V("au", []),
        }
    if iface == "org.freedesktop.NetworkManager.Device":
        name = "wlan0" if wifi() else "eth0"
        return {
            "Interface": V("s", name),
            "IpInterface": V("s", name),
            # 2 = WIFI, 1 = ETHERNET
            "DeviceType": V("u", 2 if wifi() else 1),
            # 100 = ACTIVATED, 30 = DISCONNECTED
            "State": V("u", 100 if up else 30),
            "ActiveConnection": V("o", AC if up else "/"),
            "Ip4Config": V("o", IP4 if up else "/"),
            "Ip6Config": V("o", "/"),
            "Managed": V("b", True),
            "Real": V("b", True),
            "Autoconnect": V("b", True),
            "AvailableConnections": V("ao", [CON]),
            "Driver": V("s", "ezs"),
        }
    if iface == "org.freedesktop.NetworkManager.Device.Wired":
        return {
            "Carrier": V("b", up),
            "Speed": V("u", 1000),
            "HwAddress": V("s", "02:00:00:00:00:01"),
        }
    if iface == "org.freedesktop.NetworkManager.Device.Wireless":
        return {
            "HwAddress": V("s", "02:00:00:00:00:02"),
            "PermHwAddress": V("s", "02:00:00:00:00:02"),
            # 2 = INFRA
            "Mode": V("u", 2),
            "Bitrate": V("u", 866000 if up else 0),
            "AccessPoints": V("ao", [AP]),
            "ActiveAccessPoint": V("o", AP if up else "/"),
            "WirelessCapabilities": V("u", 0),
            "LastScan": V("x", int(time.clock_gettime(time.CLOCK_BOOTTIME) * 1000)),
        }
    if iface == "org.freedesktop.NetworkManager.AccessPoint":
        return {
            "Flags": V("u", 1),
            "WpaFlags": V("u", 0),
            # 0x188 = pairwise CCMP, group CCMP, key mgmt PSK
            "RsnFlags": V("u", 0x188),
            "Ssid": V("ay", SSID.encode()),
            "Frequency": V("u", 5180),
            "HwAddress": V("s", "02:00:00:00:00:03"),
            "Mode": V("u", 2),
            "MaxBitrate": V("u", 866000),
            # Signal strength in percent; the phone's value is not read (no permission asked)
            # 신호 세기 퍼센트. 휴대폰 값은 읽지 않음(권한을 묻지 않음)
            "Strength": V("y", 80),
            "LastSeen": V("i", int(time.clock_gettime(time.CLOCK_BOOTTIME))),
        }
    if iface == "org.freedesktop.NetworkManager.Connection.Active":
        return {
            "Connection": V("o", CON),
            "SpecificObject": V("o", AP if wifi() else "/"),
            "Id": V("s", SSID),
            "Uuid": V("s", UUID),
            "Type": V("s", con_type()),
            "Devices": V("ao", [DEV]),
            # 2 = ACTIVATED, 4 = DEACTIVATED
            "State": V("u", 2 if up else 4),
            "Default": V("b", up),
            "Default6": V("b", False),
            "Vpn": V("b", False),
            "Ip4Config": V("o", IP4),
            "Ip6Config": V("o", "/"),
        }
    if iface == "org.freedesktop.NetworkManager.IP4Config":
        return {
            "AddressData": V("aa{sv}", [{"address": V("s", local_ipv4()), "prefix": V("u", 24)}]),
            "Gateway": V("s", ""),
            "NameserverData": V("aa{sv}", []),
        }
    if iface == "org.freedesktop.NetworkManager.Settings":
        return {
            "Connections": V("ao", [CON]),
            "CanModify": V("b", False),
            "Hostname": V("s", "steamdeck"),
        }
    if iface == "org.freedesktop.NetworkManager.Settings.Connection":
        return {
            "Unsaved": V("b", False),
            "Flags": V("u", 0),
            "Filename": V("s", ""),
        }
    return {}


def settings():
    V = GLib.Variant
    s = {
        "connection": {"id": V("s", SSID), "uuid": V("s", UUID), "type": V("s", con_type()),
                       "autoconnect": V("b", True)},
        "ipv4": {"method": V("s", "auto")},
        "ipv6": {"method": V("s", "ignore")},
    }
    if wifi():
        s["802-11-wireless"] = {"ssid": V("ay", SSID.encode()), "mode": V("s", "infrastructure")}
        s["802-11-wireless-security"] = {"key-mgmt": V("s", "wpa-psk")}
    else:
        s["802-3-ethernet"] = {}
    return V("(a{sa{sv}})", (s,))


def managed(base):
    """Objects below base (ObjectManager rule: children only). Asked at /org/freedesktop the
    NetworkManager object itself is a child and must be in the answer (2026-10-30)
    base 아래 객체들(ObjectManager 규칙: 자식만). /org/freedesktop에서 물으면 NetworkManager 객체 자체가
    자식이라 답에 있어야 함(2026-10-30)"""
    out = {}
    for path, ifaces in objects().items():
        if path == base or not path.startswith(base.rstrip("/") + "/"):
            continue
        out[path] = {i: props(path, i) for i in ifaces}
    return GLib.Variant("(a{oa{sa{sv}}})", (out,))


# What clients ask, each distinct request once / 클라이언트가 묻는 것, 서로 다른 요청마다 한 번
seen = set()
asked = {"n": 0}


# Program behind each bus connection (peer credentials -> /proc/<pid>/comm): which program asks
# NetworkManager - the Steam binaries hold no NetworkManager names of their own, only the web
# helper's Chromium does (scan 2026-11-01)
# 버스 연결마다 상대 프로그램(상대 자격 증명 -> /proc/<pid>/comm): 누가 NetworkManager에 묻는지. Steam 실행
# 파일에는 자체 NetworkManager 이름이 없고 웹 도우미의 Chromium에만 있었음(스캔 2026-11-01)
who_of = {}


def peer_name(conn):
    """Program and pid of the connection's other end, ? when unknown / 연결 상대의 프로그램과 pid, 모르면 ?"""
    try:
        cred = conn.get_peer_credentials()
        if cred is None:
            # Anonymous authentication leaves no credentials: ask the socket (SO_PEERCRED)
            # 익명 인증이면 자격 증명이 남지 않음: 소켓에 물음(SO_PEERCRED)
            cred = conn.get_stream().get_socket().get_credentials()
        pid = cred.get_unix_pid() if cred else -1
        if pid and pid > 0:
            with open("/proc/%d/comm" % pid) as f:
                return "%s %d" % (f.read().strip(), pid)
    except Exception:  # noqa: BLE001 - only a log detail
        pass
    return "?"


def note(kind, path, iface, name, answered, conn=None):
    asked["n"] += 1
    who = who_of.get(conn, "?") if conn is not None else "?"
    key = (kind, path, iface, name, who.split(" ")[0])
    if key in seen or len(seen) >= 80:
        return
    seen.add(key)
    log("요청 %s %s %s.%s%s [%s]" % (kind, path, iface.rsplit(".", 1)[-1], name, "" if answered else " (답 없음)", who))


def nm_call(conn, sender, path, iface, method, params, inv):
    known = method in ("GetDevices", "GetAllDevices", "GetPermissions", "CheckConnectivity", "state",
                       "ListConnections", "GetSettings", "GetManagedObjects", "GetAccessPoints",
                       "GetAllAccessPoints", "RequestScan")
    note("호출", path, iface, method, known, conn)
    V = GLib.Variant
    if method in ("GetDevices", "GetAllDevices"):
        inv.return_value(V("(ao)", ([DEV],)))
    elif method in ("GetAccessPoints", "GetAllAccessPoints"):
        inv.return_value(V("(ao)", ([AP] if wifi() else [],)))
    elif method == "RequestScan":
        inv.return_value(None)
    elif method == "GetPermissions":
        inv.return_value(V("(a{ss})", ({},)))
    elif method == "CheckConnectivity":
        inv.return_value(V("(u)", (4 if cur["up"] else 1,)))
    elif method == "state":
        inv.return_value(V("(u)", (70 if cur["up"] else 20,)))
    elif method == "ListConnections":
        inv.return_value(V("(ao)", ([CON],)))
    elif method == "GetSettings":
        inv.return_value(settings())
    elif method == "GetManagedObjects":
        inv.return_value(managed(path))
    else:
        inv.return_dbus_error("org.freedesktop.NetworkManager.NotSupported", method + " not supported")


def nm_get(conn, sender, path, iface, name):
    v = props(path, iface).get(name)
    note("속성", path, iface, name, v is not None, conn)
    return v


# ---------------------------------------------------------------- the bus
# 버스

clients = []
lock = threading.Lock()
serial = {"n": 1}
GUID = Gio.dbus_generate_guid()


def bus_call(conn, sender, path, iface, method, params, inv):
    V = GLib.Variant
    if method == "Hello":
        with lock:
            serial["n"] += 1
            name = ":1.%d" % serial["n"]
        inv.return_value(V("(s)", (name,)))
    elif method == "RequestName":
        # 1 = PRIMARY_OWNER: nobody else owns names here / 1 = 주인: 여기서는 다른 주인이 없음
        inv.return_value(V("(u)", (1,)))
    elif method == "ReleaseName":
        inv.return_value(V("(u)", (1,)))
    elif method == "GetNameOwner":
        n = params.unpack()[0]
        if n == NM_NAME or n == "org.freedesktop.DBus":
            inv.return_value(V("(s)", (NM_OWNER if n == NM_NAME else n,)))
        else:
            inv.return_dbus_error("org.freedesktop.DBus.Error.NameHasNoOwner", "Could not get owner of name '%s'" % n)
    elif method == "NameHasOwner":
        n = params.unpack()[0]
        inv.return_value(V("(b)", (n in (NM_NAME, "org.freedesktop.DBus"),)))
    elif method == "ListNames":
        inv.return_value(V("(as)", (["org.freedesktop.DBus", NM_NAME],)))
    elif method == "ListActivatableNames":
        inv.return_value(V("(as)", (["org.freedesktop.DBus"],)))
    elif method == "StartServiceByName":
        n = params.unpack()[0]
        if n == NM_NAME:
            # 2 = ALREADY_RUNNING / 2 = 이미 실행 중
            inv.return_value(V("(u)", (2,)))
        else:
            inv.return_dbus_error("org.freedesktop.DBus.Error.ServiceUnknown",
                                  "The name %s was not provided by any .service files" % n)
    elif method in ("AddMatch", "RemoveMatch"):
        if method == "AddMatch":
            note_rule(params.unpack()[0], who_of.get(conn, "?"))
        inv.return_value(None)
    elif method == "GetConnectionUnixUser":
        inv.return_value(V("(u)", (os.getuid(),)))
    elif method == "GetConnectionUnixProcessID":
        inv.return_value(V("(u)", (os.getpid(),)))
    elif method == "GetId":
        inv.return_value(V("(s)", (GUID,)))
    else:
        inv.return_dbus_error("org.freedesktop.DBus.Error.UnknownMethod", method)


# Match rules clients register, each distinct one once: which signals Steam listens for (the
# network page showed no active network although every state value said connected, 2026-11-01)
# 클라이언트가 등록하는 매치 규칙, 서로 다른 것마다 한 번: Steam이 어떤 신호를 듣는지(모든 상태 값이 연결됨인데도
# 네트워크 화면에 활성 네트워크가 없었음, 2026-11-01)
rules = set()


def note_rule(rule, who="?"):
    if rule in rules or len(rules) >= 40:
        return
    rules.add(rule)
    log("버스 규칙 [%s] %s" % (who, rule[:200]))


def send_signal(conn, path, iface, name, body):
    """Signal with the NetworkManager name as sender, as a bus would show it
    버스가 보여 줄 것처럼 NetworkManager 이름을 보낸 쪽으로 한 신호"""
    m = Gio.DBusMessage.new_signal(path, iface, name)
    m.set_sender(NM_NAME)
    m.set_body(body)
    try:
        conn.send_message(m, Gio.DBusSendMessageFlags.NONE)
    except GLib.Error:
        pass


def new_connection(server, conn):
    """Every client gets the bus object and the NetworkManager objects on its own connection
    클라이언트마다 자기 연결에 버스 객체와 NetworkManager 객체를 받음"""
    who_of[conn] = peer_name(conn)
    conn.register_object("/org/freedesktop/DBus", BUS_IFACE, bus_call, None, None)
    conn.register_object("/", BUS_IFACE, bus_call, None, None)
    # Both kinds' interfaces are registered; objects() decides which ones answers list
    # 두 종류의 인터페이스를 모두 등록함. 답에 넣을 것은 objects()가 정함
    regs = {
        NM: ["org.freedesktop.NetworkManager"],
        DEV: ["org.freedesktop.NetworkManager.Device", "org.freedesktop.NetworkManager.Device.Wired",
              "org.freedesktop.NetworkManager.Device.Wireless"],
        AP: ["org.freedesktop.NetworkManager.AccessPoint"],
        AC: ["org.freedesktop.NetworkManager.Connection.Active"],
        IP4: ["org.freedesktop.NetworkManager.IP4Config"],
        SET: ["org.freedesktop.NetworkManager.Settings"],
        CON: ["org.freedesktop.NetworkManager.Settings.Connection"],
    }
    for path, ifaces in regs.items():
        for i in ifaces:
            conn.register_object(path, NM_IFACE[i], nm_call, nm_get, None)
    for path in ("/org/freedesktop", NM):
        conn.register_object(path, NM_IFACE["org.freedesktop.DBus.ObjectManager"], nm_call, None, None)
    with lock:
        clients.append(conn)
        n = len(clients)
    conn.connect("closed", closed)
    with lock:
        serial["conns"] = serial.get("conns", 0) + 1
        total = serial["conns"]
    # First few and then every 20th, so a client that reconnects often does not flood the log
    # 처음 몇 개와 그 뒤 20번째마다: 자주 다시 붙는 클라이언트가 로그를 채우지 않게 함
    if total <= 5 or total % 20 == 0:
        log("버스 연결 %d번째 (지금 %d개) %s" % (total, n, who_of.get(conn, "?")))
    return True


def closed(conn, remote_peer_vanished, error):
    who_of.pop(conn, None)
    with lock:
        if conn in clients:
            clients.remove(conn)


def changed():
    """Tell every client that the state changed / 모든 클라이언트에 상태가 바뀌었다고 알림"""
    with lock:
        conns = list(clients)
    for conn in conns:
        for path, ifaces in objects().items():
            for i in ifaces:
                if i in ("org.freedesktop.NetworkManager.Settings", "org.freedesktop.NetworkManager.Settings.Connection"):
                    continue
                send_signal(conn, path, "org.freedesktop.DBus.Properties", "PropertiesChanged",
                            GLib.Variant("(sa{sv}as)", (i, props(path, i), [])))
        send_signal(conn, NM, "org.freedesktop.NetworkManager", "StateChanged",
                    GLib.Variant("(u)", (70 if cur["up"] else 20,)))


def start_bus():
    os.makedirs(BUS_DIR, exist_ok=True)
    try:
        os.unlink(BUS_SOCK)
    except OSError:
        pass
    obs = Gio.DBusAuthObserver.new()
    # Every peer is one of the app's own programs inside the rootfs / 모든 상대는 rootfs 안 앱 자체 프로그램임
    obs.connect("authorize-authenticated-peer", lambda o, stream, cred: True)
    server = Gio.DBusServer.new_sync("unix:path=" + BUS_SOCK,
                                     Gio.DBusServerFlags.AUTHENTICATION_ALLOW_ANONYMOUS, GUID, obs, None)
    server.connect("new-connection", new_connection)
    server.start()
    return server


# ---------------------------------------------------------------- Steam menu background
# Steam 메뉴 바탕
#
# Steam's main menu and quick access menu are separate Chromium pages with a transparent page
# background. With GPU compositing the transparent part (above and below the menu panel) is not
# cleared and shows leftovers from the second or third opening on: colour blocks with ANGLE
# Vulkan, white with GL (menu opened 6 times each: always from the 2nd/3rd opening; with an
# opaque black page background never, whtest 2026-11-01 16:20). So those two pages get an opaque
# black page background (one style rule), nothing else changes. Making it right without the rule
# is a later optimisation task (user 2026-11-01).
# Reached through Chromium's DevTools over a pipe (--remote-debugging-pipe, fds 3/4 of the web
# helper, linked to two FIFOs in /tmp by ezs-webhelper-env.sh): no network port, so other apps on
# the device cannot reach the Steam UI, and no extra process. Off with the switch file
# ~/.local/share/ezsteam/no-menubg.
# Steam 메인 메뉴와 빠른 메뉴는 페이지 바탕이 투명한 별도 Chromium 페이지임. GPU 합성에서는 투명한 부분(메뉴 패널
# 위·아래)이 지워지지 않아 두 번째·세 번째 열 때부터 남은 내용이 보임: ANGLE Vulkan은 색 블록, GL은 흰색(메뉴를 각각
# 6번 열었을 때 늘 2·3번째부터. 불투명 검정 페이지 바탕이면 한 번도 없었음, whtest 2026-11-01 16:20). 그래서 이 두
# 페이지에 불투명 검정 페이지 바탕(스타일 규칙 하나)을 주고 다른 것은 바꾸지 않음. 규칙 없이 바르게 그리게 하는 것은
# 나중 최적화 과제임(사용자 2026-11-01).
# Chromium DevTools를 파이프로 씀(--remote-debugging-pipe, 웹 도우미의 fd 3/4를 ezs-webhelper-env.sh가 /tmp의
# FIFO 두 개에 이음): 네트워크 포트가 없어 기기의 다른 앱이 Steam 화면에 닿을 수 없고, 프로세스도 늘지 않음.
# 스위치 파일 ~/.local/share/ezsteam/no-menubg로 끔.

DT_IN = "/tmp/ezs-devtools.in"
DT_OUT = "/tmp/ezs-devtools.out"
MENU_PAGES = ("MainMenu", "QuickAccess")
# Sound check on the same pipe: the page that loads the UI sounds (deck_ui_*.wav, web helper log)
# is SharedJSContext; its Web Audio contexts and their state (running/suspended/closed) are logged.
# Why: Big Picture played only the start sound; Chromium opened no further output for the move
# sounds and its own audio log said nothing (whtest 2026-11-01 18:16), so the question is whether
# the page's audio context stops (suspended/closed) or never asks for output
# 같은 파이프로 소리 확인: UI 소리(deck_ui_*.wav, 웹 도우미 로그)를 불러오는 페이지는 SharedJSContext임. 그 페이지의
# Web Audio 컨텍스트와 상태(running/suspended/closed)를 기록함. 이유: 빅픽처는 시작 소리만 났고, Chromium은 이동
# 소리용 출력을 더 열지 않았으며 자체 오디오 기록에도 아무것도 없었음(whtest 2026-11-01 18:16). 그래서 페이지의 오디오
# 컨텍스트가 멈추는지(suspended/closed), 아니면 출력을 아예 요청하지 않는지가 문제임
SOUND_PAGES = ("SharedJSContext",)
MENU_CSS = "html,body{background:#000!important}"
MENU_JS = ("(function(){var d=document;if(!d||!d.head)return 'no-head';"
           "if(d.getElementById('ezs-menubg'))return 'kept';"
           "var s=d.createElement('style');s.id='ezs-menubg';s.textContent=%s;"
           "d.head.appendChild(s);return 'added';})()") % repr(MENU_CSS).replace("'", '"')


def menubg_on():
    home = os.environ.get("HOME", "/home/steam")
    return not os.path.exists(os.path.join(home, ".local/share/ezsteam/no-menubg"))


def make_fifos():
    """The two FIFOs the web helper wrapper links to fds 3 and 4 / 웹 도우미 감쌈이 fd 3·4에 잇는 FIFO 두 개"""
    for p in (DT_IN, DT_OUT):
        try:
            os.unlink(p)
        except OSError:
            pass
        os.mkfifo(p, 0o600)


def devtools_loop():
    """Every 3 s: list targets, attach to the menu pages once per target, add the rule
    (idempotent). Messages are JSON ending in a NUL byte both ways
    3초마다: 대상 목록, 메뉴 페이지마다 한 번 붙음, 규칙 넣기(여러 번 해도 같음). 메시지는 양쪽 모두 NUL로 끝나는 JSON"""
    import json
    import select
    # Read and write ends opened read-write: never blocks, never sees an end of file while the
    # web helper restarts / 읽기·쓰기 끝을 읽기쓰기로 엶: 막히지 않고, 웹 도우미가 다시 시작하는 동안 파일 끝을 보지 않음
    wfd = os.open(DT_IN, os.O_RDWR)
    rfd = os.open(DT_OUT, os.O_RDWR | os.O_NONBLOCK)
    buf = b""
    nid = {"n": 0}
    sessions = {}   # targetId -> sessionId
    pending = {}    # message id -> (kind, targetId, title)
    state = {}      # targetId -> last result
    answered = {"any": False}
    audio_lines = {"n": 0}

    def send(obj):
        nid["n"] += 1
        obj["id"] = nid["n"]
        os.write(wfd, json.dumps(obj).encode() + b"\0")
        return nid["n"]

    started = time.time()
    while True:
        send({"method": "Target.getTargets"})
        end = time.time() + 3
        while time.time() < end:
            r, _, _ = select.select([rfd], [], [], max(0.0, end - time.time()))
            if not r:
                continue
            try:
                chunk = os.read(rfd, 65536)
            except BlockingIOError:
                continue
            buf += chunk
            while b"\0" in buf:
                raw, buf = buf.split(b"\0", 1)
                try:
                    msg = json.loads(raw)
                except ValueError:
                    continue
                if not answered["any"]:
                    answered["any"] = True
                    log("메뉴 바탕: DevTools 파이프 응답 받음")
                # Web Audio events of the sound page, at most 60 lines a session
                # 소리 페이지의 Web Audio 이벤트, 세션당 최대 60줄
                meth = msg.get("method", "")
                if meth.startswith("WebAudio.") and audio_lines["n"] < 60:
                    audio_lines["n"] += 1
                    p = msg.get("params") or {}
                    c = p.get("context") or {}
                    log("소리 확인: %s 상태 %s, 콜백 버퍼 %s프레임, 출력 채널 최대 %s" % (
                        meth.split(".", 1)[1], c.get("contextState", p.get("contextId", "?")),
                        c.get("callbackBufferSize", "-"), c.get("maxOutputChannelCount", "-")))
                mid = msg.get("id")
                res = msg.get("result") or {}
                if "targetInfos" in res:
                    live = set()
                    for t in res["targetInfos"]:
                        title = t.get("title", "")
                        if t.get("type") != "page" or not title.startswith(MENU_PAGES + SOUND_PAGES):
                            continue
                        tid = t.get("targetId")
                        live.add(tid)
                        if tid in sessions and title.startswith(SOUND_PAGES):
                            continue
                        if tid in sessions:
                            i = send({"method": "Runtime.evaluate", "sessionId": sessions[tid],
                                      "params": {"expression": MENU_JS, "returnByValue": True}})
                            pending[i] = ("eval", tid, title)
                        elif not any(k == "attach" and t2 == tid for k, t2, _ in pending.values()):
                            i = send({"method": "Target.attachToTarget",
                                      "params": {"targetId": tid, "flatten": True}})
                            pending[i] = ("attach", tid, title)
                    # Targets that are gone (web helper restart) / 사라진 대상(웹 도우미 재시작)
                    for tid in list(sessions):
                        if tid not in live:
                            sessions.pop(tid, None)
                            state.pop(tid, None)
                elif mid in pending:
                    kind, tid, title = pending.pop(mid)
                    if kind == "attach" and "sessionId" in res and title.startswith(SOUND_PAGES):
                        # Sound page: Web Audio events only, no style rule / 소리 페이지: Web Audio 이벤트만, 스타일 규칙 없음
                        sessions[tid] = res["sessionId"]
                        send({"method": "WebAudio.enable", "sessionId": sessions[tid]})
                        log("소리 확인: '%s'의 Web Audio 기록 시작" % title[:30])
                    elif kind == "attach" and "sessionId" in res:
                        sessions[tid] = res["sessionId"]
                        i = send({"method": "Runtime.evaluate", "sessionId": sessions[tid],
                                  "params": {"expression": MENU_JS, "returnByValue": True}})
                        pending[i] = ("eval", tid, title)
                    elif kind == "eval":
                        val = ((res.get("result") or {}).get("value")) or str(msg.get("error", ""))[:80]
                        # One line per page and outcome change / 페이지와 결과가 바뀔 때만 한 줄
                        if state.get(tid) != val:
                            state[tid] = val
                            log("메뉴 바탕: 페이지 '%s' %s" % (title[:30], val))
                    elif "error" in msg:
                        log("메뉴 바탕: %s 실패 %s" % (kind, str(msg["error"])[:80]))
        # Stale bookkeeping of answers that never came / 오지 않은 답의 기록 정리
        if len(pending) > 50:
            pending.clear()
        if not answered["any"] and time.time() - started > 120 and started:
            log("메뉴 바탕: 2분 동안 DevTools 파이프 응답 없음 (이 Steam 버전이 파이프를 지원하지 않을 수 있음)")
            started = 0


# ---------------------------------------------------------------- Steam start loop
# Steam 시작 반복

def read_cmd():
    with open(CMD_FILE, "rb") as f:
        args = [a.decode() for a in f.read().split(b"\0") if a]
    env = {}
    with open(ENV_FILE, "rb") as f:
        for item in f.read().split(b"\0"):
            if b"=" in item:
                k, v = item.split(b"=", 1)
                env[k.decode(errors="replace")] = v.decode(errors="replace")
    with open(CWD_FILE) as f:
        cwd = f.read().strip() or None
    return args, env, cwd


# ---------------------------------------------------------------- NetworkManager names Steam uses
# Steam이 쓰는 NetworkManager 이름

STEAM = os.path.join(os.environ.get("HOME", "/home/steam"), ".local/share/Steam")
SCAN_OUT = os.path.join(os.environ.get("HOME", "/home/steam"), "ezs-logs", "nm-scan.txt")
SCAN_KEY = os.path.join(os.environ.get("HOME", "/home/steam"), ".local/share/ezsteam", "nm-scan.key")
NM_TAG = b"org.freedesktop.NetworkManager"
# Other signs of a NetworkManager client: the libnm library and its calls, the object path form
# NetworkManager 클라이언트의 다른 흔적: libnm 라이브러리와 그 호출, 객체 경로 형태
NM_HINTS = (b"libnm.so", b"nm_client_", b"nm_device_", b"nm_access_point_", b"/org/freedesktop/NetworkManager",
            b"NMClient", b"steamos-manager", b"com.steampowered.SteamOSManager")


def scan_files():
    """Client files that may talk to NetworkManager or show its data: the native client parts and
    the Steam UI scripts / NetworkManager와 대화하거나 그 값을 보여 줄 수 있는 클라이언트 파일: 네이티브
    클라이언트 부분과 Steam 화면 스크립트"""
    import glob
    out = []
    for pat in ("linuxarm64/*.so", "steamrtarm64/*.so", "steamrtarm64/steam", "steamui/*.js"):
        out += sorted(f for f in glob.glob(os.path.join(STEAM, pat)) if os.path.isfile(f))
    return out


def scan_one(path, names, apis):
    """Strings next to every NetworkManager interface name in one file (binary: printable runs
    within 4 KB either side), plus the Steam UI's own network calls in scripts. Returns the count
    of interface name places
    한 파일에서 NetworkManager 인터페이스 이름이 있는 곳마다 그 주변 문자열(바이너리: 앞뒤 4KB 안의 출력 가능한
    문자열)과 스크립트의 Steam 화면 자체 네트워크 호출을 모음. 인터페이스 이름이 있는 곳 수를 돌려줌"""
    import mmap
    import re
    word = re.compile(rb"[\x20-\x7e]{3,}")
    ident = re.compile(rb"^[A-Za-z][A-Za-z0-9_.]{2,79}$")
    hits = 0
    with open(path, "rb") as f:
        size = os.fstat(f.fileno()).st_size
        if size == 0:
            return 0
        m = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        try:
            # Count of each hint in this file / 이 파일의 흔적마다 수
            for h in NM_HINTS:
                c = 0
                q = m.find(h)
                while q >= 0 and c < 1000:
                    c += 1
                    q = m.find(h, q + 1)
                if c:
                    k = "흔적 " + h.decode() + " @ " + os.path.basename(path)
                    names[k] = names.get(k, 0) + c
            # libnm functions the file names (Steam's network page reads NetworkManager through
            # libnm in steamclient.so, scan 2026-11-02): which device, connection and access point
            # values it uses decides what the stand-in must give
            # 파일이 이름으로 가진 libnm 함수(Steam 네트워크 화면은 steamclient.so의 libnm으로 NetworkManager를 읽음,
            # 스캔 2026-11-02): 어떤 장치·연결·접속점 값을 쓰는지가 대역이 줄 값을 정함
            if m.find(b"nm_client_") >= 0:
                for fn in set(re.findall(rb"\bnm_[a-z0-9_]{3,60}\b", m)):
                    k = "libnm " + fn.decode() + " @ " + os.path.basename(path)
                    names[k] = names.get(k, 0) + 1
            if path.endswith(".js"):
                for api in re.findall(rb"(?:Network|Wifi|WiFi|Wireless)[A-Za-z]*\.[A-Za-z]+\(?", m):
                    apis[api.decode()] = apis.get(api.decode(), 0) + 1
            pos = m.find(NM_TAG)
            while pos >= 0 and hits < 400:
                hits += 1
                lo, hi = max(0, pos - 4096), min(size, pos + 4096)
                for w in word.findall(m[lo:hi]):
                    for part in w.split(b"\x00"):
                        part = part.strip()
                        if ident.match(part):
                            k = part.decode()
                            names[k] = names.get(k, 0) + 1
                pos = m.find(NM_TAG, hi)
        finally:
            m.close()
    return hits


def nm_scan():
    """Once per client build: which NetworkManager properties and methods the Steam client names,
    written to ezs-logs/nm-scan.txt (the report carries it). Facts for the stand-in instead of
    guessing what the network page reads. Local only, nothing is sent anywhere
    클라이언트 빌드마다 한 번: Steam 클라이언트가 이름으로 가진 NetworkManager 속성·메서드를 ezs-logs/nm-scan.txt에
    씀(제보에 들어감). 네트워크 화면이 무엇을 읽는지 추측하지 않고 대역에 맞출 사실. 기기 안에서만, 어디로도 보내지 않음"""
    time.sleep(30)
    try:
        files = scan_files()
        key = "v3\n" + "\n".join("%s %d %d" % (f, os.path.getsize(f), int(os.path.getmtime(f))) for f in files)
        try:
            with open(SCAN_KEY) as k:
                if k.read() == key and os.path.isfile(SCAN_OUT):
                    log("NM 스캔: 같은 클라이언트, 지난 결과 그대로 (ezs-logs/nm-scan.txt)")
                    return
        except OSError:
            pass
        t0 = time.time()
        names, apis, per = {}, {}, []
        for f in files:
            try:
                n = scan_one(f, names, apis)
            except (OSError, ValueError):
                continue
            if n:
                per.append("%s: 인터페이스 이름 %d곳" % (os.path.relpath(f, STEAM), n))
        with open(SCAN_OUT, "w") as out:
            out.write("# NetworkManager names near its interface strings in the Steam client (%s)\n"
                      % time.strftime("%Y-%m-%d %H:%M:%S"))
            out.write("# Steam 클라이언트에서 NetworkManager 인터페이스 문자열 주변의 이름\n\n")
            for line in per:
                out.write(line + "\n")
            # Is libnm in the rootfs at all / rootfs에 libnm이 있는지
            import glob
            libnm = glob.glob("/usr/lib/aarch64-linux-gnu/libnm.so*")
            out.write("libnm: %s\n" % (", ".join(libnm) if libnm else "없음"))
            out.write("\n## names / 이름 (count)\n")
            for k in sorted(names):
                out.write("%s %d\n" % (k, names[k]))
            out.write("\n## Steam UI network calls / Steam 화면 네트워크 호출 (count)\n")
            for k in sorted(apis):
                out.write("%s %d\n" % (k, apis[k]))
        os.makedirs(os.path.dirname(SCAN_KEY), exist_ok=True)
        with open(SCAN_KEY, "w") as k:
            k.write(key)
        log("NM 스캔: 파일 %d개 중 %d개에 인터페이스 이름, 이름 %d개, 화면 호출 %d개, %.0f초 → ezs-logs/nm-scan.txt"
            % (len(files), len(per), len(names), len(apis), time.time() - t0))
    except Exception as e:
        log("NM 스캔 실패: %s" % e)


def nm_check():
    """What libnm itself sees on this bus, with the calls the Steam client makes (ezs-nmcheck.py,
    one short process); its lines and any libnm warnings go to the log
    이 버스에서 libnm 자신이 보는 것을 Steam 클라이언트와 같은 호출로 읽음(ezs-nmcheck.py, 짧은 프로세스 하나).
    그 줄과 libnm 경고를 로그에 남김"""
    try:
        r = subprocess.run([sys.executable, "/opt/ezsteam/ezs-nmcheck.py"], capture_output=True, timeout=30)
        for line in r.stdout.decode(errors="replace").splitlines():
            say(line)
        for line in r.stderr.decode(errors="replace").splitlines()[:20]:
            say("ezs-nmcheck: (stderr) " + line[:200])
    except (OSError, subprocess.SubprocessError) as e:
        log("NM 확인 실패: %s" % e)


def nm_scan_and_check():
    nm_scan()
    nm_check()


def steam_loop(loop):
    log_path = os.path.join(os.environ.get("HOME", "/home/steam"), "ezs-logs", "steam.log")
    n = 0
    try:
        while True:
            n += 1
            for p in (CMD_FILE, ENV_FILE, CWD_FILE):
                try:
                    os.unlink(p)
                except OSError:
                    pass
            # Every check of steam-run.sh, ending before the client start / steam-run.sh의 모든 검사, 클라이언트 시작 전에 끝남
            env = dict(os.environ, EZS_AGENT_STEP=str(n))
            rc = subprocess.call(["sh", RUN], env=env)
            if not os.path.exists(CMD_FILE):
                log("준비 단계가 시작 명령을 남기지 않음 (rc %d), 끝냄" % rc)
                break
            args, cenv, cwd = read_cmd()
            with open(log_path, "ab") as out:
                p = subprocess.Popen(args, env=cenv, cwd=cwd, stdout=out, stderr=subprocess.STDOUT)
                rc = p.wait()
            say("INFO steam : 종료 rc=%d (%s)" % (rc, time.strftime("%H:%M:%S")))
            if os.path.exists(BPMARK) and n < 6:
                continue
            if rc == 42 and n < 6:
                continue
            break
        try:
            with open(log_path, "rb") as f:
                for line in f.read().decode(errors="replace").splitlines()[-20:]:
                    say("LOG   " + line)
        except OSError:
            pass
        say("DONE steam")
    finally:
        GLib.idle_add(loop.quit)


def main():
    cur.update(read_state())
    try:
        server = start_bus()
    except GLib.Error as e:
        fallback("버스 시작 실패: %s" % e.message)
        return
    log("시스템 버스·NetworkManager 대역 시작 (%s, 연결 %s %s)"
        % (BUS_SOCK, "있음" if cur["up"] else "없음", cur["kind"]))

    def minute():
        if asked["n"]:
            log("1분 요청 %d건" % asked["n"])
            asked["n"] = 0
        return True

    def poll():
        s = read_state()
        if s != cur:
            cur.update(s)
            log("연결 %s %s" % ("있음" if cur["up"] else "없음", cur["kind"]))
            changed()
        return True

    GLib.timeout_add_seconds(60, minute)
    GLib.timeout_add_seconds(2, poll)
    loop = GLib.MainLoop()
    if os.environ.get("EZS_AGENT_BUS_ONLY") == "1":
        # Bus test without Steam (development) / Steam 없이 버스만 시험(개발용)
        loop.run()
        return
    # Steam menu background through the DevTools pipe (before the first start, so the web helper
    # wrapper finds the FIFOs) / DevTools 파이프로 Steam 메뉴 바탕(첫 시작 전에: 웹 도우미 감쌈이 FIFO를 찾도록)
    if menubg_on():
        try:
            make_fifos()
            threading.Thread(target=devtools_loop, daemon=True).start()
            log("메뉴 바탕: 준비 (DevTools 파이프 %s, %s)" % (DT_IN, DT_OUT))
        except OSError as e:
            log("메뉴 바탕: 준비 실패 %s" % e)
    else:
        log("메뉴 바탕: 끔 (no-menubg)")
    threading.Thread(target=steam_loop, args=(loop,), daemon=True).start()
    # Low-priority one-off scan after the start settled / 시작이 안정된 뒤 한 번 도는 낮은 우선순위 검사
    threading.Thread(target=nm_scan_and_check, daemon=True).start()
    loop.run()
    server.stop()


if __name__ == "__main__":
    main()
