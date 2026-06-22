package com.example.payment.infrastructure.config;

import com.example.payment.application.service.DistributionReadinessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DistributionReadinessStartupValidator implements ApplicationRunner {

    private final DistributionReadinessService distributionReadinessService;

    @Value("${app.distribution.fail-fast-on-blockers:false}")
    private boolean failFastOnBlockers;

    @Override
    public void run(ApplicationArguments args) {
        DistributionReadinessService.ReadinessReport report = distributionReadinessService.evaluate();

        if (report.blockingIssues().isEmpty()) {
            log.info("Distribution readiness status={}, mode={}, releaseChannel={}, warnings={}",
                    report.status(), report.mode(), report.releaseChannel(), report.warnings().size());
            return;
        }

        log.warn("Distribution readiness blocked: mode={}, releaseChannel={}, blockingIssues={}",
                report.mode(), report.releaseChannel(), report.blockingIssues());

        if (failFastOnBlockers) {
            throw new IllegalStateException("Distribution readiness blockers: " + report.blockingIssues());
        }
    }
}
