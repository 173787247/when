package com.when.api.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WhenHttpConfiguration {
    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> bean = new FilterRegistrationBean<>(new RequestIdFilter());
        bean.setOrder(Integer.MIN_VALUE + 100);
        bean.addUrlPatterns("/api/v1/*", "/admin/v1/*");
        return bean;
    }

    @Bean
    ObjectMapper whenObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }
}
