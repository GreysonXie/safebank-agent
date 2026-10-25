package cn.safebank.model;

import cn.safebank.BankException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

@Component
public class CompatibleModelHttp {
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    public CompatibleModelHttp(ObjectMapper json){this.json=json;}
    public static String normalizeBase(String value){
        try{
            String base=value.strip().replaceAll("/+$","").replaceAll("/(chat/completions|models)$","");
            URI uri=URI.create(base);
            if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getFragment()!=null||
                    !("https".equals(uri.getScheme())||"http".equals(uri.getScheme())&&Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost())))throw invalid();
            return base;
        }catch(Exception e){throw invalid();}
    }
    private static BankException invalid(){return new BankException("MODEL_CONFIGURATION_INVALID","请输入 HTTPS API 基础地址；本地测试可使用 localhost 或 127.0.0.1");}
    public ObjectNode body(ModelConnection c,Object messages,Object tools,int maximum,boolean structured){
        var body=json.createObjectNode();body.put("model",c.model());body.set("messages",json.valueToTree(messages));body.put("stream",false);
        boolean openai=c.provider().equals("openai")||URI.create(c.baseUrl()).getHost().equals("api.openai.com");
        body.put(openai?"max_completion_tokens":"max_tokens",maximum);
        if(tools instanceof Collection<?> collection&&!collection.isEmpty())body.set("tools",json.valueToTree(tools));
        if(structured)body.set("response_format",json.valueToTree(Map.of("type","json_object")));
        if(c.provider().equals("deepseek"))body.set("thinking",json.valueToTree(Map.of("type","disabled")));
        if(c.provider().equals("qwen"))body.put("enable_thinking",false);
        return body;
    }
    public JsonNode request(ModelConnection connection,String suffix,Object body){
        return request(connection,suffix,body,Duration.ofSeconds(60));
    }
    public JsonNode request(ModelConnection connection,String suffix,Object body,Duration timeout){
        if(connection.apiKey().isBlank())throw new BankException("MODEL_NOT_CONFIGURED","请填写 API Key");
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            URI endpoint=URI.create(normalizeBase(connection.baseUrl())+suffix);
            validatePublicDestination(endpoint);
            var request=HttpRequest.newBuilder(endpoint)
                    .timeout(timeout).header("Authorization","Bearer "+connection.apiKey()).header("Content-Type","application/json");
            if(body==null)request.GET();else request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            pending=http.sendAsync(request.build(),info->new LimitedBody());
            var response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if(response.statusCode()!=200){String reason=switch(response.statusCode()){case 401,403->"密钥无效或无调用权限";case 404->"地址或模型不存在";case 429->"供应商限流或余额不足";default->"服务未接受请求";};throw new BankException("MODEL_API_ERROR",reason+"（HTTP "+response.statusCode()+"）");}
            JsonNode result;
            try{result=json.readTree(response.body());}catch(Exception e){throw new BankException("MODEL_RESPONSE_INVALID","模型服务未返回有效 JSON 对象");}
            if(result==null||!result.isObject())throw new BankException("MODEL_RESPONSE_INVALID","模型服务未返回有效 JSON 对象");return result;
        }catch(BankException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new BankException("MODEL_INTERRUPTED","模型请求已中断");}
        catch(ExecutionException e){if(e.getCause() instanceof BankException bank)throw bank;throw new BankException("MODEL_UNAVAILABLE","连接失败，请检查 API 地址、网络与密钥配置");}
        catch(Exception e){throw new BankException("MODEL_UNAVAILABLE","连接失败，请检查 API 地址、网络与密钥配置");}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    public static void validatePublicDestination(URI endpoint){
        String host=endpoint.getHost();
        if(host==null)throw invalid();
        if(Set.of("localhost","127.0.0.1","::1").contains(host.toLowerCase(Locale.ROOT)))return;
        try{
            for(InetAddress address:InetAddress.getAllByName(host))if(!isPublic(address.getAddress()))throw invalid();
        }catch(UnknownHostException e){throw new BankException("MODEL_UNAVAILABLE","模型地址无法解析，请检查 API 主机名和网络连接");}
    }
    private static boolean isPublic(byte[] address){
        if(address.length==4){
            int a=address[0]&255,b=address[1]&255,c=address[2]&255;
            return !(a==0||a==10||a==127||a>=224||a==169&&b==254||a==172&&b>=16&&b<=31||a==192&&b==168||a==100&&b>=64&&b<=127||a==192&&b==0||a==198&&(b==18||b==19)||a==192&&b==88&&c==99);
        }
        if(address.length!=16)return false;
        boolean allZero=true;for(byte value:address)allZero&=value==0;
        if(allZero)return false;
        boolean mapped=true;for(int i=0;i<10;i++)mapped&=address[i]==0;mapped&=address[10]==(byte)0xff&&address[11]==(byte)0xff;
        if(mapped)return isPublic(Arrays.copyOfRange(address,12,16));
        int first=address[0]&255,second=address[1]&255;
        if(first==0||first==0xfc||first==0xfd||first==0xff||first==0xfe&&(second&0xc0)==0x80||first==0xfe&&(second&0xc0)==0xc0)return false;
        return !(first==0x20&&second==0x01&&((address[2]&255)==0x0d&&(address[3]&255)==0xb8));
    }
    // Bound both the body size and its complete download time, including a stalled body after headers.
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){
                if((long)bytes.size()+buffer.remaining()>1048576){subscription.cancel();result.completeExceptionally(new BankException("MODEL_RESPONSE_INVALID","模型响应过大"));return;}
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
