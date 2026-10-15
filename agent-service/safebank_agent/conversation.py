"""Bounded user clarification hints; never bank facts, identity or authorization."""
import unicodedata

def bounded_clarification_context(history):
 values=[value for value in history if isinstance(value,str) and value.strip()] if isinstance(history,list) else []
 values=values[-4:]
 while values and sum(map(len,values))>4000:values.pop(0)
 return values

def defers_operation(question):
 text=unicodedata.normalize('NFKC',question).strip().rstrip('。.!！?？ ').strip()
 return text in {'先不办','先不办了','暂时不办','暂不办理','先别办','先不转','先不撤销'}
