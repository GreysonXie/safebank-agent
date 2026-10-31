package cn.safebank.app.data.network

import cn.safebank.app.data.model.RiskDecisionVo
import java.io.IOException

/**
 * 后端业务错误的统一表示。
 *
 * 数据层约定：**所有** `SafeBankRepository` 方法的 `Result.failure` 里都是本类
 * （网络错误也包装成本类，`httpStatus == 0`、`code == NETWORK_ERROR`），
 * 页面只需 `catch`/`Result.onFailure { it as ApiException }` 一处即可。
 *
 * 字段来源见 docs/api-contract.md §3.2 / §9：
 * - 普通业务错误 `ErrorVO` → [code] + [message]；
 * - 风险错误 `RiskErrorVO` → 额外带 [actionId]、[security]、[retryAt]；
 * - Spring 默认错误体（`timestamp/status/error/path`）→ [code] 为 `HTTP_<status>`，
 *   [message] 是拼好的中文可读文案（含 path）；
 * - 网络层失败 → [code] 为 [NETWORK_ERROR]，[httpStatus] 为 0。
 *
 * 继承 [IOException] 是为了让"网络/业务失败"在协程里都能被当作普通 IO 异常处理
 * （页面也可以只 `runCatching`）。
 */
class ApiException(
    /** 业务错误码，例如 `SESSION_REQUIRED` / `VALIDATION_ERROR` / `HTTP_400` / `NETWORK_ERROR`。 */
    val code: String,
    /** 可直接展示给用户的中文文案（后端 message，或本地兜底文案）。 */
    override val message: String,
    /** HTTP 状态码；网络错误为 0。 */
    val httpStatus: Int,
    /** 原始响应体（便于日志排查，可能为 null）。 */
    val body: String? = null,
    /** 仅 `RiskErrorVO` 有：关联动作 id。 */
    val actionId: String? = null,
    /** 仅 `RiskErrorVO` 有：最新风险判定，页面可据此重绘确认卡。 */
    val security: RiskDecisionVo? = null,
    /** 仅 `RiskErrorVO` 有：限流/锁定时的可重试时间（ISO-8601）。 */
    val retryAt: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {

    /** 是否为本地网络/连接类错误（不是后端业务错误）。 */
    val isNetworkError: Boolean get() = httpStatus == 0 && code == NETWORK_ERROR

    /** 会话类错误（数据层会自动重建会话并重放一次，页面通常不会看到）。 */
    val isSessionError: Boolean
        get() = code == SESSION_REQUIRED || code == CSRF_REQUIRED || httpStatus == 401

    /** 版本/状态冲突（workflow 版本、chat contextVersion、参数已变等）。 */
    val isConflict: Boolean get() = httpStatus == 409 || code == CONFLICT

    /** 预览/授权过期（需要重新预览，不能重放）。 */
    val isAuthExpired: Boolean get() = code == AUTH_EXPIRED

    /** 风险判定已变（需要重新 `refreshRiskPreview`）。 */
    val isRiskStale: Boolean get() = code == RISK_DECISION_STALE

    /** 默认 profile 下 RED 动作无法验证（需要 `risk-demo` profile）。 */
    val isVerificationUnavailable: Boolean get() = code == VERIFICATION_UNAVAILABLE

    override fun toString(): String = "ApiException(code=$code, httpStatus=$httpStatus, message=$message)"

    companion object {
        const val NETWORK_ERROR = "NETWORK_ERROR"
        const val EMPTY_RESPONSE = "EMPTY_RESPONSE"
        const val PARSE_ERROR = "PARSE_ERROR"
        const val SERVER_NOT_CONFIGURED = "SERVER_NOT_CONFIGURED"

        const val SESSION_REQUIRED = "SESSION_REQUIRED"
        const val CSRF_REQUIRED = "CSRF_REQUIRED"
        const val ORIGIN_DENIED = "ORIGIN_DENIED"
        const val VALIDATION_ERROR = "VALIDATION_ERROR"
        const val NOT_FOUND = "NOT_FOUND"
        const val CONFLICT = "CONFLICT"
        const val AUTH_REQUIRED = "AUTH_REQUIRED"
        const val AUTH_EXPIRED = "AUTH_EXPIRED"
        const val INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS"
        const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
        const val RISK_DECISION_STALE = "RISK_DECISION_STALE"
        const val STEP_UP_REQUIRED = "STEP_UP_REQUIRED"
        const val VERIFICATION_INVALID = "VERIFICATION_INVALID"
        const val VERIFICATION_EXPIRED = "VERIFICATION_EXPIRED"
        const val VERIFICATION_UNAVAILABLE = "VERIFICATION_UNAVAILABLE"
        const val CHALLENGE_RATE_LIMITED = "CHALLENGE_RATE_LIMITED"
        const val SECURITY_LOCKED = "SECURITY_LOCKED"
        const val SIMULATION_DISABLED = "SIMULATION_DISABLED"
        const val QUERY_CONFIRMATION_REQUIRED = "QUERY_CONFIRMATION_REQUIRED"
    }
}
