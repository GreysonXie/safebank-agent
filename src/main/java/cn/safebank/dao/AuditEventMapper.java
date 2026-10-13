package cn.safebank.dao;
import cn.safebank.entity.AuditEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface AuditEventMapper {
    int insert(AuditEventEntity row);
    List<AuditEventEntity> selectByUser(@Param("userId") String userId);
}
