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

    @GetMapping("/")
    public String index(Model model, HttpSession session) {
        // 标记该浏览器访问过首页（管理员登录需要此标记）
        session.setAttribute("siteAccess", true);
        List<Goods> goodsList = goodsService.getAllGoods();
        model.addAttribute("goodsList", goodsList);
        return "index";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

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

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

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

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/";
    }

    @GetMapping("/goods/{id}")
    public String goodsDetail(@PathVariable Long id,
                              @RequestParam(required = false) String error,
                              Model model) {
        Optional<Goods> goods = goodsService.findById(id);
        if (goods.isEmpty()) {
            return "redirect:/";
        }
        model.addAttribute("goods", goods.get());
        if ("stock".equals(error)) {
            model.addAttribute("error", "库存不足，当前库存仅 " + goods.get().getStock() + " 件");
        }
        return "goods-detail";
    }

    @GetMapping("/orders")
    public String orders(HttpSession session, Model model) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return "redirect:/login";
        }
        List<Order> orderList = orderService.findByUserId(userId);
        model.addAttribute("orderList", orderList);
        model.addAttribute("goodsService", goodsService);
        return "orders";
    }

    @PostMapping("/order/create")
    public String createOrder(@RequestParam Long goodsId,
                              @RequestParam Integer count,
                              HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return "redirect:/login";
        }
        Order order = orderService.createOrder(userId, goodsId, count);
        if (order == null) {
            return "redirect:/goods/" + goodsId + "?error=stock";
        }
        return "redirect:/orders";
    }
}
