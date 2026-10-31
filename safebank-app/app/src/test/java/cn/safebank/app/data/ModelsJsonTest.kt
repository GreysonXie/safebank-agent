package cn.safebank.app.data

import cn.safebank.app.data.model.ActionExecutionResultVo
import cn.safebank.app.data.model.ActionVo
import cn.safebank.app.data.model.AuthorizationRequest
import cn.safebank.app.data.model.ChatRequest
import cn.safebank.app.data.model.ChatResponse
import cn.safebank.app.data.model.ConversationVo
import cn.safebank.app.data.model.StatementVo
import cn.safebank.app.data.model.WorkflowRequestDto
import cn.safebank.app.data.model.WorkflowVo
import cn.safebank.app.data.network.SafeBankJson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gson 模型解析测试（纯 JVM，不联网）。
 *
 * 覆盖 docs/api-contract.md 里最容易踩的形态差异：
 * - 动作/回执的 snake_case 字段；
 * - workflow 的 camelCase 字段 + `pendingAction: {}`；
 * - `OFFLINE_DEMO` 的 `/api/chat` 缺少 `results`/`answerSource`/`factReferences`；
 * - 缺省字段必须保留 Models.kt 的 Kotlin 默认值（SafeBankJson 的 InstanceCreator 规则）；
 * - 请求体必须是 camelCase 且不发送 null 字段。
 */
class ModelsJsonTest {

    private val gson = SafeBankJson.gson

    /** 走数据层统一入口（含 JsonNull → null 归一化）。 */
    private inline fun <reified T> decode(json: String, type: Class<T>): T =
        SafeBankJson.decode(json, type)

    @Test
    fun `transfer action snake_case fields are mapped`() {
        val action = decode<ActionVo>(TRANSFER_ACTION_JSON, ActionVo::class.java)

        assertEquals("9a1f3c62-5b1e-4a77-9c2d-1f0b7a5e4d11", action.id)
        assertEquals("u1", action.userId)
        assertEquals("TRANSFER", action.kind)
        assertEquals("b1d0", action.payloadHash)
        assertEquals("AWAITING_AUTH", action.status)
        assertEquals("2026-10-27T14:35:12.345678Z", action.expiresAt)
        assertNull(action.receiptId)
        assertNull(action.errorCode)
        assertEquals("checking", action.sourceId)
        assertEquals("zhang-a", action.recipientId)
        assertEquals("张三 / 张三 3271", action.recipientLabel)
        assertEquals(50000L, action.amountMinor)
        assertEquals("CNY", action.currency)
        assertEquals(0L, action.feeMinor)
        assertNull(action.scheduleId)
        assertTrue(action.isAwaitingAuth)
        assertFalse(action.isEmptyObject())

        // security 内部永远是 camelCase
        val security = action.security
        assertNotNull(security)
        assertEquals("e77c0a1b", security!!.decisionId)
        assertEquals("bank-risk-v1", security.policyVersion)
        assertEquals("YELLOW", security.riskLevel)
        assertTrue(security.isYellow)
        assertFalse(security.isRed)
        assertEquals(100000L, security.effectiveMinor)
        assertEquals(150000L, security.projectedMinor)
        assertFalse(security.needsRefresh)
    }

    @Test
    fun `revoke action optional monthly saving may be absent`() {
        val quarterly = decode<ActionVo>(
            """
            {"id":"a2","kind":"REVOKE","status":"AWAITING_AUTH","payload_hash":"h2",
             "mandate_id":"video","merchant":"视频会员","charge_amount_minor":6800,
             "billing_interval":"QUARTERLY","contract_status":"UNKNOWN","scope":"仅撤销银行侧代扣授权，商户合同状态未知",
             "expires_at":"2026-10-27T14:35:12Z","receipt_id":null,"error_code":null}
            """.trimIndent(),
            ActionVo::class.java,
        )
        assertEquals("video", quarterly.mandateId)
        assertEquals(6800L, quarterly.chargeAmountMinor)
        assertEquals("QUARTERLY", quarterly.billingInterval)
        // 非 MONTHLY 时后端完全不下发该键 → 保持 Kotlin 默认值 null
        assertNull(quarterly.estimatedMonthlySavingMinor)
        assertNull(quarterly.security)
    }

    @Test
    fun `execution receipt observed_at is parsed and convertible`() {
        val receipt = decode<ActionExecutionResultVo>(
            """
            {"id":"c1","user_id":"u1","kind":"TRANSFER","payload_hash":"b1d0",
             "status":"VERIFIED_SUCCESS","expires_at":"2026-10-27T14:35:12.345678Z",
             "receipt_id":"c1a2b3","error_code":null,
             "source_id":"checking","recipient_id":"zhang-a","recipient_label":"张三 / 张三 3271",
             "amount_minor":50000,"currency":"CNY","fee_minor":0,
             "observed_at":"2026-10-27T14:22:03.456789Z",
             "security":{"decisionId":"e77c0a1b","riskLevel":"YELLOW","needsRefresh":false}}
            """.trimIndent(),
            ActionExecutionResultVo::class.java,
        )
        assertEquals("VERIFIED_SUCCESS", receipt.status)
        assertEquals("c1a2b3", receipt.receiptId)
        assertEquals("2026-10-27T14:22:03.456789Z", receipt.observedAt)
        assertNull(receipt.observation)

        val action = receipt.toActionVo()
        assertEquals("c1", action.id)
        assertEquals("TRANSFER", action.kind)
        assertEquals(50000L, action.amountMinor)
        assertEquals("YELLOW", action.security?.riskLevel)
    }

    @Test
    fun `query execution receipt uses camelCase queriedAt and mandates`() {
        val receipt = decode<ActionExecutionResultVo>(
            """
            {"id":"q1","user_id":"u1","kind":"QUERY_MANDATES","payload_hash":"hq",
             "status":"VERIFIED_SUCCESS","expires_at":"2026-10-27T14:35:12Z",
             "receipt_id":"r1","error_code":null,"query":"OWN_BANK_MANDATES",
             "data":[{"id":"music","user_id":"u1","merchant":"音乐服务","amount_minor":1500,
                      "renewal_date":"2026-10-20","status":"ACTIVE","contract_status":"UNKNOWN",
                      "billing_interval":"MONTHLY"}],
             "queriedAt":"2026-10-27T14:23:00.123456Z"}
            """.trimIndent(),
            ActionExecutionResultVo::class.java,
        )
        assertEquals("QUERY_MANDATES", receipt.kind)
        assertEquals("OWN_BANK_MANDATES", receipt.query)
        assertEquals(1, receipt.data?.size)
        val mandate = receipt.data!!.first()
        assertEquals("music", mandate.id)
        assertEquals(1500L, mandate.amountMinor)
        assertEquals("MONTHLY", mandate.billingInterval)
        assertEquals("UNKNOWN", mandate.contractStatus)
        assertEquals("2026-10-27T14:23:00.123456Z", receipt.queriedAt)
    }

    @Test
    fun `workflow camelCase fields and empty pendingAction`() {
        val workflow = decode<WorkflowVo>(WORKFLOW_JSON, WorkflowVo::class.java)

        assertEquals("5b7c1a2e-0d4f-4c9a-9c11-2a3b4c5d6e7f", workflow.id)
        assertEquals(3, workflow.version)
        assertEquals("AWAITING_AUTH", workflow.state)
        assertEquals(1500L, workflow.estimatedSavingMinor)
        assertFalse(workflow.partial)
        assertEquals("2026-09", workflow.request?.month)
        assertEquals(listOf("music"), workflow.request?.mandateIds)
        assertEquals(20, workflow.request?.dayOfMonth)
        assertEquals(true, workflow.request?.onlyConfirmedSavings)
        assertEquals(1, workflow.steps.size)
        assertEquals("revoke:music", workflow.steps[0].key)
        assertEquals(1500L, workflow.steps[0].savingMinor)
        assertEquals(1, workflow.steps[0].inputVersion)

        // pendingAction 是空对象 {}：不能 NPE，且 pendingActionOrNull 必须为 null
        assertNotNull(workflow.pendingAction)
        assertTrue(workflow.pendingAction!!.isEmptyObject())
        assertNull(workflow.pendingActionOrNull)

        assertEquals(340300L, workflow.analysis?.expenseMinor)
        assertEquals(mapOf("居住" to 250000L), workflow.analysis?.categories)
    }

    @Test
    fun `workflow real pendingAction is exposed`() {
        val workflow = decode<WorkflowVo>(
            WORKFLOW_JSON.replace(
                "\"pendingAction\": {}",
                "\"pendingAction\": {\"id\":\"act-1\",\"kind\":\"REVOKE\",\"status\":\"AWAITING_AUTH\"," +
                    "\"payload_hash\":\"hp\",\"expires_at\":\"2026-10-27T14:35:12Z\"," +
                    "\"mandate_id\":\"music\",\"charge_amount_minor\":1500,\"billing_interval\":\"MONTHLY\"," +
                    "\"security\":{\"decisionId\":\"d1\",\"riskLevel\":\"YELLOW\",\"needsRefresh\":false}}",
            ),
            WorkflowVo::class.java,
        )
        val pending = workflow.pendingActionOrNull
        assertNotNull(pending)
        assertEquals("act-1", pending!!.id)
        assertEquals("REVOKE", pending.kind)
        assertEquals("music", pending.mandateId)
        assertEquals(1500L, pending.chargeAmountMinor)
        assertEquals("YELLOW", pending.security?.riskLevel)
    }

    @Test
    fun `statement parses nullable transaction fields and keeps defaults`() {
        val statement = decode<StatementVo>(STATEMENT_JSON, StatementVo::class.java)
        assertEquals("2026-09", statement.month)
        assertEquals(340300L, statement.expenseMinor)
        assertEquals(850000L, statement.incomeMinor)
        assertEquals(0L, statement.internalTransferMinor)
        assertEquals(mapOf("居住" to 250000L, "餐饮" to 82000L), statement.categories)
        assertEquals(2, statement.transactions.size)
        val rent = statement.transactions[0]
        assertEquals(-250000L, rent.amountMinor)
        assertEquals("2026-09-01", rent.bookedOn)
        assertNull(statement.transactions[1].amountMinor)
        assertNull(statement.transactions[1].bookedOn)
        assertEquals(1, statement.anomalyCandidates.size)
        assertFalse(statement.usageDataAvailable)

        // 缺省字段（categories/transactions/anomaly_candidates…）保留 Kotlin 默认值
        val minimal = decode<StatementVo>("""{"month":"2026-10"}""", StatementVo::class.java)
        assertEquals("2026-10", minimal.month)
        assertEquals(0L, minimal.expenseMinor)
        assertTrue(minimal.categories.isEmpty())
        assertTrue(minimal.transactions.isEmpty())
        assertTrue(minimal.anomalyCandidates.isEmpty())
        assertEquals("", minimal.anomalyRule)
    }

    @Test
    fun `offline demo chat response without results answerSource factReferences`() {
        val chat = decode<ChatResponse>(OFFLINE_CHAT_JSON, ChatResponse::class.java)

        assertEquals("OFFLINE_DEMO", chat.mode)
        assertTrue(chat.text.startsWith("离线工具演示"))
        assertEquals(listOf("查询交易", "程序汇总"), chat.trace)
        assertTrue(chat.actions.isEmpty())
        // 三个仅模型链路才有的键在 OFFLINE_DEMO 下完全缺失
        assertNull(chat.results)
        assertNull(chat.answerSource)
        assertNull(chat.factReferences)
        assertNull(chat.agentBackend)
        assertNull(chat.toolResults)
        assertNull(chat.clarification)
        assertEquals(2, chat.contextVersion)
        assertEquals("2026-09", chat.context?.slots?.get("primary_month")?.asString)

        val data = chat.data
        assertTrue(data != null && data.isJsonObject)
        val obj = data as JsonObject
        assertEquals("2026-09", obj.get("month").asString)
        assertEquals(340300L, obj.get("expense_minor").asLong)
    }

    @Test
    fun `chat response supports clarification three shapes and pending actions`() {
        // 形态 1：null（上面已断言）
        // 形态 2：{"required": false}
        val requiredShape = decode<ChatResponse>(
            """{"text":"hi","mode":"MODEL_CONFIGURED","clarification":{"required":false}}""",
            ChatResponse::class.java,
        )
        assertNull(requiredShape.clarificationQuestion())
        assertTrue(requiredShape.clarificationMissingSlots().isEmpty())

        // 形态 3：ClarificationVO
        val voShape = decode<ChatResponse>(
            """
            {"text":"请补充","mode":"MODEL_CONFIGURED",
             "clarification":{"question":"请选择收款人","missingSlots":["selected_recipient_id"],
                              "recipientCandidates":[{"id":"zhang-a","name":"张三","note":"同学","label":"张三 3271"}]}}
            """.trimIndent(),
            ChatResponse::class.java,
        )
        assertEquals("请选择收款人", voShape.clarificationQuestion())
        assertEquals(listOf("selected_recipient_id"), voShape.clarificationMissingSlots())

        // actions 里 AWAITING_AUTH 的才是待确认操作；缺失的 answerSource 不影响判断
        val withAction = decode<ChatResponse>(
            """
            {"text":"已生成转账确认卡","mode":"OFFLINE_DEMO","actions":[
               {"id":"a1","kind":"TRANSFER","status":"AWAITING_AUTH","payload_hash":"p",
                "expires_at":"2026-10-27T14:35:12Z","receipt_id":null,"error_code":null,
                "amount_minor":50000,"source_id":"checking","recipient_id":"zhang-a"}
             ]}
            """.trimIndent(),
            ChatResponse::class.java,
        )
        assertEquals(1, withAction.pendingActions.size)
        assertEquals("a1", withAction.pendingActions[0].id)
    }

    @Test
    fun `conversation slots are dynamic json elements`() {
        val conversation = decode<ConversationVo>(
            """
            {"conversationId":"conv-1","version":2,"intent":"STATEMENT_ANALYSIS","state":"ACTIVE",
             "slots":{"primary_month":"2026-09","amount_minor":50000,"selected_mandate_ids":["music"]},
             "proposal":null,"displayHistory":[{"user":"分析账单","assistant":"好的"}],"bankReferences":[]}
            """.trimIndent(),
            ConversationVo::class.java,
        )
        assertEquals("conv-1", conversation.conversationId)
        assertEquals(2, conversation.version)
        assertEquals("2026-09", conversation.slots["primary_month"]?.asString)
        assertEquals(50000L, conversation.slots["amount_minor"]?.asLong)
        assertEquals("music", conversation.slots["selected_mandate_ids"]?.asJsonArray?.get(0)?.asString)
        assertNull(conversation.proposal)
        assertEquals("好的", conversation.displayHistory[0].assistant)
    }

    @Test
    fun `request bodies are camelCase and omit null fields`() {
        val transfer = gson.toJson(TransferPreviewRequestJson)
        assertTrue(transfer.contains("\"sourceId\":\"checking\""))
        assertTrue(transfer.contains("\"recipientId\":\"zhang-a\""))
        assertTrue(transfer.contains("\"amountMinor\":50000"))
        assertFalse(transfer.contains("source_id"))

        val authorize = gson.toJson(
            AuthorizationRequest(
                payloadHash = "b1d0",
                decisionId = "e77c",
                userConfirmed = true,
                verificationProofId = null,
            ),
        )
        assertTrue(authorize.contains("\"payloadHash\":\"b1d0\""))
        assertTrue(authorize.contains("\"decisionId\":\"e77c\""))
        assertTrue(authorize.contains("\"userConfirmed\":true"))
        // 可空字段为 null 时 Gson 不输出该键（agent-gateway 的 fail-on-unknown-properties 也安全）
        assertFalse(authorize.contains("verificationProofId"))
        assertFalse(authorize.contains("demoVerificationAccepted"))

        val chat = gson.toJson(ChatRequest(text = "分析2026-09账单", modelRequestLimit = 5))
        assertTrue(chat.contains("\"text\""))
        assertTrue(chat.contains("\"modelRequestLimit\":5"))
        assertFalse(chat.contains("conversationId"))
        assertFalse(chat.contains("requestId"))

        val workflow = gson.toJson(
            WorkflowRequestDto(
                month = "2026-09",
                mandateIds = listOf("music"),
                sourceId = "checking",
                recipientId = "travel-recipient",
                firstDate = "2026-11-20",
                endDate = "2027-10-20",
                dayOfMonth = 20,
            ),
        )
        assertTrue(workflow.contains("\"onlyConfirmedSavings\":true"))
        assertTrue(workflow.contains("\"mandateIds\":[\"music\"]"))
        assertTrue(workflow.contains("\"dayOfMonth\":20"))
    }

    private companion object {
        val TransferPreviewRequestJson = cn.safebank.app.data.model.TransferPreviewRequest(
            sourceId = "checking",
            recipientId = "zhang-a",
            amountMinor = 50000,
        )

        const val TRANSFER_ACTION_JSON = """
        {
          "id": "9a1f3c62-5b1e-4a77-9c2d-1f0b7a5e4d11",
          "user_id": "u1",
          "kind": "TRANSFER",
          "payload_hash": "b1d0",
          "status": "AWAITING_AUTH",
          "expires_at": "2026-10-27T14:35:12.345678Z",
          "receipt_id": null,
          "error_code": null,
          "source_id": "checking",
          "recipient_id": "zhang-a",
          "recipient_label": "张三 / 张三 3271",
          "amount_minor": 50000,
          "currency": "CNY",
          "fee_minor": 0,
          "security": {
            "decisionId": "e77c0a1b",
            "policyVersion": "bank-risk-v1",
            "riskLevel": "YELLOW",
            "requiredAssurance": "CONFIRMED",
            "reasonCode": "EXPLICIT_CONFIRMATION_REQUIRED",
            "businessDate": "2026-10-27",
            "effectiveMinor": 100000,
            "projectedMinor": 150000,
            "validUntil": "2026-10-27T14:35:12.345678Z",
            "nextStep": "CONFIRM",
            "verificationMode": "EXTERNAL_MFA_REQUIRED",
            "needsRefresh": false
          }
        }
        """

        const val WORKFLOW_JSON = """
        {
          "id": "5b7c1a2e-0d4f-4c9a-9c11-2a3b4c5d6e7f",
          "version": 3,
          "state": "AWAITING_AUTH",
          "request": {
            "month": "2026-09",
            "mandateIds": ["music"],
            "sourceId": "checking",
            "recipientId": "travel-recipient",
            "firstDate": "2026-11-20",
            "endDate": "2027-10-20",
            "dayOfMonth": 20,
            "onlyConfirmedSavings": true
          },
          "estimatedSavingMinor": 1500,
          "partial": false,
          "steps": [
            {"key": "revoke:music", "kind": "REVOKE", "status": "AWAITING_AUTH",
             "actionId": "act-1", "inputVersion": 1, "savingMinor": 1500, "errorCode": null}
          ],
          "pendingAction": {},
          "analysis": {
            "month": "2026-09",
            "expense_minor": 340300,
            "income_minor": 850000,
            "internal_transfer_minor": 0,
            "categories": {"居住": 250000},
            "transactions": [],
            "anomaly_candidates": [],
            "anomaly_rule": "单笔支出达到 2000 元，仅为候选",
            "usage_data_available": false
          }
        }
        """

        const val STATEMENT_JSON = """
        {
          "month": "2026-09",
          "expense_minor": 340300,
          "income_minor": 850000,
          "internal_transfer_minor": 0,
          "categories": {"居住": 250000, "餐饮": 82000},
          "transactions": [
            {"id": "rent-9", "merchant": "租金", "category": "居住",
             "amount_minor": -250000, "booked_on": "2026-09-01"},
            {"id": "unknown-1", "merchant": "未知", "category": "其他",
             "amount_minor": null, "booked_on": null}
          ],
          "anomaly_candidates": [
            {"id": "rent-9", "merchant": "租金", "category": "居住",
             "amount_minor": -250000, "booked_on": "2026-09-01"}
          ],
          "anomaly_rule": "单笔支出达到 2000 元，仅为候选，不能推断欺诈",
          "usage_data_available": false
        }
        """

        const val OFFLINE_CHAT_JSON = """
        {
          "text": "离线工具演示（未调用模型）：\n2026-09 支出合计 3,403.00 元……",
          "mode": "OFFLINE_DEMO",
          "data": {"month": "2026-09", "expense_minor": 340300, "income_minor": 850000,
                   "internal_transfer_minor": 0, "categories": {}, "transactions": [],
                   "anomaly_candidates": [], "anomaly_rule": "规则", "usage_data_available": false},
          "actions": [],
          "trace": ["查询交易", "程序汇总"],
          "contextResults": [],
          "modelUsage": {"request_count": 0, "request_limit": 5, "calls": []},
          "timing": {"requestElapsedMs": 41, "authorizationWaitIncluded": false},
          "clarification": null,
          "conversationId": "conv-1",
          "contextVersion": 2,
          "context": {
            "conversationId": "conv-1",
            "version": 2,
            "intent": "STATEMENT_ANALYSIS",
            "state": "ACTIVE",
            "slots": {"primary_month": "2026-09"},
            "proposal": null,
            "displayHistory": [{"user": "分析2026-09账单", "assistant": "离线工具演示（未调用模型）：……"}],
            "bankReferences": []
          }
        }
        """
    }
}
