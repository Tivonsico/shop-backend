package com.shop.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/**
 * 数据库表结构更新
 * 用于 payment_record 表创建后修改字段长度
 */
@Component
public class SchemaUpdater {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpdater.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaUpdater(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void updateSchema() {
        try {
            // 扩大 prepay_id 字段长度（存支付宝跳转URL需要）
            jdbcTemplate.execute("ALTER TABLE payment_record ALTER COLUMN prepay_id VARCHAR(2048)");
            log.info("Schema: payment_record.prepay_id 已扩展至 2048 ✓");
        } catch (Exception e) {
            // 可能字段已经够长，忽略
        }

        try {
            // 扩大 err_msg 字段长度
            jdbcTemplate.execute("ALTER TABLE payment_record ALTER COLUMN err_msg VARCHAR(2048)");
            log.info("Schema: payment_record.err_msg 已扩展至 2048 ✓");
        } catch (Exception e) {
            // 忽略
        }
    }
}