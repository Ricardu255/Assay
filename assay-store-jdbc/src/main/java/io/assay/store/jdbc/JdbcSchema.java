package io.assay.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Creates the portable JDBC schema used by {@link JdbcResultStore}. */
final class JdbcSchema {

    private JdbcSchema() {
    }

    static void create(JdbcResultStore.ConnectionFactory connections) {
        try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists assay_run (
                      run_id varchar(128) primary key,
                      adapter varchar(256) not null,
                      suite varchar(128) not null,
                      source varchar(32) not null,
                      status varchar(32) not null,
                      started_at timestamp not null,
                      finished_at timestamp,
                      metadata_json text not null
                    )
                    """);
            statement.execute("""
                    create table if not exists assay_case (
                      run_id varchar(128) not null,
                      case_id varchar(256) not null,
                      payload_json text not null,
                      hard_pass boolean not null,
                      soft_warning_count int not null,
                      updated_at timestamp not null,
                      primary key (run_id, case_id)
                    )
                    """);
            statement.execute("""
                    create table if not exists assay_case_manifest (
                      run_id varchar(128) not null,
                      case_id varchar(256) not null,
                      content_sha256 varchar(64) not null,
                      primary key (run_id, case_id)
                    )
                    """);
            statement.execute("""
                    create table if not exists assay_review (
                      id varchar(64) primary key,
                      run_id varchar(128) not null,
                      case_id varchar(256) not null,
                      decision varchar(64) not null,
                      final_conclusion text not null,
                      reviewed_at timestamp not null
                    )
                    """);
            statement.execute("""
                    create table if not exists assay_evolution (
                      experiment_id varchar(128) primary key,
                      candidate_id varchar(256) not null,
                      decision varchar(32) not null,
                      result_json text not null,
                      created_at timestamp not null
                    )
                    """);
            statement.execute("""
                    create table if not exists assay_lock (
                      run_id varchar(128) primary key,
                      acquired_at timestamp not null
                    )
                    """);
        } catch (SQLException error) {
            throw new IllegalStateException("cannot initialize JDBC store schema", error);
        }
    }
}
