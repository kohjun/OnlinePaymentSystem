package com.example.payment.application.temporal;

import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이전 워크플로 코드가 남긴 이력을 현재 코드로 재생한다.
 *
 * 실행 중인 결제 워크플로는 배포 후 새 코드로 이어서 재생된다. 명령 순서가
 * 달라지면 워커가 NonDeterministicException으로 멈추고, 그 결제는 복구 단계에서
 * 빠져나오지 못한다. 워크플로 분기를 바꿀 때는 Workflow.getVersion으로 감싼다.
 */
class CompleteReservationWorkflowReplayTest {

    static Stream<Path> histories() throws Exception {
        try (Stream<Path> files = Files.list(WorkflowHistoryFixtures.DIRECTORY)) {
            var list = files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
            assertTrue(list.size() >= 6, "expected stored workflow histories in " + WorkflowHistoryFixtures.DIRECTORY);
            return list.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("histories")
    void replaysHistoryFromPreviousWorkflowCode(Path history) throws Exception {
        WorkflowReplayer.replayWorkflowExecution(
                Files.readString(history, StandardCharsets.UTF_8),
                CompleteReservationWorkflowImpl.class
        );
    }
}
