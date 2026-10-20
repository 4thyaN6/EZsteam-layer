#!/usr/bin/env python3
# Steam client language from the phone's language (user request, 2026-10-20: the client always
# started in English). The app passes its locale as EZS_LANG (BCP 47 tag, e.g. "ko-KR"); it is
# mapped to Steam's language name and written as "language" under HKCU/Software/Valve/Steam in
# ~/.steam/registry.vdf, the file where the Linux client keeps that setting. Games that follow the
# Steam language get it from there too. Applied before every start, so a change in the phone's
# settings follows; EZS_LANG unset or unknown leaves the file alone.
# 휴대폰 언어로 정하는 Steam 클라이언트 언어(사용자 요청, 2026-10-20: 클라이언트가 늘 영어로 시작했음).
# 앱이 언어를 EZS_LANG(BCP 47 태그, 예: "ko-KR")로 넘김. Steam 언어 이름으로 바꿔 ~/.steam/registry.vdf의
# HKCU/Software/Valve/Steam 아래 "language"로 씀(리눅스 클라이언트가 이 설정을 두는 파일). Steam 언어를
# 따르는 게임도 여기서 가져감. 시작마다 적용하므로 휴대폰 설정을 바꾸면 따라감. EZS_LANG이 없거나 모르는
# 언어면 파일을 건드리지 않음
#
#   python3 steam-language.py
import importlib.util
import os
import sys

# BCP 47 language (and script/region where Steam splits) -> Steam language name
# BCP 47 언어(Steam이 나누는 경우 문자·지역까지) -> Steam 언어 이름
NAMES = {
    "ko": "koreana", "en": "english", "ja": "japanese", "de": "german", "fr": "french",
    "it": "italian", "ru": "russian", "pl": "polish", "tr": "turkish", "th": "thai",
    "vi": "vietnamese", "uk": "ukrainian", "nl": "dutch", "sv": "swedish", "da": "danish",
    "fi": "finnish", "nb": "norwegian", "no": "norwegian", "cs": "czech", "hu": "hungarian",
    "ro": "romanian", "el": "greek", "bg": "bulgarian", "id": "indonesian", "ar": "arabic",
    "pt": "portuguese", "es": "spanish", "zh": "schinese",
}


def steam_name(tag):
    """Steam language name for a BCP 47 tag, or None / BCP 47 태그의 Steam 언어 이름, 없으면 None"""
    parts = tag.replace("_", "-").split("-")
    lang = parts[0].lower()
    rest = parts[1:]
    if lang == "zh":
        # Traditional script, or Taiwan / Hong Kong / Macau / 번체, 또는 대만·홍콩·마카오
        if "Hant" in rest or any(p.upper() in ("TW", "HK", "MO") for p in rest):
            return "tchinese"
        return "schinese"
    if lang == "pt" and any(p.upper() == "BR" for p in rest):
        return "brazilian"
    # Spanish outside Spain (es-419, es-MX, ...) / 스페인 밖 스페인어(es-419, es-MX 등)
    regions = [p for p in rest if p.isdigit() or (len(p) == 2 and p.isalpha())]
    if lang == "es" and regions and regions[0].upper() != "ES":
        return "latam"
    return NAMES.get(lang)


def main():
    tag = os.environ.get("EZS_LANG", "").strip()
    name = steam_name(tag) if tag else None
    if not name:
        print("INFO lang : 휴대폰 언어 %s → Steam 언어 없음, 그대로 둠" % (tag or "(없음)"))
        return 0
    here = os.path.dirname(os.path.abspath(__file__))
    spec = importlib.util.spec_from_file_location("ezs_vdf", os.path.join(here, "steam-library.py"))
    v = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(v)
    path = os.path.join(os.path.expanduser("~"), ".steam", "registry.vdf")
    data = {}
    if os.path.isfile(path):
        with open(path, encoding="utf-8", errors="replace") as f:
            data = v.parse(f.read())
    node = data
    for key in ("Registry", "HKCU", "Software", "Valve", "Steam"):
        # Keep the client's own spelling of each key (case may differ) / 클라이언트가 쓴 키 철자를 그대로 씀
        found = next((k for k in node if k.lower() == key.lower() and isinstance(node[k], dict)), None)
        if found is None:
            node[key] = {}
            found = key
        node = node[found]
    old = next((node[k] for k in node if k.lower() == "language"), None)
    if old == name:
        print("OK   lang : Steam 언어 %s (휴대폰 %s)" % (name, tag))
        return 0
    for k in [k for k in node if k.lower() == "language"]:
        del node[k]
    node["language"] = name
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".ezs-tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(v.dump(data))
    os.replace(tmp, path)
    print("OK   lang : Steam 언어 %s → %s (휴대폰 %s)" % (old or "(없음)", name, tag))
    return 0


if __name__ == "__main__":
    sys.exit(main())
