package com.aics.tool;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟业务服务：读 mock_order / mock_logistics_event，写 support_ticket。
 * 接口形态按真实业务设计（含状态、金额、运单号），替换实现即可对接真实系统。
 */
@Service
public class MockOrderService implements OrderService {

    private static final DateTimeFormatter TICKET_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneId.systemDefault());

    private final JdbcClient jdbc;

    public MockOrderService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OrderInfo> findOrder(String orderNo) {
        return jdbc.sql("""
                        SELECT order_no, status, placed_at, amount, item_summary, carrier, waybill_no
                        FROM mock_order WHERE order_no = :no
                        """)
                .param("no", orderNo)
                .query((rs, rowNum) -> {
                    Timestamp placedAt = rs.getTimestamp("placed_at");
                    return new OrderInfo(
                            rs.getString("order_no"),
                            rs.getString("status"),
                            placedAt == null ? null : placedAt.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime(),
                            rs.getBigDecimal("amount"),
                            rs.getString("item_summary"),
                            rs.getString("carrier"),
                            rs.getString("waybill_no"));
                })
                .optional();
    }

    @Override
    public Optional<LogisticsInfo> findLatestLogistics(String orderNo) {
        return jdbc.sql("""
                        SELECT e.node_desc, e.node_time, o.carrier, o.waybill_no
                        FROM mock_logistics_event e
                        JOIN mock_order o ON o.order_no = e.order_no
                        WHERE e.order_no = :no
                        ORDER BY e.node_time DESC
                        LIMIT 1
                        """)
                .param("no", orderNo)
                .query((rs, rowNum) -> {
                    Timestamp nodeTime = rs.getTimestamp("node_time");
                    return new LogisticsInfo(
                            rs.getString("carrier"),
                            rs.getString("waybill_no"),
                            rs.getString("node_desc"),
                            nodeTime == null ? null : nodeTime.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime());
                })
                .optional();
    }

    @Override
    public CreatedTicket createTicket(long sessionId, String description, String contact) {
        int etaHours = 24;
        for (int attempt = 0; attempt < 3; attempt++) {
            String ticketNo = "TK" + TICKET_DATE.format(OffsetDateTime.now())
                    + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
            try {
                jdbc.sql("""
                                INSERT INTO support_ticket (ticket_no, session_id, description, contact, eta_hours)
                                VALUES (:no, :sid, :desc, :contact, :eta)
                                """)
                        .param("no", ticketNo)
                        .param("sid", sessionId)
                        .param("desc", description)
                        .param("contact", contact)
                        .param("eta", etaHours)
                        .update();
                return new CreatedTicket(ticketNo, etaHours);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 工单号撞车：换一个号重试，最多三次
            }
        }
        throw new IllegalStateException("生成工单号失败");
    }

    static String statusText(String status) {
        return switch (status) {
            case "PAID" -> "已付款，待发货";
            case "SHIPPED" -> "已发货";
            case "SIGNED" -> "已签收";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }

    static BigDecimal safeAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
