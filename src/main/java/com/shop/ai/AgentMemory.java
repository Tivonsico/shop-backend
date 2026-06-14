package com.shop.ai;

import java.util.*;

/**
 * Agent 记忆系统
 *
 * 每个用户一个独立的对话历史，存在内存里。
 * 让 AI 能记住之前聊了什么。
 *
 * 真实场景：这里应该用 Redis 持久化，支持过期淘汰。
 */
public class AgentMemory {

    private static final int MAX_HISTORY = 20;

    // userId → 消息列表
    private static final Map<Long, List<Map<String, String>>> memories = new HashMap<>();

    /**
     * 添加一条消息到记忆
     */
    public static void addMessage(Long userId, String role, String content) {
        memories.computeIfAbsent(userId, k -> new ArrayList<>());
        List<Map<String, String>> history = memories.get(userId);
        Map<String, String> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        history.add(msg);
        // 限制记忆长度，防止内存泄漏
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    /**
     * 获取用户的对话历史
     */
    public static List<Map<String, String>> getHistory(Long userId) {
        return memories.getOrDefault(userId, new ArrayList<>());
    }

    /**
     * 获取最近几条消息作为上下文
     */
    public static String getContext(Long userId) {
        List<Map<String, String>> history = getHistory(userId);
        if (history.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("以下是对话历史：\n");
        for (Map<String, String> msg : history) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("user".equals(role)) {
                sb.append("用户：").append(content).append("\n");
            } else {
                sb.append("助手：").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 清除用户记忆
     */
    public static void clear(Long userId) {
        memories.remove(userId);
    }
}
