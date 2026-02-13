package cn.nexon.zerovector.core.document.impl;

import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.document.DocumentProcessor;
import cn.nexon.zerovector.core.document.DocumentProcessor.ProcessingConfig;

import java.util.List;

public class TextDocumentProcessor extends AbstractDocumentProcessor {
    
    public TextDocumentProcessor() {
        super();
    }
    
    public TextDocumentProcessor(ProcessingConfig config) {
        super(config);
    }
    
    @Override
    public List<String> getSupportedFormats() {
        return List.of("txt", "text");
    }
    
    @Override
    public String getName() {
        return "TextDocumentProcessor";
    }
    
    @Override
    protected List<DocumentChunk> processWithChunking(String documentId, String content, String fileName, String md5) {
        if (config.splitByParagraph()) {
            return splitByParagraphs(documentId, content, fileName, md5);
        } else if (config.splitBySentence()) {
            return splitBySentences(documentId, content, fileName, md5);
        } else {
            return splitBySize(documentId, content, fileName, md5);
        }
    }
}
