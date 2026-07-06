package io.github.liliangxu.phoneagent.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class PhoneAgentDatabaseConfig {
    @Bean
    public String phoneAgentDatabaseInitializer(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return "phoneAgentDatabaseInitialized";
    }
}
