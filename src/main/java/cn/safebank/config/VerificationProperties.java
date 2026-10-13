package cn.safebank.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component @ConfigurationProperties("bank.verification")
public class VerificationProperties {
    private boolean demoEnabled;
    private String pepper="";
    private String operatorToken="";
    private int consolePort=8101;
    public boolean isDemoEnabled(){return demoEnabled;}
    public void setDemoEnabled(boolean value){demoEnabled=value;}
    public String getPepper(){return pepper;}
    public void setPepper(String value){pepper=value;}
    public String getOperatorToken(){return operatorToken;}
    public void setOperatorToken(String value){operatorToken=value;}
    public int getConsolePort(){return consolePort;}
    public void setConsolePort(int value){consolePort=value;}
}
