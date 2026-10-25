package cn.safebank.controller;
import cn.safebank.dto.ModelSettingsRequestDTO;
import cn.safebank.service.ModelConfigurationService;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/models")
public class ModelSettingsController {
    private final ModelConfigurationService settings;
    public ModelSettingsController(ModelConfigurationService settings){this.settings=settings;}
    private String user(HttpSession s){return (String)s.getAttribute("user");}
    @GetMapping("/providers") public List<ModelProviderVO> providers(){return settings.providers();}
    @GetMapping("/configuration") public ModelSettingsVO get(HttpSession s){return settings.settings(user(s));}
    @PostMapping("/configuration") public ModelSettingsVO save(HttpSession s,@Valid @RequestBody ModelSettingsRequestDTO r){return settings.save(user(s),r);}
    @PostMapping("/discover") public Map<String,Object> discover(HttpSession s,@Valid @RequestBody ModelSettingsRequestDTO r){return Map.of("models",settings.discover(user(s),r),"source","live");}
    @PostMapping("/test") public Map<String,Object> test(HttpSession s,@Valid @RequestBody ModelSettingsRequestDTO r){return settings.test(user(s),r);}
    @PostMapping("/disable") public ModelSettingsVO disable(HttpSession s){return settings.disable(user(s));}
}
