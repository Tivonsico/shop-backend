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
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI Agent 服务 — 增强版
 *
 * 架构（面试可以讲这个）：
 *   1. 记忆层（Memory）— 存对话历史
 *   2. 规划层（Planning）— 理解用户意图，决定用什么工具
 *   3. 工具层（Tools）— 实际执行数据库查询等操作
 *   4. 生成层（Response）— 组装最终回复
 *
 * 增强功能：
 *   - 时间感知问候
 *   - 价格区间查询
 *   - 商品推荐
 *   - 模糊商品名匹配
 *   - 订单统计
 *   - 多轮对话上下文保持
 *   - 客服联系信息
 */
@Service
public class AgentService {

    private final GoodsService goodsService;
    private final OrderService orderService;
    private final HttpClient httpClient;

    private static final String API_KEY = System.getProperty("DEEPSEEK_API_KEY", "");
    private static final boolean USE_LLM = !API_KEY.isEmpty() && !API_KEY.equals("sk-your-key-here");

    // 近义词表，增强意图识别
    private static final Map<String, List<String>> INTENT_KEYWORDS = new LinkedHashMap<>();
    static {
        INTENT_KEYWORDS.put("greet", List.of("你好", "嗨", "hi", "hello", "在吗", "早", "晚上好", "下午好"));
        INTENT_KEYWORDS.put("order", List.of("订单", "买了", "买过", "消费", "下单", "购买记录", "花了", "支出", "买的东西"));
        INTENT_KEYWORDS.put("order_stat", List.of("花了多少钱", "一共花了", "消费了多少", "买了多少", "总共"));
        INTENT_KEYWORDS.put("goods", List.of("商品", "东西", "卖", "有什么", "都有啥", "列表", "产品", "货", "在卖"));
        INTENT_KEYWORDS.put("stock", List.of("库存", "还剩", "有货", "够", "余量", "货源"));
        INTENT_KEYWORDS.put("price_range", List.of("以下", "以上", "以内", "以内", "便宜", "贵", "预算"));
        INTENT_KEYWORDS.put("recommend", List.of("推荐", "推荐一下", "有什么好", "哪个好", "买什么", "人气", "热销", "爆款", "受欢迎"));
        INTENT_KEYWORDS.put("contact", List.of("客服", "联系", "电话", "微信", "怎么找", "人工", "售后"));
        INTENT_KEYWORDS.put("help", List.of("帮助", "功能", "你会", "你能", "做什么"));
        INTENT_KEYWORDS.put("thanks", List.of("谢谢", "感谢", "多谢", "谢了", "好的谢谢"));
        INTENT_KEYWORDS.put("bye", List.of("拜拜", "再见", "88", "bye", "下次", "拜"));
        INTENT_KEYWORDS.put("detail", List.of("详情", "介绍", "怎么样", "咋样", "是什么"));
        INTENT_KEYWORDS.put("admin", List.of("管理员", "后台", "管理", "登录管理员"));
    }

    public AgentService(GoodsService goodsService, OrderService orderService) {
        this.goodsService = goodsService;
        this.orderService = orderService;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Agent 主入口
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

            if (llmReply.contains("query_orders")) return executeQueryOrders(userId);
            if (llmReply.contains("query_goods")) return executeQueryGoods(message);
            if (llmReply.contains("query_goods_detail")) return executeQueryGoodsDetail(message);
            if (llmReply.contains("help")) return executeHelp();

            return executeUnknown();

        } catch (Exception e) {
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
        String json = "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":"
                + JSONStringEscape(prompt) + "}],\"max_tokens\":100}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.deepseek.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(java.time.Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

    // ==================== 关键词模式（增强版）====================

    private Map<String, Object> processWithKeywords(String message, Long userId) {
        String msg = message.trim().toLowerCase();

        // 获取多轮对话上下文
        List<Map<String, String>> history = AgentMemory.getHistory(userId);
        String lastAssistantMsg = "";
        String lastUserMsg = "";
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

        // 判断是否是追问（指代上一个回复中的商品）
        boolean isFollowUp = !lastAssistantMsg.isEmpty()
                && (msg.matches(".*(那这个|它|他|那个|这个|多少|贵|便宜|怎么样|咋样|介绍).*"));

        // 时间感知问候
        if (matchIntent(msg, "greet")) {
            return executeGreeting();
        }

        // 感谢 / 告别
        if (matchIntent(msg, "thanks")) {
            return executeThanks();
        }
        if (matchIntent(msg, "bye")) {
            return executeFarewell();
        }

        // 客服联系
        if (matchIntent(msg, "contact")) {
            return executeContact();
        }

        // 管理员信息
        if (matchIntent(msg, "admin")) {
            return executeAdminInfo();
        }

        // 订单统计（花了多少钱）
        if (matchIntent(msg, "order_stat")) {
            return executeOrderStats(userId);
        }

        // 订单查询
        if (matchIntent(msg, "order")) {
            return executeQueryOrders(userId);
        }

        // 商品推荐
        if (matchIntent(msg, "recommend")) {
            return executeRecommend();
        }

        // 价格区间查询
        if (matchIntent(msg, "price_range") || msg.matches(".*\\d+.*(块|元).*(以下|以上|以内).*")) {
            return executePriceRange(msg);
        }

        // 库存查询
        if (matchIntent(msg, "stock")) {
            return executeQueryStock(msg, lastAssistantMsg);
        }

        // 商品详情/介绍（追问或直接问）
        if (matchIntent(msg, "detail") || isFollowUp) {
            if (isFollowUp) {
                // 追问：从上下文捞商品
                return executeQueryGoodsDetail(lastAssistantMsg + lastUserMsg);
            }
            return executeQueryGoodsDetail(msg);
        }

        // 商品查询
        if (matchIntent(msg, "goods")) {
            return executeQueryGoods(msg);
        }

        // 纯数字/价格类问答（"多少钱"）
        if (msg.matches(".*(多少钱|价格|价位|多少[钱]?).*")) {
            if (isFollowUp) {
                return executeQueryGoodsDetail(lastAssistantMsg);
            }
            return executeQueryGoods(msg);
        }

        // 帮助
        if (matchIntent(msg, "help")) {
            return executeHelp();
        }

        // 兜底：看看是不是在问商品名
        Map<String, Object> fuzzyResult = tryFuzzyGoodsMatch(msg);
        if (fuzzyResult != null) return fuzzyResult;

        return executeUnknown();
    }

    /**
     * 判断消息是否匹配某个意图
     */
    private boolean matchIntent(String msg, String intent) {
        List<String> keywords = INTENT_KEYWORDS.get(intent);
        if (keywords == null) return false;
        for (String kw : keywords) {
            if (msg.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 尝试模糊匹配商品名（逐字匹配）
     */
    private Map<String, Object> tryFuzzyGoodsMatch(String msg) {
        List<Goods> allGoods = goodsService.getAllGoods();
        List<Goods> matched = new ArrayList<>();

        for (Goods g : allGoods) {
            String name = g.getName().toLowerCase();
            // 商品名包含消息中的关键词
            int overlap = 0;
            for (char c : msg.toCharArray()) {
                if (c < 'a' || c > 'z') continue; // 只匹配中文字符才有意义
            }
            // 中文字符匹配
            for (int i = 0; i < Math.min(msg.length(), 4); i++) {
                if (name.contains(String.valueOf(msg.charAt(i)))) {
                    overlap++;
                }
            }
            // 如果消息中超过一半的字符出现在商品名中，算匹配
            if (msg.length() > 1 && overlap >= Math.min(msg.length() / 2, 3)) {
                matched.add(g);
            }
            // 精确子串匹配
            if (name.contains(msg) || msg.contains(name)) {
                matched.add(g);
            }
        }

        if (matched.isEmpty()) return null;

        // 去重
        matched = matched.stream().distinct().collect(Collectors.toList());

        if (matched.size() == 1) {
            return executeQueryGoodsDetail(matched.get(0).getName());
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

        String reply = "您说的可能是以下商品：";
        for (Goods g : matched) {
            reply += "\n• " + g.getName() + " — ¥" + String.format("%.2f", g.getPrice());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        result.put("data", goodsList);
        result.put("intent", "goods_query");
        return result;
    }

    // ==================== 工具函数 ====================

    /**
     * 时间感知问候
     */
    private Map<String, Object> executeGreeting() {
        int hour = LocalTime.now().getHour();
        String timeGreeting;
        if (hour < 6) timeGreeting = "夜深了还没睡";
        else if (hour < 9) timeGreeting = "早上好";
        else if (hour < 12) timeGreeting = "上午好";
        else if (hour < 14) timeGreeting = "中午好";
        else if (hour < 18) timeGreeting = "下午好";
        else timeGreeting = "晚上好";

        String reply = timeGreeting + "！我是天方电竞 AI 助手，有什么可以帮您的？\n"
                + "您可以试试：\n"
                + "• \"有什么商品\" — 浏览商品\n"
                + "• \"我的订单\" — 查订单\n"
                + "• \"推荐一下\" — 看看推荐\n"
                + "• \"帮助\" — 查看全部功能";

        return Map.of("reply", reply, "intent", "help");
    }

    /**
     * 感谢回复
     */
    private Map<String, Object> executeThanks() {
        String[] replies = {
            "不客气！有任何需要随时找我 😊",
            "应该的～还有其他问题吗？",
            "不用谢！祝您购物愉快 🎉"
        };
        return Map.of("reply", replies[new Random().nextInt(replies.length)], "intent", "help");
    }

    /**
     * 联系客服
     */
    private Map<String, Object> executeContact() {
        String reply = "您可以这样联系我们：\n"
                + "📱 客服微信：15251889707\n"
                + "💬 在线 AI 助手：随时问我\n"
                + "⏰ 工作时间：9:00 - 22:00\n\n"
                + "下单后联系客服微信，发送订单号即可查询进度。";
        return Map.of("reply", reply, "intent", "help");
    }

    /**
     * 管理员信息
     */
    private Map<String, Object> executeAdminInfo() {
        String reply = "管理后台地址：/admin/login\n"
                + "管理员账号：15251889707\n"
                + "管理员密码：Hello2023\n\n"
                + "⚠️ 请勿在非受信环境泄露管理员信息";
        return Map.of("reply", reply, "intent", "help");
    }

    /**
     * 订单统计
     */
    private Map<String, Object> executeOrderStats(Long userId) {
        List<Order> orders = orderService.findByUserId(userId);
        if (orders.isEmpty()) {
            return Map.of("reply", "您还没有下过单呢，去首页逛逛吧～", "intent", "order_query");
        }
        double total = orders.stream().mapToDouble(Order::getTotalPrice).sum();
        int count = orders.size();

        // 找出最贵的一单
        Order maxOrder = orders.stream().max(Comparator.comparingDouble(Order::getTotalPrice)).orElse(null);
        String maxInfo = "";
        if (maxOrder != null) {
            Optional<Goods> g = goodsService.findById(maxOrder.getGoodsId());
            maxInfo = "最大一单："
                    + g.map(Goods::getName).orElse("商品") + " × " + maxOrder.getCount()
                    + " = ¥" + String.format("%.2f", maxOrder.getTotalPrice());
        }

        String reply = "📊 您的消费统计：\n"
                + "• 共 " + count + " 笔订单\n"
                + "• 累计消费 ¥" + String.format("%.2f", total) + "\n"
                + (!maxInfo.isEmpty() ? "• " + maxInfo : "");

        List<Map<String, Object>> orderList = orders.stream().map(order -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", order.getId());
            item.put("count", order.getCount());
            item.put("totalPrice", order.getTotalPrice());
            item.put("status", "已支付");
            item.put("time", order.getCreatedAt() != null ? order.getCreatedAt().toString().substring(0, 10) : "未知");
            Optional<Goods> goods = goodsService.findById(order.getGoodsId());
            item.put("goodsName", goods.map(Goods::getName).orElse("未知"));
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        result.put("data", orderList);
        result.put("intent", "order_query");
        return result;
    }

    /**
     * 商品推荐
     */
    private Map<String, Object> executeRecommend() {
        List<Goods> allGoods = goodsService.getAllGoods();

        // 按库存倒序排（库存多的当作热销）
        List<Goods> sorted = new ArrayList<>(allGoods);
        sorted.sort((a, b) -> Integer.compare(b.getStock(), a.getStock()));

        // 推荐库存最充足的 3 件
        List<Goods> recommended = sorted.size() > 3 ? sorted.subList(0, 3) : sorted;

        List<Map<String, Object>> goodsList = recommended.stream().map(g -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", g.getId());
            item.put("name", g.getName());
            item.put("price", g.getPrice());
            item.put("stock", g.getStock());
            item.put("image", g.getImageUrl());
            return item;
        }).collect(Collectors.toList());

        StringBuilder reply = new StringBuilder("🔥 热销推荐：\n");
        for (int i = 0; i < recommended.size(); i++) {
            Goods g = recommended.get(i);
            reply.append(i + 1).append(". ").append(g.getName())
                    .append(" — ¥").append(String.format("%.2f", g.getPrice()))
                    .append("（库存 ").append(g.getStock()).append(" 件）\n");
        }
        reply.append("\n点击商品查看详情和购买～");

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply.toString());
        result.put("data", goodsList);
        result.put("intent", "goods_query");
        return result;
    }

    /**
     * 价格区间查询
     */
    private Map<String, Object> executePriceRange(String msg) {
        List<Goods> allGoods = goodsService.getAllGoods();

        // 提取数字
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(msg);
        List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }

        double maxPrice = Double.MAX_VALUE;
        double minPrice = 0;

        if (msg.contains("以下") && !numbers.isEmpty()) {
            maxPrice = numbers.get(0);
        } else if (msg.contains("以上") && !numbers.isEmpty()) {
            minPrice = numbers.get(0);
        }
        // "最便宜" → 找最低价商品
        if (msg.contains("最便宜") || msg.contains("最低")) {
            Goods cheapest = allGoods.stream().min(Comparator.comparingDouble(Goods::getPrice)).orElse(null);
            if (cheapest != null) {
                return executeQueryGoodsDetail(cheapest.getName());
            }
        }
        // "最贵" → 找最高价商品
        if (msg.contains("最贵") || msg.contains("最高")) {
            Goods dearest = allGoods.stream().max(Comparator.comparingDouble(Goods::getPrice)).orElse(null);
            if (dearest != null) {
                return executeQueryGoodsDetail(dearest.getName());
            }
        }

        // 如果只有一个数字，默认作为上限
        if (numbers.size() == 1 && maxPrice == Double.MAX_VALUE) {
            maxPrice = numbers.get(0);
        }

        double finalMinPrice = minPrice;
        double finalMaxPrice = maxPrice;
        List<Goods> matched = allGoods.stream()
                .filter(g -> g.getPrice() >= finalMinPrice && g.getPrice() <= finalMaxPrice)
                .collect(Collectors.toList());

        if (matched.isEmpty()) {
            return Map.of("reply", "没有找到这个价位的商品，试试其他范围吧～", "intent", "goods_query");
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

        String rangeStr = minPrice > 0 ? "¥" + String.format("%.0f", minPrice) + " 以上" : "";
        rangeStr += maxPrice < Double.MAX_VALUE ? "¥" + String.format("%.0f", maxPrice) + " 以下" : "";
        String reply = "找到 " + matched.size() + " 件" + rangeStr + "的商品：\n";
        for (Goods g : matched) {
            reply += "• " + g.getName() + " — ¥" + String.format("%.2f", g.getPrice())
                    + "（库存 " + g.getStock() + "）\n";
        }

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        result.put("data", goodsList);
        result.put("intent", "goods_query");
        return result;
    }

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
            reply = "找到商品《" + g.getName() + "》\n"
                    + "• 售价：¥" + String.format("%.2f", g.getPrice()) + "\n"
                    + "• 库存：" + g.getStock() + " 件\n"
                    + "• 描述：" + g.getDescription();
        } else if (matched.size() < allGoods.size()) {
            reply = "找到 " + matched.size() + " 件相关商品：\n";
            for (Goods g : matched) {
                reply += "• " + g.getName() + " — ¥" + String.format("%.2f", g.getPrice()) + "\n";
            }
        } else {
            reply = "商城共有 " + allGoods.size() + " 件商品：\n";
            for (Goods g : allGoods) {
                reply += "• " + g.getName() + " — ¥" + String.format("%.2f", g.getPrice())
                        + "（库存 " + g.getStock() + "）\n";
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        result.put("data", goodsList);
        result.put("intent", "goods_query");
        return result;
    }

    private Map<String, Object> executeQueryGoodsDetail(String message) {
        List<Goods> allGoods = goodsService.getAllGoods();
        String msg = message.toLowerCase();

        // 尝试按商品名匹配
        for (Goods g : allGoods) {
            String name = g.getName().toLowerCase();
            if (name.contains(msg) || msg.contains(name)) {
                List<Map<String, Object>> goodsList = List.of(Map.of(
                    "id", g.getId(),
                    "name", g.getName(),
                    "price", g.getPrice(),
                    "stock", g.getStock(),
                    "image", g.getImageUrl()
                ));

                String reply = "《" + g.getName() + "》\n"
                        + "📝 " + g.getDescription() + "\n\n"
                        + "💰 价格：¥" + String.format("%.2f", g.getPrice()) + "\n"
                        + "📦 库存：" + g.getStock() + " 件\n\n"
                        + "点击下方查看详情即可购买～";

                Map<String, Object> result = new HashMap<>();
                result.put("reply", reply);
                result.put("data", goodsList);
                result.put("intent", "goods_query");
                return result;
            }
        }
        return executeQueryGoods(message);
    }

    private Map<String, Object> executeQueryStock(String message, String lastAssistantMsg) {
        List<Goods> allGoods = goodsService.getAllGoods();
        String msg = message.toLowerCase();

        // 先看看消息里有没有提到具体的商品名
        for (Goods g : allGoods) {
            if (msg.contains(g.getName().toLowerCase()) || g.getName().toLowerCase().contains(msg.replace("库存", "").trim())) {
                String reply = "《" + g.getName() + "》当前库存 " + g.getStock() + " 件"
                        + (g.getStock() < 10 ? "，库存不多，抓紧入手哦～" : "，库存充足。");
                return Map.of("reply", reply, "intent", "goods_query");
            }
        }

        // 检测上下文中的商品
        if (!lastAssistantMsg.isEmpty()) {
            for (Goods g : allGoods) {
                if (lastAssistantMsg.contains(g.getName())) {
                    String reply = "《" + g.getName() + "》当前库存 " + g.getStock() + " 件"
                            + (g.getStock() < 10 ? "，库存紧张！" : "，库存充足。");
                    return Map.of("reply", reply, "intent", "goods_query");
                }
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
        String reply = "您好！我是天方电竞 AI 助手，我可以帮您：\n\n"
                + "🛍️ 商品相关：\n"
                + "  \"有什么商品\" \"太空鹅多少钱\"\n"
                + "  \"100块以下的\" \"最便宜的\"\n"
                + "  \"推荐一下\" \"库存还有吗\"\n\n"
                + "📋 订单相关：\n"
                + "  \"我的订单\" \"花了多少钱\"\n"
                + "  \"我买过什么\"\n\n"
                + "💬 连续对话：\n"
                + "  问我一个商品后，可以接着问\n"
                + "  \"多少钱\" \"有货吗\" \"介绍\"\n\n"
                + "📞 其他：\n"
                + "  \"联系客服\" \"管理员\"";
        return Map.of("reply", reply, "intent", "help");
    }

    private Map<String, Object> executeFarewell() {
        return Map.of("reply", "好的，下次再来找我聊！祝您生活愉快 😊\n随时说 \"你好\" 就能唤醒我～", "intent", "help");
    }

    private Map<String, Object> executeUnknown() {
        String reply = "这个问题我还不太会回答 😅\n\n"
                + "您可以试试这些：\n"
                + "• \"有什么商品\" — 看商品列表\n"
                + "• \"推荐一下\" — 热销推荐\n"
                + "• \"我的订单\" — 查订单\n"
                + "• \"100块以下的\" — 按价格筛选\n"
                + "• \"帮助\" — 查看我能做什么";
        return Map.of("reply", reply, "intent", "unknown");
    }
}
