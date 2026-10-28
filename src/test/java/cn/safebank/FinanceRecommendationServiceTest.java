package cn.safebank;

import cn.safebank.dto.FinanceRecommendationRequestDTO;
import cn.safebank.finance.DemoFinanceDataProvider;
import cn.safebank.finance.FinanceCatalog;
import cn.safebank.finance.FinanceProduct;
import cn.safebank.service.FinanceRecommendationService;
import cn.safebank.vo.FinanceRecommendationVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static cn.safebank.dto.FinanceRecommendationRequestDTO.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceRecommendationServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T02:00:00Z"), ZoneOffset.UTC);
    private final DemoFinanceDataProvider provider = new DemoFinanceDataProvider();
    private final FinanceRecommendationService service = new FinanceRecommendationService(provider, clock);

    @Test void immediateReserveExcludesFundsAndTermDepositsEvenWithGrowthPreference() {
        var response = service.recommend(request(RiskPreference.GROWTH, Purpose.RESERVE, Liquidity.IMMEDIATE, 1000000, 36, false));
        assertEquals(List.of("DEMO-CASH"), ids(response));
        assertEquals(6, response.exclusions().size());
        assertTrue(response.exclusions().stream().filter(e -> e.productId().equals("DEMO-MONEY"))
                .flatMap(e -> e.reasons().stream()).anyMatch(reason -> reason.contains("等待赎回")));
    }

    @Test void capitalPreferenceKeepsOnlyDepositsAndFlexibleUseRemovesTermDeposit() {
        var lockable = service.recommend(request(RiskPreference.GROWTH, Purpose.PLANNED, Liquidity.LOCKABLE, 1000000, 12, true));
        assertEquals(List.of("DEMO-CASH", "DEMO-DEPOSIT"), ids(lockable));
        assertTrue(lockable.candidates().stream().allMatch(c -> c.product().capitalProtected()));
        var flexible = service.recommend(request(RiskPreference.GROWTH, Purpose.PLANNED, Liquidity.FLEXIBLE, 1000000, 12, true));
        assertEquals(List.of("DEMO-CASH"), ids(flexible));
    }

    @Test void amountAndHorizonAreEligibilityConditionsWithReasons() {
        var tooSmall = service.recommend(request(RiskPreference.GROWTH, Purpose.GROWTH, Liquidity.LOCKABLE, 100, 36, false));
        assertEquals(List.of("DEMO-CASH", "DEMO-MONEY"), ids(tooSmall));
        assertTrue(tooSmall.exclusions().stream().allMatch(e -> e.reasons().contains("低于示例起投金额")));
        var shortHorizon = service.recommend(request(RiskPreference.GROWTH, Purpose.PLANNED, Liquidity.LOCKABLE, 1000000, 1, false));
        assertEquals(List.of("DEMO-CASH", "DEMO-MONEY"), ids(shortHorizon));
        assertTrue(shortHorizon.exclusions().stream().allMatch(e -> e.reasons().contains("持有期限短于该方向建议期限")));
    }

    @ParameterizedTest
    @CsvSource({"CONSERVATIVE,2", "BALANCED,3", "GROWTH,4"})
    void preferenceCapsRiskAndUsesTheWholeEligibleCatalog(RiskPreference risk, int ceiling) {
        var response = service.recommend(request(risk, Purpose.GROWTH, Liquidity.LOCKABLE, 1000000, 36, false));
        assertTrue(response.candidates().stream().allMatch(c -> c.product().riskLevel() <= ceiling));
        assertTrue(response.candidates().stream().anyMatch(c -> c.product().riskLevel() == ceiling));
        assertEquals(response.evaluatedCount(), response.candidates().size() + response.exclusions().size());
        assertTrue(response.candidates().stream().allMatch(c -> !c.reasons().isEmpty()));
    }

    @Test void changingHistoricalReturnsDoesNotChangeRanking() {
        var request = request(RiskPreference.GROWTH, Purpose.GROWTH, Liquidity.LOCKABLE, 1000000, 36, false);
        var source = provider.catalog();
        var changed = source.products().stream().map(p -> new FinanceProduct(p.id(), p.name(), p.category(),
                p.riskLevel(), p.riskLabel(), p.minimumAmountMinor(), p.minimumHorizonMonths(), p.lockMonths(),
                p.redemptionBusinessDays(), p.capitalProtected(), 50000 - p.riskLevel() * 10000,
                p.maxDrawdownBps(), p.annualFeeBps(), p.liquidityNote(), p.description(), p.riskNote())).toList();
        var alteredService = new FinanceRecommendationService(() -> catalog(source, changed), clock);
        assertEquals(ids(service.recommend(request)), ids(alteredService.recommend(request)));
        assertEquals("DEMO-INDEX", ids(service.recommend(request)).getFirst());
    }

    @Test void noEligibleProductsReturnsEmptyResultAndKeepsExclusionEvidence() {
        var source = provider.catalog();
        var onlyIndex = source.products().stream().filter(p -> p.id().equals("DEMO-INDEX")).toList();
        var limited = new FinanceRecommendationService(() -> catalog(source, onlyIndex), clock);
        var response = limited.recommend(request(RiskPreference.CONSERVATIVE, Purpose.RESERVE, Liquidity.IMMEDIATE, 100, 1, true));
        assertTrue(response.candidates().isEmpty());
        assertEquals(1, response.exclusions().size());
        assertFalse(response.exclusions().getFirst().reasons().isEmpty());
        assertTrue(response.summary().contains("没有匹配"));
    }

    @Test void responseKeepsDataOriginPeriodAndUnknownMetricsWithoutInventingZeros() {
        var response = service.recommend(request(RiskPreference.CONSERVATIVE, Purpose.PLANNED, Liquidity.FLEXIBLE, 1000000, 12, false));
        assertEquals("DEMO", response.mode());
        assertEquals("SafeBank 本地合成产品库", response.source());
        assertEquals("2026-09-30", response.asOf().toString());
        assertEquals("2025-09-30", response.periodStart().toString());
        assertEquals("2026-09-30", response.periodEnd().toString());
        assertEquals(clock.instant(), response.generatedAt().toInstant());
        assertTrue(response.disclosure().contains("虚构示例"));
        var cash = response.candidates().stream().filter(c -> c.product().id().equals("DEMO-CASH")).findFirst().orElseThrow().product();
        assertNull(cash.periodReturnBps());
        assertNull(cash.maxDrawdownBps());
        assertNull(cash.annualFeeBps());
    }

    @ParameterizedTest
    @CsvSource({"0,12", "99,12", "10000000001,12", "100,0", "100,121"})
    void invalidAmountOrHorizonCannotReachRecommendation(long amount, int horizon) {
        var error = assertThrows(BankException.class, () -> service.recommend(request(
                RiskPreference.CONSERVATIVE, Purpose.PLANNED, Liquidity.FLEXIBLE, amount, horizon, false)));
        assertEquals("INVALID_PREFERENCES", error.code);
    }

    @Test void requiredPreferencesAndExactMaximumAmountAreValidated() {
        assertThrows(BankException.class, () -> service.recommend(null));
        assertThrows(BankException.class, () -> service.recommend(new FinanceRecommendationRequestDTO(
                null, Purpose.PLANNED, Liquidity.FLEXIBLE, 100L, 12, false)));
        assertDoesNotThrow(() -> service.recommend(request(RiskPreference.GROWTH, Purpose.GROWTH,
                Liquidity.LOCKABLE, 10000000000L, 120, false)));
    }

    private FinanceRecommendationRequestDTO request(RiskPreference risk, Purpose purpose, Liquidity liquidity,
            long amount, int horizon, boolean protection) {
        return new FinanceRecommendationRequestDTO(risk, purpose, liquidity, amount, horizon, protection);
    }

    private List<String> ids(FinanceRecommendationVO response) {
        return response.candidates().stream().map(c -> c.product().id()).toList();
    }

    private FinanceCatalog catalog(FinanceCatalog source, List<FinanceProduct> products) {
        return new FinanceCatalog(source.mode(), source.source(), source.version(), source.asOf(),
                source.periodStart(), source.periodEnd(), source.disclosure(), products);
    }
}
