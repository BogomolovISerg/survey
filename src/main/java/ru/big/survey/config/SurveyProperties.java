package ru.big.survey.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Настройки сервиса (`survey.*` в application.yml). */
@Validated
@ConfigurationProperties("survey")
public class SurveyProperties {

    /** Публичный адрес приложения с контекстом, без завершающего слэша: https://survey.bigcom.ru/survey */
    @NotBlank
    private String publicBaseUrl = "http://localhost:8080";
    private Security security = new Security();
    private Verification verification = new Verification();
    private FlashCall flashCall = new FlashCall();
    private int giftCodeLength = 6;

    public String getPublicBaseUrl() {
        return publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }
    public Verification getVerification() { return verification; }
    public void setVerification(Verification verification) { this.verification = verification; }
    public FlashCall getFlashCall() { return flashCall; }
    public void setFlashCall(FlashCall flashCall) { this.flashCall = flashCall; }
    public int getGiftCodeLength() { return giftCodeLength; }
    public void setGiftCodeLength(int giftCodeLength) { this.giftCodeLength = giftCodeLength; }

    public static class Security {
        private String tokenSecret = "";
        private Duration verificationTokenValid = Duration.ofMinutes(30);
        private Duration giftTokenValid = Duration.ofDays(30);
        private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();

        public String getTokenSecret() { return tokenSecret; }
        public void setTokenSecret(String tokenSecret) { this.tokenSecret = tokenSecret; }
        public Duration getVerificationTokenValid() { return verificationTokenValid; }
        public void setVerificationTokenValid(Duration verificationTokenValid) { this.verificationTokenValid = verificationTokenValid; }
        public Duration getGiftTokenValid() { return giftTokenValid; }
        public void setGiftTokenValid(Duration giftTokenValid) { this.giftTokenValid = giftTokenValid; }
        public BootstrapAdmin getBootstrapAdmin() { return bootstrapAdmin; }
        public void setBootstrapAdmin(BootstrapAdmin bootstrapAdmin) { this.bootstrapAdmin = bootstrapAdmin; }
    }

    public static class BootstrapAdmin {
        private String username = "admin";
        private String displayName = "Администратор";
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class Verification {
        private Duration codeTtl = Duration.ofMinutes(10);
        private int maxAttempts = 5;
        private Duration resendAfter = Duration.ofSeconds(60);
        private Duration verifiedValid = Duration.ofHours(24);
        private int maxCallsPerPhonePerDay = 8;

        public Duration getCodeTtl() { return codeTtl; }
        public void setCodeTtl(Duration codeTtl) { this.codeTtl = codeTtl; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getResendAfter() { return resendAfter; }
        public void setResendAfter(Duration resendAfter) { this.resendAfter = resendAfter; }
        public Duration getVerifiedValid() { return verifiedValid; }
        public void setVerifiedValid(Duration verifiedValid) { this.verifiedValid = verifiedValid; }
        public int getMaxCallsPerPhonePerDay() { return maxCallsPerPhonePerDay; }
        public void setMaxCallsPerPhonePerDay(int maxCallsPerPhonePerDay) { this.maxCallsPerPhonePerDay = maxCallsPerPhonePerDay; }
    }

    public static class FlashCall {
        /** zvonok | stub */
        private String provider = "zvonok";
        private String baseUrl = "https://zvonok.com/manager/cabapi_external/api/v1/phones/";
        private String publicKey = "";
        private String campaignId = "";
        private Duration timeout = Duration.ofSeconds(10);
        private String stubCode = "1234";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
        public String getCampaignId() { return campaignId; }
        public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public String getStubCode() { return stubCode; }
        public void setStubCode(String stubCode) { this.stubCode = stubCode; }
    }
}
