package cn.nexon.zerovector.core.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class LLMPromptTemplates {
    private static final Logger logger = LoggerFactory.getLogger(LLMPromptTemplates.class);
    private static final PromptConfig config = PromptConfig.getInstance();
    private static String currentVersion = config.getDefaultVersion();

    private LLMPromptTemplates() {
    }

    public static String getCurrentVersion() {
        return currentVersion;
    }

    public static void setCurrentVersion(String version) {
        if (!config.hasVersion("generateSummary", version)) {
            throw new IllegalArgumentException("Invalid version: " + version);
        }
        currentVersion = version;
        logger.info("Prompt version switched to: {}", version);
    }

    public static List<String> getAvailableVersions() {
        return config.getAvailableVersions("generateSummary");
    }

    public static void reloadConfig() {
        PromptConfig.reload();
        logger.info("Prompt config reloaded");
    }

    private static void validateParameters(String templateName, Object... args) {
        PromptTemplate template = config.getTemplate(templateName, currentVersion);
        if (!template.validateParameterCount(args.length)) {
            throw new IllegalArgumentException(
                "Parameter count mismatch for template " + templateName + 
                ": expected " + template.parameters().size() + 
                ", got " + args.length
            );
        }
    }

    public static String generateSummary(String title, String content) {
        validateParameters("generateSummary", title, content);
        PromptTemplate template = config.getTemplate("generateSummary", currentVersion);
        return template.format(title, content);
    }

    public static String clusterDocumentChunks(List<String> chunks) {
        StringBuilder chunksBuilder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            chunksBuilder.append("[").append(i).append("] ")
                    .append(chunks.get(i).substring(0, Math.min(200, chunks.get(i).length())))
                    .append("\n");
        }

        validateParameters("clusterDocumentChunks", chunksBuilder.toString());
        PromptTemplate template = config.getTemplate("clusterDocumentChunks", currentVersion);
        return template.format(chunksBuilder.toString());
    }

    public static String decideNavigation(String query, String currentNodeName, String currentNodeDescription, List<String> childNodes) {
        StringBuilder nodesBuilder = new StringBuilder();
        for (int i = 0; i < childNodes.size(); i++) {
            nodesBuilder.append("[").append(i).append("] ").append(childNodes.get(i)).append("\n");
        }

        validateParameters("decideNavigation", query, currentNodeName, currentNodeDescription, nodesBuilder.toString());
        PromptTemplate template = config.getTemplate("decideNavigation", currentVersion);
        return template.format(query, currentNodeName, currentNodeDescription, nodesBuilder.toString());
    }

    public static String extractEntities(String content) {
        validateParameters("extractEntities", content);
        PromptTemplate template = config.getTemplate("extractEntities", currentVersion);
        return template.format(content);
    }

    public static String extractKeywords(String content) {
        validateParameters("extractKeywords", content);
        PromptTemplate template = config.getTemplate("extractKeywords", currentVersion);
        return template.format(content);
    }

    public static String generateExampleQuestions(String content) {
        validateParameters("generateExampleQuestions", content);
        PromptTemplate template = config.getTemplate("generateExampleQuestions", currentVersion);
        return template.format(content);
    }

    public static String comprehendChunk(String context, int chunkIndex, int totalChunks) {
        validateParameters("comprehendChunk", chunkIndex, totalChunks, context);
        PromptTemplate template = config.getTemplate("comprehendChunk", currentVersion);
        return template.format(chunkIndex, totalChunks, context);
    }
}
