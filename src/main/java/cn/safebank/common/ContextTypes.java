package cn.safebank.common;
import cn.safebank.BankException;
import java.util.Locale;
public final class ContextTypes {
    private ContextTypes() {}
    public enum Intent { STATEMENT_ANALYSIS,TRANSACTION_CHECK,MONTH_COMPARISON,TRANSFER_DRAFT,MANDATE_QUERY,MANDATE_REVOKE_DRAFT,SAVINGS_WORKFLOW,ACTION_STATUS,CLARIFICATION,GENERAL }
    public enum ConversationState { ACTIVE,NEEDS_CLARIFICATION,AWAITING_CONFIRMATION,PAUSED,EXPIRED }
    public enum TurnState { PROCESSING,COMPLETED,FAILED,INTERRUPTED }
    public enum SlotProvenance { USER_EXPLICIT,MODEL_PROPOSED,BANK_CONFIRMED }
    public enum SlotName {
        PRIMARY_MONTH,COMPARISON_MONTH,FOCUSED_TRANSACTION_ID,RECIPIENT_CANDIDATES,SELECTED_RECIPIENT_ID,SOURCE_ACCOUNT_ID,AMOUNT_MINOR,MANDATE_CANDIDATES,SELECTED_MANDATE_IDS,EXCLUDED_MANDATE_IDS,FIRST_DATE,END_DATE,DAY_OF_MONTH,ACTIVE_ACTION_ID,ACTIVE_WORKFLOW_ID,MISSING_SLOTS;
        public String key(){return name().toLowerCase(Locale.ROOT);}
        public static SlotName from(String key){try{return valueOf(key.toUpperCase(Locale.ROOT));}catch(Exception e){throw new BankException("VALIDATION_ERROR","不支持的业务参数");}}
    }
}
