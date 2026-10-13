package cn.safebank;

import cn.safebank.common.ActionPayloadCodec;
import cn.safebank.common.ActionKind;
import cn.safebank.dto.WorkflowRequestDTO;
import cn.safebank.service.*;
import cn.safebank.vo.ActionVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only a disposable file database; never points at data/ or an online database. */
class MyBatisFileDatabaseTest {
    @TempDir Path temporary;

    private ConfigurableApplicationContext open(String url) {
        TestFileRiskSchema.prepare(url);
        return new SpringApplicationBuilder(Application.class).run(
            "--spring.main.web-application-type=none", "--spring.datasource.url="+url,
            "--bank.model.api-key=", "--logging.level.root=ERROR");
    }

    @Test void legacyPayloadAndSavedWorkflowSurviveFileReopenWithoutReserialization() {
        String url="jdbc:h2:file:"+temporary.resolve("compatibility").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE";
        String raw="{ \"currency\":\"CNY\", \"amount_minor\":50000, \"recipient_id\":\"zhang-a\", \"source_id\":\"checking\", \"recipient_label\":\"张三\", \"fee_minor\":0 }";
        String hash;UUID workflowId;String pendingId;String workflowRequest;String workflowAnalysis;
        try(var context=open(url)) {
            var db=context.getBean(JdbcTemplate.class);
            hash=context.getBean(ActionPayloadCodec.class).hash("u1",ActionKind.TRANSFER,raw);
            // Seed the old persisted format directly, including noncanonical whitespace/key order.
            db.update("INSERT INTO actions(id,user_id,kind,payload,payload_hash,status,expires_at) VALUES('legacy-action','u1','TRANSFER',?,?,'AUTHORIZED',CURRENT_TIMESTAMP+INTERVAL '1' HOUR)",raw,hash);
            var request=new WorkflowRequestDTO("2026-09",List.of("music","video"),"checking","travel-recipient","2026-11-20","2027-10-20",20,true);
            var w=context.getBean(WorkflowService.class).create("u1",request);workflowId=w.id();pendingId=((ActionVO)w.pendingAction()).id();
            workflowRequest=db.queryForObject("SELECT request_json FROM workflows WHERE id=?",String.class,workflowId.toString());
            workflowAnalysis=db.queryForObject("SELECT analysis_json FROM workflows WHERE id=?",String.class,workflowId.toString());
        }
        try(var context=open(url)) {
            var db=context.getBean(JdbcTemplate.class);
            var actions=context.getBean(ActionService.class);
            assertEquals(hash,actions.status("u1","legacy-action").payloadHash());
            assertEquals(raw,db.queryForObject("SELECT payload FROM actions WHERE id='legacy-action'",String.class));
            var execution=context.getBean(cn.safebank.service.ActionExecutionService.class);
            assertEquals("AUTH_REQUIRED",assertThrows(BankException.class,()->execution.submit("u1","legacy-action")).code);
            TestFileRiskSchema.authorize(context,actions.status("u1","legacy-action"));
            assertEquals("VERIFIED_SUCCESS",execution.submit("u1","legacy-action").action().status().name());
            execution.submit("u1","legacy-action");
            assertEquals(1193000,context.getBean(AccountService.class).balance("u1","checking"));
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ledger WHERE action_id='legacy-action'",Integer.class));
            assertEquals(0L,db.queryForObject("SELECT SUM(amount_minor) FROM ledger WHERE action_id='legacy-action'",Long.class));
            var w=context.getBean(WorkflowService.class).resume("u1",workflowId,1);
            assertEquals(1,w.version());assertEquals(0,w.estimatedSavingMinor());assertEquals(pendingId,((ActionVO)w.pendingAction()).id());
            assertEquals(workflowRequest,db.queryForObject("SELECT request_json FROM workflows WHERE id=?",String.class,workflowId.toString()));
            assertEquals(workflowAnalysis,db.queryForObject("SELECT analysis_json FROM workflows WHERE id=?",String.class,workflowId.toString()));
        }
        try(var context=open(url)) {
            assertEquals(1193000,context.getBean(AccountService.class).balance("u1","checking"));
            assertEquals(hash,context.getBean(ActionService.class).status("u1","legacy-action").payloadHash());
        }
    }
}
