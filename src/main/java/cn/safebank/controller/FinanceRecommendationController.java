package cn.safebank.controller;

import cn.safebank.dto.FinanceRecommendationRequestDTO;
import cn.safebank.service.FinanceRecommendationService;
import cn.safebank.vo.ErrorVO;
import cn.safebank.vo.FinanceRecommendationVO;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance")
public class FinanceRecommendationController {
    private final FinanceRecommendationService recommendations;
    public FinanceRecommendationController(FinanceRecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    // SessionGuard applies session ownership and CSRF protection to this read-only POST.
    @PostMapping("/recommendations")
    public FinanceRecommendationVO recommend(@Valid @RequestBody FinanceRecommendationRequestDTO request) {
        return recommendations.recommend(request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorVO invalid(MethodArgumentNotValidException e) {
        return new ErrorVO("INVALID_PREFERENCES", e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage()).findFirst().orElse("请检查推荐偏好"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorVO unreadable() {
        return new ErrorVO("INVALID_PREFERENCES", "请选择有效的推荐偏好，金额与期限须为整数");
    }
}
