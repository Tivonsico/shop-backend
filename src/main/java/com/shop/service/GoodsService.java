package com.shop.service;

import com.shop.model.Goods;
import com.shop.repository.GoodsRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 商品业务逻辑
 */
@Service
public class GoodsService {

    private final GoodsRepository goodsRepository;

    public GoodsService(GoodsRepository goodsRepository) {
        this.goodsRepository = goodsRepository;
    }

    /**
     * 获取所有商品
     *
     * @Cacheable = 把结果缓存起来
     *   第一次调用时查数据库，结果存到缓存
     *   第二次调用时直接返回缓存，不再查数据库
     *   "goodsList" 是缓存的名字
     */
    @Cacheable("goodsList")
    public List<Goods> getAllGoods() {
        // 模拟慢查询（第一次会慢，缓存后秒回）
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        return goodsRepository.findAll();
    }

    public Optional<Goods> findById(Long id) {
        return goodsRepository.findById(id);
    }

    /**
     * 保存商品（新增或修改）
     * @CacheEvict = 清空缓存，下次查商品列表时重新查数据库
     * beforeInvocation = true  = 即使保存失败也先清缓存，保证数据一致性
     */
    @CacheEvict(value = "goodsList", allEntries = true, beforeInvocation = true)
    public Goods save(Goods goods) {
        return goodsRepository.save(goods);
    }

    /**
     * 删除商品
     */
    @CacheEvict(value = "goodsList", allEntries = true, beforeInvocation = true)
    public void deleteById(Long id) {
        goodsRepository.deleteById(id);
    }
}
