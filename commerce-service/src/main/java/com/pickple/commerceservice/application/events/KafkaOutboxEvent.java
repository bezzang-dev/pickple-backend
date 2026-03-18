package com.pickple.commerceservice.application.events;

/**
 * DB 트랜잭션 커밋 이후에 Kafka 메시지를 발행하기 위한 Spring 내부 이벤트.
 * {@link KafkaOutboxEventListener}가 AFTER_COMMIT 시점에 실제 발행을 처리합니다.
 */
public class KafkaOutboxEvent {

    private final String topic;
    private final String key;
    private final Object payload;

    public KafkaOutboxEvent(String topic, String key, Object payload) {
        this.topic = topic;
        this.key = key;
        this.payload = payload;
    }

    public String getTopic() {
        return topic;
    }

    public String getKey() {
        return key;
    }

    public Object getPayload() {
        return payload;
    }
}
