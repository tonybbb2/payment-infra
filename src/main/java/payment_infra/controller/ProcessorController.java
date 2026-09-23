package payment_infra.controller;

import payment_infra.processor.FakePaymentProcessor;
import payment_infra.processor.ProcessorMode;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/processor")
@Profile("dev")
public class ProcessorController {

    private final FakePaymentProcessor paymentProcessor;

    public ProcessorController(
            FakePaymentProcessor paymentProcessor) {

        this.paymentProcessor = paymentProcessor;
    }

    @PostMapping("/mode/{mode}")
    public String setMode(
            @PathVariable ProcessorMode mode) {

        paymentProcessor.setMode(mode);

        return "Processor mode set to " + mode;
    }
}