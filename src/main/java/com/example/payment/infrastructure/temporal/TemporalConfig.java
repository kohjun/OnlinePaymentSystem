package com.example.payment.infrastructure.temporal;

import com.example.payment.application.temporal.CompleteReservationActivitiesImpl;
import com.example.payment.application.temporal.CompleteReservationWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(TemporalProperties.class)
@ConditionalOnProperty(name = "app.temporal.enabled", havingValue = "true", matchIfMissing = true)
public class TemporalConfig {

    private final TemporalProperties temporalProperties;

    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporalProperties.getTarget())
                .build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs workflowServiceStubs) {
        return WorkflowClient.newInstance(workflowServiceStubs, WorkflowClientOptions.newBuilder()
                .setNamespace(temporalProperties.getNamespace())
                .build());
    }

    @Bean
    @ConditionalOnProperty(name = "app.temporal.worker-enabled", havingValue = "true")
    public WorkerFactory paymentWorkerFactory(WorkflowClient workflowClient,
                                              CompleteReservationActivitiesImpl activities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(temporalProperties.getTaskQueue());
        worker.registerWorkflowImplementationTypes(CompleteReservationWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        return factory;
    }
}
