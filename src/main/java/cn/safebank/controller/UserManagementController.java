package cn.safebank.controller;

import cn.safebank.dto.*;
import cn.safebank.vo.*;
import cn.safebank.service.UserManagementService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserManagementController {
    private final UserManagementService users;
    public UserManagementController(UserManagementService users){this.users=users;}
    private String user(HttpSession session){return (String)session.getAttribute("user");}
    @GetMapping public List<ManagedUserVO> list(HttpSession session){return users.list(user(session));}
    @PostMapping public ManagedUserVO create(HttpSession session,@Valid @RequestBody ManagedUserRequestDTO request){return users.create(user(session),request);}
    @PostMapping("/{id}") public ManagedUserVO update(HttpSession session,@PathVariable String id,@Valid @RequestBody ManagedUserRequestDTO request){return users.update(user(session),id,request);}
    @GetMapping("/profile") public UserProfileVO profile(HttpSession session){return users.profile(user(session));}
    @PostMapping("/profile") public UserProfileVO profile(HttpSession session,@Valid @RequestBody UserProfileRequestDTO request){return users.saveProfile(user(session),request);}
}
