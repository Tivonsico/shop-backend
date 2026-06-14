package com.shop.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户模型
 *
 * 对应数据库里的 user 表
 * 每个字段就是表里的一列
 */
@Entity
@Table(name = "shop_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;              // 用户ID（自动生成）

    @Column(nullable = false, unique = true, length = 50)
    private String username;      // 用户名

    @Column(nullable = false)
    private String password;      // 密码

    @Column(length = 20)
    private String phone;         // 手机号

    @Column(length = 100)
    private String address;       // 地址

    private LocalDateTime createdAt;  // 创建时间

    // 在插入数据前自动设置创建时间
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // --- getter / setter ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
