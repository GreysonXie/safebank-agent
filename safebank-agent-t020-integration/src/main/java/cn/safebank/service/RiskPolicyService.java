package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.*;
import cn.safebank.common.RiskTypes.*;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
@Service public class RiskPolicyService {
    public static final String VERSION="bank-risk-v1";
    public static final long DAILY_THRESHOLD=100000;
    public RiskEvaluation evaluate(ActionKind kind,long amountMinor,long effectiveMinor,LocalDate date){
        if(amountMinor<0||effectiveMinor<0)throw new BankException("RISK_AMOUNT_INVALID","风险金额无效");
        boolean transfer=kind==ActionKind.TRANSFER;
        long projected;
        try{projected=transfer?Math.addExact(effectiveMinor,amountMinor):effectiveMinor;}catch(ArithmeticException overflow){throw new BankException("RISK_AMOUNT_OVERFLOW","风险金额超出可核验范围");}
        RiskLevel level=transfer&&projected>DAILY_THRESHOLD?RiskLevel.RED:RiskLevel.YELLOW;
        return new RiskEvaluation(level,level==RiskLevel.RED?AssuranceRequirement.STEP_UP:AssuranceRequirement.CONFIRMED,transfer?amountMinor:0,effectiveMinor,projected,level==RiskLevel.RED?"DAILY_OUTGOING_THRESHOLD_EXCEEDED":"EXPLICIT_CONFIRMATION_REQUIRED",date,VERSION);
    }
}
