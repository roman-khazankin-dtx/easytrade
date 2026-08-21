package com.dynatrace.easytrade.creditcardorderservice.models;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A system-wide summary of every credit-card order and its most recent status.
 *
 * <p>This is expensive to build ({@link #build}) and is therefore meant to be computed
 * rarely and served from a cache. See {@code OrderOverviewService}.
 */
public record OrdersOverview(int totalOrders, int completedOrders, Map<String, Integer> countsByStatus) {

    /**
     * Cross-references every order against the full status list to derive each order's
     * latest status and aggregate the totals.
     *
     * <p><b>Cost:</b> a naive nested scan — {@code O(orders * statuses)}. This is fine when
     * the result is cached and rebuilt only occasionally, but ruinous if it runs on the
     * request hot path (a dominant on-CPU frame that scales with the size of the database).
     */
    public static OrdersOverview build(List<OrderRow> orders, List<OrderStatusRow> statuses) {
        Map<String, Integer> countsByStatus = new HashMap<>();
        int completedOrders = 0;

        for (OrderRow order : orders) {
            OrderStatusRow latest = null;
            for (OrderStatusRow status : statuses) {
                if (!status.creditCardOrderId().equals(order.id())) {
                    continue;
                }
                if (latest == null || status.timestamp().isAfter(latest.timestamp())) {
                    latest = status;
                }
            }
            if (latest != null) {
                countsByStatus.merge(latest.status(), 1, Integer::sum);
                if (StatusType.CARD_DELIVERED.getType().equalsIgnoreCase(latest.status())) {
                    completedOrders++;
                }
            }
        }

        return new OrdersOverview(orders.size(), completedOrders, countsByStatus);
    }
}
