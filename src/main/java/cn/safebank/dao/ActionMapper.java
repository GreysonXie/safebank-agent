package cn.safebank.dao;
import cn.safebank.entity.ActionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface ActionMapper {
    List<ActionEntity> selectByUser(@Param("userId") String userId);
    int updateExpiry(@Param("userId") String userId,@Param("id") String id,@Param("expiresAt") java.time.OffsetDateTime expiresAt);
    ActionEntity selectOwned(@Param("userId") String userId,@Param("id") String id);
    ActionEntity selectOwnedForUpdate(@Param("userId") String userId,@Param("id") String id);
    int insert(ActionEntity row);
    int updateState(@Param("userId") String userId,@Param("id") String id,@Param("status") String status,@Param("receiptId") String receiptId,@Param("errorCode") String errorCode);
}
