package cn.nexon.zerovector.core.exception;

public class DocumentProcessingException extends RuntimeException {
    private final String documentId;
    private final String operation;
    
    public DocumentProcessingException(String documentId, String operation, Throwable cause) {
        super(String.format("Failed to %s document %s", operation, documentId), cause);
        this.documentId = documentId;
        this.operation = operation;
    }
    
    public DocumentProcessingException(String documentId, String operation, String message) {
        super(String.format("Failed to %s document %s: %s", operation, documentId, message));
        this.documentId = documentId;
        this.operation = operation;
    }
    
    public String getDocumentId() {
        return documentId;
    }
    
    public String getOperation() {
        return operation;
    }
}
