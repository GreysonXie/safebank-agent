package cn.safebank.config;
import cn.safebank.dao.RiskSchemaMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import java.util.Map;
@Component @DependsOnDatabaseInitialization
public class RiskSchemaInitializer implements InitializingBean {
    private static final Map<String,Integer> CONSTRAINTS=Map.ofEntries(Map.entry("RISK_USER_GUARDS",3),Map.entry("RISK_DECISIONS",13),Map.entry("VERIFICATION_CHALLENGES",8),Map.entry("ACTION_AUTHORIZATIONS",10),Map.entry("RISK_TRANSFER_USAGE",9),Map.entry("SCHEDULE_AUTHORIZATIONS",6),Map.entry("RISK_EVENTS",6));
    private final DataSource source;
    private final RiskSchemaMapper schema;
    private final boolean createNew;
    private final String url;
    private final org.springframework.beans.factory.ObjectProvider<MySqlSchemaVerifier> mysql;
    public RiskSchemaInitializer(DataSource source,RiskSchemaMapper schema,@Value("${bank.risk.schema.create-new:false}") boolean createNew,@Value("${spring.datasource.url}") String url,org.springframework.beans.factory.ObjectProvider<MySqlSchemaVerifier> mysql){
        this.source=source;this.schema=schema;this.createNew=createNew;this.url=url;this.mysql=mysql;
    }
    @Override public void afterPropertiesSet(){initialize();}
    public void initialize(){
        if(url.startsWith("jdbc:mysql:")){mysql.getObject().verify();return;}
        long count=CONSTRAINTS.keySet().stream().filter(t->schema.tableCount(t)==1).count();
        if(count==0){
            if(!createNew||!url.startsWith("jdbc:h2:mem:"))throw new IllegalStateException("Risk schema unavailable: explicit fresh in-memory schema or reviewed file migration required");
            new ResourceDatabasePopulator(new ClassPathResource("schema-risk.sql")).execute(source);
        }else if(count!=CONSTRAINTS.size())throw new IllegalStateException("Incomplete risk schema; refusing automatic repair");
        if(schema.ownerConstraintCount()!=2||CONSTRAINTS.entrySet().stream().anyMatch(e->schema.constraintCount(e.getKey())!=e.getValue()))
            throw new IllegalStateException("Incomplete risk constraints; refusing authorization");
    }
}
