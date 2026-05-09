package games.beiming.website.common.web.controller;

import games.beiming.website.common.core.response.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommonHealthController {

    @GetMapping("/internal/health")
    public ApiResponse<Map<String, String>> health() {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("status", "UP");
        return ApiResponse.success(data);
    }
}
