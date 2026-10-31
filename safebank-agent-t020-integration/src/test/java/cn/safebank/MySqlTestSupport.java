package cn.safebank;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Explicit, isolated real-MySQL test support. Never falls back to H2. */
final class MySqlTestSupport {
    static String required(String key) {
        String value=System.getenv(key);
        if(value==null||value.isBlank()) throw new IllegalStateException("Missing "+key);
        return value;
    }
    static DataSource fresh(String purpose) {
        String url=required("SAFEBANK_MYSQL_TEST_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3307/safebank_v07_test_[A-Za-z0-9_]+(?:\\?.*)?"))
            throw new IllegalStateException("Only isolated loopback MySQL test database is allowed");
        String name="safebank_v07_test_"+purpose+"_"+UUID.randomUUID().toString().replace("-","");
        if(!name.matches("[a-z0-9_]+")||name.length()>64) throw new IllegalArgumentException("Invalid test database name");
        var admin=source(url);
        try(var c=admin.getConnection();var s=c.createStatement()) {
            s.execute("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        } catch(SQLException e) {throw new IllegalStateException("Could not create isolated MySQL test database",e);}
        return source(url.replaceFirst("/safebank_v07_test_[A-Za-z0-9_]+","/"+name));
    }
    static DriverManagerDataSource source(String url) {
        return new DriverManagerDataSource(url,required("SAFEBANK_MYSQL_TEST_USER"),required("SAFEBANK_MYSQL_TEST_PASSWORD"));
    }
    static Flyway migration(DataSource source) {
        return Flyway.configure().dataSource(source).locations("classpath:db/migration/mysql")
            .cleanDisabled(true).validateMigrationNaming(true).load();
    }
    static long scalar(DataSource source,String query) throws SQLException {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(query)) {r.next();return r.getLong(1);}
    }
    static void execute(DataSource source,String query) throws SQLException {
        try(var c=source.getConnection();var s=c.createStatement()) {s.execute(query);}
    }
}
