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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 文件读取与编码检测工具。
 */
public final class FileUtils {

    private static final Logger logger = LoggerFactory.getLogger(FileUtils.class);

    private static final byte[] BOM_UTF16BE = {(byte) 0xFE, (byte) 0xFF};
    private static final byte[] BOM_UTF16LE = {(byte) 0xFF, (byte) 0xFE};

    /** 非 BOM 编码探测优先级：UTF-8 → GBK → ISO-8859-1（兜底，永不出错）。 */
    private static final List<Charset> ENCODING_CANDIDATES = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GBK"),
            StandardCharsets.ISO_8859_1);

    private static final int CHARSET_SAMPLE_SIZE = 64 * 1024;

    private FileUtils() {
    }

    /**
     * 自动检测字节数组编码：先看 UTF-16 BOM，再按 UTF-8 → GBK → ISO-8859-1 严格解码。
     */
    public static Charset detectCharset(byte[] bytes) {
        return detectCharset(bytes, false);
    }

    /** 采样末尾可能截断多字节字符，此时只要求采样是合法编码前缀。 */
    private static Charset detectCharset(byte[] bytes, boolean allowIncompleteTail) {
        if (bytes.length >= 2) {
            if (bytes[0] == BOM_UTF16BE[0] && bytes[1] == BOM_UTF16BE[1]) {
                return StandardCharsets.UTF_16BE;
            }
            if (bytes[0] == BOM_UTF16LE[0] && bytes[1] == BOM_UTF16LE[1]) {
                return StandardCharsets.UTF_16LE;
            }
        }
        for (Charset charset : ENCODING_CANDIDATES) {
            try {
                if (allowIncompleteTail) {
                    decodePrefix(bytes, charset);
                } else {
                    decodeStrict(bytes, charset);
                }
                return charset;
            } catch (CharacterCodingException e) {
                logger.debug("字节序列不是合法的 {} 编码，尝试下一个", charset.name());
            }
        }
        return StandardCharsets.ISO_8859_1;
    }

    /** 从文件路径自动检测编码（读取前 64KB 采样）。 */
    public static Charset detectCharset(Path filePath) throws IOException {
        long fileSize = Files.size(filePath);
        int sampleSize = (int) Math.min(fileSize, CHARSET_SAMPLE_SIZE);
        byte[] sample;
        try (var in = Files.newInputStream(filePath)) {
            sample = in.readNBytes(sampleSize);
        }
        Charset detected = detectCharset(sample, true);
        logger.debug("文件 {} 检测到编码: {}", filePath, detected.name());
        return detected;
    }

    /** 读取文本文件，自动检测编码并严格解码。 */
    public static String readFileToString(Path filePath) throws IOException {
        return readTextFile(filePath).content();
    }

    /** 读取文本文件并返回检测到的编码，供源文件按字节区间读取时使用。 */
    public static TextFile readTextFile(Path filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        Charset charset = detectCharset(bytes);
        return new TextFile(decodeStrict(bytes, charset), charset);
    }

    public record TextFile(String content, Charset charset) {
    }

    public static String readFileToString(String filePath) throws IOException {
        return readFileToString(Paths.get(filePath));
    }

    private static String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer result = decoder.decode(ByteBuffer.wrap(bytes));
        return result.toString();
    }

    /** 校验采样是否是合法前缀；允许末尾留下不完整的多字节序列。 */
    private static void decodePrefix(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer output = CharBuffer.allocate(Math.max(1, bytes.length));
        CoderResult result = decoder.decode(input, output, false);
        if (result.isError()) {
            throw new CharacterCodingException();
        }
    }

    public static String getFileName(Path filePath) {
        return filePath.getFileName().toString();
    }

    public static String getFileName(String filePath) {
        return Paths.get(filePath).getFileName().toString();
    }
}
