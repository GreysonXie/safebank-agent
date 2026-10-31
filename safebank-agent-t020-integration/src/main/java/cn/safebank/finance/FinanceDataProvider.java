package cn.safebank.finance;

/** Future iFinD adapters must supply a dated, normalized, read-only catalog. */
public interface FinanceDataProvider {
    FinanceCatalog catalog();
}
