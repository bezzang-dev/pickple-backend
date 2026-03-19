package com.pickple.common_module.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventSerializer {

    private static final Logger log = LoggerFactory.getLogger(EventSerializer.class);

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private EventSerializer() {
    }

    public static <T> String serialize(T object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("직렬화 중 오류 발생: type={}", object.getClass().getName(), e);
            throw new RuntimeException("직렬화 중 오류 발생: " + e.getMessage(), e);
        }
    }

    public static <T> T deserialize(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("역직렬화 중 오류 발생: targetType={}", clazz.getName(), e);
            throw new RuntimeException("역직렬화 중 오류 발생: " + e.getMessage(), e);
        }
    }
}