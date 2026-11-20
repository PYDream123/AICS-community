package com.aics.tool;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 业务服务契约：MVP 是查模拟表，将来换真实服务只替换实现类（技术设计 §3.5.1）。 */
public interface OrderService {

    record OrderInfo(
            String orderNo,
            String status,
            OffsetDateTime placedAt,
            java.math.BigDecimal amount,
            String itemSummary,
            String carrier,
            String waybillNo) {
    }

    Optional<OrderInfo> findOrder(String orderNo);

    record LogisticsInfo(String carrier, String waybillNo, String latestNode, OffsetDateTime latestNodeTime) {
    }

    Optional<LogisticsInfo> findLatestLogistics(String orderNo);

    record CreatedTicket(String ticketNo, int etaHours) {
    }

    /** 幂等由 ToolFacade 的幂等键保证，这里只负责落库。 */
    CreatedTicket createTicket(long sessionId, String description, String contact);

    default Map<String, Object> toDisplayRows(OrderInfo order, String statusText) {
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("订单号", order.orderNo());
        rows.put("状态", statusText);
        rows.put("下单时间", order.placedAt() == null ? "" : order.placedAt().toLocalDateTime().toString().replace('T', ' '));
        rows.put("实付金额", "¥ " + order.amount().setScale(2, java.math.RoundingMode.HALF_UP));
        rows.put("商品", order.itemSummary());
        return rows;
    }
}
