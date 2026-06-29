package com.shop.service;

import com.shop.model.Order;
import com.shop.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务：自动取消超时未支付的订单
 */
@Service
public class OrderCleanupService {

    private static final Logger log = LoggerFactory.getLogger(OrderCleanupService.class);

    private final OrderRepository orderRepository;

    public OrderCleanupService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * 每小时执行一次，取消超过24小时未支付的订单
     */
    @Scheduled(fixedRate = 3600000) // 1小时 = 3600000ms
    public void cancelExpiredOrders() {
        try {
            LocalDateTime deadline = LocalDateTime.now().minusHours(24);
            List<Order> expired = orderRepository.findUnpaidBefore(deadline);

            if (expired.isEmpty()) return;

            int count = 0;
            for (Order order : expired) {
                order.setStatus("CANCELLED");
                orderRepository.save(order);
                count++;
            }

            log.info("自动取消超时订单: {} 个", count);
        } catch (Exception e) {
            log.error("自动取消超时订单失败", e);
        }
    }
}
