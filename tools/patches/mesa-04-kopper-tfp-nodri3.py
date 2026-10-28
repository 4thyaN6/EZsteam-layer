#!/usr/bin/env python3
# Zink (kopper) GLX texture-from-pixmap on an X server without DRI3 (Xvfb). Steam's Big Picture
# composer binds X pixmaps as textures (glXBindTexImageEXT -> dri_set_tex_buffer2); kopper then asks
# the server for the pixmap's buffer with xcb_dri3_buffer_from_pixmap without checking that DRI3
# exists, and xcb closes the whole connection ("xcb request for missing extension DRI3 (minor 3)"
# then "xcb error 2 (extension not supported)", the helper exits; 2026-10-29).
# Patch: use the DRI3 buffer only when the server has DRI3; otherwise the texture is a normal one
# filled by the existing software path (drisw_update_tex_buffer, X GetImage), also when the screen
# can import dma-bufs (that check skipped the fill before). With DRI3 (Xwayland + GPU device)
# nothing changes. Own patch (MIT, same as Mesa). Applied by tools/build-mesax.sh only.
# Exit 0 = applied, 1 = anchors not found (build goes on without it).
# DRI3가 없는 X 서버(Xvfb)에서 Zink(kopper)의 GLX texture-from-pixmap. Steam 빅픽처 합성기는 X 픽스맵을 텍스처로 묶음
# (glXBindTexImageEXT -> dri_set_tex_buffer2). 그러면 kopper가 DRI3가 있는지 확인하지 않고 xcb_dri3_buffer_from_pixmap으로 픽스맵
# 버퍼를 서버에 요청해, xcb가 연결 전체를 닫음("xcb request for missing extension DRI3 (minor 3)" 다음 "xcb error 2 (extension
# not supported)", 도우미 종료, 2026-10-29).
# 패치: 서버에 DRI3가 있을 때만 DRI3 버퍼를 씀. 없으면 일반 텍스처를 만들고 기존 소프트웨어 경로(drisw_update_tex_buffer, X
# GetImage)로 채움. 화면이 dma-buf를 가져올 수 있어도 채움(전에는 그 조건 때문에 채우지 않았음). DRI3가 있으면(Xwayland + GPU
# 장치) 바뀌는 것 없음. 자체 패치(Mesa와 같은 MIT). tools/build-mesax.sh에서만 적용함.
# 종료 0 = 적용, 1 = 기준 위치를 못 찾음(빌드는 패치 없이 계속)
import re
import sys

p = sys.argv[1] + "/src/gallium/frontends/dri/kopper.c"
s = open(p).read()


def fail(msg):
    print("mesa-04-kopper-tfp-nodri3: " + msg + " (not applied)", file=sys.stderr)
    sys.exit(1)


# 1. DRI3 check next to the pixmap buffer helper (inside its XCB block)
#    픽스맵 버퍼 도우미 옆에 DRI3 확인 함수(그 XCB 구역 안)
end = "#endif //VK_USE_PLATFORM_XCB_KHR\n"
if "kopper_get_pixmap_buffer(struct dri_drawable *drawable," not in s or s.count(end) < 1:
    fail("kopper_get_pixmap_buffer block not found")
i = s.index("kopper_get_pixmap_buffer(struct dri_drawable *drawable,")
j = s.index(end, i)
helper = """
/* EZsteam: does the X server of this drawable have DRI3? */
static bool
kopper_has_dri3(struct dri_drawable *drawable)
{
   VkXcbSurfaceCreateInfoKHR *xcb = (VkXcbSurfaceCreateInfoKHR *)&drawable->info.bos;
   const xcb_query_extension_reply_t *ext = xcb_get_extension_data(xcb->connection, &xcb_dri3_id);
   return ext && ext->present;
}
"""
s = s[:j] + helper + s[j:]

# 2. Pixmap front buffer from DRI3 only when the server has it / 서버에 DRI3가 있을 때만 DRI3로 픽스맵 앞 버퍼를 받음
r = re.compile(r"else if \(is_pixmap && statts\[i\] == ST_ATTACHMENT_FRONT_LEFT && !screen->is_sw\) \{")
if len(r.findall(s)) != 1:
    fail("pixmap front buffer condition not found once")
s = r.sub("else if (is_pixmap && statts[i] == ST_ATTACHMENT_FRONT_LEFT && !screen->is_sw && kopper_has_dri3(drawable)) {", s)

# 3. Software fill whenever the pixmap did not come through DRI3 / 픽스맵이 DRI3로 오지 않았으면 항상 소프트웨어로 채움
r = re.compile(r"if \(screen->has_dmabuf \|\| drawable->is_window \|\|")
if len(r.findall(s)) != 1:
    fail("kopper_update_tex_buffer condition not found once")
s = r.sub("if ((screen->has_dmabuf && drawable->image) || drawable->is_window ||", s)

open(p, "w").write(s)
print("mesa-04-kopper-tfp-nodri3: applied")
