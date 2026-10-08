package com.techcoder.sqlperf.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TextsTest {

    @Test
    void truncatesToTheOracleByteLimit() {
        String ascii = "a".repeat(250);
        assertThat(Texts.truncate(ascii, 200)).hasSize(200).endsWith("...");
        assertThat(Texts.truncate("short", 200)).isEqualTo("short");

        // 150 characters but 300 bytes in UTF-8: too long for VARCHAR2(200 BYTE)
        String accented = "é".repeat(150);
        String cut = Texts.truncate(accented, 200);
        assertThat(cut.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(200);
        assertThat(cut).endsWith("...");

        // never splits a surrogate pair
        String emoji = "😀".repeat(60);
        assertThat(Texts.truncate(emoji, 100).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(100);
        assertThat(Texts.utf8Length("aé😀")).isEqualTo(1 + 2 + 4);
    }
}
