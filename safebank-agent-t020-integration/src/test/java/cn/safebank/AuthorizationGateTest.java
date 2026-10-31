package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import cn.safebank.common.RiskTypes.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:authorizationGate;DB_CLOSE_DELAY=-1","bank.verification.demo-enabled=true"})
@Import({TestVerificationConfiguration.class,RiskConcurrencyTest.Clocks.class})
class AuthorizationGateTest extends BankTestFixture {
    @Autowired ActionService actions;@Autowired AuthorizationGateService gate;@Autowired VerificationService verifier;
    @Autowired CapturingVerificationProvider provider;@Autowired MutableRiskClock clock;
    @BeforeEach void time(){clock.set(Instant.parse("2026-10-09T04:00:00Z"));provider.failDelivery=false;}
    private ActionVO preview(long amount){return actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",amount));}
    private AuthorizationRequestDTO request(ActionVO a,String proof,boolean confirmed){return new AuthorizationRequestDTO(a.payloadHash(),a.security().decisionId(),confirmed,proof);}
    private String proof(ActionVO a){var c=verifier.create("u1",a.id(),new ChallengeRequestDTO(a.payloadHash(),a.security().decisionId()),RiskChannel.HTTP).value();return verifier.verify("u1",a.id(),c.challengeId(),new ChallengeVerifyRequestDTO(provider.code(c.challengeId()))).value().verificationProofId();}
    @Test void twoSixHundredAuthorizationsRequireStepUp() throws Exception {
        var a=preview(60000);var b=preview(60000);CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var one=pool.submit(()->{start.await();return gate.authorize("u1",a.id(),request(a,null,true),RiskChannel.HTTP);});
            var two=pool.submit(()->{start.await();return gate.authorize("u1",b.id(),request(b,null,true),RiskChannel.HTTP);});start.countDown();
            var r1=one.get(10,TimeUnit.SECONDS);var r2=two.get(10,TimeUnit.SECONDS);assertNotEquals(r1.accepted(),r2.accepted());assertEquals("STEP_UP_REQUIRED",(r1.accepted()?r2:r1).failure().code());
        }
        assertEquals(60000,db.queryForObject("SELECT SUM(amount_minor) FROM risk_transfer_usage WHERE state='RESERVED'",Long.class));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void previewDoesNotReserve(){preview(150000);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM risk_transfer_usage",Integer.class));}
    @Test void duplicateAuthorizationReusesGrant(){var a=preview(60000);assertTrue(gate.authorize("u1",a.id(),request(a,null,true),RiskChannel.HTTP).accepted());assertTrue(gate.authorize("u1",a.id(),request(a,null,true),RiskChannel.HTTP).accepted());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM risk_transfer_usage",Integer.class));}
    @Test void proofCannotChangeHashPolicyOrDate(){var a=preview(150000);String p=proof(a);var wrong=new AuthorizationRequestDTO("bad",a.security().decisionId(),true,p);assertFalse(gate.authorize("u1",a.id(),wrong,RiskChannel.HTTP).accepted());db.update("UPDATE risk_decisions SET policy_version='old' WHERE id=?",a.security().decisionId());assertFalse(gate.authorize("u1",a.id(),request(a,p,true),RiskChannel.HTTP).accepted());clock.set(Instant.parse("2026-10-09T16:00:01Z"));assertFalse(gate.authorize("u1",a.id(),request(a,p,true),RiskChannel.HTTP).accepted());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM risk_transfer_usage",Integer.class));}
    @Test void unconfirmedRequestCannotConsumeVerifiedChallenge(){var a=preview(150000);String p=proof(a);assertFalse(gate.authorize("u1",a.id(),request(a,p,false),RiskChannel.HTTP).accepted());assertEquals("VERIFIED",db.queryForObject("SELECT status FROM verification_challenges WHERE id=?",String.class,p));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));}
    @Test void rejectionCommitsFreshDecisionAndAudit(){var a=preview(60000);var b=preview(60000);gate.authorize("u1",a.id(),request(a,null,true),RiskChannel.HTTP);var r=gate.authorize("u1",b.id(),request(b,null,true),RiskChannel.HTTP);assertEquals(RiskLevel.RED,r.failure().security().riskLevel());assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM risk_decisions WHERE action_id=?",Integer.class,b.id()));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM risk_events WHERE action_id=? AND reason_code='STEP_UP_REQUIRED'",Integer.class,b.id()));}
    @Test void cancelOnlyReleasesUnsubmittedUsage(){var a=preview(60000);gate.authorize("u1",a.id(),request(a,null,true),RiskChannel.HTTP);assertTrue(gate.cancel("u1",a.id(),RiskChannel.HTTP).accepted());assertEquals("RELEASED",db.queryForObject("SELECT state FROM risk_transfer_usage WHERE action_id=?",String.class,a.id()));var b=preview(50000);gate.authorize("u1",b.id(),request(b,null,true),RiskChannel.HTTP);db.update("UPDATE risk_transfer_usage SET state='SUBMITTED' WHERE action_id=?",b.id());assertFalse(gate.cancel("u1",b.id(),RiskChannel.HTTP).accepted());assertEquals("SUBMITTED",db.queryForObject("SELECT state FROM risk_transfer_usage WHERE action_id=?",String.class,b.id()));}
}
