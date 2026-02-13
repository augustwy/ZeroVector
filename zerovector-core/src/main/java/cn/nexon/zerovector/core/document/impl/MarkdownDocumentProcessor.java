package cn.nexon.zerovector.core.document.impl;

import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.document.DocumentProcessor;
import cn.nexon.zerovector.core.document.DocumentProcessor.ProcessingConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownDocumentProcessor extends AbstractDocumentProcessor {
    
    public MarkdownDocumentProcessor() {
        super();
    }
    
    public MarkdownDocumentProcessor(ProcessingConfig config) {
        super(config);
    }
    
    @Override
    public List<String> getSupportedFormats() {
        return List.of("md", "markdown");
    }
    
    @Override
    public String getName() {
        return "MarkdownDocumentProcessor";
    }
    
    @Override
    protected List<DocumentChunk> processWithChunking(String documentId, String content, String fileName, String md5) {
        if (config.splitByHeading()) {
            return splitByHeadings(documentId, content, fileName, md5);
        } else if (config.splitByParagraph()) {
            return splitByParagraphs(documentId, content, fileName, md5);
        } else if (config.splitBySentence()) {
            return splitBySentences(documentId, content, fileName, md5);
        } else {
            return splitBySize(documentId, content, fileName, md5);
        }
    }
    
    private List<DocumentChunk> splitByHeadings(String documentId, String content, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        Pattern headingPattern = Pattern.compile(config.headingPattern(), Pattern.MULTILINE);
        Matcher matcher = headingPattern.matcher(content);
        
        int lastEnd = 0;
        int sectionIndex = 0;
        
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                String sectionContent = content.substring(lastEnd, matcher.start()).trim();
                if (!sectionContent.isEmpty()) {
                    chunks.add(createChunk(documentId, sectionIndex++, sectionContent, fileName + " - 引言", md5));
                }
            }
            
            lastEnd = matcher.start();
        }
        
        if (lastEnd < content.length()) {
            String sectionContent = content.substring(lastEnd).trim();
            if (!sectionContent.isEmpty()) {
                chunks.add(createChunk(documentId, sectionIndex, sectionContent, fileName + " - 结尾", md5));
            }
        }
        
        return chunks;
    }
}
