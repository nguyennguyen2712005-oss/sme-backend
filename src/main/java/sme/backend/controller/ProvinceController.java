package sme.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/provinces")
public class ProvinceController {

    private static final String UPSTREAM = "https://provinces.open-api.vn/api/v2/?depth=2";
    private static final AtomicReference<String> CACHE = new AtomicReference<>();

    @GetMapping
    public ResponseEntity<String> getProvinces() {
        String cached = CACHE.get();
        if (cached != null) {
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .body(cached);
        }
        String data = new RestTemplate().getForObject(UPSTREAM, String.class);
        CACHE.set(data);
        return ResponseEntity.ok()
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(data);
    }
}
