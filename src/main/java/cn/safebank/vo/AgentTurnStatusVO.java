package cn.safebank.vo;

/** Owner-visible projection. Never contains grant, question or model response data. */
public record AgentTurnStatusVO(String turnId,String state,int reconcileFailures,long outputVersion,boolean requiresReview) {}
