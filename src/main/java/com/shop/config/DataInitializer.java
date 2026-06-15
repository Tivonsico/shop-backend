package com.shop.config;

import com.shop.model.Goods;
import com.shop.model.User;
import com.shop.repository.GoodsRepository;
import com.shop.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 数据初始化器
 *
 * 项目启动时自动往数据库里插入测试数据
 * 这样你启动后就能直接测试 API，不用手动插数据
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final GoodsRepository goodsRepository;
    private final UserRepository userRepository;

    public DataInitializer(GoodsRepository goodsRepository, UserRepository userRepository) {
        this.goodsRepository = goodsRepository;
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        // 如果数据库里已经有数据了就不插了
        if (goodsRepository.count() > 0) {
            return;
        }

        // 插入测试商品
        Goods g1 = new Goods();
        g1.setName("腾讯QQ正版铜工艺品太空鹅");
        g1.setPrice(190.0);
        g1.setStock(100);
        g1.setDescription("全铜打造，精致工艺，面试字节必备吉祥物");
        g1.setImageUrl("https://qcloudimg.tencent-cloud.cn/raw/063123361b3a397f4ba6894591c3a006.png");
        goodsRepository.save(g1);

        Goods g2 = new Goods();
        g2.setName("Java 面试高分宝典");
        g2.setPrice(79.0);
        g2.setStock(42);
        g2.setDescription("从 Spring Boot 到 JVM 调优，面字节够用了");
        g2.setImageUrl("https://picsum.photos/seed/java/400/400");
        goodsRepository.save(g2);

        Goods g3 = new Goods();
        g3.setName("程序员颈椎按摩仪");
        g3.setPrice(259.0);
        g3.setStock(88);
        g3.setDescription("写码写累了？躺平也能拿 Offer");
        g3.setImageUrl("https://picsum.photos/seed/neck/400/400");
        goodsRepository.save(g3);

        // 插入一个测试用户
        User user = new User();
        user.setUsername("test");
        user.setPassword("123456");
        user.setPhone("13800138000");
        userRepository.save(user);

        System.out.println("===== 测试数据已插入 =====");
        System.out.println("测试账号：test / 123456");
        System.out.println("商品数量：" + goodsRepository.count());
    }
}
