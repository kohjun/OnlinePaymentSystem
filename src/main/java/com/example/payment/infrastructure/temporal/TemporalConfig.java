package com.example.payment.infrastructure.temporal;

import com.example.payment.application.temporal.CompleteReservationActivitiesImpl;
import com.example.payment.application.temporal.CompleteReservationWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * 추적 인터셉터는 선택적으로 붙인다. 추적을 끄면 인터셉터 빈이 없고,
     * 그때도 워크플로는 그대로 동작해야 한다.
     */
    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs workflowServiceStubs,
                                         ObjectProvider<OpenTracingClientInterceptor> tracingInterceptor) {
        WorkflowClientOptions.Builder options = WorkflowClientOptions.newBuilder()
                .setNamespace(temporalProperties.getNamespace());

        tracingInterceptor.ifAvailable(interceptor -> options.setInterceptors(interceptor));

        return WorkflowClient.newInstance(workflowServiceStubs, options.build());
    }

    @Bean
    @ConditionalOnProperty(name = "app.temporal.worker-enabled", havingValue = "true")
    public WorkerFactory paymentWorkerFactory(WorkflowClient workflowClient,
                                              CompleteReservationActivitiesImpl activities,
                                              ObjectProvider<OpenTracingWorkerInterceptor> tracingInterceptor) {
        WorkerFactoryOptions.Builder factoryOptions = WorkerFactoryOptions.newBuilder();
        tracingInterceptor.ifAvailable(interceptor -> factoryOptions.setWorkerInterceptors(interceptor));

        WorkerFactory factory = WorkerFactory.newInstance(workflowClient, factoryOptions.build());
        Worker worker = factory.newWorker(temporalProperties.getTaskQueue());
        worker.registerWorkflowImplementationTypes(CompleteReservationWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        return factory;
    }
}
