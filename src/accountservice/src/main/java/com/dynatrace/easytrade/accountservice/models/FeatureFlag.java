package com.dynatrace.easytrade.accountservice.models;

public record FeatureFlag(String id, Boolean enabled, String name, String description, Boolean isModifiable,
        String tag) {
}
