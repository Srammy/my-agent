package com.example.myagent.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.jdbc.DataSourceBuilder;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.boot.ApplicationRunner;

@Configuration
public class KnowledgePostgresConfiguration {

  @Bean(name = "knowledgePostgresqlDataSource", destroyMethod = "close")
  HikariDataSource knowledgePostgresqlDataSource(KnowledgeProperties properties) {
    KnowledgeProperties.Postgresql postgres = properties.postgresql();
    return DataSourceBuilder.create()
        .type(HikariDataSource.class)
        .url(postgres.url())
        .username(postgres.username())
        .password(postgres.password())
        .build();
  }

  @Bean(name = "knowledgePostgresqlJdbcTemplate")
  JdbcTemplate knowledgePostgresqlJdbcTemplate(
      @org.springframework.beans.factory.annotation.Qualifier("knowledgePostgresqlDataSource")
          DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  @Bean(name = "knowledgePostgresqlTransactionManager")
  DataSourceTransactionManager knowledgePostgresqlTransactionManager(
      @org.springframework.beans.factory.annotation.Qualifier("knowledgePostgresqlDataSource")
          DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  @Bean(name = "knowledgePostgresqlFlyway")
  Flyway knowledgePostgresqlFlyway(
      KnowledgeProperties properties,
      @org.springframework.beans.factory.annotation.Qualifier("knowledgePostgresqlDataSource")
          DataSource dataSource) {
    KnowledgeProperties.Postgresql postgres = properties.postgresql();
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(postgres.schema())
        .locations("classpath:" + postgres.migrationLocations())
        .load();
  }

  @Bean
  ApplicationRunner knowledgePostgresqlMigrationRunner(
      @org.springframework.beans.factory.annotation.Qualifier("knowledgePostgresqlFlyway")
          Flyway flyway) {
    return args -> flyway.migrate();
  }
}
