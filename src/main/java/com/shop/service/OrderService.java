package com.shop.service;

import com.shop.model.Goods;
import com.shop.model.Order;
import com.shop.repository.OrderRepository;
import org.springframework.stereotype.Service;

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
     * 下单
     *
     * @return 如果商品不存在或库存不足，返回 null
     */
    public Order createOrder(Long userId, Long goodsId, Integer count) {
        // 0. 限制单次最多购买 100 个
        if (count > 100) count = 100;
        if (count < 1) return null;

        // 1. 检查商品是否存在
        Optional<Goods> goodsOpt = goodsService.findById(goodsId);
        if (goodsOpt.isEmpty()) {
            return null;  // 商品不存在
        }

        Goods goods = goodsOpt.get();

        // 2. 检查库存
        if (goods.getStock() < count) {
            return null;  // 库存不足
        }

        // 3. 扣减库存
        goods.setStock(goods.getStock() - count);
        goodsService.save(goods);

        // 4. 创建订单
        Order order = new Order();
        order.setUserId(userId);
        order.setGoodsId(goodsId);
        order.setCount(count);
        order.setTotalPrice(goods.getPrice() * count);

        return orderRepository.save(order);
    }

    /**
     * 查所有订单（管理后台用）
     */
    public List<Order> findAll() {
        return orderRepository.findAll();
    }

    /**
     * 计算某个用户的下单总金额（管理后台用）
     */
    public Double getUserTotalSpent(Long userId) {
        List<Order> orders = orderRepository.findByUserId(userId);
        return orders.stream()
                .mapToDouble(Order::getTotalPrice)
                .sum();
    }

    /**
     * 根据ID查订单
     */
    public Optional<Order> findById(Long id) {
        return orderRepository.findById(id);
    }

    /**
     * 查某个用户的所有订单
     */
    public List<Order> findByUserId(Long userId) {
        return orderRepository.findByUserId(userId);
    }
}
