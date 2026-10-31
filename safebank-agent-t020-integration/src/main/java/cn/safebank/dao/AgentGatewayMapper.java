package cn.safebank.dao;
import cn.safebank.entity.*;
import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.List;
@Mapper public interface AgentGatewayMapper {
 AgentGatewayTurnEntity gateway(String turnId);
 AgentGatewayTurnEntity lockGateway(String turnId);
 int insertGateway(AgentGatewayTurnEntity row);
 int updateGateway(AgentGatewayTurnEntity row);
 AgentTurnReceiptEntity lockTurn(@Param("userId") String userId,@Param("turnId") String turnId);
 AgentServiceGrantEntity grant(String tokenHash);
 AgentServiceGrantEntity grantCurrent(String tokenHash);
 int revoke(@Param("userId") String userId,@Param("turnId") String turnId);
 int insertGrant(AgentServiceGrantEntity row);
 int useGrant(@Param("id") String id);
 AgentToolCallReceiptEntity receipt(@Param("turnId") String turnId,@Param("key") String key);
 List<AgentToolCallReceiptEntity> receipts(String turnId);
 /** 该会话最近一次指定工具回执：只读快照，用于界面跟随对话口径。 */
 AgentToolCallReceiptEntity latestReceiptByTool(@Param("userId") String userId,@Param("conversationId") String conversationId,@Param("toolName") String toolName);
 int insertReceipt(AgentToolCallReceiptEntity row);
 List<AgentGatewayTurnEntity> due(@Param("now") OffsetDateTime now);
 List<String> pendingAck(@Param("userId") String userId,@Param("conversationId") String conversationId);
}
