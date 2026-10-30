package cn.safebank.wealth;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
/** 单产品在指定区间内的历史表现：端点为区间内首末已记录样本，变化由程序按落盘 4 位净值计算。 */
public record WealthHistoryVO(String product,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("requested_from") String requestedFrom,
    @JsonProperty("requested_to") String requestedTo,
    @JsonProperty("actual_from") String actualFrom,
    @JsonProperty("actual_to") String actualTo,
    @JsonProperty("nav_from") String navFrom,
    @JsonProperty("nav_to") String navTo,
    @JsonProperty("change_percent") BigDecimal changePercent,
    int records,
    @JsonProperty("dataset_version") String datasetVersion,
    @JsonProperty("catalog_dataset_id") String catalogDatasetId,
    @JsonProperty("max_drawdown_percent") BigDecimal maxDrawdownPercent,
    @JsonProperty("drawdown_peak_date") String drawdownPeakDate,
    @JsonProperty("drawdown_peak_nav") String drawdownPeakNav,
    @JsonProperty("drawdown_trough_date") String drawdownTroughDate,
    @JsonProperty("drawdown_trough_nav") String drawdownTroughNav,
    @JsonProperty("drawdown_note") String drawdownNote,
    @JsonProperty("coverage_note") String coverageNote,
    List<Map<String,Object>> series) {}
