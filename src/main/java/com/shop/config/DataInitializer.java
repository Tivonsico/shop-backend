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
        g1.setDescription("全铜打造，精致工艺");
        g1.setImageUrl("https://qcloudimg.tencent-cloud.cn/raw/063123361b3a397f4ba6894591c3a006.png");
        goodsRepository.save(g1);

        Goods g2 = new Goods();
        g2.setName("随身蓝牙无线音箱");
        g2.setPrice(100.0);
        g2.setStock(50);
        g2.setDescription("小型便携式迷你户外音箱");
        g2.setImageUrl("https://qcloudimg.tencent-cloud.cn/raw/7b2c975b21d24c43f1609e0b0328dccf.png");
        goodsRepository.save(g2);

        Goods g3 = new Goods();
        g3.setName("软底一脚蹬小白鞋");
        g3.setPrice(300.0);
        g3.setStock(200);
        g3.setDescription("休闲棉加绒，显脚瘦");
        g3.setImageUrl("https://qcloudimg.tencent-cloud.cn/raw/62eb1d8d8ea3b05302c199636f787438.png");
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
