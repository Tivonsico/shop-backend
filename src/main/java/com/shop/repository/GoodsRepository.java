package com.shop.repository;

import com.shop.model.Goods;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GoodsRepository extends JpaRepository<Goods, Long> {
    // 直接用 findAll() 就能拿到所有商品
}
