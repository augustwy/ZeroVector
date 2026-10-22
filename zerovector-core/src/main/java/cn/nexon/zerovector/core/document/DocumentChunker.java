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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节切片器。
 *
 * <p>优先按 Markdown 标题（{@code #} ~ {@code ######}）切片；没有标题时按段落/长度
 * 切分。每个 Section 都带原文字符区间，供源文件按字节区间懒加载。
 *
 * <p>Section 首尾相接覆盖整篇正文，保证按顺序累计字符长度即可得到正确的源文件
 * 字节偏移（UTF-8/GBK 等无状态编码）。
 */
public final class DocumentChunker {

    private static final int MIN_SECTION_CHARS = 200;
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})[ \\t]+(.+?)[ \\t]*$");
    private static final Pattern CN_CHAPTER = Pattern.compile(
            "^第[一二三四五六七八九十百千0-9]+[章节篇部](?:[ \\t].*)?$");
    private static final Pattern CN_SECTION = Pattern.compile(
            "^[一二三四五六七八九十]+、(?:[ \\t]*.*)?$");

    private DocumentChunker() {
    }

    public record Section(String title, String text, int start, int end, int headingLevel) {
    }

    public record HeadingMatch(int level, String title) {
    }

    /** 识别单行标题（不处理代码围栏，由调用方负责）。 */
    public static HeadingMatch matchHeading(String line) {
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
            // UTF-8/UTF-16 BOM 不应破坏第一行标题识别
            line = line.substring(1);
        }
        Matcher matcher = HEADING.matcher(line);
        if (matcher.matches()) {
            String title = matcher.group(2).trim();
            return title.isEmpty() ? null : new HeadingMatch(matcher.group(1).length(), title);
        }
        String trimmed = line.trim();
        if (CN_CHAPTER.matcher(trimmed).matches()) {
            return new HeadingMatch(1, trimmed);
        }
        if (CN_SECTION.matcher(trimmed).matches()) {
            return new HeadingMatch(2, trimmed);
        }
        return null;
    }

    /** 整篇文档作为一个 Section（默认小文档路径）。 */
    public static Section whole(String title, String content) {
        return new Section(title == null || title.isBlank() ? "文档" : title,
                content, 0, content == null ? 0 : content.length(), 0);
    }

    private record Heading(int start, int end, int level, String title) {
    }

    /**
     * 切分正文。
     *
     * @param content       已解码的完整正文
     * @param maxSectionChars 单个章节的最大字符数
     * @return 首尾相接的章节列表
     */
    public static List<Section> split(String content, int maxSectionChars) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        int max = Math.max(MIN_SECTION_CHARS, maxSectionChars);

        List<Heading> headings = findHeadings(content);
        if (headings.isEmpty()) {
            return splitLong("正文", content, 0, content.length(), 0, max);
        }

        List<Section> sections = new ArrayList<>();
        Heading first = headings.get(0);
        if (first.start() > 0) {
            String preamble = content.substring(0, first.start());
            if (!preamble.isBlank()) {
                sections.addAll(splitLong("前言", preamble, 0, first.start(), 0, max));
            }
        }

        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            int end = i + 1 < headings.size() ? headings.get(i + 1).start() : content.length();
            String text = content.substring(heading.start(), end);
            if (!text.isBlank()) {
                sections.addAll(splitLong(heading.title(), text, heading.start(), end,
                        heading.level(), max));
            }
        }
        return sections;
    }

    private static List<Heading> findHeadings(String content) {
        List<Heading> headings = new ArrayList<>();
        int offset = 0;
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLength = 0;

        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.endsWith("\r")
                    ? rawLine.substring(0, rawLine.length() - 1)
                    : rawLine;
            String trimmed = line.stripLeading();

            // 跳过 fenced code block 中的 "#"，避免把 shell 注释当成章节
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                char marker = trimmed.charAt(0);
                int count = 0;
                while (count < trimmed.length() && trimmed.charAt(count) == marker) {
                    count++;
                }
                if (!inFence) {
                    inFence = true;
                    fenceChar = marker;
                    fenceLength = count;
                } else if (marker == fenceChar && count >= fenceLength) {
                    inFence = false;
                }
            } else if (!inFence) {
                HeadingMatch heading = matchHeading(line);
                if (heading != null) {
                    headings.add(new Heading(offset, offset + line.length(),
                            heading.level(), heading.title()));
                }
            }
            offset += rawLine.length() + 1;
        }
        return headings;
    }

    /** 按上限切分一段连续文本，尽量在换行/空格处断开。 */
    private static List<Section> splitLong(String title, String text, int absStart, int absEnd,
                                           int headingLevel, int maxChars) {
        List<Section> parts = new ArrayList<>();
        int position = 0;
        while (position < text.length()) {
            int end = Math.min(position + maxChars, text.length());
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end - 1);
                if (newline > position + maxChars / 2) {
                    end = newline + 1;
                } else {
                    int space = text.lastIndexOf(' ', end - 1);
                    if (space > position + maxChars / 2) {
                        end = space + 1;
                    }
                }
            }
            if (end <= position) {
                end = Math.min(position + maxChars, text.length());
            }
            // 不要切断 UTF-16 代理对，否则源文件字节区间会错位
            if (end < text.length() && end > position && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            String part = text.substring(position, end);
            if (!part.isBlank()) {
                parts.add(new Section(title, part, absStart + position, absStart + end, headingLevel));
            }
            position = end;
        }

        if (parts.size() <= 1) {
            return parts;
        }
        List<Section> named = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            Section part = parts.get(i);
            named.add(new Section(title + " (" + (i + 1) + "/" + parts.size() + ")",
                    part.text(), part.start(), part.end(), headingLevel));
        }
        return named;
    }
}
