package com.shop.ai;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 聊天控制器
 *
 * GET  /ai      — 聊天页面
 * POST /api/ai/chat — 发送消息（JSON）
 * POST /api/ai/clear — 清除对话记忆
 */
@Controller
public class AIController {

    private final AgentService agentService;

    public AIController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/ai")
    public String aiPage(Model model, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return "redirect:/login";
        }
        model.addAttribute("username", session.getAttribute("username"));
        model.addAttribute("userId", userId);
        return "ai-chat";
    }

    @ResponseBody
    @PostMapping("/api/ai/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body, HttpSession session) {
        String message = body.getOrDefault("message", "");
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) userId = 0L;
        return agentService.process(message, userId);
    }

    @ResponseBody
    @PostMapping("/api/ai/clear")
    public Map<String, Object> clear(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId != null) AgentMemory.clear(userId);
        return Map.of("success", true);
    }
}
