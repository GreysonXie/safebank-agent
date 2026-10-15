"""Bounded user clarification hints; never bank facts, identity or authorization."""
import unicodedata
import re

def bounded_clarification_context(history):
 values=[value for value in history if isinstance(value,str) and value.strip()] if isinstance(history,list) else []
 values=values[-4:]
 while values and sum(map(len,values))>4000:values.pop(0)
 return values

def defers_operation(question):
 text=unicodedata.normalize('NFKC',question).strip().rstrip('。.!！?？ ').strip()
 return text in {'先不办','先不办了','暂时不办','暂不办理','先别办','先不转','先不撤销'}

def requires_amendment_target(question,previous_plan,history,previous_question):
 """Recognize only standalone amount edits; never recover a completed older intent."""
 text=unicodedata.normalize('NFKC',question).strip().rstrip('。.!！?？ ').strip()
 if not re.fullmatch(r'(?:再)?(?:改成|改为|换成)\s*[0-9]+(?:\.[0-9]{1,2})?\s*(?:元|块)?',text):return False
 if any(tool.get('tool')=='transfer_preview' for tool in previous_plan.get('tools',[])):return False
 if previous_plan.get('kind')=='clarification':
  fragments=bounded_clarification_context(history) or [previous_question]
  if any(re.search(r'转(?:账|给|[0-9])|给.+转',fragment) for fragment in fragments):return False
 return True

def requires_revoke_preview(question):
 """Bounded explicit preparation requests only; never permission to execute."""
 text=unicodedata.normalize('NFKC',question)
 for clause in re.split(r'[，,。；;！？!?\n]',text):
  if re.search(r'能否|能不能|可以|是否|吗|么|是什么|什么意思|影响|风险',clause):continue
  match=re.search(r'(?:只|先|请|帮我)*(?:生成|准备|创建)[^，,。；;！？!?\n]{0,40}撤销预览',clause)
  if match and not re.search(r'不|没|未|别|勿|无需',clause[:match.start()]):return True
 return False
