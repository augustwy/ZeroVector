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

package cn.nexon.zerovector.core.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamingDocumentChunkerTest {

    @TempDir
    Path tempDir;

    @Test
    void sectionsKeepSourceByteRanges() throws Exception {
        String content = "前言\n# Alpha\nalpha body\n## Beta\nbeta body\n";
        Path file = writeUtf8("headings.md", content);

        StreamingDocumentChunker.Result result = StreamingDocumentChunker.parse(
                file, StandardCharsets.UTF_8, 1000, null);

        assertEquals(3, result.sections().size());
        assertEquals("前言", result.sections().get(0).title());
        assertEquals("Alpha", result.sections().get(1).title());
        assertEquals("Beta", result.sections().get(2).title());
        assertSectionsMatch(content, StandardCharsets.UTF_8, result.sections());
    }

    @Test
    void noHeadingsUsesWholeDocumentTitle() throws Exception {
        String content = "alpha line\nbeta line\n";
        Path file = writeUtf8("plain.md", content);

        StreamingDocumentChunker.Result result = StreamingDocumentChunker.parse(
                file, StandardCharsets.UTF_8, 1000, null);

        assertEquals(1, result.sections().size());
        assertEquals("正文", result.sections().get(0).title());
        assertSectionsMatch(content, StandardCharsets.UTF_8, result.sections());
    }

    @Test
    void copySinkReceivesExactContentAndUtf8Offsets() throws Exception {
        String content = "前言\r\n# Alpha\r\n中文内容\r\n";
        Path file = writeUtf8("crlf.md", content);
        StringBuilder copy = new StringBuilder();

        StreamingDocumentChunker.Result result = StreamingDocumentChunker.parse(
                file, StandardCharsets.UTF_8, 1000, copy::append);

        assertEquals(content, copy.toString());
        assertSectionsMatch(content, StandardCharsets.UTF_8, result.sections());
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, result.totalBytes());
        assertEquals(content.length(), result.totalChars());
    }

    @Test
    void singleHugeLineIsSplitWithoutWholeFileBuffering() throws Exception {
        String content = "a".repeat(5000);
        Path file = writeUtf8("one-line.md", content);
        StringBuilder copy = new StringBuilder();

        StreamingDocumentChunker.Result result = StreamingDocumentChunker.parse(
                file, StandardCharsets.UTF_8, 200, copy::append);

        assertEquals(content, copy.toString());
        assertTrue(result.sections().size() > 1);
        for (StreamingDocumentChunker.SectionRef section : result.sections()) {
            assertTrue(section.byteLength() <= 200);
        }
        assertEquals(content.length(), result.totalChars());
        assertEquals(content.length(), result.totalBytes());
        assertSectionsMatchBytes(content.getBytes(StandardCharsets.UTF_8), result.sections());
    }

    @Test
    void utf16BomOffsetsReadBackOriginalBytes() throws Exception {
        String content = "# Alpha\n中文内容\n# Beta\n更多内容\n";
        byte[] body = content.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[body.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(body, 0, bytes, 2, body.length);
        Path file = tempDir.resolve("utf16.md");
        Files.write(file, bytes);

        StreamingDocumentChunker.Result result = StreamingDocumentChunker.parse(
                file, StandardCharsets.UTF_16LE, 1000, null);

        assertEquals(2, result.sections().size());
        assertEquals("Alpha", result.sections().get(0).title());
        assertEquals("Beta", result.sections().get(1).title());
        assertEquals(bytes.length, result.totalBytes());
        assertSectionsMatchBytes(bytes, result.sections());
    }

    @Test
    void gbkOffsetsReadBackOriginalBytes() throws Exception {
        String content = "第一章 概述\n中文内容\n第二章 安装\n更多内容\n";
        Charset gbk = Charset.forName("GBK");
        Path file = tempDir.resolve("gbk.md");
        Files.write(file, content.getBytes(gbk));

        StreamingDocumentChunker.Result result = StreamingDocumentChunker.parse(
                file, gbk, 1000, null);

        assertEquals(2, result.sections().size());
        assertSectionsMatch(content, gbk, result.sections());
        assertEquals(content.getBytes(gbk).length, result.totalBytes());
    }

    @Test
    void headingsInsideFencesAreNotSections() throws Exception {
        String content = "# Real\nintro\n```bash\n# not a heading\n```\n# Next\nbody\n";
        Path file = writeUtf8("fence.md", content);

        StreamingDocumentChunker.Result result = StreamingDocumentChunker.parse(
                file, StandardCharsets.UTF_8, 1000, null);

        assertEquals(2, result.sections().size());
        assertEquals("Real", result.sections().get(0).title());
        assertEquals("Next", result.sections().get(1).title());
        assertSectionsMatch(content, StandardCharsets.UTF_8, result.sections());
    }

    @Test
    void longSectionsAreSplitByLineWithoutExceedingLimit() throws Exception {
        String content = "alpha line\n".repeat(100);
        Path file = writeUtf8("long.md", content);

        StreamingDocumentChunker.Result result = StreamingDocumentChunker.parse(
                file, StandardCharsets.UTF_8, 200, null);

        assertTrue(result.sections().size() > 1);
        for (StreamingDocumentChunker.SectionRef section : result.sections()) {
            String text = new String(content.getBytes(StandardCharsets.UTF_8),
                    (int) section.byteOffset(), section.byteLength(),
                    StandardCharsets.UTF_8);
            assertTrue(text.length() <= 200, text.length() + " > 200");
        }
        assertSectionsMatch(content, StandardCharsets.UTF_8, result.sections());
    }

    private void assertSectionsMatch(String content, Charset charset,
                                     List<StreamingDocumentChunker.SectionRef> sections) {
        assertSectionsMatchBytes(content.getBytes(charset), sections);
    }

    private void assertSectionsMatchBytes(byte[] bytes,
                                          List<StreamingDocumentChunker.SectionRef> sections) {
        long expectedOffset = 0;
        for (StreamingDocumentChunker.SectionRef section : sections) {
            assertEquals(expectedOffset, section.byteOffset());
            assertTrue(section.byteOffset() + section.byteLength() <= bytes.length);
            expectedOffset += section.byteLength();
        }
    }

    private Path writeUtf8(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
