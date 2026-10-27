package io.github.ikhemissi.sdp.migrator;

import io.github.ikhemissi.sdp.migrator.MigratorProperties.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;

/**
 * One-shot job: applies Flyway migrations and/or database maintenance, then exits. The process exit code is 0
 * on success and non-zero on failure, so the Container Apps job execution reflects the outcome.
 */
@SpringBootApplication
@EnableConfigurationProperties(MigratorProperties.class)
public class DbMigratorApplication {

    private static final Logger log = LoggerFactory.getLogger(DbMigratorApplication.class);

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(DbMigratorApplication.class, args)));
    }

    @Bean
    FlywayMigrationStrategy migrationStrategy(MigratorProperties properties, PrincipalsTask principals) {
        return flyway -> {
            if (properties.tasks().contains(Task.MIGRATE)) {
                principals.ensurePrincipals();
                var result = flyway.migrate();
                principals.grantGroupRoles();
                var current = flyway.info().current();
                log.info("Flyway migration completed: {} migration(s) applied, schema version {}",
                        result.migrationsExecuted, current == null ? "none" : current.getVersion());
            } else {
                log.info("Flyway migration skipped (task 'migrate' not selected)");
            }
        };
    }

    @Bean
    ApplicationRunner maintenanceRunner(MigratorProperties properties, MaintenanceTask maintenance) {
        return args -> {
            if (properties.tasks().contains(Task.MAINTAIN)) {
                maintenance.run();
            }
        };
    }
}
