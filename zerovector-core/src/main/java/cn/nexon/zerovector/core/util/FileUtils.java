package cn.nexon.zerovector.core.util;

import cn.nexon.zerovector.core.exception.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class FileUtils {
    
    private FileUtils() {}
    
    public static String readFileToString(Path filePath) throws IOException {
        return Files.readString(filePath);
    }
    
    public static String readFileToString(String filePath) throws IOException {
        return Files.readString(Paths.get(filePath));
    }
    
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
