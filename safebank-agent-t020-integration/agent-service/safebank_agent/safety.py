"""Narrow social-engineering context gate; never an authorization or risk downgrade.

This covers explicit security-account/unfreezing combinations, not arbitrary fraud.
The hold is conversation-local. Read queries remain available after a warning.
"""
import re
import unicodedata

def _credential_scan_text(text):
    """Exclude only explicitly reported, immediately discouraged scam quotes.

    This affects classification only; original model output remains unchanged.
    Every request outside the bounded quote is still scanned.
    """
    text=unicodedata.normalize('NFKC',text)
    quotes=re.compile(r'“[^”\n]{1,200}”|‘[^’\n]{1,200}’|"[^"\n]{1,200}"')
    report=re.compile(r'(?:诈骗|可疑)(?:短信|消息|信息)[^。；;！？!?\n]{0,12}(?:写着|声称|要求|内容为)[：:\s]*$')
    warning=re.compile(r'^[，,\s]*(?:请)?(?:不要照做|不要按此操作|切勿照做)(?=[，,。；;！？!?\s]|$)')
    for match in reversed(list(quotes.finditer(text))):
        if report.search(text[max(0,match.start()-40):match.start()]) and warning.match(text[match.end():]):
            text=text[:match.start()]+'[已明确警告的诈骗原文]'+text[match.end():]
    return text


_CREDENTIAL_CHANNELS=r'发给我|发送给我|告诉我|回复给我|提供给我|发过来|发来|在这里(?:输入|填写|发送)|在聊天(?:里|中)(?:输入|填写|发送|回复)|(?:发送|发|贴|粘贴)(?:到|在)(?:这里|聊天(?:里|中))'


def requests_chat_credentials(text):
    """Reject direct collection in chat; official-page verification is separate.

    Narrow output contract, not a complete semantic privacy classifier.
    """
    negative=re.compile(r'(?:不要|不得|不能|不应|无需|不会|不用|不必)(?:把|将|你|您|你的|您的|再|继续|向我|给我|提供|发送|发|告诉|要求){0,4}\s*$')
    credentials=re.compile(r'验证码|密码(?!怎么|如何|修改|重置|忘记|设置|强度|安全|错误|问题)|服务密钥|后台密钥|API\s*(?:key|密钥)',re.I)
    channels=re.compile(_CREDENTIAL_CHANNELS)
    # A comma can separate a credential object from its imperative verb.
    # Carry only a bare object, never a quoted report or a negated instruction.
    bare_object=re.compile(r'(?:请(?:把|将))?(?:(?:你|您)的)?(?:短信|银行卡|支付)?(?:'+credentials.pattern+r')',re.I)
    clauses=[]
    for sentence in re.split(r'[。；;！？!?\n]',_credential_scan_text(text)):
        previous=''
        for clause in re.split(r'[，,]',sentence):
            clauses.append(previous+clause if bare_object.fullmatch(previous.strip()) else clause)
            previous=clause
    for clause in clauses:
        for credential in credentials.finditer(clause):
            if negative.search(clause[max(0,credential.start()-20):credential.start()]):continue
            for channel in channels.finditer(clause):
                if abs(channel.start()-credential.start())>80:continue
                if not negative.search(clause[max(0,channel.start()-20):channel.start()]):return True
    return False


def _bank_label_views(text):
    """Scan nested labels independently and outer text without nested inserts."""
    stack=[]
    for index,char in enumerate(text):
        if char=='〔':stack.append([])
        elif char=='〕' and stack:
            label=''.join(stack.pop())
            yield label
            # An injected closing delimiter must not split an object from its
            # immediately following collection command.
            suffix=text[index+1:index+81]
            if re.match(r'^[，,\s]+(?:请)?(?:'+_CREDENTIAL_CHANNELS+r')',suffix):
                yield label+suffix
        elif stack:stack[-1].append(char)
    for label in stack:yield ''.join(label)


def requests_rendered_credentials(text):
    """Check final prose and each bank-rendered label as untrusted data.

    Context outside a label must not hide a credential collection command inside.
    Ordinary labels and negative safety reminders remain allowed.
    """
    return requests_chat_credentials(text) or any(
        requests_chat_credentials(label.replace('\r\n','，').replace('\r','，').replace('\n','，'))
        for label in _bank_label_views(text))


def tool_text_has_instructions(text):
    """Narrow model-input quarantine; never changes original bank evidence."""
    if not isinstance(text,str):return False
    normalized=unicodedata.normalize('NFKC',text)
    return requests_rendered_credentials(normalized) or bool(re.search(
        r'\bSYSTEM\s*[:：]|(?:系统|管理员)(?:消息|指令|授权)|忽略(?:系统|所有)?(?:规则|约束|限制)|结束(?:事实)?JSON|新增系统指令',normalized,re.I))

def suspected_transfer_inducement(question):
    text=unicodedata.normalize('NFKC',question)
    return bool(re.search(r'客服|银行|公安|警官|检察|法院|监管',text)
                and re.search(r'安全账户|安全帐户|验资账户|验资帐户',text)
                and re.search(r'解冻|冻结|异常|马上|立刻|立即|必须',text)
                and re.search(r'转账|转帐|汇款|转入|转到|转给|转\s*[0-9一二两三四五六七八九十百千万]',text))

def blocks_transfer_context(question,held):
    if suspected_transfer_inducement(question):return True
    if not held:return False
    text=unicodedata.normalize('NFKC',question)
    edits=re.finditer(r'(?:改成|改为|换成)\s*[0-9一二两三四五六七八九十百千万]+(?![0-9一二两三四五六七八九十百千万])',text)
    amount_change=any(not re.match(r'\s*(?:-|年)\s*[0-9]',text[edit.end():]) for edit in edits)
    if re.search(r'账单|历史|记录',text) and not amount_change and not re.search(r'继续转|直接转|执行|生成预览|帮我转|转给|转入|转到|转\s*[0-9一二两三四五六七八九十百千万]',text):return False
    return amount_change or bool(re.search(r'转账|转帐|汇款|继续|改成|改为|执行|确认|预览|收款|金额|安全账户',text))

def adequate_transfer_warning(text):
    def affirmative(pattern):
        return any(not re.search(r'(?:不是|并非|不属于|没有|不必|无需|不要|不得|不能|不应|不用|不会|不能认定|不能确定|无法|未能)(?:再|继续|你|您|向我|要求你|要求您|(?:替|代替)(?:你|您)(?:执行或)?)?\s*$',text[max(0,m.start()-12):m.start()]) for m in re.finditer(pattern,text))
    return (affirmative(r'诈骗|骗局|冒充')
            and affirmative(r'不要转账|不要(?:再|继续)(?:操作|进行)?转账|暂停(?:这笔|该笔|当前)?(?:[0-9零一二两三四五六七八九十百千万]+元)?(?:的)?转账(?:操作)?|先不要转|切勿转|停止转账|先别转')
            and '官方' in text and bool(re.search(r'核实|核验|确认|联系',text))
            and not affirmative(r'(?:请)?(?:告诉我|提供|补充|确认)[^。；;！？!?\n]{0,30}(?:付款账户|收款人|手机号|金额|验证码|密码)|(?:操作卡|预览)[^。；;！？!?\n]{0,16}(?:授权|提交)|完成授权|继续转账|继续汇款'))
