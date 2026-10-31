package cn.safebank.dao;
import org.apache.ibatis.annotations.*;
@Mapper public interface RiskSchemaMapper {
    int tableCount(@Param("table") String table);
    int constraintCount(@Param("table") String table);
    int ownerConstraintCount();
}
