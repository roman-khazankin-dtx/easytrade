package com.dynatrace.easytrade.accountservice;

import com.dynatrace.easytrade.accountservice.models.FeatureFlag;
import dev.openfeature.sdk.*;

public class JavaProvider implements FeatureProvider {
    private final String name = "Java Provider";
    private FeatureFlagClient featureFlagClient = new FeatureFlagClient();

    @Override
    public Metadata getMetadata() {
        return () -> name;
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        FeatureFlag flag = featureFlagClient.getFlag(key);
        return constructProviderEvaluation(flag.enabled());
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        return constructProviderEvaluation(defaultValue);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        return constructProviderEvaluation(defaultValue);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        return constructProviderEvaluation(defaultValue);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        return constructProviderEvaluation(defaultValue);
    }

    private <T> ProviderEvaluation<T> constructProviderEvaluation(T value) {
        return ProviderEvaluation.<T>builder().value(value).build();
    }
}
