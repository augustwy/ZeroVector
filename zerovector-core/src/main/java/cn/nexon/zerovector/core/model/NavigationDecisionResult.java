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
 * 导航决策结果
 *
 * <p>字段与 {@code decideNavigation} 提示词约定的 JSON 一一对应：
 * <ul>
 *   <li>{@code action}：动作类型（select_child / expand_multiple / fallback_search / stop），
 *       为空时兼容旧协议，退化为按 {@code selectedIndex} 处理</li>
 *   <li>{@code selectedIndex}：单子节点选择的下标（-1 表示无）</li>
 *   <li>{@code selectedIndexes}：多分支选择的下标列表</li>
 * </ul>
 *
 * @param action 动作类型
 * @param selectedIndex 选择的子节点索引
 * @param selectedIndexes 多分支选择的子节点索引列表
 * @param reasoning 推理过程
 * @param confidence 置信度
 */
public record NavigationDecisionResult(
        String action,
        Integer selectedIndex,
        java.util.List<Integer> selectedIndexes,
        String reasoning,
        double confidence) {}
