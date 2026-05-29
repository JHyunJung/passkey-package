package com.crosscert.passkey.core.license;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as gated by a license feature flag. In onprem mode,
 * the method is only invoked when LicenseStateMachine.token().features
 * contains the named feature. In SaaS mode, the aspect bean is not
 * registered, so the annotation has no effect.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresFeature {
    String value();
}
