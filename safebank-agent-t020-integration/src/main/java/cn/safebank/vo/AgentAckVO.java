package cn.safebank.vo;
import java.util.Map;
public record AgentAckVO(String turnId,long outputVersion,String resultHash,Map<String,Object> response) {}
