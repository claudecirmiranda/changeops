package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.application.port.in.ProcessDeployResultUseCase;
import com.changeops.deployorchestrator.domain.event.DeployFinishedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeployEventConsumer {

    private final ProcessDeployResultUseCase processDeployResultUseCase;
    private final Counter consumeErrorCounter;
    private final Counter retryCounter;
    private final Counter dltCounter;

    public DeployEventConsumer(
            ProcessDeployResultUseCase processDeployResultUseCase,
            MeterRegistry meterRegistry) {
        this.processDeployResultUseCase = processDeployResultUseCase;
        this.consumeErrorCounter = Counter.builder("events_failed_total")
                .tag("consumer", "deploy-orchestrator")
                .register(meterRegistry);
        this.retryCounter = Counter.builder("events_retry_total")
                .tag("consumer", "deploy-orchestrator")
                .description("Total number of event processing retries")
                .register(meterRegistry);
        this.dltCounter = Counter.builder("events_dlt_total")
                .tag("consumer", "deploy-orchestrator")
                .description("Total number of events sent to DLT after max retries")
                .register(meterRegistry);
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 500, multiplier = 2.0, maxDelay = 10_000),
            autoCreateTopics = "true",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlt"
    )
    @KafkaListener(
            topics = "${changeops.kafka.topics.deploy-finished}",
            groupId = "${changeops.kafka.consumer.group-id}",
            containerFactory = "deployEventListenerContainerFactory"
    )
    public void onDeployFinished(
            ConsumerRecord<String, DeployFinishedEvent> record,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {

        DeployFinishedEvent event = record.value();

        MDC.put("correlation_id", event.correlationId() != null
                ? event.correlationId().toString() : "unknown");
        MDC.put("deploy_id", event.payload().deployId().toString());

        try {
            log.info("Received DeployFinishedEvent: topic={}, offset={}, deployId={}, result={}",
                    topic, offset, event.payload().deployId(), event.payload().result());

            processDeployResultUseCase.execute(event);

        } catch (Exception e) {
            consumeErrorCounter.increment();
            retryCounter.increment();
            log.error("Error processing DeployFinishedEvent — will retry: deployId={}, attempt topic={}",
                    event.payload().deployId(), topic, e);
            throw e;
        } finally {
            MDC.remove("correlation_id");
            MDC.remove("deploy_id");
        }
    }

    @KafkaListener(
            topics = "${changeops.kafka.topics.deploy-finished}-dlt",
            groupId = "${changeops.kafka.consumer.group-id}-dlt"
    )
    public void onDlt(ConsumerRecord<String, DeployFinishedEvent> record) {
        DeployFinishedEvent event = record.value();
        try {
            if (event != null && event.payload() != null) {
                MDC.put("correlation_id", event.correlationId() != null
                        ? event.correlationId().toString() : "unknown");
                MDC.put("change_id", event.payload().changeId() != null
                        ? event.payload().changeId().toString() : "unknown");
                MDC.put("deploy_id", event.payload().deployId() != null
                        ? event.payload().deployId().toString() : "unknown");
                log.error("Event sent to DLQ after max retries: key={}, deployId={}, changeId={}, result={}",
                        record.key(), event.payload().deployId(),
                        event.payload().changeId(), event.payload().result());
            } else {
                log.error("Event sent to DLQ after max retries: key={}, payload=null", record.key());
            }
            consumeErrorCounter.increment();
            dltCounter.increment();
        } finally {
            MDC.remove("correlation_id");
            MDC.remove("change_id");
            MDC.remove("deploy_id");
        }
    }
}
