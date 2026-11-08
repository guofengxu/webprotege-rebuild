package wprebuild.app.access;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates one {@code SYSTEM_ADMIN} at startup when {@code users} is empty and
 * {@code wp.bootstrap.admin.username}, {@code password}, and {@code email} are all set.
 */
@Component
public final class BootstrapAdmin implements ApplicationRunner {

    static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final BootstrapAdminProperties properties;

    private final UserService users;

    private final JdbcUserRepository repository;

    private final TransactionTemplate transactions;

    public BootstrapAdmin(
            BootstrapAdminProperties properties,
            UserService users,
            JdbcUserRepository repository,
            PlatformTransactionManager transactions) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.users = Objects.requireNonNull(users, "users");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.partial()) {
            log.warn(
                    "ignoring bootstrap admin because wp.bootstrap.admin.username, password, and email must all be set");
            return;
        }
        if (!properties.configured()) {
            return;
        }
        transactions.executeWithoutResult(status -> {
            // Two nodes can start together. The lock makes "the table is empty" a single decision.
            repository.lockUsers();
            if (!repository.isEmpty()) {
                return;
            }
            String username = properties.username().strip();
            users.create(username, username, properties.email().strip(), properties.password(), List.of(SYSTEM_ADMIN));
            log.info("created bootstrap administrator {}", username);
        });
    }
}
