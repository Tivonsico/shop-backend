package com.shop.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 支付记录
 * 记录每次支付请求和结果，与订单是一对一关系
 */
@Entity
@Table(name = "payment_record")
public class PaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long orderId;          // 订单 ID

    @Column(length = 20)
    private String method;         // 支付方式：WECHAT / ALIPAY

    @Column(length = 64)
    private String tradeNo;        // 支付平台交易号（支付成功后有）

    @Column(length = 512)
    private String prepayId;       // 预支付标识 / code_url

    @Column(length = 20)
    private String status;         // UNPAID / PAID / CLOSED

    @Column(columnDefinition = "TEXT")
    private String notifyRaw;      // 回调原始数据（JSON）

    @Column(length = 512)
    private String errMsg;         // 错误信息（如果创建失败）

    private LocalDateTime createdAt;
    private LocalDateTime paidAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = "UNPAID";
    }

    // --- getter / setter ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getTradeNo() { return tradeNo; }
    public void setTradeNo(String tradeNo) { this.tradeNo = tradeNo; }

    public String getPrepayId() { return prepayId; }
    public void setPrepayId(String prepayId) { this.prepayId = prepayId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNotifyRaw() { return notifyRaw; }
    public void setNotifyRaw(String notifyRaw) { this.notifyRaw = notifyRaw; }

    public String getErrMsg() { return errMsg; }
    public void setErrMsg(String errMsg) { this.errMsg = errMsg; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
}