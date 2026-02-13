package cn.nexon.zerovector.core.model;

import java.util.List;

/**
 * 导航结果记录
 */
public record NavigationResult(
        List<DocumentChunk> documents,
        String reasoning,
        List<NavigationPath> path
) {}
