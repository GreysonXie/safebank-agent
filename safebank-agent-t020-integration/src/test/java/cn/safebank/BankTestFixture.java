package cn.safebank;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;
@org.springframework.context.annotation.Import(TestBankConfiguration.class)
public abstract class BankTestFixture {
    @Autowired protected BankTestSupport bank;
    @Autowired protected cn.safebank.agent.BankToolGateway gateway;
    @Autowired protected JdbcTemplate db;
    @Autowired protected DataSource dataSource;
    @Autowired protected org.springframework.context.ApplicationContext context;
    @BeforeEach void resetFixture() {
        db.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("schema-risk.sql"),new ClassPathResource("schema-context.sql"),new ClassPathResource("data.sql")).execute(dataSource);
    }
    protected String authorize(java.util.Map<String,Object> a) {
        String id=a.get("id").toString();bank.authorize("u1",id,a.get("payload_hash").toString());return id;
    }
}
