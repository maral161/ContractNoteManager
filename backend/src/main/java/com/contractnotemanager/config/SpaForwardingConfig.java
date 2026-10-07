package com.contractnotemanager.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the React app's index.html for the client-side routes when the built UI is bundled. */
@Configuration
public class SpaForwardingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String route : new String[] {"/orders", "/contract-notes", "/unmatched-notes", "/imports"}) {
            registry.addViewController(route).setViewName("forward:/index.html");
        }
    }
}
