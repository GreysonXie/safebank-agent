package cn.safebank.config;

import cn.safebank.dao.MySqlSchemaMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

/** Exact reviewed V1/V2 manifest. Detects missing or weakened predicates/owner keys without DDL. */
@Component @Profile("mysql")
public class MySqlSchemaVerifier {
    private final MySqlSchemaMapper mapper;
    private final Map<String,List<String>> expected;
    public MySqlSchemaVerifier(MySqlSchemaMapper mapper,ObjectMapper json,@org.springframework.beans.factory.annotation.Value("${bank.mysql.schema-manifest:db/mysql-v2-manifest.json}") String manifest) throws IOException {
        this.mapper=mapper;
        try(var in=new ClassPathResource(manifest).getInputStream()) {
            expected=json.readValue(in,new TypeReference<Map<String,List<String>>>(){});
        }
        try(var in=new ClassPathResource("db/mysql-v6-workspace-manifest.json").getInputStream()) {
            Map<String,List<String>> workspace=json.readValue(in,new TypeReference<Map<String,List<String>>>(){});
            for(String kind:List.of("tables","columns","constraints","keys"))expected.get(kind).addAll(workspace.get(kind));
        }
    }
    public void verify(){
        check("tables",mapper.tables());
        check("columns",mapper.columns());check("constraints",mapper.constraints());check("keys",mapper.keys());
    }
    private void check(String kind,List<String> actual){
        if(!new TreeSet<>(expected.get(kind)).equals(new TreeSet<>(actual)))
            throw new IllegalStateException("MySQL "+kind+" differ from reviewed migration manifest; refusing automatic repair");
    }
}
