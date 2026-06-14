package com.shop.repository;

import com.shop.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户数据访问层
 *
 * JpaRepository<User, Long> 的意思是：
 *   - 操作 User 这个模型
 *   - 主键是 Long 类型
 *
 * 不需要写任何实现代码，Spring 自动提供：
 *   save()、findById()、findAll()、delete() 等方法
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // 根据用户名查用户（登录时用到）
    Optional<User> findByUsername(String username);
}
