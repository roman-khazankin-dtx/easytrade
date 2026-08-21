package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.OrderRow;
import com.dynatrace.easytrade.creditcardorderservice.models.OrderStatusRow;
import com.dynatrace.easytrade.creditcardorderservice.models.OrdersOverview;
import com.dynatrace.easytrade.creditcardorderservice.models.StatusType;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class OrdersOverviewTest {

    private static OrderStatusRow status(String orderId, StatusType type, int minute) {
        return new OrderStatusRow(orderId, type.getType(),
                OffsetDateTime.of(2026, 1, 1, 0, minute, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void build_derivesLatestStatusPerOrder_countsByStatus() {
        List<OrderRow> orders = List.of(new OrderRow("A"), new OrderRow("B"));
        List<OrderStatusRow> statuses = List.of(
                status("A", StatusType.ORDER_CREATED, 0),
                status("A", StatusType.CARD_ORDERED, 5),   // A's latest
                status("B", StatusType.ORDER_CREATED, 0)); // B's latest

        OrdersOverview overview = OrdersOverview.build(orders, statuses);

        assertEquals(2, overview.totalOrders());
        assertEquals(0, overview.completedOrders());
        assertEquals(1, overview.countsByStatus().get(StatusType.CARD_ORDERED.getType()));
        assertEquals(1, overview.countsByStatus().get(StatusType.ORDER_CREATED.getType()));
    }

    @Test
    void build_countsDeliveredOrdersAsCompleted() {
        List<OrderRow> orders = List.of(new OrderRow("A"));
        List<OrderStatusRow> statuses = List.of(
                status("A", StatusType.CARD_SHIPPED, 0),
                status("A", StatusType.CARD_DELIVERED, 10)); // latest => completed

        OrdersOverview overview = OrdersOverview.build(orders, statuses);

        assertEquals(1, overview.totalOrders());
        assertEquals(1, overview.completedOrders());
        assertEquals(1, overview.countsByStatus().get(StatusType.CARD_DELIVERED.getType()));
    }

    @Test
    void build_ordersWithNoStatusAreCountedButNotClassified() {
        List<OrderRow> orders = List.of(new OrderRow("A"), new OrderRow("orphan"));
        List<OrderStatusRow> statuses = List.of(status("A", StatusType.ORDER_CREATED, 0));

        OrdersOverview overview = OrdersOverview.build(orders, statuses);

        assertEquals(2, overview.totalOrders());
        assertEquals(0, overview.completedOrders());
        assertEquals(1, overview.countsByStatus().size());
    }
}
