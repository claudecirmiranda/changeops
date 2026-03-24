package com.changeops.deployorchestrator.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Simulated post-deploy checklist.
 * Each check represents a validation step that would call real systems in production
 * (health endpoints, smoke tests, metric thresholds, etc.)
 */
@Slf4j
@Service
public class PostDeployChecklistService {

    public ChecklistResult execute(UUID changeId, UUID deployId, boolean deploySucceeded) {
        log.info("Running post-deploy checklist: changeId={}, deployId={}", changeId, deployId);

        List<CheckItem> items = new ArrayList<>();

        // Check 1: Deploy result gate
        items.add(check("deploy-result-gate",
                deploySucceeded, "Deploy result was FAILURE"));

        // Check 2: Simulate healthcheck (always passes when deploy succeeded)
        items.add(check("healthcheck",
                deploySucceeded, "Service did not become healthy after deploy"));

        // Check 3: Simulate smoke test
        items.add(check("smoke-test",
                deploySucceeded, "Smoke test failed post-deploy"));

        // Check 4: Simulate metric threshold
        items.add(check("error-rate-threshold",
                deploySucceeded, "Error rate exceeded threshold post-deploy"));

        boolean allPassed = items.stream().allMatch(CheckItem::passed);
        String failureReason = items.stream()
                .filter(i -> !i.passed())
                .findFirst()
                .map(CheckItem::failureMessage)
                .orElse(null);

        log.info("Checklist complete: changeId={}, passed={}, reason={}",
                changeId, allPassed, failureReason);

        return new ChecklistResult(allPassed, failureReason, items);
    }

    private CheckItem check(String name, boolean passed, String failureMessage) {
        log.debug("Checklist item [{}]: {}", name, passed ? "PASS" : "FAIL");
        return new CheckItem(name, passed, passed ? null : failureMessage);
    }

    public record CheckItem(String name, boolean passed, String failureMessage) {}

    public record ChecklistResult(
            boolean allPassed,
            String failureReason,
            List<CheckItem> items) {}
}
