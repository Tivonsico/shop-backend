package com.shop.service;

import com.shop.model.PaymentRecord;
import com.shop.repository.PaymentRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 支付宝电脑网站支付 —— 直接读环境变量，不依赖 Spring @Value
 *
 * 流程：
 *   用户选支付宝 → 后端生成签名 URL
 *   → 浏览器跳转到支付宝页面 → 用户输密码付款
 *   → 支付宝回调 notify_url（服务端）+ 跳回 return_url（浏览器）
 */
@Service
public class AlipayService {

    private static final Logger log = LoggerFactory.getLogger(AlipayService.class);
    private static final String GATEWAY_PROD = "https://openapi.alipay.com/gateway.do";
    private static final String GATEWAY_SANDBOX = "https://openapi.alipaydev.com/gateway.do";

    private final PaymentRepository paymentRepository;

    // 从 System.getenv() 直接读取，不走 Spring 属性链
    private String appId;
    private String appPrivateKey;
    private String alipayPublicKey;
    private String notifyUrl;
    private String returnUrl;
    private boolean sandbox;

    public AlipayService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @PostConstruct
    public void init() {
        // 从环境变量读取
        this.appId = getEnv("ALIPAY_APP_ID");
        this.appPrivateKey = getEnv("ALIPAY_APP_PRIVATE_KEY");
        this.alipayPublicKey = getEnv("ALIPAY_PUBLIC_KEY");
        this.notifyUrl = getEnv("ALIPAY_NOTIFY_URL");
        this.returnUrl = getEnv("ALIPAY_RETURN_URL");
        String sandboxStr = getEnv("ALIPAY_SANDBOX");
        this.sandbox = !"false".equalsIgnoreCase(sandboxStr);

        if (isConfigured()) {
            log.info("支付宝支付已配置完成 ✓");
        } else {
            log.warn("支付宝支付未完全配置: appId={}, privateKey={}, publicKey={}",
                    appId != null && !appId.isEmpty() ? "✓" : "✗",
                    appPrivateKey != null && !appPrivateKey.isEmpty() ? "✓" : "✗",
                    alipayPublicKey != null && !alipayPublicKey.isEmpty() ? "✓" : "✗");
        }
    }

    /** 是否配好了支付宝支付 */
    public boolean isConfigured() {
        return appId != null && !appId.isEmpty()
                && appPrivateKey != null && !appPrivateKey.isEmpty()
                && alipayPublicKey != null && !alipayPublicKey.isEmpty();
    }

    /**
     * 生成支付宝电脑网站支付跳转 URL
     */
    public String createPayPageUrl(Long orderId, Double amount, String subject) {
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setMethod("ALIPAY");
        record.setStatus("UNPAID");
        paymentRepository.save(record);

        try {
            String bizContent = "{"
                    + "\"out_trade_no\":\"" + orderId + "\","
                    + "\"product_code\":\"FAST_INSTANT_TRADE_PAY\","
                    + "\"total_amount\":" + String.format("%.2f", amount) + ","
                    + "\"subject\":\"" + escapeJson(subject) + "\""
                    + "}";

            Map<String, String> params = new TreeMap<>();
            params.put("app_id", appId);
            params.put("method", "alipay.trade.page.pay");
            params.put("format", "JSON");
            params.put("charset", "UTF-8");
            params.put("sign_type", "RSA2");
            params.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            params.put("version", "1.0");
            params.put("notify_url", notifyUrl != null ? notifyUrl : "");
            params.put("return_url", returnUrl != null ? returnUrl : "");
            params.put("biz_content", bizContent);

            String signContent = buildSignContent(params);
            String sign = rsaSign(signContent);

            String gateway = sandbox ? GATEWAY_SANDBOX : GATEWAY_PROD;
            StringBuilder url = new StringBuilder(gateway);
            for (Map.Entry<String, String> entry : params.entrySet()) {
                url.append(url.indexOf("?") < 0 ? "?" : "&")
                        .append(entry.getKey())
                        .append("=")
                        .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            url.append("&sign=").append(URLEncoder.encode(sign, "UTF-8"));

            String payUrl = url.toString();
            log.info("支付宝支付URL已生成: orderId={}, amount={}", orderId, amount);

            record.setPrepayId(payUrl);
            paymentRepository.save(record);

            return payUrl;

        } catch (Exception e) {
            log.error("支付宝下单失败", e);
            record.setStatus("CLOSED");
            record.setErrMsg(e.getMessage());
            paymentRepository.save(record);
            throw new RuntimeException("支付宝下单失败: " + e.getMessage());
        }
    }

    /**
     * 验证支付宝回调签名
     */
    public boolean verifyNotify(Map<String, String> params) {
        try {
            String sign = params.get("sign");
            if (sign == null) return false;

            Map<String, String> sorted = new TreeMap<>(params);
            sorted.remove("sign");
            sorted.remove("sign_type");

            StringBuilder content = new StringBuilder();
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    if (content.length() > 0) content.append("&");
                    content.append(entry.getKey()).append("=").append(entry.getValue());
                }
            }

            return rsaVerify(content.toString(), sign);
        } catch (Exception e) {
            log.error("支付宝验签失败", e);
            return false;
        }
    }

    /**
     * 处理支付宝回调
     */
    public Long handleNotify(Map<String, String> params) {
        try {
            String tradeStatus = params.get("trade_status");
            String outTradeNo = params.get("out_trade_no");
            String tradeNo = params.get("trade_no");

            log.info("支付宝回调: outTradeNo={}, tradeStatus={}, tradeNo={}",
                    outTradeNo, tradeStatus, tradeNo);

            if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
                log.warn("支付宝交易未成功: {}", tradeStatus);
                return null;
            }
            if (outTradeNo == null) return null;

            Long orderId = Long.parseLong(outTradeNo);

            PaymentRecord record = paymentRepository.findByOrderId(orderId).orElse(null);
            if (record == null) {
                log.warn("支付记录不存在: {}", orderId);
                return null;
            }

            record.setStatus("PAID");
            record.setTradeNo(tradeNo);
            record.setNotifyRaw(params.toString());
            record.setPaidAt(LocalDateTime.now());
            paymentRepository.save(record);

            return orderId;

        } catch (Exception e) {
            log.error("支付宝回调处理失败", e);
            return null;
        }
    }

    // =================== 私有方法 ===================

    private String getEnv(String name) {
        String val = System.getenv(name);
        if (val == null || val.trim().isEmpty()) {
            // 也试试小写版本（Spring Boot 有时会设小写）
            val = System.getenv(name.toLowerCase());
        }
        return val != null ? val.trim() : "";
    }

    private String buildSignContent(Map<String, String> sortedParams) {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            String value = entry.getValue();
            if (value != null && !value.isEmpty()) {
                content.append(entry.getKey()).append("=").append(value).append("&");
            }
        }
        if (content.length() > 0) {
            content.deleteCharAt(content.length() - 1);
        }
        return content.toString();
    }

    private String rsaSign(String content) {
        try {
            String key = appPrivateKey
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(key);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = kf.generatePrivate(spec);

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new RuntimeException("支付宝签名失败", e);
        }
    }

    private boolean rsaVerify(String content, String sign) {
        try {
            String key = alipayPublicKey
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(key);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey publicKey = kf.generatePublic(spec);

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception e) {
            log.error("支付宝验签失败", e);
            return false;
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}