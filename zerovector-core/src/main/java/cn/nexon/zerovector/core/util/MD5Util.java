package cn.nexon.zerovector.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5工具类
 * 用于计算文件或字符串的MD5值
 */
public class MD5Util {
    
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    
    /**
     * 计算文件的MD5值
     * @param filePath 文件路径
     * @return MD5值的十六进制字符串
     * @throws IOException 如果读取文件失败
     */
    public static String calculateMD5(Path filePath) throws IOException {
        try (InputStream is = Files.newInputStream(filePath)) {
            return calculateMD5(is);
        }
    }
    
    /**
     * 计算输入流的MD5值
     * @param inputStream 输入流
     * @return MD5值的十六进制字符串
     * @throws IOException 如果读取流失败
     */
    public static String calculateMD5(InputStream inputStream) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
            
            byte[] hashBytes = md.digest();
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5算法不可用", e);
        }
    }
    
    /**
     * 计算字符串的MD5值
     * @param content 字符串内容
     * @return MD5值的十六进制字符串
     */
    public static String calculateMD5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] contentBytes = content.getBytes("UTF-8");
            byte[] hashBytes = md.digest(contentBytes);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            throw new RuntimeException("计算MD5失败", e);
        }
    }
    
    /**
     * 将字节数组转换为十六进制字符串
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }
}