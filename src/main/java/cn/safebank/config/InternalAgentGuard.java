package cn.safebank.config;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@Component @Profile("agent-gateway")
public class InternalAgentGuard extends OncePerRequestFilter {
    private final byte[] expected;
    public InternalAgentGuard(@Value("${bank.agent.service-key}") String key){if(key.length()<64)throw new IllegalStateException("Internal service key must contain at least 256 bits of generated entropy");expected=("Bearer "+key).getBytes(StandardCharsets.UTF_8);}
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getRequestURI().startsWith("/internal/");}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String auth=r.getHeader("Authorization");
        if(auth==null||auth.length()>256||!MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8))){reject(response,403,"INTERNAL_AUTH_REQUIRED");return;}
        if(!r.getMethod().equals("GET")&&(r.getContentLengthLong()<0||r.getContentLengthLong()>65536)){reject(response,413,"INTERNAL_REQUEST_TOO_LARGE");return;}
        chain.doFilter(r,response);
    }
    private void reject(HttpServletResponse r,int status,String code)throws IOException{r.setStatus(status);r.setContentType("application/json;charset=UTF-8");r.getWriter().write("{\"code\":\""+code+"\",\"message\":\"内部服务请求未获允许\"}");}
}
