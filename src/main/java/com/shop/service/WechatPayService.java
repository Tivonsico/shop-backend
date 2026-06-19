package com.shop.service;

import com.shop.model.PaymentRecord;
import com.shop.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;

/**
 * 微信支付 V3 Native 模式
 *
 * 流程：
 *   后端调微信 API → 拿到 code_url → 前端生成二维码
 *   用户扫码支付 → 微信回调 notify_url → 后端确认
 *
 * 申请微信商户号后，需要配置：
 *   - WECHAT_APP_ID       公众号/小程序 AppID
 *   - WECHAT_MCH_ID       商户号
 *   - WECHAT_API_V3_KEY   APIv3 密钥（在商户平台设置->API安全）
 *   - WECHAT_MERCHANT_SERIAL_NO  商户证书序列号
 *   - WECHAT_PRIVATE_KEY  商户私钥（PEM 格式，含 -----BEGIN PRIVATE KEY-----）
 */
@Service
public class WechatPayService {

    private static final Logger log = LoggerFactory.getLogger(WechatPayService.class);
    private static final String API_URL = "https://api.mch.weixin.qq.com/v3/pay/transactions/native";

    private final RestTemplate restTemplate = new RestTemplate();
    private final PaymentRepository paymentRepository;

    @Value("${wechat.app-id:}")
    private String appId;

    @Value("${wechat.mch-id:}")
    private String mchId;

    @Value("${wechat.api-v3-key:}")
    private String apiV3Key;

    @Value("${wechat.merchant-serial-no:}")
    private String merchantSerialNo;

    @Value("${wechat.private-key:}")
    private String privateKeyPem;

    @Value("${wechat.notify-url:}")
    private String notifyUrl;

    public WechatPayService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /** 是否配好了微信支付 */
    public boolean isConfigured() {
        return !appId.isEmpty() && !mchId.isEmpty() && !apiV3Key.isEmpty()
                && !merchantSerialNo.isEmpty() && !privateKeyPem.isEmpty();
    }

    /**
     * 调微信 Native 支付 API，生成订单并返回 code_url（二维码内容）
     *
     * @param orderId     订单 ID（我们系统里的）
     * @param totalFee    金额，单位：分
     * @param description 商品描述
     * @return code_url（前端用它生成二维码）
     */
    public String createOrder(Long orderId, Integer totalFee, String description) {
        // 保存支付记录
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setMethod("WECHAT");
        record.setStatus("UNPAID");
        paymentRepository.save(record);

        try {
            String body = buildRequestBody(orderId, totalFee, description);
            HttpHeaders headers = buildHttpHeaders("POST", "/v3/pay/transactions/native", body);
            HttpEntity<String> request = new HttpEntity<>(body, headers);

            log.info("WeChat Pay request: orderId={}, totalFee={}", orderId, totalFee);
            ResponseEntity<String> response = restTemplate.exchange(
                    API_URL, HttpMethod.POST, request, String.class);

            String respBody = response.getBody();
            log.info("WeChat Pay response: {}", respBody);

            // 解析 code_url
            String codeUrl = extractJsonValue(respBody, "code_url");
            if (codeUrl == null) {
                throw new RuntimeException("微信支付返回缺少 code_url: " + respBody);
            }

            // 更新支付记录
            record.setPrepayId(codeUrl);
            paymentRepository.save(record);

            return codeUrl;

        } catch (Exception e) {
            log.error("WeChat Pay create order failed", e);
            record.setStatus("CLOSED");
            record.setErrMsg(e.getMessage());
            paymentRepository.save(record);
            throw new RuntimeException("微信支付下单失败: " + e.getMessage());
        }
    }

    /**
     * 处理微信支付回调通知
     *
     * @param wechatpaySignature 微信签名（Header: Wechatpay-Signature）
     * @param wechatpaySerial    微信平台证书序列号（Header: Wechatpay-Serial）
     * @param wechatpayTimestamp 时间戳（Header: Wechatpay-Timestamp）
     * @param wechatpayNonce     随机串（Header: Wechatpay-Nonce）
     * @param body               请求体（JSON，包含加密的支付结果）
     * @return 支付成功的订单 ID，null 表示验证失败
     */
    public Long handleNotify(String wechatpaySignature, String wechatpaySerial,
                             String wechatpayTimestamp, String wechatpayNonce, String body) {
        try {
            log.info("WeChat notify received: serial={}, timestamp={}", wechatpaySerial, wechatpayTimestamp);

            // 1. 解密 resource 得到明文
            // resource 结构：{"ciphertext":"...","nonce":"...","associated_data":"..."}
            String ciphertext = extractJsonValue(body, "ciphertext");
            String nonce = extractJsonValue(body, "nonce");
            String associatedData = extractJsonValue(body, "associated_data");

            if (ciphertext == null || nonce == null) {
                log.warn("WeChat notify missing ciphertext or nonce");
                return null;
            }

            String plaintext = decryptAesGcm(ciphertext, nonce, associatedData != null ? associatedData : "");
            log.info("WeChat notify plaintext: {}", plaintext);

            // 2. 从明文中解析 out_trade_no（即我们的 orderId）
            String outTradeNo = extractJsonValue(plaintext, "out_trade_no");
            String tradeState = extractJsonValue(plaintext, "trade_state");
            String tradeNo = extractJsonValue(plaintext, "transaction_id");

            if (outTradeNo == null || !"SUCCESS".equals(tradeState)) {
                log.warn("WeChat pay not success: tradeState={}", tradeState);
                return null;
            }

            Long orderId = Long.parseLong(outTradeNo);

            // 3. 更新支付记录
            PaymentRecord record = paymentRepository.findByOrderId(orderId).orElse(null);
            if (record == null) {
                log.warn("Payment record not found for order: {}", orderId);
                return null;
            }

            record.setStatus("PAID");
            record.setTradeNo(tradeNo);
            record.setNotifyRaw(body);
            record.setPaidAt(LocalDateTime.now());
            paymentRepository.save(record);

            return orderId;

        } catch (Exception e) {
            log.error("WeChat notify handle failed", e);
            return null;
        }
    }

    // =================== 私有方法 ===================

    /** 构建请求体 JSON */
    private String buildRequestBody(Long orderId, Integer totalFee, String description) {
        return "{"
                + "\"appid\":\"" + escapeJson(appId) + "\","
                + "\"mchid\":\"" + escapeJson(mchId) + "\","
                + "\"description\":\"" + escapeJson(description) + "\","
                + "\"out_trade_no\":\"" + orderId + "\","
                + "\"notify_url\":\"" + escapeJson(notifyUrl) + "\","
                + "\"amount\":{\"total\":" + totalFee + ",\"currency\":\"CNY\"}"
                + "}";
    }

    /** 构建 HTTP 请求头（含微信认证签名） */
    private HttpHeaders buildHttpHeaders(String method, String urlPath, String body) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        // 拼出待签名字符串
        String message = method + "\n" + urlPath + "\n" + timestamp + "\n" + nonce + "\n"
                + (body == null ? "" : body) + "\n";

        // 用商户私钥签名
        String signature = signSha256Rsa(message);

        // 构造 Authorization 头
        String auth = "WECHATPAY2-SHA256-RSA2048 "
                + "mchid=\"" + mchId + "\","
                + "nonce_str=\"" + nonce + "\","
                + "timestamp=\"" + timestamp + "\","
                + "serial_no=\"" + merchantSerialNo + "\","
                + "signature=\"" + signature + "\"";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", auth);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "shop-backend/1.0");
        return headers;
    }

    /** 用 RSA 私钥签 SHA256WithRSA */
    private String signSha256Rsa(String message) {
        try {
            PrivateKey key = parsePrivateKey();
            Signature sign = Signature.getInstance("SHA256withRSA");
            sign.initSign(key);
            sign.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sign.sign());
        } catch (Exception e) {
            throw new RuntimeException("微信签名失败", e);
        }
    }

    /** 解析 PEM 格式的私钥 */
    private PrivateKey parsePrivateKey() throws Exception {
        String key = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(key);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    /** AES-256-GCM 解密（用于解密微信回调内容） */
    private String decryptAesGcm(String ciphertext, String nonce, String associatedData) {
        try {
            byte[] keyBytes = apiV3Key.getBytes(StandardCharsets.UTF_8);
            byte[] nonceBytes = nonce.getBytes(StandardCharsets.UTF_8);
            byte[] adBytes = associatedData.getBytes(StandardCharsets.UTF_8);
            byte[] cipherBytes = Base64.getDecoder().decode(ciphertext);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonceBytes);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            cipher.updateAAD(adBytes);
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("微信回调解密失败", e);
        }
    }

    /** 从 JSON 里提取指定 key 的字符串值（简单解析，不用 Jackson） */
    private String extractJsonValue(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        StringBuilder value = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                value.append(json.charAt(i + 1));
                i++;
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }

    /** JSON 字符串转义 */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}