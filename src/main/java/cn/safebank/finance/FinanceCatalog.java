package cn.safebank.finance;

import java.time.LocalDate;
import java.util.List;

public record FinanceCatalog(String mode, String source, String version,
        LocalDate asOf, LocalDate periodStart, LocalDate periodEnd,
        String disclosure, List<FinanceProduct> products) {
    public FinanceCatalog { products = List.copyOf(products); }
}
