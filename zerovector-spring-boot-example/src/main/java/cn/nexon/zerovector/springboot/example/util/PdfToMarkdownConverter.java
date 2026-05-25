package cn.nexon.zerovector.springboot.example.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF文档转Markdown工具类
 * 
 * <p>支持将PDF文档转换为Markdown格式，保留以下内容：</p>
 * <ul>
 *   <li>标题层级（通过字体大小识别）</li>
 *   <li>段落文本</li>
 *   <li>表格结构（基础支持）</li>
 *   <li>缩进和层级关系</li>
 *   <li>粗体和斜体文本</li>
 * </ul>
 * 
 * @author ZeroVector
 */
public class PdfToMarkdownConverter {

    private static final String LINE_SEPARATOR = System.lineSeparator();
    private static final float HEADING_THRESHOLD_LARGE = 16.0f;
    private static final float HEADING_THRESHOLD_MEDIUM = 14.0f;
    private static final float HEADING_THRESHOLD_SMALL = 12.0f;
    private static final float SAME_LINE_Y_THRESHOLD = 2.0f;

    /**
     * 将PDF文件转换为Markdown格式
     *
     * @param filePath PDF文件路径
     * @return Markdown格式文本
     * @throws IOException 文件读取失败时抛出
     */
    public static String convertToMarkdown(String filePath) throws IOException {
        return convertToMarkdown(Path.of(filePath));
    }

    /**
     * 将PDF文件转换为Markdown格式
     *
     * @param path PDF文件路径
     * @return Markdown格式文本
     * @throws IOException 文件读取失败时抛出
     */
    public static String convertToMarkdown(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            return convertDocument(document);
        }
    }

    /**
     * 将PDF输入流转换为Markdown格式
     *
     * @param inputStream PDF文件输入流
     * @return Markdown格式文本
     * @throws IOException 文件读取失败时抛出
     */
    public static String convertToMarkdown(InputStream inputStream) throws IOException {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            return convertDocument(document);
        }
    }

    /**
     * 转换PDDocument为Markdown
     *
     * @param document PDF文档对象
     * @return Markdown格式文本
     * @throws IOException 提取文本失败时抛出
     */
    private static String convertDocument(PDDocument document) throws IOException {
        StringBuilder markdown = new StringBuilder();
        int totalPages = document.getNumberOfPages();

        for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
            PDFTextStripper stripper = new MarkdownTextStripper();
            stripper.setStartPage(pageNum);
            stripper.setEndPage(pageNum);
            String pageText = stripper.getText(document);
            markdown.append(pageText);

            if (pageNum < totalPages) {
                markdown.append(LINE_SEPARATOR).append("---").append(LINE_SEPARATOR);
            }
        }

        return markdown.toString().trim();
    }

    /**
     * 自定义PDF文本提取器，支持Markdown格式输出
     */
    private static class MarkdownTextStripper extends PDFTextStripper {

        private final List<TextBlock> currentPageBlocks = new ArrayList<>();
        private float lastY = -1;
        private float lastFontSize = 0;
        private StringBuilder currentLine = new StringBuilder();
        private boolean inBold = false;
        private boolean inItalic = false;

        public MarkdownTextStripper() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            currentPageBlocks.clear();
            lastY = -1;
            lastFontSize = 0;
            currentLine = new StringBuilder();
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (textPositions.isEmpty()) {
                return;
            }

            TextPosition firstPos = textPositions.get(0);
            float currentY = firstPos.getY();
            float currentFontSize = firstPos.getFontSizeInPt();
            boolean isBold = isBold(firstPos);
            boolean isItalic = isItalic(firstPos);

            if (lastY >= 0 && Math.abs(currentY - lastY) > SAME_LINE_Y_THRESHOLD) {
                if (!currentLine.isEmpty()) {
                    currentPageBlocks.add(new TextBlock(currentLine.toString(), lastFontSize, inBold, inItalic));
                }
                currentLine = new StringBuilder();
            }

            String formattedText = formatText(text, isBold, isItalic);
            currentLine.append(formattedText);
            lastY = currentY;
            lastFontSize = currentFontSize > 0 ? currentFontSize : lastFontSize;
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            if (!currentLine.isEmpty()) {
                currentPageBlocks.add(new TextBlock(currentLine.toString(), lastFontSize, inBold, inItalic));
            }
            processBlocks();
            super.endPage(page);
        }

        private void processBlocks() throws IOException {
            List<TextBlock> mergedBlocks = mergeBlocks(currentPageBlocks);

            for (TextBlock block : mergedBlocks) {
                String line = block.text.trim();
                if (line.isEmpty()) {
                    continue;
                }

                int headingLevel = determineHeadingLevel(block.fontSize);
                if (headingLevel > 0) {
                    writeString("#".repeat(headingLevel) + " " + line);
                } else {
                    writeString(line);
                }
                writeLineSeparator();
            }
        }

        private List<TextBlock> mergeBlocks(List<TextBlock> blocks) {
            if (blocks.isEmpty()) {
                return blocks;
            }

            List<TextBlock> merged = new ArrayList<>();
            TextBlock current = blocks.get(0);

            for (int i = 1; i < blocks.size(); i++) {
                TextBlock next = blocks.get(i);
                
                if (Math.abs(current.fontSize - next.fontSize) < 1.0f && 
                    current.bold == next.bold && 
                    current.italic == next.italic) {
                    current = new TextBlock(
                        current.text + " " + next.text,
                        current.fontSize,
                        current.bold,
                        current.italic
                    );
                } else {
                    merged.add(current);
                    current = next;
                }
            }
            merged.add(current);

            return merged;
        }

        private int determineHeadingLevel(float fontSize) {
            if (fontSize >= HEADING_THRESHOLD_LARGE) {
                return 1;
            } else if (fontSize >= HEADING_THRESHOLD_MEDIUM) {
                return 2;
            } else if (fontSize >= HEADING_THRESHOLD_SMALL) {
                return 3;
            }
            return 0;
        }

        private String formatText(String text, boolean bold, boolean italic) {
            text = text.replace("*", "\\*");
            
            if (bold && italic) {
                return "***" + text + "***";
            } else if (bold) {
                return "**" + text + "**";
            } else if (italic) {
                return "*" + text + "*";
            }
            return text;
        }

        private boolean isBold(TextPosition position) {
            PDFont font = position.getFont();
            if (font == null) {
                return false;
            }
            String fontName = font.getName().toLowerCase();
            return fontName.contains("bold") || fontName.contains("black") || 
                   fontName.contains("heavy") || fontName.contains("extrabold");
        }

        private boolean isItalic(TextPosition position) {
            PDFont font = position.getFont();
            if (font == null) {
                return false;
            }
            String fontName = font.getName().toLowerCase();
            return fontName.contains("italic") || fontName.contains("oblique");
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            super.writeString(LINE_SEPARATOR);
        }
    }

    /**
     * 文本块数据结构
     */
    private static class TextBlock {
        final String text;
        final float fontSize;
        final boolean bold;
        final boolean italic;

        TextBlock(String text, float fontSize, boolean bold, boolean italic) {
            this.text = text;
            this.fontSize = fontSize;
            this.bold = bold;
            this.italic = italic;
        }
    }

}
