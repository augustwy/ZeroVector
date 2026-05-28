package cn.nexon.zerovector.core.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileUtils {

    private static final Logger logger = LoggerFactory.getLogger(FileUtils.class);

    /** BOM 字节序列 → 对应编码 */
    private static final byte[] BOM_UTF16BE = {(byte) 0xFE, (byte) 0xFF};
    private static final byte[] BOM_UTF16LE = {(byte) 0xFF, (byte) 0xFE};

    /** 非 BOM 编码探测优先级：UTF-8 → GBK → ISO-8859-1（兜底，永不出错） */
    private static final List<Charset> ENCODING_CANDIDATES = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GBK"),
            StandardCharsets.ISO_8859_1);

    /** 大文件编码探测采样大小：64KB */
    private static final int CHARSET_SAMPLE_SIZE = 64 * 1024;

    private FileUtils() {}

    // ==================== 编码自动检测 ====================

    /**
     * 自动检测字节数组的编码：
     * 先检查 UTF-16 BOM（FE FF / FF FE），
     * 再按 UTF-8 → GBK → ISO-8859-1 顺序尝试严格解码。
     *
     * @param bytes 待检测的字节数组（至少 2 字节）
     * @return 检测到的 Charset（ISO-8859-1 永不出错，作为兜底）
     */
    public static Charset detectCharset(byte[] bytes) {
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
                decodeStrict(bytes, charset);
                return charset;
            } catch (CharacterCodingException e) {
                logger.debug("字节序列不是合法的 {} 编码，尝试下一个", charset.name());
            }
        }
        return StandardCharsets.ISO_8859_1;
    }

    /**
     * 从文件路径自动检测编码（读取前 64KB 采样）。
     */
    public static Charset detectCharset(Path filePath) throws IOException {
        long fileSize = Files.size(filePath);
        int sampleSize = (int) Math.min(fileSize, CHARSET_SAMPLE_SIZE);
        byte[] sample;
        try (var in = Files.newInputStream(filePath)) {
            sample = in.readNBytes(sampleSize);
        }
        Charset detected = detectCharset(sample);
        logger.debug("文件 {} 检测到编码: {}", filePath, detected.name());
        return detected;
    }

    // ==================== 文件读取（自动编码） ====================

    public static String readFileToString(Path filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        Charset charset = detectCharset(bytes);
        if (charset != StandardCharsets.UTF_8) {
            logger.debug("文件 {} 使用 {} 编码读取", filePath, charset.name());
        }
        return decodeStrict(bytes, charset);
    }

    public static String readFileToString(String filePath) throws IOException {
        return readFileToString(Paths.get(filePath));
    }

    /**
     * 按指定编码从文件路径读取全部内容。
     */
    public static String readFileToString(Path filePath, Charset charset) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        return decodeStrict(bytes, charset);
    }

    // ==================== UTF-8 转换 ====================

    /**
     * 将文件转换为 UTF-8 编码存储。先自动检测原编码读取，再以 UTF-8 写回。
     * 如果文件已经是合法的 UTF-8，则跳过转换。
     */
    public static void convertToUtf8(Path filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        Charset detected = detectCharset(bytes);
        if (detected == StandardCharsets.UTF_8) {
            return;
        }
        logger.info("文件 {} 从 {} 转换为 UTF-8", filePath, detected.name());
        String content = decodeStrict(bytes, detected);
        Files.writeString(filePath, content, StandardCharsets.UTF_8);
    }

    // ==================== 内部工具 ====================

    /**
     * 严格解码：遇到非法字节或不可映射字符即报错（不静默替换）。
     */
    static String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer result = decoder.decode(ByteBuffer.wrap(bytes));
        return result.toString();
    }

    // ==================== 文件操作 ====================

    public static long getFileSize(Path filePath) throws IOException {
        return Files.size(filePath);
    }

    public static long getFileSize(String filePath) throws IOException {
        return Files.size(Paths.get(filePath));
    }

    public static boolean fileExists(Path filePath) {
        return Files.exists(filePath);
    }

    public static boolean fileExists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }

    public static boolean isFileEmpty(Path filePath) throws IOException {
        return Files.size(filePath) == 0;
    }

    public static boolean isFileEmpty(String filePath) throws IOException {
        return Files.size(Paths.get(filePath)) == 0;
    }

    public static Path createDirectories(Path dirPath) throws IOException {
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }
        return dirPath;
    }

    public static Path createDirectories(String dirPath) throws IOException {
        return createDirectories(Paths.get(dirPath));
    }

    public static Path copyFile(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target.toAbsolutePath();
    }

    public static Path copyFileWithTimestamp(Path source, Path targetDir) throws IOException {
        String fileName = source.getFileName().toString();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String copiedFileName = timestamp + "_" + fileName;
        Path target = Paths.get(targetDir.toString(), copiedFileName);
        return copyFile(source, target);
    }

    public static String getFileName(Path filePath) {
        return filePath.getFileName().toString();
    }

    public static String getFileName(String filePath) {
        return Paths.get(filePath).getFileName().toString();
    }

    public static Path toAbsolutePath(Path path) {
        return path.toAbsolutePath();
    }

    public static Path toAbsolutePath(String path) {
        return Paths.get(path).toAbsolutePath();
    }
}
