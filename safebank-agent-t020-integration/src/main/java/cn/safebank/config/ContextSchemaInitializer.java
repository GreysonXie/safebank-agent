package cn.safebank.config;
import cn.safebank.dao.ContextSchemaMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import java.util.List;
@Component @DependsOn("riskSchemaInitializer")
public class ContextSchemaInitializer implements InitializingBean {
    private final DataSource source;
    private final ContextSchemaMapper schema;
    private final boolean create;
    private final String url;
    public ContextSchemaInitializer(DataSource source,ContextSchemaMapper schema,
            @Value("${bank.context.schema.create-new:false}") boolean create,
            @Value("${spring.datasource.url}") String url) {
        this.source=source;this.schema=schema;this.create=create;this.url=url;
    }
    public void afterPropertiesSet() {
        // riskSchemaInitializer already verified the full MySQL manifest.
        if(url.startsWith("jdbc:mysql:"))return;
        var tables=List.of("agent_conversations","agent_turn_receipts","agent_context_evidence","agent_context_slots");
        long count=tables.stream().filter(t->schema.tableCount(t)==1).count();
        if(count==0){
            if(!create||!url.startsWith("jdbc:h2:mem:"))throw new IllegalStateException("Context schema requires explicit fresh memory initialization or reviewed migration");
            new ResourceDatabasePopulator(new ClassPathResource("schema-context.sql")).execute(source);
        }else if(count!=4)throw new IllegalStateException("Incomplete context schema; refusing automatic repair");
        if(schema.ownerConstraintCount()!=6)throw new IllegalStateException("Incomplete context ownership constraints");
    }
}
