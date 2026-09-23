package payment_infra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentInfraApplication {

    public static void main(String[] args) {

        SpringApplication.run(
                PaymentInfraApplication.class,
                args
        );
    }
}