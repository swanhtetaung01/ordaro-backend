package app.ordaro.auth;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class JwksController {

    private final JwtKeys keys;

    JwksController(JwtKeys keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return keys.publicJwks();
    }
}
