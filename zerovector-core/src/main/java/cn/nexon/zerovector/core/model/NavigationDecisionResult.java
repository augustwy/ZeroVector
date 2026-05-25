package cn.nexon.zerovector.core.model;

/**
 * 导航决策结果
 *
 * @param selectedIndex 选择的子节点索引
 * @param reasoning 推理过程
 * @param confidence 置信度
 */
public record NavigationDecisionResult(int selectedIndex, String reasoning, double confidence) {}
