package cn.safebank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Metadata is read from new H2/MySQL databases, never an original live database. */
@EnabledIfEnvironmentVariable(named="SAFEBANK_MYSQL_TEST_URL",matches=".+")
class MySqlSchemaComparisonTest {
    @Test void exportActualStructures() throws Exception {
        var mysql=MySqlTestSupport.fresh("diff");MySqlTestSupport.migration(mysql).migrate();
        var h2=new DriverManagerDataSource("jdbc:h2:mem:diff_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("schema-risk.sql"),new ClassPathResource("schema-context.sql")).execute(h2);
        var path=Path.of("target/mysql-evidence");Files.createDirectories(path);
        var json=new ObjectMapper();
        json.writerWithDefaultPrettyPrinter().writeValue(path.resolve("h2-structure.json").toFile(),structure(h2));
        json.writerWithDefaultPrettyPrinter().writeValue(path.resolve("mysql-structure.json").toFile(),structure(mysql));
        assertEquals(24,structure(h2).size());assertEquals(26,structure(mysql).size());
        assertTrue(((Map<?,?>)structure(h2).get("accounts")).containsKey("checks"),"Export source CHECK predicates as well as keys");
    }
    static Map<String,Object> structure(DataSource ds) throws Exception {
        var result=new TreeMap<String,Object>();
        try(var c=ds.getConnection()) {
            var md=c.getMetaData();boolean mysql=md.getDatabaseProductName().equals("MySQL");
            String catalog=mysql?c.getCatalog():null,schema=mysql?null:"PUBLIC";
            try(var tables=md.getTables(catalog,schema,"%",new String[]{"TABLE"})) {
                while(tables.next()) {
                    String original=tables.getString("TABLE_NAME"),table=original.toLowerCase(Locale.ROOT);
                    if(table.equals("flyway_schema_history"))continue;
                    var columns=new ArrayList<Map<String,Object>>();
                    try(var r=md.getColumns(catalog,schema,original,"%")) {while(r.next()) {
                        var col=new LinkedHashMap<String,Object>();
                        col.put("name",r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));col.put("type",r.getString("TYPE_NAME"));
                        col.put("size",r.getLong("COLUMN_SIZE"));col.put("scale",r.getInt("DECIMAL_DIGITS"));
                        col.put("nullable",r.getInt("NULLABLE")!=0);col.put("default",r.getString("COLUMN_DEF"));columns.add(col);
                    }}
                    var primary=new ArrayList<String>();try(var r=md.getPrimaryKeys(catalog,schema,original)) {while(r.next())primary.add(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));}
                    var foreign=new TreeMap<String,List<String>>();try(var r=md.getImportedKeys(catalog,schema,original)) {while(r.next()) {
                        foreign.computeIfAbsent(r.getString("FK_NAME"),k->new ArrayList<>()).add((r.getString("FKCOLUMN_NAME")+"->"+r.getString("PKTABLE_NAME")+"."+r.getString("PKCOLUMN_NAME")).toLowerCase(Locale.ROOT));
                    }}
                    var unique=new TreeMap<String,List<String>>();try(var r=md.getIndexInfo(catalog,schema,original,true,false)) {while(r.next()) {
                        if(r.getString("COLUMN_NAME")!=null)unique.computeIfAbsent(r.getString("INDEX_NAME"),k->new ArrayList<>()).add(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }}
                    var body=new LinkedHashMap<String,Object>();body.put("columns",columns);body.put("primary",primary);
                    body.put("foreign",foreign.values());body.put("unique",unique.values());
                    var checks=new ArrayList<String>();
                    String checkQuery="SELECT cc.check_clause FROM information_schema.check_constraints cc JOIN information_schema.table_constraints tc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name WHERE tc.table_schema="+(mysql?"DATABASE()":"'PUBLIC'")+" AND tc.table_name=?";
                    try(var s=c.prepareStatement(checkQuery)){s.setString(1,original);try(var r=s.executeQuery()){while(r.next())checks.add(r.getString(1));}}body.put("checks",checks);
                    if(mysql) {
                        var collations=new TreeMap<String,String>();
                        try(var s=c.prepareStatement("SELECT column_name,collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?")) {
                            s.setString(1,table);try(var r=s.executeQuery()){while(r.next())if(r.getString(2)!=null)collations.put(r.getString(1),r.getString(2));}
                        }body.put("collations",collations);
                    }
                    result.put(table,body);
                }
            }
        }return result;
    }
}
