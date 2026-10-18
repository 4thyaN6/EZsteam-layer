package app.ezsteam.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides private values in log text before it is saved for sharing: Steam account names and
 * persona names (read from the client's loginusers.vdf), SteamID64 and [U:1:n] account ids, sign-in
 * tokens (JWT and their id numbers) and
 * e-mail addresses. Same rules as EZlator's core/LogScrubber (same author) so reports from both
 * read the same; game names, exe names and paths are kept (needed to read a report).
 * Pure Java apart from file reading.
 * 공유용으로 저장하기 전에 로그 글의 개인 값을 가림: Steam 계정 이름과 표시 이름(클라이언트
 * loginusers.vdf에서 읽음), SteamID64와 [U:1:n] 계정 id, 로그인 토큰(JWT와 그 id 숫자), 이메일 주소. EZlator core/LogScrubber와 같은
 * 규칙(같은 작성자)이라 두 쪽 제보를 같은 방식으로 읽을 수 있음. 게임 이름·exe 이름·경로는 남김
 * (제보를 읽는 데 필요함). 파일 읽기 외에는 순수 Java임
 */
public final class LogScrubber {

    public static final String HIDDEN = "***";

    // JSON Web Token (Steam refresh tokens are JWTs: three base64url parts)
    // JSON 웹 토큰(Steam refresh token은 JWT임: base64url 세 부분)
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    // SteamID64 of individual accounts: 7656119 + 10 digits / 개인 계정 SteamID64: 7656119 + 숫자 10자리
    private static final Pattern STEAM_ID64 = Pattern.compile("(?<![0-9])7656119[0-9]{10}(?![0-9])");
    // Steam account id in the client's "[U:1:<number>]" form (connection_log.txt), and the id
    // number of a sign-in token ("Using JWT <number>")
    // 클라이언트의 "[U:1:<숫자>]" 형식 Steam 계정 id(connection_log.txt)와 로그인 토큰 id 숫자
    // ("Using JWT <숫자>")
    private static final Pattern STEAM_ID3 = Pattern.compile("\\[U:1:[1-9][0-9]*\\]");
    private static final Pattern JWT_ID = Pattern.compile("(Using JWT )[0-9]+");
    // E-mail addresses / 이메일 주소
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    // Names in the client's loginusers.vdf / 클라이언트 loginusers.vdf의 이름
    private static final Pattern VDF_NAME = Pattern.compile("\"(?:AccountName|PersonaName)\"\\s+\"([^\"]+)\"");

    private final List<Pattern> secrets = new ArrayList<>();

    /** Scrubber for the given secret strings (shorter than 3 characters ignored)
     *  주어진 비밀 문자열용(3글자보다 짧으면 무시) */
    public LogScrubber(Collection<String> names) {
        for (String s : names) {
            if (s == null || s.trim().length() < 3) continue;
            // Whole words only, so a name like "game" does not hide "Game.exe"
            // 단어 단위로만 바꿈. 그래서 "game" 같은 이름이 "Game.exe"를 가리지 않음
            secrets.add(Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(s.trim()) + "(?![A-Za-z0-9_.])",
                    Pattern.CASE_INSENSITIVE));
        }
    }

    /**
     * Scrubber with the account and persona names the Steam client stored in the rootfs
     * (home/steam/.local/share/Steam/config/loginusers.vdf); no names when the file is missing.
     * Steam 클라이언트가 rootfs에 저장한 계정·표시 이름(home/steam/.local/share/Steam/config/
     * loginusers.vdf)을 쓰는 가리개. 파일이 없으면 이름 없음
     */
    public static LogScrubber forRootfs(File rootfs) {
        List<String> names = new ArrayList<>();
        File f = new File(rootfs, "home/steam/.local/share/Steam/config/loginusers.vdf");
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = VDF_NAME.matcher(text);
            while (m.find()) names.add(m.group(1));
        } catch (IOException ignored) {
            // not signed in yet / 아직 로그인 전
        }
        return new LogScrubber(names);
    }

    /** One line or a whole text with every secret replaced / 모든 비밀 값을 바꾼 한 줄 또는 글 */
    public String scrub(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Pattern p : secrets) {
            Matcher m = p.matcher(out);
            if (m.find()) out = m.replaceAll(Matcher.quoteReplacement(HIDDEN));
        }
        out = JWT.matcher(out).replaceAll(HIDDEN);
        out = STEAM_ID64.matcher(out).replaceAll("<steamid>");
        out = STEAM_ID3.matcher(out).replaceAll("[U:1:<id>]");
        out = JWT_ID.matcher(out).replaceAll("$1<id>");
        out = EMAIL.matcher(out).replaceAll("<email>");
        return out;
    }
}
