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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void sampleEndingInsideUtf8CharacterStillDetectsUtf8() throws Exception {
        // 让 64KB 采样恰好停在“中”的第一个字节，旧实现会把采样误判为 ISO-8859-1
        String content = "a".repeat(64 * 1024 - 1) + "中";
        Path file = tempDir.resolve("utf8-boundary.md");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertEquals(StandardCharsets.UTF_8, FileUtils.detectCharset(file));
    }
}
