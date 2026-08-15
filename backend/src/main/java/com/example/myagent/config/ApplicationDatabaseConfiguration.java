package com.example.myagent.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class ApplicationDatabaseConfiguration {

  @Bean(name = "dataSource", destroyMethod = "close")
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource applicationDataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder()
        .type(HikariDataSource.class)
        .build();
  }

  @Bean(name = "applicationFlyway")
  Flyway applicationFlyway(DataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load();
  }

  @Bean(name = "applicationFlywayInitializer")
  FlywayMigrationInitializer applicationFlywayInitializer(
      @org.springframework.beans.factory.annotation.Qualifier("applicationFlyway") Flyway flyway) {
    return new FlywayMigrationInitializer(flyway);
  }
}
