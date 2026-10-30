package cn.safebank.wealth;
import cn.safebank.BankException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;
/**
 * 演示理财产品目录：唯一运行数据源为 classpath 上的 products-v1.json（由产品方模拟理财产品_v1.json 复制）。
 * 只读、不新增数据库表；synthetic/来源/版本保留在数据里，用户端展示用 display_name。
 */
@Component public class WealthCatalog {
    private final JsonNode source;
    private final Map<String,Object> catalog;
    private final Map<String,JsonNode> products=new LinkedHashMap<>();
    private final String revision;
    public WealthCatalog(ObjectMapper json){
        byte[] raw;
        try(InputStream stream=getClass().getResourceAsStream("/wealth/products-v1.json")){
            if(stream==null)throw new BankException("CATALOG_UNAVAILABLE","产品目录未随应用打包");
            raw=stream.readAllBytes();
        }catch(java.io.IOException e){throw new IllegalStateException("产品目录读取失败",e);}
        try{
            source=json.readTree(raw);
            revision=hash(raw);
        }catch(Exception e){throw new IllegalStateException("产品目录读取失败",e);}
        var list=new ArrayList<Object>();
        for(var node:source.path("products")){products.put(node.path("id").asText(),node);list.add(json.convertValue(node,Map.class));}
        var meta=new LinkedHashMap<String,Object>();
        meta.put("dataset_id",source.path("dataset_id").asText());
        meta.put("catalog_revision",revision);
        meta.put("reference_checked_on",source.path("reference_checked_on").asText());
        meta.put("synthetic",source.path("synthetic").asBoolean(true));
        meta.put("catalog_scope",source.path("catalog_scope").asText());
        meta.put("risk_scale_note",source.path("risk_scale_note").asText());
        meta.put("products",List.copyOf(list));
        meta.put("count",list.size());
        catalog=Collections.unmodifiableMap(meta);
    }
    private static String hash(byte[] raw)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256").digest(raw);
        var text=new StringBuilder();
        for(int i=0;i<6;i++)text.append(String.format("%02x",digest[i]));
        return text.toString();
    }
    /** 完整目录；产品全集仅指本目录。 */
    public Map<String,Object> catalog(){return catalog;}
    public String revision(){return revision;}
    public JsonNode product(String id){return products.get(id);}
    public Set<String> ids(){return products.keySet();}
    /**
     * 校验页面所选的两个产品：必须是两个互异的已收录 ID，且选取所依据的目录版本未过期。
     * 只返回规范身份与显示名，不把客户端文本当条款。
     */
    public List<Map<String,Object>> validateSelection(List<String> ids,String usedRevision){
        if(ids==null||ids.size()!=2)throw new BankException("VALIDATION_ERROR","请选择两个不同的产品");
        if(ids.get(0)==null||ids.get(1)==null||ids.get(0).equals(ids.get(1)))throw new BankException("VALIDATION_ERROR","请选择两个不同的产品");
        if(usedRevision==null||!usedRevision.equals(revision))throw new BankException("CATALOG_STALE","产品目录已更新，请重新查看产品后再对比");
        var result=new ArrayList<Map<String,Object>>();
        for(var id:ids){
            var node=products.get(id);
            if(node==null)throw new BankException("VALIDATION_ERROR","所选产品不在当前目录："+id);
            result.add(Map.of("id",node.path("id").asText(),"display_name",node.path("display_name").asText(node.path("name").asText())));
        }
        return List.copyOf(result);
    }
}
