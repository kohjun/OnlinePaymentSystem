package com.example.payment.application.temporal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 워크플로 코드를 바꾸기 전에, 현재 배포 코드의 이력을 고정 파일로 남긴다.
 * 이미 있는 파일은 덮어쓰지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "GENERATE_TEMPORAL_HISTORY_FIXTURES", matches = "true")
class WorkflowHistoryFixtureGeneratorTest {

    @Test
    void writesHistoriesFromCurrentWorkflowCode() throws Exception {
        WorkflowHistoryFixtures.write(WorkflowHistoryFixtures.generate());
    }
}
