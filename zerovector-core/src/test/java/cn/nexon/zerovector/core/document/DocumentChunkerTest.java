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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentChunkerTest {

    @Test
    void markdownHeadingsBecomeSections() {
        String content = "# 第一章\n内容一\n\n## 1.1 小节\n内容二\n";
        List<DocumentChunker.Section> sections = DocumentChunker.split(content, 1000);

        assertEquals(2, sections.size());
        assertEquals("第一章", sections.get(0).title());
        assertTrue(sections.get(0).text().contains("内容一"));
        assertEquals("1.1 小节", sections.get(1).title());
        assertTrue(sections.get(1).text().contains("内容二"));
    }

    @Test
    void headingsInsideCodeFenceAreIgnored() {
        String content = "# 真章节\n说明\n\n```bash\n# 这是 shell 注释\n```\n";
        List<DocumentChunker.Section> sections = DocumentChunker.split(content, 1000);

        assertEquals(1, sections.size());
        assertEquals("真章节", sections.get(0).title());
        assertTrue(sections.get(0).text().contains("# 这是 shell 注释"));
    }

    @Test
    void chineseChapterHeadingsAreRecognized() {
        String content = "第一章 概述\n内容一\n\n一、安装\n内容二\n";
        List<DocumentChunker.Section> sections = DocumentChunker.split(content, 1000);

        assertEquals(2, sections.size());
        assertEquals("第一章 概述", sections.get(0).title());
        assertEquals("一、安装", sections.get(1).title());
    }

    @Test
    void sectionsCoverContentContiguously() {
        String content = "前言\n# 标题\n正文\n## 子标题\n更多正文\n";
        List<DocumentChunker.Section> sections = DocumentChunker.split(content, 1000);

        assertEquals(3, sections.size());
        assertEquals(0, sections.get(0).start());
        for (int i = 1; i < sections.size(); i++) {
            assertEquals(sections.get(i - 1).end(), sections.get(i).start());
        }
        assertEquals(content.length(), sections.get(sections.size() - 1).end());
    }

    @Test
    void longSectionIsSplitAtBoundaries() {
        String content = "alpha line\n".repeat(100);
        List<DocumentChunker.Section> sections = DocumentChunker.split(content, 200);

        assertTrue(sections.size() > 1);
        for (int i = 0; i < sections.size(); i++) {
            DocumentChunker.Section section = sections.get(i);
            assertTrue(section.text().length() <= 200);
            assertEquals("正文 (" + (i + 1) + "/" + sections.size() + ")", section.title());
        }
    }

    @Test
    void noHeadingsFallsBackToLengthSplit() {
        String content = "a".repeat(500);
        List<DocumentChunker.Section> sections = DocumentChunker.split(content, 200);

        assertEquals(3, sections.size());
        assertEquals(0, sections.get(0).start());
        assertEquals(500, sections.get(2).end());
    }

    @Test
    void blankContentReturnsEmptyList() {
        assertTrue(DocumentChunker.split("   \n", 1000).isEmpty());
    }
}
