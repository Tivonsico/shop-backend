package com.shop.repository;

import com.shop.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // 根据用户ID查该用户的所有订单
    List<Order> findByUserId(Long userId);

    // 查超时的未支付订单（createdAt 早于指定时间）
    @Query("SELECT o FROM Order o WHERE o.status = 'UNPAID' AND o.createdAt < :time")
    List<Order> findUnpaidBefore(@Param("time") LocalDateTime time);
}
