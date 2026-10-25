package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.dto.ModelSettingsRequestDTO;
import cn.safebank.model.*;
import cn.safebank.vo.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

@Service
public class ModelConfigurationService {
    private record Saved(ModelConnection connection,String verifiedAt,String source) {}
    private final ObjectMapper json;private final CompatibleModelHttp http;private final Path directory;private final Environment environment;private final Clock clock;
    private final Map<String,Saved> cache=new HashMap<>();private final Map<String,String> successfulTests=new HashMap<>();private final SecureRandom random=new SecureRandom();
    public ModelConfigurationService(ObjectMapper json,CompatibleModelHttp http,Environment environment,Clock clock,
            @Value("${bank.model.settings-path:${SAFEBANK_MODEL_SETTINGS_PATH:./data/model-settings}}") String directory){
        this.json=json;this.http=http;this.environment=environment;this.clock=clock;this.directory=Path.of(directory).toAbsolutePath().normalize();
    }
    public List<ModelProviderVO> providers(){return List.of(
        new ModelProviderVO("deepseek","DeepSeek","deepseek","https://api.deepseek.com",List.of("deepseek-flash","deepseek-v4-pro")),
        new ModelProviderVO("qwen","通义千问","qwen","https://dashscope.aliyuncs.com/compatible-mode/v1",List.of()),
        new ModelProviderVO("zhipu","智谱 GLM","zhipu","https://open.bigmodel.cn/api/paas/v4",List.of()),
        new ModelProviderVO("moonshot","Kimi","moonshot","https://api.moonshot.cn/v1",List.of()),
        new ModelProviderVO("doubao","豆包 / 火山方舟","doubao","https://ark.cn-beijing.volces.com/api/v3",List.of()),
        new ModelProviderVO("minimax","MiniMax","minimax","https://api.minimax.io/v1",List.of()),
        new ModelProviderVO("hunyuan","腾讯混元","hunyuan","https://api.hunyuan.cloud.tencent.com/v1",List.of()),
        new ModelProviderVO("siliconflow","硅基流动","siliconcloud","https://api.siliconflow.cn/v1",List.of()),
        new ModelProviderVO("baichuan","百川","baichuan","https://api.baichuan-ai.com/v1",List.of()),
        new ModelProviderVO("openai","OpenAI","openai","https://api.openai.com/v1",List.of("gpt-6.1","gpt-6","gpt-5.6")),
        new ModelProviderVO("custom","自定义兼容接口","custom","",List.of())) ;}
    public synchronized ModelConnection connection(String user){return saved(user).connection();}
    public synchronized ModelSettingsVO settings(String user){var s=saved(user);var c=s.connection();return new ModelSettingsVO(c.provider(),c.baseUrl(),c.model(),!c.apiKey().isBlank(),c.enabled(),s.verifiedAt()!=null,s.verifiedAt(),c.revision(),s.source());}
    public synchronized ModelSettingsVO save(String user,ModelSettingsRequestDTO request){
        var c=candidate(user,request,true);String verified=successfulTests.get(fingerprint(c));
        var value=new Saved(new ModelConnection(c.provider(),c.baseUrl(),c.apiKey(),c.model(),c.enabled(),UUID.randomUUID().toString()),verified,"local");
        persist(user,value);cache.put(user,value);return settings(user);
    }
    public synchronized ModelSettingsVO disable(String user){var old=saved(user);var c=old.connection();var value=new Saved(new ModelConnection(c.provider(),c.baseUrl(),c.apiKey(),c.model(),false,UUID.randomUUID().toString()),null,"local");persist(user,value);cache.put(user,value);return settings(user);}
    public List<String> discover(String user,ModelSettingsRequestDTO request){
        ModelConnection c; synchronized(this){c=candidate(user,request,false);}
        var result=http.request(c,"/models",null);var rows=result.path("data");if(!rows.isArray())throw new BankException("MODEL_DISCOVERY_UNAVAILABLE","该接口不提供标准模型列表，请手动填写模型 ID");
        var ids=new TreeSet<String>();for(var row:rows){var id=row.path("id");if(id.isTextual()&&!id.asText().isBlank()&&id.asText().length()<=200)ids.add(id.asText());if(ids.size()>=1000)break;}return List.copyOf(ids);
    }
    public Map<String,Object> test(String user,ModelSettingsRequestDTO request){
        ModelConnection c;synchronized(this){c=candidate(user,request,true);}
        long start=System.nanoTime();var result=http.request(c,"/chat/completions",http.body(c,List.of(Map.of("role","user","content","Reply with the word OK.")),List.of(),512,false));
        var message=result.path("choices").path(0).path("message");
        if(!message.isObject()||message.path("content").asText("").isBlank()||!"stop".equals(result.path("choices").path(0).path("finish_reason").asText()))throw new BankException("MODEL_RESPONSE_INVALID","接口已响应，但没有完整的文本回复；请检查是否为对话模型");
        synchronized(this){successfulTests.put(fingerprint(c),clock.instant().toString());}
        return Map.of("valid",true,"model",c.model(),"latencyMs",(System.nanoTime()-start)/1000000,"message","连接检测通过，可保存并应用。检测不代表所有工具能力均受支持。");
    }
    private ModelConnection candidate(String user,ModelSettingsRequestDTO r,boolean requireModel){
        if(providers().stream().noneMatch(p->p.id().equals(r.provider())))throw new BankException("VALIDATION_ERROR","请选择支持的供应商或自定义接口");
        String base=CompatibleModelHttp.normalizeBase(r.baseUrl()),model=r.model()==null?"":r.model().strip();
        if(requireModel&&(model.isBlank()||model.length()>200)||model.chars().anyMatch(Character::isISOControl))throw new BankException("VALIDATION_ERROR","请填写有效的模型 ID");
        var old=saved(user).connection();String key=r.apiKey()==null?"":r.apiKey().strip();
        if(key.isBlank()&&!r.clearApiKey()&&old.provider().equals(r.provider())&&old.baseUrl().equals(base))key=old.apiKey();
        if(key.isBlank()||key.length()>4096||key.chars().anyMatch(Character::isISOControl))throw new BankException("MODEL_NOT_CONFIGURED","请填写有效的 API Key；切换供应商或地址后需重新填写");
        return new ModelConnection(r.provider(),base,key,model,r.enabled()==null||r.enabled(),"draft");
    }
    private Saved saved(String user){return cache.computeIfAbsent(user,id->{
        Path file=file(id);if(Files.exists(file))try{var r=json.readTree(Files.readString(file));String key=decrypt(id,r.path("secret").asText());return new Saved(new ModelConnection(r.path("provider").asText(),CompatibleModelHttp.normalizeBase(r.path("baseUrl").asText()),key,r.path("model").asText(),r.path("enabled").asBoolean(),r.path("revision").asText()),r.path("verifiedAt").isTextual()?r.path("verifiedAt").asText():null,"local");}catch(Exception e){throw storageError();}
        String base=environment.getProperty("bank.model.base-url","https://api.deepseek.com"),key=environment.getProperty("bank.model.api-key",""),model=environment.getProperty("bank.model.name","deepseek-v4-pro");
        if(environment.matchesProfiles("langgraph-agent")){base=environment.getProperty("SAFEBANK_MODEL_URL",base);model=environment.getProperty("bank.agent.model-name",model);key=environment.getProperty("SAFEBANK_MODEL_API_KEY","");if(key.isBlank())key=environment.getProperty("DEEPSEEK_API_KEY","");}
        if(!"u1".equals(id))key="";
        String provider=base.contains("deepseek.com")?"deepseek":base.contains("api.openai.com")?"openai":"custom";
        return new Saved(new ModelConnection(provider,CompatibleModelHttp.normalizeBase(base),key,model,!key.isBlank(),"environment"),null,"environment");
    });}
    private Path file(String user){return directory.resolve(hash(user)+".json");}
    private String fingerprint(ModelConnection c){return hash(c.provider()+"\n"+c.baseUrl()+"\n"+c.apiKey()+"\n"+c.model());}
    private String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw storageError();}}
    private void persist(String user,Saved saved){
        try{Files.createDirectories(directory);restrict(directory,true);var c=saved.connection();var root=json.createObjectNode();root.put("provider",c.provider());root.put("baseUrl",c.baseUrl());root.put("model",c.model());root.put("enabled",c.enabled());root.put("revision",c.revision());root.put("secret",encrypt(user,c.apiKey()));if(saved.verifiedAt()!=null)root.put("verifiedAt",saved.verifiedAt());
            Path temporary=Files.createTempFile(directory,"model-",".tmp");restrict(temporary,false);Files.writeString(temporary,json.writeValueAsString(root));try{Files.move(temporary,file(user),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file(user),StandardCopyOption.REPLACE_EXISTING);}restrict(file(user),false);
        }catch(Exception e){throw storageError();}
    }
    private byte[] master(boolean create)throws Exception{
        Path file=directory.resolve(".key");if(!Files.exists(file)&&create){byte[] bytes=new byte[32];random.nextBytes(bytes);try{Files.write(file,bytes,StandardOpenOption.CREATE_NEW);restrict(file,false);}catch(FileAlreadyExistsException ignored){}}
        byte[] bytes=Files.readAllBytes(file);if(bytes.length!=32)throw storageError();return bytes;
    }
    private String encrypt(String user,String key)throws Exception{byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(master(true),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(user.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)));}
    private String decrypt(String user,String text)throws Exception{var parts=text.split("\\.",2);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(master(false),"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));cipher.updateAAD(user.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);}
    private void restrict(Path path,boolean directory)throws Exception{if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));}
    private BankException storageError(){return new BankException("MODEL_SETTINGS_UNAVAILABLE","本地模型配置无法读取或保存，请检查配置目录权限");}
}
