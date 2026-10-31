package cn.safebank.model;
import static cn.safebank.model.ModelContracts.*;
public interface ModelClient {
    ModelReply complete(ModelRequest request);
    String mode();
    String modelName();
    default int availableRequests(){return Integer.MAX_VALUE;}
}
