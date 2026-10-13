package cn.safebank.dao;
import org.apache.ibatis.annotations.*;
@Mapper public interface ContextSchemaMapper {
    int tableCount(@Param("name") String name);
    int ownerConstraintCount();
}
