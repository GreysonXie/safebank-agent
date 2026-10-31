package cn.safebank.app.data.network

import cn.safebank.app.data.model.ApiErrorBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * HTTP 错误响应 / 网络异常的解析器。
 *
 * 后端错误体有两种形态（docs/api-contract.md §3.2）：
 * 1. `ErrorVO` / `RiskErrorVO`：`{"code","message",...}`；
 * 2. Spring 默认错误体：`{"timestamp","status","error","path"}`（`message` 被
 *    `server.error.include-message=never` 关掉，所以必须自己拼可读文案）。
 *
 * 另外拦截器直出的 `401 SESSION_REQUIRED` / `403 CSRF_REQUIRED` 也是第一种形态。
 */
object ErrorParser {

    /** 解析非 2xx 响应体为 [ApiException]。 */
    fun parse(httpStatus: Int, rawBody: String?): ApiException {
        val body = rawBody?.takeIf { it.isNotBlank() }
        val parsed = try {
            if (body == null) null else SafeBankJson.gson.fromJson(body, ApiErrorBody::class.java)
        } catch (e: Exception) {
            null
        }

        // 形态 1：ErrorVO / RiskErrorVO —— 有业务 code
        if (parsed != null) {
            val code = parsed.code.takeIf { it.isNotBlank() }
            if (code != null) {
                val message = parsed.message.takeIf { it.isNotBlank() } ?: defaultMessage(code, httpStatus)
                return ApiException(
                    code = code,
                    message = message,
                    httpStatus = httpStatus,
                    body = body,
                    actionId = parsed.actionId,
                    security = parsed.security,
                    retryAt = parsed.retryAt,
                )
            }
        }

        // 形态 2：Spring 默认错误体 —— status + error + path
        val springStatus = parsed?.status ?: httpStatus
        val springError = parsed?.error?.takeIf { it.isNotBlank() }
        val path = parsed?.path?.takeIf { it.isNotBlank() }
        if (springError != null || path != null) {
            val message = buildString {
                append(statusMessage(springStatus))
                if (springError != null) append("（").append(springError).append("）")
                if (path != null) append("：").append(path)
            }
            return ApiException(
                code = httpCode(httpStatus),
                message = message,
                httpStatus = httpStatus,
                body = body,
            )
        }

        // 形态 3：兜底（空 body / 非 JSON）
        return ApiException(
            code = httpCode(httpStatus),
            message = statusMessage(httpStatus),
            httpStatus = httpStatus,
            body = body,
        )
    }

    /** 把 OkHttp/IO 异常包装成统一的 [ApiException]（`httpStatus == 0`）。 */
    fun network(e: IOException): ApiException {
        if (e is ApiException) return e
        val message = when (e) {
            is UnknownHostException -> "无法连接服务器：域名解析失败，请检查「设置」里的服务器地址"
            is ConnectException -> "无法连接服务器，请确认后端已启动、地址与端口正确"
            is SocketTimeoutException -> "请求超时，请检查网络或稍后重试"
            is SSLException -> "HTTPS 证书校验失败，请确认服务器地址使用 http://"
            else -> "网络请求失败：${e.message ?: e.javaClass.simpleName}"
        }
        return ApiException(
            code = ApiException.NETWORK_ERROR,
            message = message,
            httpStatus = 0,
            cause = e,
        )
    }

    /** 本地解析失败（2xx 但响应体不是合法 JSON / 不符合模型）。 */
    fun unexpected(e: Throwable): ApiException = ApiException(
        code = ApiException.PARSE_ERROR,
        message = "服务器响应格式异常，请查看日志后重试（${e.message ?: e.javaClass.simpleName}）",
        httpStatus = -1,
        cause = e,
    )

    /** 会话失效（数据层会自动重建会话并重放一次，页面一般看不到）。 */
    fun isSessionExpiry(e: ApiException): Boolean = e.httpStatus == 401 ||
        e.code == ApiException.SESSION_REQUIRED ||
        e.code == ApiException.CSRF_REQUIRED

    private fun httpCode(httpStatus: Int): String =
        if (httpStatus > 0) "HTTP_$httpStatus" else ApiException.NETWORK_ERROR

    /** HTTP 状态码 → 中文可读文案。 */
    fun statusMessage(httpStatus: Int): String = when (httpStatus) {
        400 -> "请求不合法"
        401 -> "会话已失效，请重新建立会话"
        403 -> "没有权限执行该操作"
        404 -> "资源不存在"
        405 -> "接口不支持该请求方式"
        409 -> "操作状态已改变，请刷新后重试"
        410 -> "会话上下文已过期，请重新建立"
        422 -> "请求参数无法处理"
        423 -> "出于安全原因操作已被暂时锁定"
        429 -> "请求过于频繁，请稍后重试"
        500 -> "服务器内部错误"
        502 -> "网关错误"
        503 -> "服务暂不可用"
        504 -> "服务器响应超时"
        else -> if (httpStatus > 0) "请求失败（HTTP $httpStatus）" else "网络请求失败"
    }

    /** 常见业务码的后备文案（后端一般会自带中文 message，这里只兜底）。 */
    fun defaultMessage(code: String, httpStatus: Int): String = when (code) {
        ApiException.SESSION_REQUIRED -> "会话已失效，请重新建立会话"
        ApiException.CSRF_REQUIRED -> "会话校验失败，请重试"
        ApiException.ORIGIN_DENIED -> "请求来源不被允许"
        ApiException.VALIDATION_ERROR -> "请求参数不合法"
        ApiException.NOT_FOUND -> "资源不存在或不属于当前用户"
        ApiException.CONFLICT -> "操作状态已改变，请刷新后重试"
        ApiException.AUTH_REQUIRED -> "提交前需要先完成确认授权"
        ApiException.AUTH_EXPIRED -> "预览或授权已过期，请重新生成预览"
        ApiException.INSUFFICIENT_FUNDS -> "账户余额不足"
        ApiException.LIMIT_EXCEEDED -> "超出单笔限额（¥10,000）"
        ApiException.RISK_DECISION_STALE -> "风险判定已变化，请重新刷新风险预览"
        ApiException.STEP_UP_REQUIRED -> "该操作需要额外的短信/验证码验证"
        ApiException.VERIFICATION_INVALID -> "验证码不正确"
        ApiException.VERIFICATION_EXPIRED -> "验证码已过期，请重新获取"
        ApiException.VERIFICATION_UNAVAILABLE -> "当前后端未启用演示验证，请使用 risk-demo profile 启动后端"
        ApiException.CHALLENGE_RATE_LIMITED -> "验证码请求过于频繁，请稍后再试"
        ApiException.SECURITY_LOCKED -> "出于安全原因操作已被暂时锁定"
        ApiException.SIMULATION_DISABLED -> "当前后端未开启模拟/故障注入（需要 risk-demo profile）"
        ApiException.QUERY_CONFIRMATION_REQUIRED -> "读取本人代扣列表前需要确认查询范围"
        "CONTEXT_PROCESSING" -> "原请求仍在处理中，请稍后重试"
        "CONTEXT_INTERRUPTED" -> "原请求未完成，请先核对关联动作，不会自动重放"
        "CONTEXT_EXPIRED" -> "对话上下文已过期，请新建对话"
        "ACTION_ALREADY_COMPLETED" -> "该动作已结束，只能查询结果，不能新建相同动作"
        "ACTION_RECONCILING" -> "该动作正在对账中，请稍后查询"
        "RISK_RECONCILIATION_REQUIRED" -> "存在未核验的交易，需要先完成对账"
        else -> statusMessage(httpStatus)
    }
}
