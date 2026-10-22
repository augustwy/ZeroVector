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

package cn.nexon.zerovector.springboot.example.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * 把上传的二进制文档转换成核心可处理的纯文本。
 *
 * <p>PDF/DOCX 先转换为 Markdown；其余格式按文本处理。
 */
public final class DocumentTextExtractor {

    private DocumentTextExtractor() {
    }

    public static Path toText(Path source, Path workDir) throws IOException {
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return writeMarkdown(PdfToMarkdownConverter.convertToMarkdown(source), workDir);
        }
        if (name.endsWith(".docx")) {
            return writeMarkdown(WordToMarkdownConverter.convertToMarkdown(source), workDir);
        }
        return source;
    }

    private static Path writeMarkdown(String markdown, Path workDir) throws IOException {
        Files.createDirectories(workDir);
        Path target = workDir.resolve(UUID.randomUUID() + ".md");
        Files.writeString(target, markdown, StandardCharsets.UTF_8);
        return target;
    }
}
