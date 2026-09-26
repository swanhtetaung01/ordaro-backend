package app.trillopos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import app.trillopos.admin.AdminCommands;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TrilloposBackendApplication {

    /** {@code admin <command>} runs an operator command and exits; anything else starts the API. */
    public static void main(String[] args) {
        if (args.length > 0 && "admin".equals(args[0])) {
            System.exit(AdminCommands.launch(TrilloposBackendApplication.class, args));
        }
        SpringApplication.run(TrilloposBackendApplication.class, args);
    }
}
