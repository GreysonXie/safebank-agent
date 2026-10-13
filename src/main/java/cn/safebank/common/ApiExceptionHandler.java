package cn.safebank.common;
import cn.safebank.BankException;
import cn.safebank.vo.ErrorVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice public class ApiExceptionHandler {
    @ExceptionHandler(RiskRejectedException.class) public ResponseEntity<cn.safebank.vo.RiskErrorVO> riskError(RiskRejectedException e) {
        var f=e.failure();int status=switch(f.code()){
            case "STEP_UP_REQUIRED","VERIFICATION_INVALID","VERIFICATION_EXPIRED"->403;
            case "CHALLENGE_RATE_LIMITED"->429;
            case "SECURITY_LOCKED","RISK_RECONCILIATION_REQUIRED"->423;
            case "VERIFICATION_UNAVAILABLE"->503;
            case "NOT_FOUND"->404;
            default->409;
        };
        return ResponseEntity.status(status).body(new cn.safebank.vo.RiskErrorVO(f.code(),f.message(),f.actionId(),f.security(),f.retryAt()));
    }
    @ExceptionHandler(BankException.class) public ResponseEntity<ErrorVO> bankError(BankException e,HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        int status=(path.equals("/api/workflows")||path.startsWith("/api/workflows/"))&&e.code.equals("CONFLICT")?409:400;
        if(path.equals("/api/chat")||path.startsWith("/api/conversations/"))status=switch(e.code){case "CONFLICT","CONTEXT_PROCESSING","CONTEXT_INTERRUPTED"->409;case "NOT_FOUND"->404;case "CONTEXT_EXPIRED"->410;default->400;};
        return ResponseEntity.status(status).body(new ErrorVO(e.code,e.getMessage()));
    }
}
