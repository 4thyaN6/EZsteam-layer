#!/usr/bin/env python3
# Valve's ARM64 Proton without a trip to the Library > Tools list: when it is not installed, ask the
# client to download it from Valve's servers by leaving an "update required" install record
# (appmanifest) for it. Nothing of Valve is shipped with the app; the client downloads it itself.
# 라이브러리 > 도구 목록을 거치지 않는 Valve ARM64 Proton: 설치되어 있지 않으면 "업데이트 필요" 상태의 설치
# 기록(appmanifest)을 남겨 클라이언트가 Valve 서버에서 직접 받게 함. 앱에는 Valve 파일이 없음. 클라이언트가
# 스스로 받음.
#
#   python3 steam-proton.py <Steam dir>
#
# - Installed (a steamapps/common/*/toolmanifest.vdf that requires 4185400 next to a "proton" file,
#   the same rule as compat/ezproton): its appid and folder are written to the log (to confirm the
#   appid below on real devices), nothing else happens
#   설치됨(4185400을 요구하는 steamapps/common/*/toolmanifest.vdf 옆에 "proton" 파일, compat/ezproton과
#   같은 규칙): appid와 폴더를 로그에 씀(아래 appid를 실제 기기에서 확인하기 위함). 그 밖에는 아무것도 안 함
# - Not installed and no record yet: steamapps/appmanifest_<APPID>.acf with StateFlags "6"
#   (installed + update required), so the client queues the download at start
#   설치 안 됨, 기록도 없음: StateFlags "6"(설치됨 + 업데이트 필요)인 steamapps/appmanifest_<APPID>.acf를
#   만들어 클라이언트가 시작할 때 다운로드를 대기열에 넣게 함
#
# APPID 4628740 = "Proton 11.0 (ARM64)" per the community list of Proton appids
# (steamcommunity.com/sharedfiles/filedetails/?id=2013973376); not yet confirmed on a device, the
# "installed" log line above confirms it. The install record format follows the client's own
# appmanifest files; StateFlags 6 = 4 (installed) | 2 (update required) (LaunchBox help article).
# APPID 4628740 = 커뮤니티 Proton appid 목록의 "Proton 11.0 (ARM64)"(steamcommunity.com/sharedfiles/
# filedetails/?id=2013973376). 기기에서는 아직 확인 안 됨. 위 "설치됨" 로그 줄로 확인함. 설치 기록 형식은
# 클라이언트 자체 appmanifest를 따름. StateFlags 6 = 4(설치됨) | 2(업데이트 필요)(LaunchBox 도움말)
import glob
import os
import re
import sys

APPID = "4628740"
NAME = "Proton 11.0 (ARM64)"


def installed(steam):
    """(folder, appid or None) of an installed ARM64 Proton, else None / 설치된 ARM64 Proton"""
    for m in sorted(glob.glob(os.path.join(steam, "steamapps", "common", "*", "toolmanifest.vdf"))):
        folder = os.path.dirname(m)
        try:
            with open(m, encoding="utf-8", errors="replace") as f:
                if "4185400" not in f.read():
                    continue
        except OSError:
            continue
        if not os.path.isfile(os.path.join(folder, "proton")):
            continue
        # The install record whose installdir is this folder / installdir가 이 폴더인 설치 기록
        appid = None
        for acf in glob.glob(os.path.join(steam, "steamapps", "appmanifest_*.acf")):
            try:
                with open(acf, encoding="utf-8", errors="replace") as f:
                    text = f.read()
            except OSError:
                continue
            m2 = re.search(r'"installdir"\s+"([^"]*)"', text)
            if m2 and m2.group(1) == os.path.basename(folder):
                appid = re.search(r"appmanifest_(\d+)\.acf$", acf).group(1)
                break
        return folder, appid
    return None


def main():
    steam = sys.argv[1]
    found = installed(steam)
    if found:
        print("OK   proton : %s (appid %s)" % (os.path.basename(found[0]), found[1] or "기록 없음"))
        return
    acf = os.path.join(steam, "steamapps", "appmanifest_%s.acf" % APPID)
    if os.path.isfile(acf):
        print("INFO proton : %s 받는 중 또는 대기 (Steam 다운로드 화면 확인)" % NAME)
        return
    if not os.path.isdir(os.path.join(steam, "steamapps")):
        print("INFO proton : steamapps 없음 (첫 실행 전), 다음 시작부터 %s 받기 요청" % NAME)
        return
    text = ('"AppState"\n{\n'
            '\t"appid"\t\t"%s"\n'
            '\t"Universe"\t\t"1"\n'
            '\t"name"\t\t"%s"\n'
            '\t"StateFlags"\t\t"6"\n'
            '\t"installdir"\t\t"%s"\n'
            '\t"LastUpdated"\t\t"0"\n'
            '\t"SizeOnDisk"\t\t"0"\n'
            '\t"buildid"\t\t"0"\n'
            '\t"AutoUpdateBehavior"\t\t"0"\n'
            '\t"AllowOtherDownloadsWhileRunning"\t\t"0"\n'
            '\t"ScheduledAutoUpdate"\t\t"0"\n'
            '}\n') % (APPID, NAME, NAME)
    tmp = acf + ".ezs-tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, acf)
    print("OK   proton : %s 없음 → Steam에 받기 요청 (appid %s, 다운로드 화면에서 진행 확인)" % (NAME, APPID))


if __name__ == "__main__":
    main()
