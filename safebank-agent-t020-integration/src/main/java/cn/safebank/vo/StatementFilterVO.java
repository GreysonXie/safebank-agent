package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
/**
 * 单月账单的受控组合筛选结果：月份 + 类别 + 收支方向 + 单笔金额下限（严格大于，单位分）。
 * 合计由服务端对匹配行重算，与 transactions 同源；matched_count 是全部匹配数，
 * 明细可能因容量或隔离被省略，省略不代表没有记录。
 */
public record StatementFilterVO(String month,String category,String direction,
    @JsonProperty("direction_code") String directionCode,
    @JsonProperty("min_amount_minor") long minAmountMinor,
    @JsonProperty("expense_minor") long expenseMinor,
    @JsonProperty("income_minor") long incomeMinor,
    @JsonProperty("matched_count") int matchedCount,
    @JsonProperty("list_scope_note") String listScopeNote,
    Map<String,Long> categories,
    List<TransactionVO> transactions) {}
