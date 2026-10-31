package cn.safebank;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import java.net.URI;
@Component
public class SessionGuard implements HandlerInterceptor,WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this).addPathPatterns("/api/**").excludePathPatterns("/api/session"); }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws Exception {
        var session=request.getSession(false);
        if(session==null||session.getAttribute("user")==null)return reject(response,401,"SESSION_REQUIRED");
        if(!request.getMethod().equals("GET")){
            if(!java.util.Objects.equals(session.getAttribute("csrf"),request.getHeader("X-CSRF-Token")))return reject(response,403,"CSRF_REQUIRED");
            String origin=request.getHeader("Origin");
            if(origin!=null){try{URI uri=URI.create(origin);int port=uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();
                if(!uri.getScheme().equals(request.getScheme())||!uri.getHost().equals(request.getServerName())||port!=request.getServerPort())return reject(response,403,"ORIGIN_DENIED");
            }catch(Exception e){return reject(response,403,"ORIGIN_DENIED");}}
        }return true;
    }
    private boolean reject(HttpServletResponse response,int status,String code) throws Exception { response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":\""+code+"\",\"message\":\"请刷新页面建立本地演示会话\"}");return false; }
}
