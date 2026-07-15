package com.schwab.eventledger.gateway.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class AppConfig {

    @Bean
    RestClient accountRestClient(
            RestClient.Builder builder,
            GatewayProperties properties,
            ObservationRegistry observationRegistry,
            TracePropagationInterceptor tracePropagationInterceptor) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.getAccountService().getConnectTimeout())
                .withReadTimeout(properties.getAccountService().getReadTimeout());
        return builder
                .baseUrl(properties.getAccountService().getBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .observationRegistry(observationRegistry)
                .requestInterceptor(tracePropagationInterceptor)
                .build();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
