package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.OrderRow;
import com.dynatrace.easytrade.creditcardorderservice.models.OrderStatusRow;
import com.dynatrace.easytrade.creditcardorderservice.models.OrdersOverview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Serves a cached system-wide {@link OrdersOverview}.
 *
 * <p>Building the overview is expensive ({@link OrdersOverview#build}), so it is cached and
 * only rebuilt on a cache miss. Under normal operation the cache stays warm and
 * {@link #getOverview()} is effectively free.
 *
 * <p><b>Known issue:</b> the cache is invalidated on <i>every</i> status write
 * (see the {@link #invalidate()} callers in {@code OrderController} and {@code WorkScheduler}).
 * Because status writes happen continuously — the {@code WorkScheduler} advances orders on
 * every tick — the cache is almost never warm, so the expensive rebuild ends up running on
 * the request hot path instead of once in a while. The result is a sustained on-CPU hotspot
 * in {@link OrdersOverview#build} that is invisible in traces (no extra DB spans) and only
 * localizable from a CPU profile.
 */
@Service
public class OrderOverviewService {
    private static final Logger logger = LoggerFactory.getLogger(OrderOverviewService.class);

    private final DatabaseHelper dbHelper;
    private volatile OrdersOverview cached;

    public OrderOverviewService(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /** Returns the cached overview, rebuilding it only on a cache miss. */
    public OrdersOverview getOverview() throws SQLException {
        OrdersOverview current = cached;
        if (current != null) {
            return current;
        }
        OrdersOverview rebuilt = recompute();
        cached = rebuilt;
        return rebuilt;
    }

    /** Drops the cached overview so the next read rebuilds it. Called on every status write. */
    public void invalidate() {
        cached = null;
    }

    private OrdersOverview recompute() throws SQLException {
        logger.debug("Rebuilding orders overview from the database");
        try (Connection conn = dbHelper.getConnection()) {
            List<OrderRow> orders = dbHelper.getAllOrders(conn);
            List<OrderStatusRow> statuses = dbHelper.getAllOrderStatuses(conn);
            return OrdersOverview.build(orders, statuses);
        }
    }
}
