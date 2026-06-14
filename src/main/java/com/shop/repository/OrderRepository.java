package com.shop.repository;

import com.shop.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // 根据用户ID查该用户的所有订单
    List<Order> findByUserId(Long userId);
}
