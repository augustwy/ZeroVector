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
 * LLM 调用抽象。整个系统通过这一个方法调用大模型，缓存策略通过 RequestType 区分。
 */
public interface LLMProvider {

    /**
     * 调用 LLM。
     *
     * @param prompt 提示词
     * @param type   请求类型，用于缓存策略区分
     * @return LLM 响应（内容 + token 消耗 + 耗时）
     */
    LLMResponse chat(String prompt, SmartCacheStrategy.RequestType type);
}
