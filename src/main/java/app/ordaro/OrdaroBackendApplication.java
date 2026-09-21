package app.ordaro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OrdaroBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdaroBackendApplication.class, args);
    }
}
