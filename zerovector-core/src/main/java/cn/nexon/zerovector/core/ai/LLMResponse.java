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

public record LLMResponse(
    String content,
    int inputTokens,
    int outputTokens,
    int totalTokens,
    long duration,
    boolean success,
    String errorMessage
) {
    public static LLMResponse success(String content, int inputTokens, int outputTokens, long duration) {
        return new LLMResponse(
            content,
            inputTokens,
            outputTokens,
            inputTokens + outputTokens,
            duration,
            true,
            null
        );
    }

    public static LLMResponse success(String content, long duration) {
        return new LLMResponse(
            content,
            0,
            0,
            0,
            duration,
            true,
            null
        );
    }

    public static LLMResponse failure(String errorMessage, long duration) {
        return new LLMResponse(
            "",
            0,
            0,
            0,
            duration,
            false,
            errorMessage
        );
    }

    public static LLMResponse failure(String errorMessage) {
        return failure(errorMessage, 0);
    }
}
