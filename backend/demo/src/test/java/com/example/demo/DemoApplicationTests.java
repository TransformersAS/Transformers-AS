package com.example.demo;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class DemoApplicationTests {

	@Container
	@ServiceConnection
	static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
			.withDatabaseName("marketplace_test")
			.withUsername("marketplace_test_user")
			.withPassword("marketplace_test_password");

	@Autowired
	DataSource dataSource;

	@Autowired
	Flyway flyway;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Test
	void contextLoads() throws Exception {
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).isEqualTo(mysql.getJdbcUrl());
			assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("8.4.");
		}
		try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
			assertThat(connection.getMetaData().getURL()).isEqualTo(mysql.getJdbcUrl());
		}
		assertThat(new JdbcTemplate(dataSource).queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables "
						+ "WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'",
				Integer.class)).isEqualTo(1);
		assertThat(entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto"))
				.isEqualTo("validate");
	}

}
