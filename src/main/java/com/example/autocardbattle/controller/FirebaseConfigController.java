import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "https://warrior-0.github.io")
public class FirebaseConfigController {

    @Value("${FIREBASE_API_KEY:}") private String apiKey;
    @Value("${FIREBASE_AUTH_DOMAIN:}") private String authDomain;
    @Value("${FIREBASE_PROJECT_ID:}") private String projectId;
    @Value("${FIREBASE_STORAGE_BUCKET:}") private String storageBucket;
    @Value("${FIREBASE_SENDER_ID:}") private String messagingSenderId;
    @Value("${FIREBASE_APP_ID:}") private String appId;

    @GetMapping("/firebase")
    public Map<String, String> getFirebaseConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("apiKey", apiKey);
        config.put("authDomain", authDomain);
        config.put("projectId", projectId);
        config.put("storageBucket", storageBucket);
        config.put("messagingSenderId", messagingSenderId);
        config.put("appId", appId);
        return config;
    }
}
