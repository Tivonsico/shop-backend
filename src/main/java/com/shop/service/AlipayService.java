package com.shop.service;

import com.shop.model.PaymentRecord;
import com.shop.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 支付宝当面付（扫码支付）—— 手动实现，不依赖支付宝 SDK
 *
 * 流程：
 *   后端调支付宝 API → 拿到 qr_code → 前端生成二维码
 *   用户扫码支付 → 支付宝回调 notify_url → 后端确认
 *
 * 申请支付宝商户号后，需要配置：
 *   - ALIPAY_APP_ID          应用 ID
 *   - ALIPAY_APP_PRIVATE_KEY 应用私钥（PKCS8 格式）
 *   - ALIPAY_PUBLIC_KEY      支付宝公钥
 *   - ALIPAY_NOTIFY_URL      回调地址
 */
@Service
public class AlipayService {

    private static final Logger log = LoggerFactory.getLogger(AlipayService.class);
    private static final String GATEWAY_PROD = "https://openapi.alipay.com/gateway.do";
    private static final String GATEWAY_SANDBOX = "https://openapi.alipaydev.com/gateway.do";

    private final RestTemplate restTemplate = new RestTemplate();
    private final PaymentRepository paymentRepository;

    @Value("${alipay.app-id:}")
    private String appId;

    @Value("${alipay.app-private-key:}")
    private String appPrivateKey;

    @Value("${alipay.alipay-public-key:}")
    private String alipayPublicKey;

    @Value("${alipay.notify-url:}")
    private String notifyUrl;

    @Value("${alipay.sandbox:true}")
    private boolean sandbox;

    public AlipayService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /** 是否配好了支付宝支付 */
    public boolean isConfigured() {
        return !appId.isEmpty() && !appPrivateKey.isEmpty() && !alipayPublicKey.isEmpty();
    }

    /**
     * 创建支付宝当面付订单，返回二维码字符串
     *
     * @param orderId  订单 ID
     * @param amount   金额，单位：元
     * @param subject  订单标题
     * @return qr_code（前端用它生成二维码）
     */
    public String createOrder(Long orderId, Double amount, String subject) {
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setMethod("ALIPAY");
        record.setStatus("UNPAID");
        paymentRepository.save(record);

        try {
            String bizContent = "{"
                    + "\"out_trade_no\":\"" + orderId + "\","
                    + "\"total_amount\":" + String.format("%.2f", amount) + ","
                    + "\"subject\":\"" + escapeJson(subject) + "\""
                    + "}";

            // 构建公共参数
            Map<String, String> params = new TreeMap<>(); // TreeMap 自动按 key 排序
            params.put("app_id", appId);
            params.put("method", "alipay.trade.precreate");
            params.put("format", "JSON");
            params.put("charset", "UTF-8");
            params.put("sign_type", "RSA2");
            params.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            params.put("version", "1.0");
            params.put("notify_url", notifyUrl);
            params.put("biz_content", bizContent);

            // 签名
            String signContent = buildSignContent(params);
            String sign = rsaSign(signContent);
            params.put("sign", sign);

            log.info("Alipay precreate: orderId={}, amount={}", orderId, amount);

            // 发送请求
            String response = doPost(params);
            log.info("Alipay precreate response: {}", response);

            // 解析 qr_code
            String qrCode = parseJsonValue(response, "qr_code");
            if (qrCode == null || qrCode.isEmpty()) {
                String code = parseJsonValue(response, "code");
                String subMsg = parseJsonValue(response, "sub_msg");
                throw new RuntimeException("支付宝下单失败: code=" + code + ", subMsg=" + (subMsg != null ? subMsg : "无"));
            }

            record.setPrepayId(qrCode);
            paymentRepository.save(record);

            return qrCode;

        } catch (Exception e) {
            log.error("Alipay create order failed", e);
            record.setStatus("CLOSED");
            record.setErrMsg(e.getMessage());
            paymentRepository.save(record);
            throw new RuntimeException("支付宝下单失败: " + e.getMessage());
        }
    }

    /**
     * 验证支付宝回调签名
     *
     * @param params 支付宝回调的所有参数
     * @return true 验证通过
     */
    public boolean verifyNotify(Map<String, String> params) {
        try {
            String sign = params.get("sign");
            if (sign == null) return false;

            // 移除 sign 和 sign_type，剩下的排序后拼接
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
            log.error("Alipay verify failed", e);
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

            log.info("Alipay notify: outTradeNo={}, tradeStatus={}, tradeNo={}",
                    outTradeNo, tradeStatus, tradeNo);

            if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
                log.warn("Alipay trade not success: {}", tradeStatus);
                return null;
            }
            if (outTradeNo == null) return null;

            Long orderId = Long.parseLong(outTradeNo);

            PaymentRecord record = paymentRepository.findByOrderId(orderId).orElse(null);
            if (record == null) {
                log.warn("Payment record not found for order: {}", orderId);
                return null;
            }

            record.setStatus("PAID");
            record.setTradeNo(tradeNo);
            record.setNotifyRaw(params.toString());
            record.setPaidAt(LocalDateTime.now());
            paymentRepository.save(record);

            return orderId;

        } catch (Exception e) {
            log.error("Alipay notify handle failed", e);
            return null;
        }
    }

    // =================== 私有方法 ===================

    /** 构建待签名字符串：key1=value1&key2=value2... */
    private String buildSignContent(Map<String, String> sortedParams) {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value != null && !value.isEmpty() && !"sign".equals(key)) {
                content.append(key).append("=").append(value).append("&");
            }
        }
        if (content.length() > 0) {
            content.deleteCharAt(content.length() - 1);
        }
        return content.toString();
    }

    /** RSA2 签名 */
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

    /** 验证 RSA2 签名 */
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

    /** POST 提交表单到支付宝网关 */
    private String doPost(Map<String, String> params) {
        String gateway = sandbox ? GATEWAY_SANDBOX : GATEWAY_PROD;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            form.add(entry.getKey(), entry.getValue());
        }

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                gateway, HttpMethod.POST, request, String.class);

        return response.getBody();
    }

    /** 从支付宝响应 JSON 中提取值 */
    private String parseJsonValue(String json, String key) {
        if (json == null) return null;
        // 支付宝返回格式: {"alipay_trade_precreate_response":{"code":"10000","qr_code":"xxx"},"sign":"xxx"}
        // 先找到响应体
        int respStart = json.indexOf('{', json.indexOf('{') + 1); // 第二层
        int respEnd = json.indexOf('}', respStart);
        if (respStart < 0 || respEnd < 0) return null;
        String respBody = json.substring(respStart, respEnd + 1);

        // 提取 key 的值
        String search = "\"" + key + "\":\"";
        int start = respBody.indexOf(search);
        if (start < 0) {
            // 可能是非字符串值（如 code 返回 "10000"）
            String search2 = "\"" + key + "\":";
            int s2 = respBody.indexOf(search2);
            if (s2 < 0) return null;
            s2 += search2.length();
            int e2 = respBody.indexOf(",", s2);
            if (e2 < 0) e2 = respBody.indexOf("}", s2);
            if (e2 < 0) return null;
            String val = respBody.substring(s2, e2).trim();
            if (val.startsWith("\"") && val.endsWith("\"")) {
                val = val.substring(1, val.length() - 1);
            }
            return val;
        }
        start += search.length();
        StringBuilder value = new StringBuilder();
        for (int i = start; i < respBody.length(); i++) {
            char c = respBody.charAt(i);
            if (c == '\\' && i + 1 < respBody.length()) {
                value.append(respBody.charAt(i + 1));
                i++;
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        return value.toString();
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