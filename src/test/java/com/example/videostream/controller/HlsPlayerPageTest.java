package com.example.videostream.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The player page names the hls.js WebJar by version. This catches a pom bump that leaves the page pointing at
 * a script that is no longer on the classpath, which only a browser would otherwise notice.
 */
class HlsPlayerPageTest {

    @Test
    void theScriptsTheMarkupReferencesAreOnTheClasspath() throws IOException {
        String html;
        try (InputStream in = getClass().getResourceAsStream("/static/player.html")) {
            assertThat(in).as("static/player.html").isNotNull();
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher scripts = Pattern.compile("<script src=\"([^\"]+)\"").matcher(html);
        int found = 0;
        while (scripts.find()) {
            found++;
            String src = scripts.group(1);
            String resource = src.startsWith("/webjars/") ? "/META-INF/resources" + src : "/static" + src;
            assertThat(getClass().getResource(resource)).as(src).isNotNull();
        }
        assertThat(found).isEqualTo(2);
    }
}
