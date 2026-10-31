package cn.safebank.config;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.*;
import java.time.OffsetDateTime;
import java.util.Properties;

@Configuration
public class MyBatisDialectConfig {
    @Bean @Profile("mysql") public java.time.Clock mysqlClock(@org.springframework.beans.factory.annotation.Value("${bank.simulation.clock-enabled:false}") boolean enabled){
        return new cn.safebank.common.SimulationClock(java.time.Clock.tick(java.time.Clock.systemUTC(),java.time.Duration.ofNanos(1000)),enabled);
    }
    @Bean DatabaseIdProvider databaseIdProvider(){
        var provider=new VendorDatabaseIdProvider();var properties=new Properties();
        properties.setProperty("H2","h2");properties.setProperty("MySQL","mysql");provider.setProperties(properties);return provider;
    }
    @Bean @Profile("mysql") ConfigurationCustomizer mysqlUtcTypes(){
        return configuration->configuration.getTypeHandlerRegistry().register(OffsetDateTime.class,new UtcOffsetDateTimeTypeHandler());
    }
}
