package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.common.RiskTypes.*;
import cn.safebank.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:riskQuery;DB_CLOSE_DELAY=-1"})
class RiskQueryTest extends BankTestFixture {
    @Autowired RiskQueryService queries;@Autowired AuthorizationGateService gate;
    private String confirmed(){var a=queries.prepareMandates("u1",RiskChannel.HTTP);assertTrue(gate.authorize("u1",a.id(),new AuthorizationRequestDTO(a.payloadHash(),a.security().decisionId(),true,null),RiskChannel.HTTP).accepted());return a.id();}
    @Test void confirmedQueryDoesNotCallMoneyAdapterOrCreateLedger(){String id=confirmed();var result=queries.executeAuthorized("u1",id);assertEquals(2,result.data().size());assertNotNull(result.queriedAt());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM risk_transfer_usage",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM execution_observations",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));assertEquals(1243000,bank.balance("u1","checking"));}
    @Test void repeatQueryReturnsReceiptWithoutPretendingFreshData(){String id=confirmed();var first=queries.executeAuthorized("u1",id);var repeat=queries.executeAuthorized("u1",id);assertEquals(first.action().receiptId(),repeat.action().receiptId());assertNull(repeat.data());assertNull(repeat.queriedAt());}
}
