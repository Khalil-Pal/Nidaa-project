package com.humanitarian.platform.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-5: the Content-Security-Policy is {@code script-src 'self'}, so a page that
 * regains an inline script, an {@code on*} attribute or a {@code javascript:}
 * URL breaks silently in the browser. This test fails the build instead. It
 * also checks the generated markup inside the page scripts, because an
 * {@code onclick="..."} written by innerHTML is blocked just the same.
 */
class StaticPagesCspTest {

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script\\b(?![^>]*\\bsrc=)[^>]*>\\s*\\S", Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDLER_ATTR = Pattern.compile("\\son[a-z]+\\s*=\\s*[\"'`]", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_URL = Pattern.compile("href\\s*=\\s*[\"']\\s*javascript:", Pattern.CASE_INSENSITIVE);

    @Test
    void noPageCarriesInlineScriptOrHandlers() throws IOException {
        List<String> problems = new ArrayList<>();
        int pages = 0, scripts = 0;
        try (Stream<Path> files = Files.walk(STATIC)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".html")) {
                    pages++;
                    String html = Files.readString(file, StandardCharsets.UTF_8);
                    report(problems, file, "inline <script>", INLINE_SCRIPT.matcher(html));
                    report(problems, file, "inline handler attribute", HANDLER_ATTR.matcher(html));
                    report(problems, file, "javascript: URL", JS_URL.matcher(html));
                } else if (name.endsWith(".js")) {
                    scripts++;
                    String js = Files.readString(file, StandardCharsets.UTF_8);
                    // markup built in template strings: a handler attribute there is inline too
                    report(problems, file, "handler attribute in generated markup", HANDLER_ATTR.matcher(js));
                }
            }
        }
        assertTrue(pages >= 18 && scripts >= 17, "expected the whole frontend to be scanned, saw " + pages + " pages and " + scripts + " scripts");
        assertTrue(problems.isEmpty(), "strict CSP would block:\n  " + String.join("\n  ", problems));
    }

    private static void report(List<String> problems, Path file, String what, Matcher m) {
        while (m.find()) {
            problems.add(file.getFileName() + ": " + what + " at offset " + m.start() + ": " + m.group().trim());
        }
    }
}
