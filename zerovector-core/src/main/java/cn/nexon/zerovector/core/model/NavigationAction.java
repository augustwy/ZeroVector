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
 */
public sealed interface NavigationAction 
    permits NavigationAction.SelectChild, NavigationAction.ExpandMultiple, NavigationAction.SelectLeaves, NavigationAction.FallbackSearch, NavigationAction.Stop {
    
    /**
     * 选择子节点继续导航
     */
    record SelectChild(String nodeId, String reasoning, double confidence) implements NavigationAction {}
    
    /**
     * 多分支并行处理
     */
    record ExpandMultiple(java.util.List<String> nodeIds, String reasoning) implements NavigationAction {}
    
    /**
     * 选择叶子节点（文档块）作为结果
     */
    record SelectLeaves(java.util.List<String> chunkIds, String reasoning) implements NavigationAction {}
    
    /**
     * 兜底策略：当置信度过低时触发
     */
    record FallbackSearch(String reason) implements NavigationAction {} // 兜底策略
    
    /**
     * 停止导航
     */
    record Stop(String reasoning) implements NavigationAction {}
}