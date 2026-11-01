#!/usr/bin/env python3
# Test only (web helper variant EZS_WH_NOBLUR=1): turns off the blur behind Steam's header and
# footer bars by adding one style rule to every Steam UI page, through Chromium's DevTools protocol
# on 127.0.0.1:8080 (--remote-debugging-port, added by ezs-webhelper-env.sh for this variant only).
# Why: the Steam menu panel showed broken pixels exactly where the 40-row header and footer bars
# (translucent, blurred) overlap it, with GPU compositing only (white with GL, colour blocks with
# ANGLE Vulkan, correct with software compositing; driver, ANGLE and Chromium switches did not change
# it, 2026-10-31). The same rule ("backdrop-filter: none") was the workaround in another Chromium
# app with broken blurred areas. This checks whether the blur is the cause.
# Caution: while this variant runs, any program on the device can reach the DevTools port and so
# the logged-in Steam UI. Never on by default; ezs-whtest.bat removes the variant afterwards.
# Standard library only, one process, polls every 3 s, ends when the web helper ends.
#
# 시험 전용(웹 도우미 변형 EZS_WH_NOBLUR=1): Steam 머리줄·안내줄 뒤의 흐림을 끔. 모든 Steam 화면 페이지에 스타일 규칙
# 하나를 넣음. Chromium DevTools 프로토콜(127.0.0.1:8080, --remote-debugging-port. 이 변형일 때만
# ezs-webhelper-env.sh가 더함)을 씀.
# 이유: Steam 메뉴 패널에서 40줄 머리줄·안내줄(반투명 흐림)과 겹치는 자리만 픽셀이 깨졌음. GPU 합성에서만 그랬음(GL은
# 흰색, ANGLE Vulkan은 색 블록, 소프트웨어 합성은 정상. 드라이버·ANGLE·Chromium 스위치로는 바뀌지 않았음,
# 2026-10-31). 같은 규칙("backdrop-filter: none")이 흐림 영역이 깨지던 다른 Chromium 앱의 우회책이었음.
# 흐림이 원인인지 확인함.
# 주의: 이 변형이 도는 동안 기기의 어떤 프로그램이든 DevTools 포트, 곧 로그인된 Steam 화면에 닿을 수 있음. 기본값으로는
# 절대 켜지 않음. ezs-whtest.bat가 끝나면 변형을 지움.
# 표준 라이브러리만, 프로세스 하나, 3초마다 확인, 웹 도우미가 끝나면 끝남.
import base64
import json
import os
import socket
import sys
import time
import urllib.request

PORT = 8080
# The rule: EZS_WH_CSS when given, else no blur behind anything. With the blur off the menu
# background was sharp (rule applied) and the bands still broke (whtest 2026-11-01 13:55); the
# broken bands are the panel column above/below the panel, which no layer seems to cover once the
# page slides right, so the next rule gives the page an opaque black background
# 규칙: EZS_WH_CSS가 있으면 그것, 없으면 어떤 것 뒤에도 흐림 없음. 흐림을 끄자 메뉴 배경이 선명해졌는데(규칙 적용됨)
# 띠는 그대로 깨졌음(whtest 2026-11-01 13:55). 깨진 띠는 페이지가 오른쪽으로 밀린 뒤 패널 위·아래의 패널 열로, 어떤
# 층도 덮지 않는 자리로 보임. 그래서 다음 규칙은 페이지에 불투명 검정 바탕을 줌
CSS = os.environ.get("EZS_WH_CSS") or "*{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}"
# Adds the rule once per document; answers what it did / 문서마다 규칙을 한 번만 넣음. 한 일을 돌려줌
JS = ("(function(){var d=document;if(!d||!d.head)return 'no-head';"
      "if(d.getElementById('ezs-noblur'))return 'kept';"
      "var s=d.createElement('style');s.id='ezs-noblur';s.textContent=%s;"
      "d.head.appendChild(s);return 'added';})()" % json.dumps(CSS))


def log(msg):
    # "ezs-webhelper" prefix: the app shows Steam output lines with that word (STEAM_KEY), the
    # injector's own prefix was filtered out (2026-11-01 13:58)
    # "ezs-webhelper" 머리말: 앱은 그 낱말이 있는 Steam 출력 줄을 보여 줌(STEAM_KEY). 주입기 자체 머리말은 걸러졌음
    # (2026-11-01 13:58)
    print("ezs-webhelper: css " + msg, flush=True)


def targets():
    """Page targets of the DevTools port / DevTools 포트의 페이지 대상"""
    with urllib.request.urlopen("http://127.0.0.1:%d/json" % PORT, timeout=2) as r:
        return [t for t in json.load(r) if t.get("type") == "page" and t.get("webSocketDebuggerUrl")]


def ws_eval(url, expr):
    """One Runtime.evaluate over a minimal WebSocket client (RFC 6455, text frames, client masked)
    최소 WebSocket 클라이언트(RFC 6455, 텍스트 프레임, 클라이언트 마스크)로 Runtime.evaluate 한 번"""
    rest = url.split("://", 1)[1]
    hostport, path = rest.split("/", 1)
    host, port = hostport.split(":")
    s = socket.create_connection((host, int(port)), timeout=3)
    try:
        key = base64.b64encode(os.urandom(16)).decode()
        s.sendall(("GET /%s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (path, hostport, key)).encode())
        head = b""
        while b"\r\n\r\n" not in head:
            c = s.recv(1024)
            if not c:
                raise OSError("handshake closed")
            head += c
        if b" 101 " not in head.split(b"\r\n", 1)[0]:
            raise OSError(head.split(b"\r\n", 1)[0].decode("latin-1"))
        buf = head.split(b"\r\n\r\n", 1)[1]
        msg = json.dumps({"id": 1, "method": "Runtime.evaluate",
                          "params": {"expression": expr, "returnByValue": True}}).encode()
        mask = os.urandom(4)
        n = len(msg)
        hdr = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + n.to_bytes(2, "big"))
        s.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(msg)))
        # Read frames until the answer with id 1 / id 1 답이 올 때까지 프레임을 읽음
        deadline = time.time() + 3
        while time.time() < deadline:
            while len(buf) < 2:
                buf += s.recv(65536)
            ln = buf[1] & 0x7F
            off = 2
            if ln == 126:
                while len(buf) < 4:
                    buf += s.recv(65536)
                ln = int.from_bytes(buf[2:4], "big")
                off = 4
            elif ln == 127:
                while len(buf) < 10:
                    buf += s.recv(65536)
                ln = int.from_bytes(buf[2:10], "big")
                off = 10
            while len(buf) < off + ln:
                buf += s.recv(65536)
            payload, buf = buf[off:off + ln], buf[off + ln:]
            try:
                j = json.loads(payload)
            except ValueError:
                continue
            if j.get("id") == 1:
                return j.get("result", {}).get("result", {}).get("value", j.get("error"))
        return "timeout"
    finally:
        s.close()


def main():
    parent = os.getppid()
    seen = {}
    log("시작, 포트 %d, 3초마다 확인, 규칙 %s" % (PORT, CSS[:80]))
    reached = False
    while os.getppid() == parent:
        try:
            ts = targets()
            if not reached:
                reached = True
                log("DevTools 포트 연결됨")
            for t in ts:
                tid = t.get("id")
                try:
                    res = ws_eval(t["webSocketDebuggerUrl"], JS)
                except OSError as e:
                    res = "error %s" % e
                # One line per page and outcome change / 페이지와 결과가 바뀔 때만 한 줄
                if seen.get(tid) != res:
                    seen[tid] = res
                    log("페이지 '%s': %s" % (t.get("title", "")[:40], res))
        except OSError:
            pass
        time.sleep(3)
    log("웹 도우미 끝남, 종료")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
