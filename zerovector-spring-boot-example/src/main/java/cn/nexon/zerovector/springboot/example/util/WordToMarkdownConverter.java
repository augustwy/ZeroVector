package cn.nexon.zerovector.springboot.example.util;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFStyle;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Word文档转Markdown工具类
 * 
 * <p>支持将.docx格式的Word文档转换为Markdown格式，保留以下内容：</p>
 * <ul>
 *   <li>标题层级（H1-H6）</li>
 *   <li>段落文本及格式（粗体、斜体）</li>
 *   <li>有序列表和无序列表</li>
 *   <li>表格结构</li>
 *   <li>缩进和层级关系</li>
 * </ul>
 * 
 * @author ZeroVector
 */
public class WordToMarkdownConverter {

    private static final String LINE_SEPARATOR = System.lineSeparator();
    private static final int MAX_HEADING_LEVEL = 6;

    /**
     * 将Word文件转换为Markdown格式
     *
     * @param filePath Word文件路径
     * @return Markdown格式文本
     * @throws IOException 文件读取失败时抛出
     */
    public static String convertToMarkdown(String filePath) throws IOException {
        return convertToMarkdown(Path.of(filePath));
    }

    /**
     * 将Word文件转换为Markdown格式
     *
     * @param path Word文件路径
     * @return Markdown格式文本
     * @throws IOException 文件读取失败时抛出
     */
    public static String convertToMarkdown(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(is)) {
            return convertDocument(document);
        }
    }

    /**
     * 将Word输入流转换为Markdown格式
     *
     * @param inputStream Word文件输入流
     * @return Markdown格式文本
     * @throws IOException 文件读取失败时抛出
     */
    public static String convertToMarkdown(InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            return convertDocument(document);
        }
    }

    /**
     * 转换XWPFDocument为Markdown
     *
     * @param document Word文档对象
     * @return Markdown格式文本
     */
    private static String convertDocument(XWPFDocument document) {
        StringBuilder markdown = new StringBuilder();
        List<IBodyElement> bodyElements = document.getBodyElements();
        XWPFStyles styles = document.getStyles();

        for (IBodyElement element : bodyElements) {
            if (element instanceof XWPFParagraph paragraph) {
                String paragraphMd = convertParagraph(paragraph, styles);
                if (!paragraphMd.isEmpty()) {
                    markdown.append(paragraphMd).append(LINE_SEPARATOR);
                }
            } else if (element instanceof XWPFTable table) {
                String tableMd = convertTable(table);
                markdown.append(tableMd).append(LINE_SEPARATOR);
            }
        }

        return markdown.toString().trim();
    }

    /**
     * 转换段落为Markdown
     *
     * @param paragraph Word段落
     * @param styles 文档样式
     * @return Markdown格式段落
     */
    private static String convertParagraph(XWPFParagraph paragraph, XWPFStyles styles) {
        String text = paragraph.getText();
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        String styleId = paragraph.getStyle();
        int headingLevel = getHeadingLevel(styleId, styles);

        if (headingLevel > 0) {
            return "#".repeat(Math.min(headingLevel, MAX_HEADING_LEVEL)) + " " + text.trim();
        }

        String listPrefix = getListPrefix(paragraph);
        if (listPrefix != null) {
            int indentLevel = getIndentLevel(paragraph);
            String indent = "  ".repeat(indentLevel);
            return indent + listPrefix + formatRuns(paragraph);
        }

        return formatRuns(paragraph);
    }

    /**
     * 获取标题级别
     *
     * @param styleId 样式ID
     * @param styles 文档样式
     * @return 标题级别（1-6），如果不是标题返回0
     */
    private static int getHeadingLevel(String styleId, XWPFStyles styles) {
        if (styleId == null) {
            return 0;
        }

        int level = extractHeadingNumber(styleId);
        if (level > 0) {
            return level;
        }

        if (styles != null) {
            XWPFStyle style = styles.getStyle(styleId);
            if (style != null) {
                level = extractHeadingNumber(style.getName());
                if (level > 0) {
                    return level;
                }
            }
        }

        return 0;
    }

    private static int extractHeadingNumber(String name) {
        if (name == null) return 0;
        String lower = name.toLowerCase();
        if (!lower.contains("heading") && !lower.contains("标题")) return 0;
        try {
            String numPart = lower.replaceAll("[^0-9]", "");
            return numPart.isEmpty() ? 1 : Integer.parseInt(numPart);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 获取列表前缀
     *
     * @param paragraph 段落
     * @return 列表前缀（如 "- " 或 "1. "），如果不是列表返回null
     */
    private static String getListPrefix(XWPFParagraph paragraph) {
        String numFmt = paragraph.getNumFmt();
        if (numFmt == null) {
            String text = paragraph.getText();
            if (text != null && !text.trim().isEmpty()) {
                String trimmed = text.trim();
                if (trimmed.matches("^[•●○◆◇].*")) {
                    return "- ";
                }
            }
            return null;
        }

        return switch (numFmt.toLowerCase()) {
            case "bullet" -> "- ";
            case "decimal" -> {
                int num = paragraph.getNumIlvl() != null ? paragraph.getNumIlvl().intValue() + 1 : 1;
                yield num + ". ";
            }
            default -> "- ";
        };
    }

    /**
     * 获取缩进级别
     *
     * @param paragraph 段落
     * @return 缩进级别（0开始）
     */
    private static int getIndentLevel(XWPFParagraph paragraph) {
        Integer indent = paragraph.getIndentationLeft();
        return indent != null ? Math.min(indent / 720, 6) : 0;
    }

    /**
     * 格式化段落中的文本运行，保留粗体和斜体
     *
     * @param paragraph 段落
     * @return 格式化后的文本
     */
    private static String formatRuns(XWPFParagraph paragraph) {
        StringBuilder result = new StringBuilder();
        List<XWPFRun> runs = paragraph.getRuns();

        for (XWPFRun run : runs) {
            String runText = run.getText(0);
            if (runText == null || runText.isEmpty()) {
                continue;
            }

            boolean bold = run.isBold();
            boolean italic = run.isItalic();

            if (bold && italic) {
                result.append("***").append(runText).append("***");
            } else if (bold) {
                result.append("**").append(runText).append("**");
            } else if (italic) {
                result.append("*").append(runText).append("*");
            } else {
                result.append(runText);
            }
        }

        return result.toString();
    }

    /**
     * 转换表格为Markdown
     *
     * @param table Word表格
     * @return Markdown格式表格
     */
    private static String convertTable(XWPFTable table) {
        StringBuilder markdown = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();

        if (rows.isEmpty()) {
            return "";
        }

        List<String[]> tableData = new ArrayList<>();
        int maxColumns = 0;

        for (XWPFTableRow row : rows) {
            List<XWPFTableCell> cells = row.getTableCells();
            String[] cellTexts = new String[cells.size()];
            for (int i = 0; i < cells.size(); i++) {
                cellTexts[i] = getCellText(cells.get(i)).replace("|", "\\|");
            }
            tableData.add(cellTexts);
            maxColumns = Math.max(maxColumns, cells.size());
        }

        if (maxColumns == 0) {
            return "";
        }

        for (int rowIndex = 0; rowIndex < tableData.size(); rowIndex++) {
            String[] row = tableData.get(rowIndex);
            markdown.append("|");
            for (int col = 0; col < maxColumns; col++) {
                String cellText = col < row.length ? row[col] : "";
                markdown.append(" ").append(cellText).append(" |");
            }
            markdown.append(LINE_SEPARATOR);

            if (rowIndex == 0) {
                markdown.append("|");
                for (int col = 0; col < maxColumns; col++) {
                    markdown.append(" --- |");
                }
                markdown.append(LINE_SEPARATOR);
            }
        }

        return markdown.toString().trim();
    }

    /**
     * 获取单元格文本（处理嵌套内容）
     *
     * @param cell 表格单元格
     * @return 单元格文本
     */
    private static String getCellText(XWPFTableCell cell) {
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph para : cell.getParagraphs()) {
            String paraText = para.getText();
            if (paraText != null && !paraText.trim().isEmpty()) {
                if (!text.isEmpty()) {
                    text.append(" ");
                }
                text.append(paraText.trim());
            }
        }
        return text.toString().trim();
    }
}
