package cn.safebank.converter;
import cn.safebank.vo.NoPendingActionVO;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
public class EmptyPendingActionSerializer extends JsonSerializer<NoPendingActionVO> {
    @Override public void serialize(NoPendingActionVO value,JsonGenerator out,SerializerProvider provider) throws IOException{out.writeStartObject();out.writeEndObject();}
}
