package cn.safebank.controller;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import cn.safebank.service.CardManagementService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/cards")
public class CardManagementController {
    private final CardManagementService cards;
    public CardManagementController(CardManagementService cards){this.cards=cards;}
    private String user(HttpSession s){return (String)s.getAttribute("user");}
    @GetMapping public List<BankCardVO> list(HttpSession s){return cards.list(user(s));}
    @GetMapping("/operations") public List<CardOperationVO> history(HttpSession s){return cards.history(user(s));}
    @GetMapping("/operations/{id}") public CardOperationVO get(HttpSession s,@PathVariable String id){return cards.get(user(s),id);}
    @PostMapping("/preview") public CardOperationVO preview(HttpSession s,@Valid @RequestBody CardChangeDTO request){return cards.preview(user(s),request);}
    @PostMapping("/operations/{id}/confirm") public CardOperationVO confirm(HttpSession s,@PathVariable String id,@Valid @RequestBody CardConfirmDTO request){return cards.confirm(user(s),id,request);}
    @PostMapping("/operations/{id}/cancel") public CardOperationVO cancel(HttpSession s,@PathVariable String id){return cards.cancel(user(s),id);}
}
