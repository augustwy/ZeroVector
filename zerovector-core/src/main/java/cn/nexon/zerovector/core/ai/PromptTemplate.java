package cn.nexon.zerovector.core.ai;

import java.util.List;
import java.util.Objects;

public record PromptTemplate(
    String version,
    String template,
    List<Parameter> parameters
) {

    public record Parameter(
        String name,
        String type,
        String description
    ) {
        public Parameter {
            Objects.requireNonNull(name, "Parameter name cannot be null");
            Objects.requireNonNull(type, "Parameter type cannot be null");
        }
    }

    public PromptTemplate {
        Objects.requireNonNull(version, "Version cannot be null");
        Objects.requireNonNull(template, "Template cannot be null");
        Objects.requireNonNull(parameters, "Parameters cannot be null");
    }

    public String format(Object... args) {
        return template.formatted(args);
    }

    public boolean validateParameterCount(int argCount) {
        return argCount == parameters.size();
    }

    public String getParameterType(String paramName) {
        return parameters.stream()
            .filter(p -> p.name().equals(paramName))
            .findFirst()
            .map(Parameter::type)
            .orElse(null);
    }
}
