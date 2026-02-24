package cn.nexon.zerovector.core.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PromptConfig {
    private static final Logger logger = LoggerFactory.getLogger(PromptConfig.class);
    private static final String CONFIG_FILE = "prompt-templates.yml";
    
    private static volatile PromptConfig instance;
    private final Map<String, Map<String, PromptTemplate>> templates;
    private final String defaultVersion;
    
    private PromptConfig(ConfigData configData) {
        this.templates = new ConcurrentHashMap<>();
        this.defaultVersion = configData.defaultVersion();
        
        for (Map.Entry<String, TemplateConfig> entry : configData.templates().entrySet()) {
            String templateName = entry.getKey();
            TemplateConfig templateConfig = entry.getValue();
            Map<String, PromptTemplate> versionMap = new ConcurrentHashMap<>();
            
            for (TemplateVersion version : templateConfig.versions()) {
                List<PromptTemplate.Parameter> parameters = version.parameters() != null 
                    ? version.parameters().stream()
                        .map(p -> new PromptTemplate.Parameter(p.name(), p.type(), p.description()))
                        .toList()
                    : List.of();
                
                PromptTemplate template = new PromptTemplate(
                    version.version(),
                    version.template(),
                    parameters
                );
                versionMap.put(version.version(), template);
            }
            
            templates.put(templateName, versionMap);
        }
        
        logger.info("PromptConfig loaded with {} templates", templates.size());
    }
    
    public static PromptConfig getInstance() {
        if (instance == null) {
            synchronized (PromptConfig.class) {
                if (instance == null) {
                    instance = loadConfig();
                }
            }
        }
        return instance;
    }
    
    private static PromptConfig loadConfig() {
        try (InputStream inputStream = PromptConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Cannot find " + CONFIG_FILE + " in classpath");
            }
            
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            ConfigData configData = mapper.readValue(inputStream, ConfigData.class);
            return new PromptConfig(configData);
        } catch (IOException e) {
            logger.error("Failed to load prompt config from {}", CONFIG_FILE, e);
            throw new IllegalStateException("Failed to load prompt config", e);
        }
    }
    
    public static void reload() {
        synchronized (PromptConfig.class) {
            instance = loadConfig();
        }
    }
    
    public PromptTemplate getTemplate(String templateName) {
        return getTemplate(templateName, defaultVersion);
    }
    
    public PromptTemplate getTemplate(String templateName, String version) {
        Map<String, PromptTemplate> versionMap = templates.get(templateName);
        if (versionMap == null) {
            throw new IllegalArgumentException("Template not found: " + templateName);
        }
        
        PromptTemplate template = versionMap.get(version);
        if (template == null) {
            throw new IllegalArgumentException("Version not found for template " + templateName + ": " + version);
        }
        
        return template;
    }
    
    public List<String> getAvailableVersions(String templateName) {
        Map<String, PromptTemplate> versionMap = templates.get(templateName);
        if (versionMap == null) {
            return List.of();
        }
        return new ArrayList<>(versionMap.keySet());
    }
    
    public Set<String> getAvailableTemplates() {
        return new HashSet<>(templates.keySet());
    }
    
    public String getDefaultVersion() {
        return defaultVersion;
    }
    
    public boolean hasTemplate(String templateName) {
        return templates.containsKey(templateName);
    }
    
    public boolean hasVersion(String templateName, String version) {
        Map<String, PromptTemplate> versionMap = templates.get(templateName);
        return versionMap != null && versionMap.containsKey(version);
    }
    
    private record ConfigData(
        String version,
        String defaultVersion,
        Map<String, TemplateConfig> templates
    ) {}
    
    private record TemplateConfig(
        List<TemplateVersion> versions
    ) {}
    
    private record TemplateVersion(
        String version,
        String template,
        List<Parameter> parameters
    ) {}
    
    private record Parameter(
        String name,
        String type,
        String description
    ) {}
}
