package com.when.app;

import com.when.admin.api.AdminCorsConfiguration;
import com.when.admin.api.AdminHttpController;
import com.when.api.http.BusinessHttpController;
import com.when.api.http.GlobalApiExceptionHandler;
import com.when.api.http.WhenHttpConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Spring MVC composition boundary; the Vue console remains a separate static artifact. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({
        BusinessHttpController.class,
        AdminHttpController.class,
        AdminCorsConfiguration.class,
        GlobalApiExceptionHandler.class,
        WhenHttpConfiguration.class
})
public class WhenHttpApplication {
}
