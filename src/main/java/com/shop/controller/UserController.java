package com.shop.controller;

import com.shop.model.User;
import com.shop.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * 用户 API
 *
 * @RestController = 这是一个 API 控制器
 * @RequestMapping("/api/user") = 所有接口都以 /api/user 开头
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * POST /api/user/register
     *
     * 请求体（JSON）：
     *   { "username": "张三", "password": "123456", "phone": "138xxxx" }
     *
     * 返回：
     *   成功 → { "success": true, "data": { "id": 1, "username": "张三", ... } }
     *   失败 → { "success": false, "message": "用户名已存在" }
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String phone = body.get("phone");

        User user = userService.register(username, password, phone);
        if (user == null) {
            return Map.of("success", false, "message", "用户名已存在");
        }
        return Map.of("success", true, "data", user);
    }

    /**
     * POST /api/user/login
     *
     * 请求体：{ "username": "张三", "password": "123456" }
     *
     * 返回：
     *   成功 → { "success": true, "data": { "id": 1, "username": "张三" } }
     *   失败 → { "success": false, "message": "用户名或密码错误" }
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        User user = userService.login(username, password);
        if (user == null) {
            return Map.of("success", false, "message", "用户名或密码错误");
        }
        return Map.of("success", true, "data", user);
    }

    /**
     * GET /api/user/{id}
     *
     * 根据ID查用户
     */
    @GetMapping("/{id}")
    public Map<String, Object> getUser(@PathVariable Long id) {
        Optional<User> user = userService.findById(id);
        if (user.isEmpty()) {
            return Map.of("success", false, "message", "用户不存在");
        }
        return Map.of("success", true, "data", user.get());
    }
}
