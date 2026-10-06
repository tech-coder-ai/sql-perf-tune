package com.techcoder.sqlperf.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime settings the Angular app needs before it starts (no rebuild per environment). */
@RestController
public class UiConfigController {

    private final SptProperties props;

    public UiConfigController(SptProperties props) {
        this.props = props;
    }

    public record UiConfig(String agGridLicenseKey) {
    }

    @GetMapping("/api/ui-config")
    public UiConfig uiConfig() {
        return new UiConfig(props.ui().agGridLicenseKey());
    }
}
