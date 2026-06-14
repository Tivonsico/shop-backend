package com.shop.ai;

import com.shop.model.Goods;
import com.shop.model.Order;
import com.shop.model.User;
import com.shop.service.GoodsService;
import com.shop.service.OrderService;
import com.shop.service.UserService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 服务
 *
 * 模拟 AI Agent 的工作流程：
 *   1. 识别用户意图（Intent Recognition）
 *   2. 调用对应工具获取数据（Function Calling）
 *   3. 组装回复（Response Generation）
 *
 * 接入真实 LLM 后，只需要把步骤 1 和步骤 3 换成大模型调用即可。
 */
@Service
public class AIService {

    private final GoodsService goodsService;
    private final OrderService orderService;
    private final UserService userService;

    public AIService(GoodsService goodsService, OrderService orderService, UserService userService) {
        this.goodsService = goodsService;
        this.orderService = orderService;
        this.userService = userService;
    }

    /**
     * 处理用户消息，返回 AI 回复
     */
    public Map<String, Object> chat(String message, Long userId) {
        String intent = recognizeIntent(message);
        return switch (intent) {
            case "order_query" -> handleOrderQuery(userId);
            case "goods_query" -> handleGoodsQuery(message);
            case "help" -> handleHelp();
            default -> handleUnknown();
        };
    }

    /**
     * 意图识别（模拟 LLM Function Calling）
     *
     * 真实场景：这里调大模型做意图分类
     * 当前方案：关键词匹配
     */
    private String recognizeIntent(String message) {
        String msg = message.toLowerCase();
        if (msg.contains("订单") || msg.contains("订单") || msg.contains("买") || msg.contains("购买")) {
            return "order_query";
        }
        if (msg.contains("商品") || msg.contains("东西") || msg.contains("卖") || msg.contains("有")) {
            return "goods_query";
        }
        if (msg.contains("帮助") || msg.contains("你好") || msg.contains("功能") || msg.contains("能")) {
            return "help";
        }
        return "unknown";
    }

    /**
     * 处理订单查询（RAG：从数据库检索真实数据）
     */
    private Map<String, Object> handleOrderQuery(Long userId) {
        List<Order> orders = orderService.findByUserId(userId);
        List<Map<String, Object>> orderList = orders.stream().map(order -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", order.getId());
            item.put("count", order.getCount());
            item.put("totalPrice", order.getTotalPrice());
            item.put("status", order.getStatus());
            item.put("time", order.getCreatedAt() != null ? order.getCreatedAt().toString() : "未知");

            Optional<Goods> goods = goodsService.findById(order.getGoodsId());
            item.put("goodsName", goods.map(Goods::getName).orElse("未知商品"));
            return item;
        }).collect(Collectors.toList());

        String reply;
        if (orderList.isEmpty()) {
            reply = "您目前还没有订单，快去首页逛逛吧～";
        } else {
            double totalSpent = orders.stream().mapToDouble(Order::getTotalPrice).sum();
            reply = "您有 " + orderList.size() + " 笔订单，累计消费 ¥" + String.format("%.2f", totalSpent);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        result.put("data", orderList);
        result.put("intent", "order_query");
        return result;
    }

    /**
     * 处理商品查询（RAG：从数据库检索真实数据）
     */
    private Map<String, Object> handleGoodsQuery(String message) {
        List<Goods> allGoods = goodsService.getAllGoods();

        // 如果消息包含商品名称关键词，尝试搜索
        List<Goods> matched = allGoods;
        for (Goods g : allGoods) {
            if (message.contains(g.getName()) || message.contains(g.getName().substring(0, Math.min(2, g.getName().length())))) {
                matched = List.of(g);
                break;
            }
        }

        List<Map<String, Object>> goodsList = matched.stream().map(g -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", g.getId());
            item.put("name", g.getName());
            item.put("price", g.getPrice());
            item.put("stock", g.getStock());
            item.put("description", g.getDescription());
            return item;
        }).collect(Collectors.toList());

        String reply;
        if (goodsList.size() == 1) {
            Goods g = matched.get(0);
            reply = "《" + g.getName() + "》售价 ¥" + String.format("%.2f", g.getPrice()) + "，库存 " + g.getStock() + " 件。" + g.getDescription();
        } else {
            reply = "商城共有 " + goodsList.size() + " 件商品，价格从 ¥" +
                    String.format("%.2f", allGoods.stream().mapToDouble(Goods::getPrice).min().orElse(0)) +
                    " 到 ¥" + String.format("%.2f", allGoods.stream().mapToDouble(Goods::getPrice).max().orElse(0)) + " 不等。";
        }

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        result.put("data", goodsList);
        result.put("intent", "goods_query");
        return result;
    }

    private Map<String, Object> handleHelp() {
        String reply = "您好！我是天方电竞 AI 助手，您可以问我：\n" +
                "• \"有什么商品\" — 查看商品列表\n" +
                "• \"我的订单\" — 查询我的订单\n" +
                "• 其他问题我会尽力解答～";

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        result.put("intent", "help");
        return result;
    }

    private Map<String, Object> handleUnknown() {
        String reply = "抱歉我还没理解您的意思。您可以试试问\"有什么商品\"或\"我的订单\"。";

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        result.put("intent", "unknown");
        return result;
    }
}
