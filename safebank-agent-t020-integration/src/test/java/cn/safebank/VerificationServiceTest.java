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
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:verification;DB_CLOSE_DELAY=-1","bank.verification.demo-enabled=true"})
@Import({TestVerificationConfiguration.class,RiskConcurrencyTest.Clocks.class})
class VerificationServiceTest extends BankTestFixture {
    @Autowired VerificationService verification;@Autowired RiskDecisionService decisions;@Autowired RiskUserLockService locks;
    @Autowired CapturingVerificationProvider provider;@Autowired MutableRiskClock clock;
    @BeforeEach void time(){clock.set(Instant.parse("2026-10-09T04:00:00Z"));provider.failDelivery=false;}
    private ActionVO red(){String id=bank.preview("u1","checking","zhang-a",150000).get("id").toString();return locks.withUserLock("u1",()->decisions.actionVO(decisions.owned("u1",id,true),decisions.assessLocked("u1",id,RiskChannel.HTTP)));}
    private ChallengeVO challenge(ActionVO a){var d=decisions.view("u1",a.id());return verification.create("u1",a.id(),new ChallengeRequestDTO(a.payloadHash(),d.decisionId()),RiskChannel.HTTP).value();}
    @Test void correctCodeOnlyCreatesProof(){var a=red();var c=challenge(a);var result=verification.verify("u1",a.id(),c.challengeId(),new ChallengeVerifyRequestDTO(provider.code(c.challengeId())));assertTrue(result.accepted());assertEquals("AWAITING_AUTH",bank.status("u1",a.id()).get("status"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM risk_transfer_usage",Integer.class));}
    @Test void thirdFailureLocksChallenge(){var a=red();var c=challenge(a);for(int i=0;i<3;i++)assertFalse(verification.verify("u1",a.id(),c.challengeId(),new ChallengeVerifyRequestDTO("bad")).accepted());assertEquals("LOCKED",db.queryForObject("SELECT status FROM verification_challenges WHERE id=?",String.class,c.challengeId()));assertEquals(3,db.queryForObject("SELECT failed_verifications FROM risk_user_guards WHERE user_id='u1'",Integer.class));}
    @Test void fiveFailuresPersistUserLock(){var a=red();var c=challenge(a);for(int i=0;i<3;i++)verification.verify("u1",a.id(),c.challengeId(),new ChallengeVerifyRequestDTO("bad"));clock.set(clock.instant().plusSeconds(61));var b=red();var c2=challenge(b);for(int i=0;i<2;i++)verification.verify("u1",b.id(),c2.challengeId(),new ChallengeVerifyRequestDTO("bad"));assertNotNull(db.queryForObject("SELECT locked_until FROM risk_user_guards WHERE user_id='u1'",OffsetDateTime.class));assertEquals(5,db.queryForObject("SELECT failed_verifications FROM risk_user_guards WHERE user_id='u1'",Integer.class));assertFalse(verification.verify("u1",b.id(),c2.challengeId(),new ChallengeVerifyRequestDTO(provider.code(c2.challengeId()))).accepted());assertFalse(bank.accounts("u1").isEmpty());}
    @Test void deliveryFailureCannotProduceProof(){provider.failDelivery=true;var a=red();var d=decisions.view("u1",a.id());var result=verification.create("u1",a.id(),new ChallengeRequestDTO(a.payloadHash(),d.decisionId()),RiskChannel.HTTP);assertEquals("VERIFICATION_UNAVAILABLE",result.failure().code());assertEquals("DELIVERY_FAILED",db.queryForObject("SELECT status FROM verification_challenges",String.class));}
    @Test void crossActionOrUserProofRejected(){var a=red();var c=challenge(a);var b=red();assertFalse(verification.verify("u1",b.id(),c.challengeId(),new ChallengeVerifyRequestDTO(provider.code(c.challengeId()))).accepted());assertThrows(BankException.class,()->verification.verify("u2",a.id(),c.challengeId(),new ChallengeVerifyRequestDTO(provider.code(c.challengeId()))));}
    @Test void expiryClampedToMidnight(){clock.set(Instant.parse("2026-10-09T15:59:50Z"));var a=red();var c=challenge(a);assertEquals(Instant.parse("2026-10-09T16:00:00Z"),c.expiresAt());}
    @Test void ordinaryApiAndModelCannotReadCode() throws Exception {var a=red();var c=challenge(a);var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(c);assertFalse(json.contains(provider.code(c.challengeId())));assertFalse(json.contains("digest"));assertEquals("LOCAL_DEMO_ONLY",c.verificationMode());}
}
