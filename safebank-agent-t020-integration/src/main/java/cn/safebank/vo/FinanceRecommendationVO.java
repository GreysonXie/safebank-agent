package cn.safebank.vo;

import cn.safebank.dto.FinanceRecommendationRequestDTO;
import cn.safebank.finance.FinanceProduct;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record FinanceRecommendationVO(
        String mode, String source, String catalogVersion, LocalDate asOf,
        LocalDate periodStart, LocalDate periodEnd, OffsetDateTime generatedAt,
        String disclosure, FinanceRecommendationRequestDTO preferences,
        int evaluatedCount, String summary, List<Candidate> candidates,
        List<Exclusion> exclusions, List<String> rules) {
    public record Candidate(FinanceProduct product, List<String> reasons) {}
    public record Exclusion(String productId, String productName, List<String> reasons) {}
}
