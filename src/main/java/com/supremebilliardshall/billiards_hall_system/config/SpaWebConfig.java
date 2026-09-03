package com.supremebilliardshall.billiards_hall_system.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

// Serves the React build out of the jar, and makes deep links work.
//
// The SPA owns its own routing: /floor and /checkout/{id} mean something to React and nothing
// to Spring. Without this, a hard refresh on either would 404, because there is no such file
// and no such controller — which is the classic way a single-page app appears broken only for
// the people who bookmark things.
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // The API must never be answered with HTML. A missing endpoint stays a
                        // 404 so a typo in a fetch fails loudly instead of resolving to the app
                        // shell and being parsed as JSON.
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
