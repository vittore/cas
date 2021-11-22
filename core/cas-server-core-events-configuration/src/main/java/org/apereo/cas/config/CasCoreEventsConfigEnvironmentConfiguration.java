package org.apereo.cas.config;

import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.CasConfigurationPropertiesEnvironmentManager;
import org.apereo.cas.support.events.listener.DefaultCasConfigurationEventListener;
import org.apereo.cas.util.spring.CasEventListener;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * This is {@link CasCoreEventsConfigEnvironmentConfiguration}.
 *
 * @author Misagh Moayyed
 * @since 5.1.0
 */
@Configuration(value = "CasCoreEventsConfigEnvironmentConfiguration", proxyBeanMethods = false)
@EnableConfigurationProperties(CasConfigurationProperties.class)
public class CasCoreEventsConfigEnvironmentConfiguration {

    @ConditionalOnMissingBean(name = "casConfigurationEventListener")
    @Bean
    public CasEventListener casConfigurationEventListener(
        @Qualifier("configurationPropertiesEnvironmentManager")
        final CasConfigurationPropertiesEnvironmentManager manager,
        final ConfigurationPropertiesBindingPostProcessor binder,
        final ContextRefresher contextRefresher,
        final ConfigurableApplicationContext applicationContext) {
        return new DefaultCasConfigurationEventListener(manager, binder, contextRefresher, applicationContext);
    }
}
