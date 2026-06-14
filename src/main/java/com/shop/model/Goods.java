package com.shop.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 商品模型
 */
@Entity
@Table(name = "shop_goods")
public class Goods {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;              // 商品ID

    @Column(nullable = false)
    private String name;          // 商品名称

    @Column(length = 2000)
    private String description;   // 商品描述

    @Column(nullable = false)
    private Double price;         // 价格

    private Integer stock;        // 库存

    @Column(length = 500)
    private String imageUrl;      // 图片地址

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    // --- getter / setter ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
