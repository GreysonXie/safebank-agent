package cn.safebank.vo;
/** Usage totals; no project call quota is enforced. */
public record ModelBudgetVO(boolean applies,int limit,int used,int remaining,boolean unlimited) {}
