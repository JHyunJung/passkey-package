package com.crosscert.passkey.core.license;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Aspect
@Component
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
public class FeatureGateAspect {

    private final LicenseStateMachine stateMachine;

    public FeatureGateAspect(LicenseStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @Around("@annotation(com.crosscert.passkey.core.license.RequiresFeature)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {
        // Refresh state — feature gate must respect DEAD even between heartbeats
        stateMachine.recompute();
        LicenseStateMachine.Snapshot snap = stateMachine.snapshot();
        if (snap.state() == LicenseState.DEAD) {
            throw new FeatureNotLicensedException("license-dead");
        }
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        RequiresFeature ann = sig.getMethod().getAnnotation(RequiresFeature.class);
        String feature = ann.value();
        if (!snap.token().hasFeature(feature)) {
            throw new FeatureNotLicensedException(feature);
        }
        return pjp.proceed();
    }
}
