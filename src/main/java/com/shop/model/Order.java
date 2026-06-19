package com.shop.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 订单模型
 */
@Entity
@Table(name = "shop_order")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;              // 订单ID

    @Column(nullable = false)
    private Long userId;          // 下单用户的ID

    @Column(nullable = false)
    private Long goodsId;         // 商品ID

    @Column(nullable = false)
    private Integer count;        // 购买数量

    @Column(nullable = false)
    private Double totalPrice;    // 总价

    @Column(length = 20)
    private String status;        // 状态：UNPAID / PAID / CANCELLED

    @Column(length = 20)
    private String paymentMethod; // 支付方式：WECHAT / ALIPAY

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); if (status == null) status = "UNPAID"; }

    // --- getter / setter ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getGoodsId() { return goodsId; }
    public void setGoodsId(Long goodsId) { this.goodsId = goodsId; }

    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }

    public Double getTotalPrice() { return totalPrice; }
    public void setTotalPrice(Double totalPrice) { this.totalPrice = totalPrice; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
