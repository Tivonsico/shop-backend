package com.shop.controller;

import com.shop.model.Goods;
import com.shop.model.Order;
import com.shop.model.User;
import com.shop.service.GoodsService;
import com.shop.service.OrderService;
import com.shop.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final GoodsService goodsService;
    private final OrderService orderService;
    private final UserService userService;

    public AdminController(GoodsService goodsService, OrderService orderService, UserService userService) {
        this.goodsService = goodsService;
        this.orderService = orderService;
        this.userService = userService;
    }

    // ========== 登录 ==========

    @GetMapping("/login")
    public String loginPage(HttpSession session, Model model) {
        Object error = session.getAttribute("adminError");
        if (error != null) {
            model.addAttribute("error", error);
            session.removeAttribute("adminError");
        }
        return "admin/login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String username,
                        @RequestParam String password,
                        HttpSession session) {
        if ("admin".equals(username) && "admin123".equals(password)) {
            session.setAttribute("adminLogin", true);
            return "redirect:/admin/goods";
        }
        session.setAttribute("adminError", "账号或密码错误");
        return "redirect:/admin/login";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.removeAttribute("adminLogin");
        return "redirect:/admin/login";
    }

    // ========== 商品管理 ==========

    @GetMapping("/goods")
    public String goodsList(Model model, HttpSession session) {
        if (!isAdmin(session)) return "redirect:/admin/login";
        model.addAttribute("goodsList", goodsService.getAllGoods());
        return "admin/goods-list";
    }

    @GetMapping("/goods/add")
    public String goodsAddPage(HttpSession session, Model model) {
        if (!isAdmin(session)) return "redirect:/admin/login";
        model.addAttribute("isEdit", false);
        model.addAttribute("actionUrl", "/admin/goods/add");
        return "admin/goods-form";
    }

    @PostMapping("/goods/add")
    public String goodsAdd(@RequestParam String name,
                           @RequestParam String description,
                           @RequestParam Double price,
                           @RequestParam Integer stock,
                           @RequestParam(required = false) MultipartFile imageFile,
                           HttpSession session) {
        if (!isAdmin(session)) return "redirect:/admin/login";

        try {
            Goods goods = new Goods();
            goods.setName(name);
            goods.setDescription(description);
            goods.setPrice(price);
            goods.setStock(stock);

            if (imageFile != null && !imageFile.isEmpty()) {
                String fileName = saveImage(imageFile);
                goods.setImageUrl("/uploads/" + fileName);
            } else {
                goods.setImageUrl("https://qcloudimg.tencent-cloud.cn/raw/063123361b3a397f4ba6894591c3a006.png");
            }

            goodsService.save(goods);
        } catch (Exception e) {
            log.error("添加商品失败", e);
        }
        return "redirect:/admin/goods";
    }

    @GetMapping("/goods/edit/{id}")
    public String goodsEditPage(@PathVariable Long id, Model model, HttpSession session) {
        if (!isAdmin(session)) return "redirect:/admin/login";
        Optional<Goods> goods = goodsService.findById(id);
        if (goods.isEmpty()) return "redirect:/admin/goods";
        model.addAttribute("goods", goods.get());
        model.addAttribute("isEdit", true);
        model.addAttribute("actionUrl", "/admin/goods/edit/" + id);
        return "admin/goods-form";
    }

    @PostMapping("/goods/edit/{id}")
    public String goodsEdit(@PathVariable Long id,
                            @RequestParam String name,
                            @RequestParam String description,
                            @RequestParam Double price,
                            @RequestParam Integer stock,
                            @RequestParam(required = false) MultipartFile imageFile,
                            HttpSession session) {
        if (!isAdmin(session)) return "redirect:/admin/login";

        try {
            Optional<Goods> goodsOpt = goodsService.findById(id);
            if (goodsOpt.isEmpty()) return "redirect:/admin/goods";

            Goods g = goodsOpt.get();
            g.setName(name);
            g.setDescription(description);
            g.setPrice(price);
            g.setStock(stock);

            if (imageFile != null && !imageFile.isEmpty()) {
                String fileName = saveImage(imageFile);
                g.setImageUrl("/uploads/" + fileName);
            }

            goodsService.save(g);
        } catch (Exception e) {
            log.error("编辑商品失败", e);
        }
        return "redirect:/admin/goods";
    }

    @PostMapping("/goods/delete/{id}")
    public String goodsDelete(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return "redirect:/admin/login";
        goodsService.deleteById(id);
        return "redirect:/admin/goods";
    }

    // ========== 订单管理 ==========

    @GetMapping("/orders")
    public String orderList(Model model, HttpSession session) {
        if (!isAdmin(session)) return "redirect:/admin/login";
        List<Order> orders = orderService.findAll();
        model.addAttribute("orders", orders);
        model.addAttribute("goodsService", goodsService);
        return "admin/order-list";
    }

    // ========== 用户管理 ==========

    @GetMapping("/users")
    public String userList(Model model, HttpSession session) {
        if (!isAdmin(session)) return "redirect:/admin/login";
        List<User> users = userService.findAll();
        model.addAttribute("users", users);
        model.addAttribute("orderService", orderService);
        return "admin/user-list";
    }

    // ========== 工具方法 ==========

    private boolean isAdmin(HttpSession session) {
        return session.getAttribute("adminLogin") != null;
    }

    private String saveImage(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf("."));
        }
        String fileName = UUID.randomUUID().toString() + ext;

        String uploadDir = System.getProperty("user.dir") + "/uploads/";
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File dest = new File(uploadDir + fileName);
        file.transferTo(dest);

        return fileName;
    }
}
