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

package cn.nexon.zerovector.core.model;

/**
 * 导航行为协议
 * [KEY] 利用 Sealed Interface 严格约束 LLM 的输出行为
 *
 * <p>解析器（见 HybridNavigator#parseNavigationResponse）按 {@code action} 字段分发：
 * <ul>
 *   <li>{@code select_child} → {@link SelectChild}（选中单个子节点下钻）</li>
 *   <li>{@code expand_multiple} → {@link ExpandMultiple}（多分支并行，提高召回）</li>
 *   <li>{@code fallback_search} → {@link FallbackSearch}（LLM 主动降级为关键词检索）</li>
 *   <li>{@code stop} / 无匹配 → {@link Stop}</li>
 * </ul>
 *
 * <p>注：不设"按 chunkId 直选叶子"的动作——LLM 在提示词中看不到 chunkId，
 * 该意图由 {@link ExpandMultiple} 选中多个叶子节点覆盖。
 */
public sealed interface NavigationAction
    permits NavigationAction.SelectChild, NavigationAction.ExpandMultiple, NavigationAction.FallbackSearch, NavigationAction.Stop {

    /**
     * 选择子节点继续导航
     */
    record SelectChild(String nodeId, String reasoning, double confidence) implements NavigationAction {}

    /**
     * 多分支并行处理：查询横跨多个类目或有歧义时，同时展开多个子节点
     */
    record ExpandMultiple(java.util.List<String> nodeIds, String reasoning) implements NavigationAction {}

    /**
     * LLM 主动降级：当前节点下无合适子节点，改用关键词倒排检索兜底
     */
    record FallbackSearch(String reason) implements NavigationAction {}

    /**
     * 停止导航
     */
    record Stop(String reasoning) implements NavigationAction {}
}