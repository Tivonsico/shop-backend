package com.shop.ai;

import com.shop.model.Goods;
import com.shop.model.Order;
import com.shop.service.GoodsService;
import com.shop.service.OrderService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI Agent 服务
 *
 * 架构（面试可以讲这个）：
 *   1. 记忆层（Memory）— 存对话历史
 *   2. 规划层（Planning）— 理解用户意图，决定用什么工具
 *   3. 工具层（Tools）— 实际执行数据库查询等操作
 *   4. 生成层（Response）— 组装最终回复
 *
 * 如果配置了 DEEPSEEK_API_KEY，会用真实大模型理解语义；
 * 没有的话用增强关键词匹配兜底。
 */
@Service
public class AgentService {

    private final GoodsService goodsService;
    private final OrderService orderService;
    private final HttpClient httpClient;

    // 从环境变量读取 DeepSeek API Key（可选）
    // 设置方式：System.setProperty("DEEPSEEK_API_KEY", "sk-xxx")
    // 或者在 IDEA 运行配置的 VM Options 加 -D DEEPSEEK_API_KEY=sk-xxx
    private static final String API_KEY = System.getProperty("DEEPSEEK_API_KEY", "");
    private static final boolean USE_LLM = !API_KEY.isEmpty() && !API_KEY.equals("sk-your-key-here");

    public AgentService(GoodsService goodsService, OrderService orderService) {
        this.goodsService = goodsService;
        this.orderService = orderService;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Agent 主入口
     *
     * 1. 保存用户消息到记忆
     * 2. 识别意图（用 LLM 或关键词）
     * 3. 调用对应工具
     * 4. 保存回复到记忆
     */
    public Map<String, Object> process(String message, Long userId) {
        AgentMemory.addMessage(userId, "user", message);

        Map<String, Object> result;
        if (USE_LLM) {
            result = processWithLLM(message, userId);
        } else {
            result = processWithKeywords(message, userId);
        }

        String reply = (String) result.get("reply");
        AgentMemory.addMessage(userId, "assistant", reply);
        return result;
    }

    // ==================== LLM 模式（调用 DeepSeek API）====================

    private Map<String, Object> processWithLLM(String message, Long userId) {
        try {
            String context = buildLLMContext(userId);
            String prompt = "你是天方电竞商城的 AI 助手。你擅长查询用户的订单信息、商品信息。\n\n"
                    + context
                    + "\n\n用户最新消息：" + message
                    + "\n\n请根据对话历史和用户消息，判断用户想做什么，然后从以下工具中选择一个执行：\n"
                    + "1. query_orders — 查询我的订单\n"
                    + "2. query_goods — 查询商品信息\n"
                    + "3. query_goods_detail — 查询某个商品的详细信息\n"
                    + "4. help — 帮助信息\n\n"
                    + "请只回复工具名称和参数，格式：工具名|参数（没有参数填无）";

            String llmReply = callDeepSeek(prompt);

            // 解析 LLM 返回的工具调用
            if (llmReply.contains("query_orders")) return executeQueryOrders(userId);
            if (llmReply.contains("query_goods")) return executeQueryGoods(message);
            if (llmReply.contains("query_goods_detail")) return executeQueryGoodsDetail(message);
            if (llmReply.contains("help")) return executeHelp();

            return executeUnknown();

        } catch (Exception e) {
            // LLM 调用失败，降级到关键词模式
            return processWithKeywords(message, userId);
        }
    }

    private String buildLLMContext(Long userId) {
        String history = AgentMemory.getContext(userId);
        List<Order> orders = orderService.findByUserId(userId);
        List<Goods> goods = goodsService.getAllGoods();

        StringBuilder sb = new StringBuilder();
        sb.append(history).append("\n");
        sb.append("系统信息：\n");
        sb.append("用户有 ").append(orders.size()).append(" 笔订单\n");
        sb.append("商城共有 ").append(goods.size()).append(" 件商品\n");

        if (!orders.isEmpty()) {
            sb.append("最近订单：");
            Order last = orders.get(orders.size() - 1);
            goodsService.findById(last.getGoodsId()).ifPresent(g ->
                    sb.append(g.getName()).append(" × ").append(last.getCount())
            );
            sb.append("\n");
        }
        return sb.toString();
    }

    private String callDeepSeek(String prompt) throws Exception {
        String json = "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":" +
                JSONStringEscape(prompt) + "}],\"max_tokens\":100}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.deepseek.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(java.time.Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        // 简单解析返回内容
        String body = response.body();
        if (body.contains("\"content\":\"")) {
            int start = body.indexOf("\"content\":\"") + 11;
            int end = body.indexOf("\"", start);
            if (start > 10 && end > start) {
                return body.substring(start, end);
            }
        }
        return "";
    }

    private String JSONStringEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== 关键词模式（兜底）====================

    private Map<String, Object> processWithKeywords(String message, Long userId) {
        // 先看记忆中有没有上下文可以辅助判断
        List<Map<String, String>> history = AgentMemory.getHistory(userId);
        String fullContext = history.stream()
                .map(m -> m.get("role") + ": " + m.get("content"))
                .collect(Collectors.joining("\n"));

        String msg = message.toLowerCase();

        // 多轮对话：如果用户说"那这个呢""多少钱"等，结合上一条消息
        String lastUserMsg = "";
        String lastAssistantMsg = "";
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> m = history.get(i);
            if ("assistant".equals(m.get("role")) && lastAssistantMsg.isEmpty()) {
                lastAssistantMsg = m.get("content");
            }
            if ("user".equals(m.get("role")) && lastUserMsg.isEmpty()) {
                lastUserMsg = m.get("content");
            }
            if (!lastUserMsg.isEmpty() && !lastAssistantMsg.isEmpty()) break;
        }

        // 处理上下文相关的追问
        boolean isFollowUp = msg.matches(".*(那这个|它|他|那个|这个|多少|贵|便宜|怎么样).*")
                && !lastAssistantMsg.isEmpty();

        // 意图识别（增强版）
        if (msg.contains("订单") || msg.contains("买了") || msg.contains("买过") || msg.contains("消费")) {
            return executeQueryOrders(userId);
        }
        if (msg.contains("商品") || msg.contains("东西") || msg.contains("卖") || msg.contains("有什么")) {
            return executeQueryGoods(message);
        }
        if (msg.contains("库存") || msg.contains("还剩") || msg.contains("有货")) {
            return executeQueryStock(message);
        }
        if (msg.contains("价格") || msg.contains("多少钱") || msg.contains("贵") || msg.contains("便宜")) {
            if (isFollowUp) {
                // 结合上一条助手的回复判断在说哪个商品
                return executeQueryGoods(lastAssistantMsg);
            }
            return executeQueryGoods(message);
        }
        if (isFollowUp && (msg.contains("详情") || msg.contains("介绍") || msg.contains("怎么样"))) {
            return executeQueryGoods(lastAssistantMsg + lastUserMsg);
        }
        if (msg.contains("帮助") || msg.contains("你好") || msg.contains("功能") || msg.contains("能")) {
            return executeHelp();
        }
        if (msg.contains("谢谢") || msg.contains("拜拜") || msg.contains("再见")) {
            return executeFarewell();
        }

        return executeUnknown();
    }

    // ==================== 工具函数 ====================

    private Map<String, Object> executeQueryOrders(Long userId) {
        List<Order> orders = orderService.findByUserId(userId);
        List<Map<String, Object>> orderList = new ArrayList<>();

        for (Order order : orders) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", order.getId());
            item.put("count", order.getCount());
            item.put("totalPrice", order.getTotalPrice());
            item.put("status", "已支付");
            item.put("time", order.getCreatedAt() != null ? order.getCreatedAt().toString().substring(0, 10) : "未知");
            Optional<Goods> goods = goodsService.findById(order.getGoodsId());
            item.put("goodsName", goods.map(Goods::getName).orElse("未知"));
            orderList.add(item);
        }

        String reply;
        if (orderList.isEmpty()) {
            reply = "您目前还没有订单呢，去首页逛逛吧～";
        } else {
            double total = orders.stream().mapToDouble(Order::getTotalPrice).sum();
            reply = "您共有 " + orderList.size() + " 笔订单，累计消费 ¥" + String.format("%.2f", total);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        result.put("data", orderList);
        result.put("intent", "order_query");
        return result;
    }

    private Map<String, Object> executeQueryGoods(String message) {
        List<Goods> allGoods = goodsService.getAllGoods();
        String msg = message.toLowerCase();

        // 尝试匹配商品名称
        List<Goods> matched = new ArrayList<>();
        for (Goods g : allGoods) {
            String name = g.getName().toLowerCase();
            if (name.contains(msg) || msg.contains(name)) {
                matched.add(g);
            }
        }
        if (matched.isEmpty()) {
            // 没匹配到，返回全部
            matched = allGoods;
        }

        List<Map<String, Object>> goodsList = matched.stream().map(g -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", g.getId());
            item.put("name", g.getName());
            item.put("price", g.getPrice());
            item.put("stock", g.getStock());
            item.put("image", g.getImageUrl());
            return item;
        }).collect(Collectors.toList());

        String reply;
        if (matched.size() == 1) {
            Goods g = matched.get(0);
            reply = "找到商品《" + g.getName() + "》，售价 ¥" + String.format("%.2f", g.getPrice())
                    + "，库存 " + g.getStock() + " 件。您可以点这个商品查看详情和购买。";
        } else if (matched.size() < allGoods.size()) {
            reply = "找到 " + matched.size() + " 件相关商品：";
        } else {
            reply = "商城共有 " + allGoods.size() + " 件商品，价格从 ¥"
                    + String.format("%.2f", allGoods.stream().mapToDouble(Goods::getPrice).min().orElse(0))
                    + " 到 ¥" + String.format("%.2f", allGoods.stream().mapToDouble(Goods::getPrice).max().orElse(0));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        result.put("data", goodsList);
        result.put("intent", "goods_query");
        return result;
    }

    private Map<String, Object> executeQueryGoodsDetail(String message) {
        // 尝试在消息中找到商品名
        List<Goods> allGoods = goodsService.getAllGoods();
        for (Goods g : allGoods) {
            if (message.contains(g.getName()) || g.getName().contains(message)) {
                Map<String, Object> result = executeQueryGoods(g.getName());
                result.put("reply", "《" + g.getName() + "》" + g.getDescription()
                        + " 售价 ¥" + String.format("%.2f", g.getPrice())
                        + "，库存 " + g.getStock() + " 件。");
                return result;
            }
        }
        return executeQueryGoods(message);
    }

    private Map<String, Object> executeQueryStock(String message) {
        List<Goods> allGoods = goodsService.getAllGoods();
        for (Goods g : allGoods) {
            if (message.contains(g.getName()) || g.getName().contains(message.replace("库存", "").trim())) {
                String reply = "《" + g.getName() + "》当前库存 " + g.getStock() + " 件"
                        + (g.getStock() < 10 ? "，库存不多，抓紧购买哦～" : "，库存充足。");
                return Map.of("reply", reply, "intent", "goods_query");
            }
        }
        // 列出库存不足的商品
        List<Goods> lowStock = allGoods.stream()
                .filter(g -> g.getStock() < 20)
                .collect(Collectors.toList());
        if (lowStock.isEmpty()) {
            return Map.of("reply", "所有商品库存都还充足～", "intent", "goods_query");
        }
        String names = lowStock.stream().map(Goods::getName).collect(Collectors.joining("、"));
        return Map.of("reply", "以下商品库存不多：" + names + "，快去看看吧", "intent", "goods_query");
    }

    private Map<String, Object> executeHelp() {
        String reply = "您好！我是天方电竞 AI 助手，我可以帮您：\n"
                + "• 查商品 — \"有什么商品\"\"太空鹅多少钱\"\n"
                + "• 查库存 — \"太空鹅还有货吗\"\n"
                + "• 查订单 — \"我的订单\"\"我买过什么\"\n"
                + "• 连续对话 — 问我一个商品后，可以接着问\"多少钱\"\"有货吗\"";
        return Map.of("reply", reply, "intent", "help");
    }

    private Map<String, Object> executeFarewell() {
        return Map.of("reply", "不客气！有任何需要随时找我 😊", "intent", "help");
    }

    private Map<String, Object> executeUnknown() {
        String reply = "这个问题我还不太会回答。您可以试试：\n"
                + "• \"有什么商品\" — 看商品列表\n"
                + "• \"我的订单\" — 查订单\n"
                + "• \"帮助\" — 查看我能做什么";
        return Map.of("reply", reply, "intent", "unknown");
    }
}
