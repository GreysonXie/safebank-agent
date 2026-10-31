package cn.safebank.dao;
import cn.safebank.entity.LedgerEntryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface LedgerEntryMapper {
    int insert(LedgerEntryEntity row);
    List<LedgerEntryEntity> selectByAction(@Param("actionId") String actionId);
}
