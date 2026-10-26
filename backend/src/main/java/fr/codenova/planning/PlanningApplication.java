package fr.codenova.planning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PlanningApplication {
  public static void main(String[] args) {
    SpringApplication.run(PlanningApplication.class, args);
  }
}
