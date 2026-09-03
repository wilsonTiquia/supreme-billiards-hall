package com.supremebilliardshall.billiards_hall_system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Hibernate writes jsonb columns through its own Jackson mapper, not the one the web layer
// uses. Left at its default that mapper has no java.time support, so an OffsetDateTime inside
// an audit_log snapshot fails at flush time and rolls back the change it was recording.
// ISO-8601 rather than epoch numbers because these columns get read by eye.
@Configuration
public class HibernateJsonConfig {

    @Bean
    public HibernatePropertiesCustomizer jsonFormatMapperCustomizer() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        return properties -> properties.put(
                AvailableSettings.JSON_FORMAT_MAPPER,
                new JacksonJsonFormatMapper(objectMapper));
    }
}
