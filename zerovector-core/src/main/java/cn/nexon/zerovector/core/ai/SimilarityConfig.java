/*
 * Copyright 2025 nexonlab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.nexon.zerovector.core.ai;

/**
 * 相似提示词匹配配置（不可变）。
 *
 * <p>此前该配置是 {@link SmartCacheStrategy} 的静态可变字段，导致同一 JVM 内多个
 * {@code CachedLLMProvider} / 多个 Spring 上下文互相覆盖。改为按实例携带后，
 * 每个 provider 拥有独立配置，不再有全局可变状态。
 */
public record SimilarityConfig(
        double similarityThreshold,
        int maxSimilarityLength,
        boolean enableSimilarityMatching) {

    /** 默认配置：阈值 0.85、最大比较长度 500、启用相似匹配。 */
    public static final SimilarityConfig DEFAULT = new SimilarityConfig(0.85, 500, true);

    public SimilarityConfig {
        // 阈值裁剪至 [0,1]；长度必须为正，否则回落默认值，避免非法配置进入匹配逻辑
        if (Double.isNaN(similarityThreshold)
                || similarityThreshold < 0
                || similarityThreshold > 1) {
            similarityThreshold = 0.85;
        }
        if (maxSimilarityLength <= 0) {
            maxSimilarityLength = 500;
        }
    }
}
