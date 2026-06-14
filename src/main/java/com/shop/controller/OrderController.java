package com.shop.controller;

import com.shop.model.Order;
import com.shop.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单 API
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * POST /api/order/create
     *
     * 请求体：{ "userId": 1, "goodsId": 1, "count": 2 }
     */
    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Long userId = Long.valueOf(body.get("userId").toString());
        Long goodsId = Long.valueOf(body.get("goodsId").toString());
        Integer count = Integer.valueOf(body.get("count").toString());

        Order order = orderService.createOrder(userId, goodsId, count);
        if (order == null) {
            return Map.of("success", false, "message", "商品不存在或库存不足");
        }
        return Map.of("success", true, "data", order);
    }

    /**
     * GET /api/order/{id}
     *
     * 查某个订单
     */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        Optional<Order> order = orderService.findById(id);
        if (order.isEmpty()) {
            return Map.of("success", false, "message", "订单不存在");
        }
        return Map.of("success", true, "data", order.get());
    }

    /**
     * GET /api/order/user/{userId}
     *
     * 查某个用户的所有订单
     */
    @GetMapping("/user/{userId}")
    public Map<String, Object> userOrders(@PathVariable Long userId) {
        List<Order> orders = orderService.findByUserId(userId);
        return Map.of("success", true, "data", orders);
    }
}
