package com.shop.controller;

import com.shop.model.Goods;
import com.shop.model.Order;
import com.shop.model.User;
import com.shop.service.GoodsService;
import com.shop.service.OrderService;
import com.shop.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 页面控制器
 *
 * @Controller = 返回网页（不是 JSON）
 * 这里的每个方法返回一个 HTML 页面
 */
@Controller
public class PageController {

    private final GoodsService goodsService;
    private final OrderService orderService;
    private final UserService userService;

    public PageController(GoodsService goodsService, OrderService orderService, UserService userService) {
        this.goodsService = goodsService;
        this.orderService = orderService;
        this.userService = userService;
    }

    /**
     * 首页：显示所有商品
     */
    @GetMapping("/")
    public String index(Model model) {
        List<Goods> goodsList = goodsService.getAllGoods();
        model.addAttribute("goodsList", goodsList);
        return "index";
    }

    /**
     * 登录页
     */
    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    /**
     * 处理登录
     */
    @PostMapping("/login")
    public String login(@RequestParam String username,
                        @RequestParam String password,
                        HttpSession session,
                        Model model) {
        User user = userService.login(username, password);
        if (user == null) {
            model.addAttribute("error", "用户名或密码错误");
            return "login";
        }
        session.setAttribute("userId", user.getId());
        session.setAttribute("username", user.getUsername());
        return "redirect:/";
    }

    /**
     * 注册页
     */
    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    /**
     * 处理注册
     */
    @PostMapping("/register")
    public String register(@RequestParam String username,
                           @RequestParam String password,
                           @RequestParam(required = false) String phone,
                           Model model) {
        User user = userService.register(username, password, phone);
        if (user == null) {
            model.addAttribute("error", "用户名已存在");
            return "register";
        }
        return "redirect:/login";
    }

    /**
     * 退出登录
     */
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/";
    }

    /**
     * 商品详情页
     */
    @GetMapping("/goods/{id}")
    public String goodsDetail(@PathVariable Long id, Model model) {
        Optional<Goods> goods = goodsService.findById(id);
        if (goods.isEmpty()) {
            return "redirect:/";
        }
        model.addAttribute("goods", goods.get());
        return "goods-detail";
    }

    /**
     * 我的订单页
     */
    @GetMapping("/orders")
    public String orders(HttpSession session, Model model) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return "redirect:/login";
        }
        List<Order> orderList = orderService.findByUserId(userId);
        // 把商品信息也查出来，展示用
        model.addAttribute("orderList", orderList);
        model.addAttribute("goodsService", goodsService);
        return "orders";
    }

    /**
     * 下单处理
     */
    @PostMapping("/order/create")
    public String createOrder(@RequestParam Long goodsId,
                              @RequestParam Integer count,
                              HttpSession session,
                              Model model) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return "redirect:/login";
        }

        Order order = orderService.createOrder(userId, goodsId, count);
        if (order == null) {
            model.addAttribute("error", "商品不存在或库存不足");
            Optional<Goods> goods = goodsService.findById(goodsId);
            goods.ifPresent(g -> model.addAttribute("goods", g));
            return "goods-detail";
        }

        return "redirect:/orders";
    }
}
