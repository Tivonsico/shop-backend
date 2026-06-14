package com.shop.controller;

import com.shop.model.Goods;
import com.shop.service.GoodsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 商品 API
 */
@RestController
@RequestMapping("/api/goods")
public class GoodsController {

    private final GoodsService goodsService;

    public GoodsController(GoodsService goodsService) {
        this.goodsService = goodsService;
    }

    /**
     * GET /api/goods/list
     *
     * 获取所有商品列表（带缓存）
     */
    @GetMapping("/list")
    public Map<String, Object> list() {
        List<Goods> goodsList = goodsService.getAllGoods();
        return Map.of("success", true, "data", goodsList);
    }

    /**
     * GET /api/goods/{id}
     *
     * 获取单个商品详情
     */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        Optional<Goods> goods = goodsService.findById(id);
        if (goods.isEmpty()) {
            return Map.of("success", false, "message", "商品不存在");
        }
        return Map.of("success", true, "data", goods.get());
    }
}
