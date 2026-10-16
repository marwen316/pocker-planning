package fr.codenova.planning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class PlanningApplication extends SpringBootServletInitializer {
  @Override
  protected SpringApplicationBuilder configure(SpringApplicationBuilder b) {
    return b.sources(PlanningApplication.class);
  }

  public static void main(String[] args) {
    SpringApplication.run(PlanningApplication.class, args);
  }
}
