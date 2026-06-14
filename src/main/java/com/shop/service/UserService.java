package com.shop.service;

import com.shop.model.User;
import com.shop.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 用户业务逻辑
 *
 * @Service = 这是 Spring 的一个服务类
 * 控制层（Controller）调这里，这里调数据层（Repository）
 */
@Service
public class UserService {

    // Spring 自动注入 UserRepository（不用你自己 new）
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 注册
     * 如果用户名已存在，返回 null
     * 否则保存用户，返回保存后的用户
     */
    public User register(String username, String password, String phone) {
        // 先查一下用户名有没有被注册过
        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            return null;  // 用户名已存在
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(password);  // 简单版，不搞加密
        user.setPhone(phone);
        return userRepository.save(user);
    }

    /**
     * 登录
     * 用户名密码都对 → 返回用户
     * 不对 → 返回 null
     */
    public User login(String username, String password) {
        Optional<User> user = userRepository.findByUsername(username);
        if (user.isPresent() && user.get().getPassword().equals(password)) {
            return user.get();
        }
        return null;
    }

    /**
     * 查所有用户（管理后台用）
     */
    public List<User> findAll() {
        return userRepository.findAll();
    }

    /**
     * 根据ID查用户
     */
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }
}
