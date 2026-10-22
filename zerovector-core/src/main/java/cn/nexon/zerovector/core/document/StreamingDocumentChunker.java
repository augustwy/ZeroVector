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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式章节切片器：不把整篇文件读入内存。
 *
 * <p>按行读取，边解码边累计源文件字节偏移；只保留章节元数据（标题、offset、length），
 * 章节正文在需要理解/回答时再按区间读取。
 */
public final class StreamingDocumentChunker {

    private static final int MIN_SECTION_CHARS = 200;

    private StreamingDocumentChunker() {
    }

    public record SectionRef(String title, long byteOffset, int byteLength, int headingLevel) {
    }

    public record Result(List<SectionRef> sections, long totalChars, long totalBytes) {
    }

    @FunctionalInterface
    public interface CopySink {
        void write(String text) throws IOException;
    }

    /**
     * @param file            源文件
     * @param charset         源文件编码
     * @param maxSectionChars 单章节最大字符数
     * @param copySink        非空时同步写 UTF-8 副本（COPY 模式）
     */
    public static Result parse(Path file, Charset charset, int maxSectionChars,
                               CopySink copySink) throws IOException {
        int maxChars = Math.max(MIN_SECTION_CHARS, maxSectionChars);
        List<SectionRef> sections = new ArrayList<>();

        ParserState state = new ParserState(sections, copySink, maxChars, charset);
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), decoder))) {
            char[] buffer = new char[8192];
            StringBuilder line = new StringBuilder(256);
            boolean truncated = false;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    char c = buffer[i];
                    line.append(c);
                    if (c == '\n') {
                        state.processLine(line.toString(), !truncated);
                        line.setLength(0);
                        truncated = false;
                    } else if (line.length() >= maxChars
                            && !Character.isHighSurrogate(line.charAt(line.length() - 1))) {
                        // 极端无换行文件：按上限切断，避免一行把整篇读入内存；不切断代理对
                        state.processFragment(line.toString());
                        line.setLength(0);
                        truncated = true;
                    }
                }
            }
            if (line.length() > 0) {
                if (truncated) {
                    state.processFragment(line.toString());
                } else {
                    state.processLine(line.toString(), true);
                }
            }
        }
        state.finish();
        return new Result(List.copyOf(sections), state.totalChars, state.totalBytes);
    }

    private static final class ParserState {
        private final List<SectionRef> sections;
        private final CopySink copySink;
        private final int maxChars;
        private final Charset sourceCharset;

        private long totalChars;
        private long totalBytes;
        private long sectionStartByte;
        private long sectionChars;
        private String sectionTitle = "正文";
        private String sectionBaseTitle = "正文";
        private int sectionLevel;
        private boolean sectionNonBlank;
        private boolean seenHeading;
        private int part = 1;
        private boolean inFence;
        private char fenceChar;
        private int fenceLength;

        private ParserState(List<SectionRef> sections, CopySink copySink, int maxChars, Charset sourceCharset) {
            this.sections = sections;
            this.copySink = copySink;
            this.maxChars = maxChars;
            this.sourceCharset = sourceCharset;
        }

        private void processLine(String line, boolean complete) throws IOException {
            if (complete) {
                boolean fenceLine = isFenceLine(line);
                boolean wasInFence = inFence;
                if (fenceLine) {
                    toggleFence(line);
                }

                DocumentChunker.HeadingMatch heading = (!wasInFence && !fenceLine)
                        ? DocumentChunker.matchHeading(stripLineEnding(line))
                        : null;

                if (heading != null) {
                    if (!seenHeading && sectionNonBlank && sectionChars > 0) {
                        sectionTitle = "前言";
                    }
                    finishSection();
                    seenHeading = true;
                    sectionTitle = heading.title();
                    sectionBaseTitle = heading.title();
                    sectionLevel = heading.level();
                    sectionStartByte = totalBytes;
                    sectionChars = 0;
                    sectionNonBlank = false;
                    part = 1;
                } else {
                    splitBefore(line);
                }
            } else {
                splitBefore(line);
            }
            add(line);
        }

        private void processFragment(String fragment) throws IOException {
            splitBefore(fragment);
            add(fragment);
        }

        private void splitBefore(String line) {
            if (sectionChars > 0 && sectionChars + line.length() > maxChars) {
                finishSection();
                sectionStartByte = totalBytes;
                sectionChars = 0;
                sectionNonBlank = false;
                part++;
                sectionTitle = sectionBaseTitle + " (" + part + ")";
            }
        }

        private void add(String line) throws IOException {
            if (copySink != null) {
                copySink.write(line);
            }
            sectionChars += line.length();
            sectionNonBlank |= !line.isBlank();
            totalChars += line.length();
            totalBytes += encodedLength(line);
        }

        private void finishSection() {
            if (sectionNonBlank && sectionChars > 0) {
                long length = totalBytes - sectionStartByte;
                sections.add(new SectionRef(sectionTitle, sectionStartByte,
                        (int) Math.min(Integer.MAX_VALUE, length), sectionLevel));
            }
        }

        private void finish() {
            finishSection();
        }

        private long encodedLength(String line) {
            Charset charset = copySink != null ? StandardCharsets.UTF_8 : sourceCharset;
            return line.getBytes(charset).length;
        }

        private boolean isFenceLine(String line) {
            String trimmed = line.stripLeading();
            return trimmed.startsWith("```") || trimmed.startsWith("~~~");
        }

        private void toggleFence(String line) {
            String trimmed = line.stripLeading();
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
        }

        private static String stripLineEnding(String line) {
            int end = line.length();
            if (end > 0 && line.charAt(end - 1) == '\n') {
                end--;
            }
            if (end > 0 && line.charAt(end - 1) == '\r') {
                end--;
            }
            return line.substring(0, end);
        }
    }
}
