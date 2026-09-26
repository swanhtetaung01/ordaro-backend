package app.trillopos.admin;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import app.trillopos.shared.web.ApiException;

/**
 * {@code java -jar trillopos-backend.jar admin <command> [argument]}: the application without its
 * web server, against the same database, for the server's operator. On the server:
 * {@code deploy/admin.sh reset-password 09xxxxxxxxx}.
 */
@Component
@ConditionalOnProperty("trillopos.admin.command")
public class AdminCommands implements ApplicationRunner, ExitCodeGenerator {

    static final String USAGE = """
            usage: admin <command> [argument]
              reset-password <phone>   set a new random password, end every session, clear the lockout
              unlock <phone>           clear a wrong-password lockout only
              shops                    list every shop on this server
            """;

    private final AdminService admin;
    private final Environment environment;
    private final PrintStream out;
    private int exitCode;

    public AdminCommands(AdminService admin, Environment environment) {
        this.admin = admin;
        this.environment = environment;
        this.out = System.out;
    }

    /** Starts the application in command mode and returns the exit code; used by {@code main}. */
    public static int launch(Class<?> application, String[] args) {
        String command = args.length > 1 ? args[1] : "help";
        String[] rest = args.length > 2 ? java.util.Arrays.copyOfRange(args, 2, args.length) : new String[0];
        SpringApplication app = new SpringApplication(application);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(Map.of("trillopos.admin.command", command, "logging.level.root", "WARN"));
        ConfigurableApplicationContext context = app.run(rest);
        return SpringApplication.exit(context);
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = environment.getProperty("trillopos.admin.command", "help");
        List<String> arguments = args.getNonOptionArgs();
        try {
            switch (command) {
                case "reset-password" -> {
                    String phone = required(arguments);
                    String temporary = admin.resetPassword(phone);
                    out.println("New password for " + phone + ": " + temporary);
                    out.println("Every session of this account has ended. Ask them to sign in with it and change");
                    out.println("it under Account straight away. It is shown only this once.");
                }
                case "unlock" -> {
                    String phone = required(arguments);
                    admin.unlock(phone);
                    out.println("Unlocked " + phone + ".");
                }
                case "shops" -> admin.shops().forEach(shop -> out.printf("%s  %-30s  %-10s  %s%n",
                        shop.createdAt(), shop.name(), shop.businessType(), shop.slug()));
                default -> {
                    out.print(USAGE);
                    exitCode = "help".equals(command) ? 0 : 2;
                }
            }
        } catch (ApiException e) {
            out.println("Error: " + e.getMessage());
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private String required(List<String> arguments) {
        if (arguments.isEmpty()) {
            throw ApiException.badRequest("argument_required", "this command needs a phone number");
        }
        return arguments.getFirst();
    }
}
