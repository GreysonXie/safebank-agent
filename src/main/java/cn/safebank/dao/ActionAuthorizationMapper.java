package cn.safebank.dao;
import cn.safebank.entity.ActionAuthorizationEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface ActionAuthorizationMapper {
    int bindScheduleDelegation(@Param("userId") String userId,@Param("id") String id);
    ActionAuthorizationEntity selectOwned(@Param("userId") String userId,@Param("id") String id);
    ActionAuthorizationEntity selectOwnedForUpdate(@Param("userId") String userId,@Param("id") String id);
    List<ActionAuthorizationEntity> selectByUser(@Param("userId") String userId);
    List<ActionAuthorizationEntity> selectByAction(@Param("userId") String userId,@Param("actionId") String actionId);
    int insert(ActionAuthorizationEntity row);
    int update(ActionAuthorizationEntity row);
}
