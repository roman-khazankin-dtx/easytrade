package com.dynatrace.easytrade.creditcardorderservice.models;

import java.time.OffsetDateTime;

/** Lightweight projection of a status row, used to build the orders overview. */
public record OrderStatusRow(String creditCardOrderId, String status, OffsetDateTime timestamp) {}
