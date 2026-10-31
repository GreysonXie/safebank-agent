package cn.safebank.dao;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;
/** Read-only structural inspection; schema writes belong to Flyway. */
@Mapper public interface MySqlSchemaMapper {
    List<String> tables();
    List<String> columns();
    List<String> constraints();
    List<String> keys();
}
