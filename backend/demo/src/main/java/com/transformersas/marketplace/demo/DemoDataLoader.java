package com.transformersas.marketplace.demo;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/**
 * Deja la base con datos de demostración al arrancar: cuentas de cada rol con el correo verificado, el catálogo de
 * ejemplo, pedidos en varios estados con su seguimiento, devoluciones y una reclamación. Así, arranque como arranque el
 * sistema (Compose, Swarm o el ejecutable de Windows), se puede usar de inmediato sin cargar nada a mano.
 *
 * <p>No es una migración de Flyway a propósito: quedaría registrada en el historial de esquema y una base que la
 * aplicó dejaría de validar si luego arranca sin ella (por ejemplo, el backend lanzado desde el IDE). El script
 * {@value #SCRIPT} es repetible: cada inserción comprueba si el dato ya existe, así que volver a arrancar no duplica ni
 * modifica nada. Corre en una sola transacción: o queda todo o no queda nada.
 *
 * <p>En Swarm las réplicas del backend arrancan a la vez sobre la misma base. Sin coordinación, sus inserciones se
 * bloquean entre sí y MySQL aborta una por interbloqueo, lo que tumba el arranque de esa réplica. Por eso la carga se
 * hace bajo un candado de MySQL ({@code GET_LOCK}): la primera réplica carga y confirma; la siguiente espera, entra
 * después y no encuentra nada que añadir. El candado se suelta solo tras confirmar, para que la siguiente vea los datos.
 *
 * <p>Se ejecuta antes que los demás arranques ({@link DemoProvisioning} y los del perfil {@code local}), que encuentran
 * así sus cuentas ya creadas con la misma contraseña y los mismos roles que esperan.
 */
@Component
@ConditionalOnProperty(name = "marketplace.demo-data.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DemoDataLoader implements ApplicationRunner {
    static final String SCRIPT = "demo-data/datos-demo.sql";
    static final String LOCK = "marketplace_demo_data";
    private static final int LOCK_TIMEOUT_SECONDS = 120;
    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final JdbcTemplate jdbc;
    private final String script;
    private final int lockTimeoutSeconds;

    @Autowired
    public DemoDataLoader(JdbcTemplate jdbc) {
        this(jdbc, SCRIPT, LOCK_TIMEOUT_SECONDS);
    }

    /** Para las pruebas: otro script y otra espera, sin cambiar el comportamiento real. */
    DemoDataLoader(JdbcTemplate jdbc, String script, int lockTimeoutSeconds) {
        this.jdbc = jdbc;
        this.script = script;
        this.lockTimeoutSeconds = lockTimeoutSeconds;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Una sola conexión para todo: el candado, las variables y las tablas temporales del script son de la sesión.
        jdbc.execute((ConnectionCallback<Void>) this::load);
        log.info("Datos de demostración listos: {} cuentas, {} productos, {} pedidos",
                count("user_accounts"), count("products"), count("orders"));
    }

    private Void load(Connection connection) throws SQLException {
        acquireLock(connection);
        boolean autoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource(script), StandardCharsets.UTF_8));
            connection.commit();
        } catch (RuntimeException | SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
            releaseLock(connection);
        }
        return null;
    }

    private void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            lock.setString(1, LOCK);
            lock.setInt(2, lockTimeoutSeconds);
            try (ResultSet result = lock.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new IllegalStateException("Otra réplica no terminó de cargar los datos de demostración en "
                            + lockTimeoutSeconds + " s");
                }
            }
        }
    }

    private static void releaseLock(Connection connection) throws SQLException {
        try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            release.setString(1, LOCK);
            release.execute();
        }
    }

    private long count(String table) {
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return rows == null ? 0 : rows;
    }
}
