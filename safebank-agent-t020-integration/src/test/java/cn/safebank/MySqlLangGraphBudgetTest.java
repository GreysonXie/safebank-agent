package cn.safebank;
import cn.safebank.dto.*;
import cn.safebank.service.*;
import cn.safebank.model.DebugRequestBudget;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="SAFEBANK_MYSQL_TEST_URL",matches=".+")
class MySqlLangGraphBudgetTest {
 @TempDir static Path temp;static ConfigurableApplicationContext app;static JdbcTemplate db;
 @BeforeAll static void start(){var ds=(DriverManagerDataSource)MySqlTestSupport.fresh("graphbudget");app=new SpringApplicationBuilder(Application.class).profiles("mysql","mysql-test","agent-gateway","langgraph-agent").run("--server.port=0","--spring.datasource.url="+ds.getUrl(),"--bank.agent.service-key="+"x".repeat(64),"--bank.agent.reconcile.enabled=false","--bank.model.debug-budget.path="+temp.resolve("budget.json"),"--logging.level.root=ERROR","--spring.main.banner-mode=off");db=new JdbcTemplate(ds);}
 @AfterAll static void close(){if(app!=null)app.close();}
 @Test void javaAcceptedLowerLimitSurvivesGrantRenewalAndCannotBeRaised(){
  var turns=app.getBean(GatewayTurnService.class);var caps=app.getBean(AgentCapabilityService.class);var budget=app.getBean(AgentBudgetService.class);
  var run=turns.begin("u1",new ChatRequestDTO("仅一次模型请求",1),Instant.now().plusSeconds(1800));String turn=run.lease().turnId();String token=caps.issue(turn);
  assertEquals(1,db.queryForObject("SELECT input_limit FROM agent_model_turn_limits WHERE turn_id=?",Integer.class,turn));
  var reserve=new AgentBudgetReserveDTO("1".repeat(64),"2".repeat(64),turn,"plan");var first=budget.reserve(token,reserve);String renewed=caps.renew(turn,token);
  assertEquals(first.get("reservationId"),budget.reserve(renewed,reserve).get("reservationId"));
  assertEquals("MODEL_TURN_BUDGET_EXCEEDED",assertThrows(BankException.class,()->budget.reserve(renewed,new AgentBudgetReserveDTO("3".repeat(64),"4".repeat(64),turn,"draft"))).code);
  assertEquals(1,app.getBean(DebugRequestBudget.class).snapshot().reserved());
  assertThrows(BankException.class,()->budget.reserve(token,reserve));
 }
}

