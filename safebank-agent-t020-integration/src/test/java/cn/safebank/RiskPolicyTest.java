package cn.safebank;
import cn.safebank.service.RiskPolicyService;
import cn.safebank.common.ActionKind;
import cn.safebank.common.RiskTypes.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RiskPolicyTest {
    private final RiskPolicyService policy=new RiskPolicyService();
    private final LocalDate date=LocalDate.of(2026,10,9);
    @Test void exactDailyBoundary(){
        assertEquals(RiskLevel.YELLOW,policy.evaluate(ActionKind.TRANSFER,100000,0,date).level());
        assertEquals(RiskLevel.RED,policy.evaluate(ActionKind.TRANSFER,100001,0,date).level());
        assertEquals(RiskLevel.RED,policy.evaluate(ActionKind.TRANSFER,60000,60000,date).level());
        assertEquals(AssuranceRequirement.CONFIRMED,policy.evaluate(ActionKind.SCHEDULE,600000,0,date).requiredAssurance());
    }
    @Test void overflowFailsClosed(){assertThrows(BankException.class,()->policy.evaluate(ActionKind.TRANSFER,1,Long.MAX_VALUE,date));}
}
