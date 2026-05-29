package com.crosscert.passkey.core.license;

/**
 * Thrown by FeatureGateAspect when a method gated with
 * @RequiresFeature is invoked but the current license does not
 * include that feature. GlobalExceptionHandler maps this to
 * HTTP 403 with ErrorCode.FEATURE_NOT_LICENSED.
 */
public class FeatureNotLicensedException extends RuntimeException {
    private final String feature;

    public FeatureNotLicensedException(String feature) {
        super("Feature not licensed: " + feature);
        this.feature = feature;
    }

    public String feature() { return feature; }
}
