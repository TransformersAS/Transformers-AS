package com.transformersas.marketplace;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class MarketplaceApplicationTests {

	@Container
	@ServiceConnection
	static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
			.withDatabaseName("marketplace_test")
			.withUsername("marketplace_test_user")
			.withPassword("marketplace_test_password");

	@Autowired
	DataSource dataSource;

	@Autowired
	Flyway flyway;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ApplicationContext applicationContext;

	@Autowired
	CacheManager cacheManager;

	@Test
	void contextLoads() throws Exception {
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).isEqualTo(mysql.getJdbcUrl());
			assertThat(connection.getMetaData().getDatabaseProductVersion()).isEqualTo("8.4.11");
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
		assertThat(applicationContext.getBeansOfType(UserDetailsService.class)).isEmpty();
		assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
		assertThat(cacheManager.getCacheNames()).isEmpty();
	}

	@Test
	void healthEndpointsRemainPublic() throws Exception {
		for (String path : new String[] {"/actuator/health", "/actuator/health/liveness",
				"/actuator/health/readiness"}) {
			mockMvc.perform(get(path))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("UP"))
					.andExpect(jsonPath("$.components").doesNotExist());
		}
		mockMvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
	}

}
