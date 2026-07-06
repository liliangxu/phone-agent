package io.github.liliangxu.phoneagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

@SpringBootApplication
@EnableConfigurationProperties(PhoneAgentProperties.class)
@EnableScheduling
public class PhoneAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PhoneAgentApplication.class, args);
    }
}
