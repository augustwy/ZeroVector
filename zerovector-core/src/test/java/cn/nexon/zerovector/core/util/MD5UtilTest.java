/*
 * Copyright 2025 nexonlab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.nexon.zerovector.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MD5UtilTest {

    @Test
    void calculateMD5_knownString_returnsCorrectHash() {
        // MD5("hello") = 5d41402abc4b2a76b9719d911017c592
        String hash = MD5Util.calculateMD5("hello");
        assertEquals("5d41402abc4b2a76b9719d911017c592", hash);
    }

    @Test
    void calculateMD5_emptyString_returnsCorrectHash() {
        // MD5("") = d41d8cd98f00b204e9800998ecf8427e
        String hash = MD5Util.calculateMD5("");
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hash);
    }

    @Test
    void calculateMD5_sameString_returnsSameHash() {
        String hash1 = MD5Util.calculateMD5("zerovector knowledge base");
        String hash2 = MD5Util.calculateMD5("zerovector knowledge base");
        assertEquals(hash1, hash2);
    }

    @Test
    void calculateMD5_differentStrings_returnsDifferentHashes() {
        String hash1 = MD5Util.calculateMD5("abc");
        String hash2 = MD5Util.calculateMD5("xyz");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void calculateMD5_nullString_throwsException() {
        assertThrows(RuntimeException.class, () -> MD5Util.calculateMD5((String) null));
    }

    @Test
    void calculateMD5_fromFile_returnsHash(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello");

        String hash = MD5Util.calculateMD5(file);
        assertEquals("5d41402abc4b2a76b9719d911017c592", hash);
    }

    @Test
    void calculateMD5_fromEmptyFile_returnsEmptyHash(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.createFile(file);

        String hash = MD5Util.calculateMD5(file);
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hash);
    }

    @Test
    void calculateMD5_fromNonExistentFile_throwsException() {
        assertThrows(IOException.class,
            () -> MD5Util.calculateMD5(Path.of("/nonexistent/file.txt")));
    }

    @Test
    void calculateMD5_unicodeContent_returnsConsistentHash() {
        String chinese = "零向量知识库";
        String hash1 = MD5Util.calculateMD5(chinese);
        String hash2 = MD5Util.calculateMD5(chinese);
        assertEquals(hash1, hash2);
        assertNotNull(hash1);
        assertEquals(32, hash1.length());
    }

    @Test
    void calculateMD5_hashLength_is32HexChars() {
        String hash = MD5Util.calculateMD5("any content");
        assertEquals(32, hash.length());
        assertTrue(hash.matches("[0-9a-f]{32}"));
    }
}
