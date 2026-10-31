package cn.safebank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
/** Explicit reviewed migration on disposable @TempDir databases only, never application startup. */
final class TestFileRiskSchema {
    static void prepare(String url){
        var source=new DriverManagerDataSource(url,"sa","");var db=new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("data.sql")).execute(source);
        if(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='RISK_USER_GUARDS'",Integer.class)==0)new ResourceDatabasePopulator(new ClassPathResource("schema-risk.sql")).execute(source);
        if(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='AGENT_CONVERSATIONS'",Integer.class)==0)new ResourceDatabasePopulator(new ClassPathResource("schema-context.sql")).execute(source);
    }
    static void authorize(org.springframework.context.ApplicationContext context,cn.safebank.vo.ActionVO a){
        var gate=context.getBean(cn.safebank.service.AuthorizationGateService.class);
        var d=gate.createDecision("u1",a.id(),cn.safebank.common.RiskTypes.RiskChannel.HTTP);
        cn.safebank.common.RiskRejectedException.value(gate.authorize("u1",a.id(),new cn.safebank.dto.AuthorizationRequestDTO(a.payloadHash(),d.decisionId(),true,null),cn.safebank.common.RiskTypes.RiskChannel.HTTP));
    }
}
