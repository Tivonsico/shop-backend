package com.shop.service;

import com.shop.model.Goods;
import com.shop.model.Order;
import com.shop.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 订单业务逻辑
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final GoodsService goodsService;

    public OrderService(OrderRepository orderRepository, GoodsService goodsService) {
        this.orderRepository = orderRepository;
        this.goodsService = goodsService;
    }

    /**
     * 创建订单（未支付，不扣库存）
     *
     * @return 如果商品不存在或库存不足，返回 null
     */
    public Order createOrder(Long userId, Long goodsId, Integer count) {
        if (count > 100) count = 100;
        if (count < 1) return null;

        Optional<Goods> goodsOpt = goodsService.findById(goodsId);
        if (goodsOpt.isEmpty()) return null;

        Goods goods = goodsOpt.get();
        if (goods.getStock() < count) return null;

        Order order = new Order();
        order.setUserId(userId);
        order.setGoodsId(goodsId);
        order.setCount(count);
        order.setTotalPrice(goods.getPrice() * count);
        order.setStatus("UNPAID");

        return orderRepository.save(order);
    }

    /**
     * 确认支付：扣库存 + 修改状态为 PAID
     * 由支付回调触发
     */
    @Transactional
    public boolean confirmPayment(Long orderId) {
        Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) return false;

        Order order = orderOpt.get();
        if (!"UNPAID".equals(order.getStatus())) return false;

        Optional<Goods> goodsOpt = goodsService.findById(order.getGoodsId());
        if (goodsOpt.isEmpty()) return false;

        Goods goods = goodsOpt.get();
        if (goods.getStock() < order.getCount()) return false;

        goods.setStock(goods.getStock() - order.getCount());
        goodsService.save(goods);

        order.setStatus("PAID");
        orderRepository.save(order);
        return true;
    }

    /**
     * 取消订单（把 UNPAID 订单取消）
     */
    public boolean cancelOrder(Long orderId) {
        Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) return false;

        Order order = orderOpt.get();
        if (!"UNPAID".equals(order.getStatus())) return false;

        order.setStatus("CANCELLED");
        orderRepository.save(order);
        return true;
    }

    public List<Order> findAll() {
        return orderRepository.findAll();
    }

    public Double getUserTotalSpent(Long userId) {
        List<Order> orders = orderRepository.findByUserId(userId);
        return orders.stream()
                .mapToDouble(Order::getTotalPrice)
                .sum();
    }

    public Optional<Order> findById(Long id) {
        return orderRepository.findById(id);
    }

    public List<Order> findByUserId(Long userId) {
        return orderRepository.findByUserId(userId);
    }

    public Order save(Order order) {
        return orderRepository.save(order);
    }
}