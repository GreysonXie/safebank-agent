package cn.safebank.dao;
import cn.safebank.entity.AgentContextEvidenceEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface AgentContextEvidenceMapper {
    List<AgentContextEvidenceEntity> selectByConversation(@Param("userId") String userId,@Param("conversationId") String conversationId);
    int updateOwned(AgentContextEvidenceEntity row);
    int insert(AgentContextEvidenceEntity row);
}
