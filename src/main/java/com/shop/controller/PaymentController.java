package com.shop.controller;

import com.shop.model.Order;
import com.shop.service.AlipayService;
import com.shop.service.OrderService;
import com.shop.service.WechatPayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.util.*;

/**
 * 支付 API
 *
 * 同时支持微信支付和支付宝，前端根据用户选择调对应接口
 */
@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final WechatPayService wechatPayService;
    private final AlipayService alipayService;
    private final OrderService orderService;

    public PaymentController(WechatPayService wechatPayService,
                             AlipayService alipayService,
                             OrderService orderService) {
        this.wechatPayService = wechatPayService;
        this.alipayService = alipayService;
        this.orderService = orderService;
    }

    /**
     * 创建支付并获取二维码
     *
     * POST /api/payment/create
     * Body: { "orderId": 1, "method": "WECHAT" }
     *
     * @return { "success": true, "qrcode": "weixin://..." }
     */
    @PostMapping("/create")
    public Map<String, Object> createPayment(@RequestBody Map<String, Object> body) {
        Long orderId = Long.valueOf(body.get("orderId").toString());
        String method = body.getOrDefault("method", "WECHAT").toString().toUpperCase();

        // 查订单
        Optional<Order> orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty()) {
            return Map.of("success", false, "message", "订单不存在");
        }

        Order order = orderOpt.get();
        if (!"UNPAID".equals(order.getStatus())) {
            return Map.of("success", false, "message", "订单已支付或已取消");
        }

        try {
            if ("ALIPAY".equals(method)) {
                // 支付宝电脑网站支付
                if (!alipayService.isConfigured()) {
                    return Map.of("success", false, "message", "支付宝未配置，请在环境变量中设置 ALIPAY_* 参数");
                }
                String redirectUrl = alipayService.createPayPageUrl(orderId, order.getTotalPrice(),
                        "天方电竞-" + order.getGoodsId());
                order.setPaymentMethod("ALIPAY");
                orderService.save(order);

                return Map.of(
                        "success", true,
                        "redirectUrl", redirectUrl,
                        "method", "ALIPAY",
                        "orderId", orderId
                );

            } else {
                // 微信支付 Native 扫码（金额单位：分）
                if (!wechatPayService.isConfigured()) {
                    return Map.of("success", false, "message", "微信支付未配置，请在环境变量中设置 WECHAT_* 参数");
                }
                int totalFee = (int) Math.round(order.getTotalPrice() * 100);
                String qrcode = wechatPayService.createOrder(orderId, totalFee,
                        "天方电竞-" + order.getGoodsId());
                order.setPaymentMethod("WECHAT");
                orderService.save(order);

                return Map.of(
                        "success", true,
                        "qrcode", qrcode,
                        "method", "WECHAT",
                        "orderId", orderId
                );
            }

        } catch (Exception e) {
            log.error("创建支付失败", e);
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * 手动确认支付（用户点击"已完成支付"时调用）
     *
     * POST /api/payment/confirm/{orderId}
     *
     * @return { "success": true, "status": "PAID" }
     */
    @PostMapping("/confirm/{orderId}")
    public Map<String, Object> confirmPayment(@PathVariable Long orderId) {
        boolean ok = orderService.confirmPayment(orderId);
        if (ok) {
            return Map.of("success", true, "status", "PAID");
        }
        return Map.of("success", false, "message", "确认失败，订单不存在或已支付/已取消");
    }

    /**
     * 轮询查询支付状态
     *
     * GET /api/payment/status/{orderId}
     *
     * @return { "success": true, "status": "PAID", "orderId": 1 }
     */
    @GetMapping("/status/{orderId}")
    public Map<String, Object> getPaymentStatus(@PathVariable Long orderId) {
        Optional<Order> orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty()) {
            return Map.of("success", false, "message", "订单不存在");
        }

        Order order = orderOpt.get();
        return Map.of(
                "success", true,
                "status", order.getStatus(),
                "orderId", orderId
        );
    }

    // ========== 支付回调（微信） ==========

    /**
     * 微信支付回调
     * 微信服务器会 POST 到这个地址
     */
    @PostMapping({"/notify/wechat", "/notify/wechat/"})
    public Object wechatNotify(HttpServletRequest request, HttpServletResponse response) {
        try {
            // 读取请求体
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = request.getReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            String body = sb.toString();
            log.info("WeChat notify body: {}", body);

            // 获取微信签名头
            String signature = request.getHeader("Wechatpay-Signature");
            String serial = request.getHeader("Wechatpay-Serial");
            String timestamp = request.getHeader("Wechatpay-Timestamp");
            String nonce = request.getHeader("Wechatpay-Nonce");

            // 处理回调
            Long orderId = wechatPayService.handleNotify(signature, serial, timestamp, nonce, body);

            if (orderId != null) {
                // 确认支付：扣库存 + 改状态
                boolean ok = orderService.confirmPayment(orderId);
                if (ok) {
                    log.info("Payment confirmed for order: {}", orderId);
                } else {
                    log.warn("Confirm payment failed for order: {}", orderId);
                }

                // 告诉微信收到通知了（微信不再重试）
                response.setStatus(200);
                response.setContentType("application/json");
                return Map.of("code", "SUCCESS", "message", "成功");
            } else {
                // 签名验证失败或业务处理失败
                response.setStatus(200);
                response.setContentType("application/json");
                return Map.of("code", "FAIL", "message", "失败");
            }

        } catch (Exception e) {
            log.error("WeChat notify error", e);
            response.setStatus(500);
            return Map.of("code", "FAIL", "message", e.getMessage());
        }
    }

    // ========== 支付回调（支付宝） ==========

    /**
     * 支付宝支付回调
     * 支付宝服务器会 POST 到这个地址
     */
    @PostMapping({"/notify/alipay", "/notify/alipay/"})
    public Object alipayNotify(HttpServletRequest request, HttpServletResponse response) {
        try {
            // 提取支付宝回调参数
            Map<String, String> params = new HashMap<>();
            Map<String, String[]> requestParams = request.getParameterMap();
            for (Map.Entry<String, String[]> entry : requestParams.entrySet()) {
                params.put(entry.getKey(), entry.getValue()[0]);
            }

            log.info("Alipay notify params: {}", params);

            // 验证签名
            boolean verified = alipayService.verifyNotify(params);
            if (!verified) {
                log.warn("Alipay notify signature verification failed");
                response.setContentType("text/plain;charset=UTF-8");
                return "failure";
            }

            // 处理回调
            Long orderId = alipayService.handleNotify(params);

            if (orderId != null) {
                boolean ok = orderService.confirmPayment(orderId);
                if (ok) {
                    log.info("Payment confirmed for order: {}", orderId);
                } else {
                    log.warn("Confirm payment failed for order: {}", orderId);
                }

                // 支付宝要求返回 "success"（全小写）
                response.setContentType("text/plain;charset=UTF-8");
                return "success";
            } else {
                response.setContentType("text/plain;charset=UTF-8");
                return "failure";
            }

        } catch (Exception e) {
            log.error("Alipay notify error", e);
            response.setStatus(500);
            return "failure";
        }
    }

    // ========== 订单管理（取消） ==========

    /**
     * 取消未支付的订单
     * POST /api/order/cancel/{orderId}
     */
    @PostMapping("/cancel/{orderId}")
    @ResponseBody
    public Map<String, Object> cancelOrder(@PathVariable Long orderId) {
        boolean ok = orderService.cancelOrder(orderId);
        return Map.of("success", ok, "message", ok ? "订单已取消" : "取消失败");
    }
}