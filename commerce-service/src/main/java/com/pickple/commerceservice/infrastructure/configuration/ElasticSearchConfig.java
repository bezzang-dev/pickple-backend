package com.pickple.commerceservice.infrastructure.configuration;

import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;
import org.springframework.data.elasticsearch.support.HttpHeaders;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Configuration
public class ElasticSearchConfig extends ElasticsearchConfiguration {
    private final String hostUrl;
    private final String username;
    private final String password;
    private final boolean sslEnabled;

    public ElasticSearchConfig(@Value("${spring.data.elasticsearch.host}") String hostUrl,
                         @Value("${spring.data.elasticsearch.username}") String username,
                         @Value("${spring.data.elasticsearch.password}") String password,
                         @Value("${spring.data.elasticsearch.ssl-enabled:false}") boolean sslEnabled) {
        this.hostUrl = hostUrl;
        this.username = username;
        this.password = password;
        this.sslEnabled = sslEnabled;
    }

    @Override
    @SneakyThrows
    public ClientConfiguration clientConfiguration() {
        var builder = ClientConfiguration.builder()
                .connectedTo(hostUrl);

        ClientConfiguration.TerminalClientConfigurationBuilder terminalBuilder = sslEnabled
                ? builder.usingSsl().withBasicAuth(username, password)
                : builder.withBasicAuth(username, password);

        return terminalBuilder
                .withConnectTimeout(Duration.ofSeconds(5))
                .withSocketTimeout(Duration.ofSeconds(3))
                .withHeaders(() -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.add("currentTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    return headers;
                })
                .build();
    }
}
