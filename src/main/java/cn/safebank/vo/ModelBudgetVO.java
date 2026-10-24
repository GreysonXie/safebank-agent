package cn.safebank.vo;

/** Aggregate project quota only; never exposes individual request records. */
public record ModelBudgetVO(boolean applies,int limit,int used,int remaining) {}
