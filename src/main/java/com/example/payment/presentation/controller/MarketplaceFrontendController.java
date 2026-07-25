package com.example.payment.presentation.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class MarketplaceFrontendController {

    @GetMapping({"/app", "/app/"})
    public String marketplaceApp() {
        return "forward:/app/index.html";
    }

    @GetMapping({"/", "/index.html", "/shared.html", "/seller.html"})
    public RedirectView legacyEntryPoint() {
        RedirectView redirect = new RedirectView("/app/");
        redirect.setContextRelative(true);
        return redirect;
    }
}
