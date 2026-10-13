package cn.safebank;
import cn.safebank.bank.FaultInjectingBankAdapter;
import cn.safebank.service.ActionService;
import cn.safebank.dto.TransferPreviewRequestDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mybatis-recovery;DB_CLOSE_DELAY=-1","bank.model.api-key=","bank.simulation.faults-enabled=true"})
class MyBatisRecoveryTest extends BankTestFixture {
    @Autowired cn.safebank.service.ActionExecutionService execution;
    @Autowired ActionService actions;
    @Autowired FaultInjectingBankAdapter faults;
    @TempDir Path temporary;
    @Test void lostResponseRequiresLookupAndNotSubmittedCanRetryOriginalAction() {
        var a=actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",50000));bank.authorize("u1",a.id(),a.payloadHash());
        faults.configure("u1",a.id(),AFTER_COMMIT_RESPONSE_LOST,NONE);
        assertEquals("UNKNOWN",execution.submit("u1",a.id()).action().status().name());
        faults.configure("u1",a.id(),NONE,LOOKUP_UNAVAILABLE);
        assertEquals("UNKNOWN",execution.observe("u1",a.id()).action().status().name());
        faults.configure("u1",a.id(),NONE,NONE);
        assertEquals("VERIFIED_SUCCESS",execution.observe("u1",a.id()).action().status().name());
        execution.submit("u1",a.id());assertEquals(1193000,bank.balance("u1","checking"));
        var b=actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",10000));bank.authorize("u1",b.id(),b.payloadHash());
        db.update("INSERT INTO execution_observations VALUES(?,'u1','UNKNOWN',NULL,'SUBMISSION_STARTED',CURRENT_TIMESTAMP)",b.id());
        assertEquals("NOT_SUBMITTED",((cn.safebank.vo.ExecutionReceiptVO)execution.observe("u1",b.id())).observation());
        bank.authorize("u1",b.id(),b.payloadHash());assertEquals("VERIFIED_SUCCESS",execution.submit("u1",b.id()).action().status().name());
    }
    @Test void fileDatabaseRecoveryAfterRestartNeverRepeatsDebit() {
        String url="jdbc:h2:file:"+temporary.resolve("recovery").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE";
        String id;TestFileRiskSchema.prepare(url);
        try(var context=new SpringApplicationBuilder(Application.class).run("--spring.main.web-application-type=none","--spring.datasource.url="+url,"--bank.model.api-key=","--bank.simulation.faults-enabled=true","--logging.level.root=ERROR")){
            var actions=context.getBean(ActionService.class);var a=actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",50000));id=a.id();TestFileRiskSchema.authorize(context,a);
            context.getBean(FaultInjectingBankAdapter.class).configure("u1",id,AFTER_COMMIT_RESPONSE_LOST,NONE);
            assertEquals("UNKNOWN",context.getBean(cn.safebank.service.ActionExecutionService.class).submit("u1",id).action().status().name());
        }
        try(var context=new SpringApplicationBuilder(Application.class).run("--spring.main.web-application-type=none","--spring.datasource.url="+url,"--bank.model.api-key=","--bank.simulation.faults-enabled=true","--logging.level.root=ERROR")){
            var execution=context.getBean(cn.safebank.service.ActionExecutionService.class);
            assertEquals("VERIFIED_SUCCESS",execution.observe("u1",id).action().status().name());execution.submit("u1",id);
            assertEquals(1193000,context.getBean(cn.safebank.service.AccountService.class).balance("u1","checking"));
        }
    }
}
