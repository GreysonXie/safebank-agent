package cn.safebank.dao;
import cn.safebank.entity.AgentContextSlotEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface AgentContextSlotMapper {
    List<AgentContextSlotEntity> selectByConversation(@Param("userId") String userId,@Param("conversationId") String conversationId);
    int updateOwned(AgentContextSlotEntity row);
    int deleteOwned(@Param("userId") String userId,@Param("conversationId") String conversationId);
    int insert(AgentContextSlotEntity row);
}
