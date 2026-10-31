package cn.safebank.vo;
public sealed interface ActionExecutionResultVO permits ExecutionReceiptVO,QueryExecutionReceiptVO { ActionVO action(); }
